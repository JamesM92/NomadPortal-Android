"""
LXMF messaging service.

Each user gets their own LXMRouter instance (with its own storage path) so
that send and receive both use that user's LXMF delivery address.  Messages
are always accepted for any registered delivery identity — there is no
reception window; the server stores messages on behalf of users regardless
of whether they are currently logged in.
"""

import logging
import os
import threading
import time
from typing import Optional

log = logging.getLogger(__name__)

PATH_WAIT = 10  # seconds to wait for identity recall after path request


def _channel_to_255(v) -> int:
    """Normalise one FIELD_ICON_APPEARANCE color channel to a 0-255 int,
    accepting either a 0-1 float (the real LXMF/Sideband convention — see
    _rgba_to_hex's own doc comment) or an already-0-255 value."""
    try:
        v = float(v)
    except (TypeError, ValueError):
        return 128
    if v <= 1.0:
        v *= 255.0
    return max(0, min(255, int(round(v))))


def _rgba_to_hex(value) -> str:
    """FIELD_ICON_APPEARANCE color channel -> '#rrggbb'.

    **Two incompatible real-world conventions exist for this channel's
    shape**, confirmed directly against both clients' actual source (not
    guessed):
    - Sideband (LXMF's own reference client, by the LXMF library's own
      author): a [r,g,b] or [r,g,b,a] sequence of 0-1 floats — see its
      own DEFAULT_APPEARANCE = ["account", [0,0,0,1], [1,1,1,1]].
    - MeshChat (github.com/liamcottle/reticulum-meshchat, meshchat.py):
      a raw 3-byte RGB `bytes` object, no alpha — sent via
      `ColourUtils.hex_colour_to_byte_array()` (`bytes.fromhex(hex)`)
      and read back via `icon_appearance[1].hex()`, which would raise on
      a list of floats. Confirmed by a real interop failure: this app's
      icon rendered as a flat grey circle in MeshChat once this function
      switched to Sideband's float shape, and MeshChat's own icons
      stopped resolving here too — MeshChat is the more prevalent
      client of the two for this specific feature, and the one this app
      is actually tested against.
    This function accepts *both* shapes when reading an inbound
    icon (a bytes/bytearray triplet, or a float/int sequence) — see
    _hex_to_icon_bytes below for which shape this app itself *sends*,
    now MeshChat's, with Sideband no longer producible from this app.
    Falls back to grey for anything unrecognized.
    """
    if isinstance(value, (bytes, bytearray)) and len(value) >= 3:
        return "#%02x%02x%02x" % (value[0], value[1], value[2])
    if isinstance(value, (list, tuple)) and len(value) >= 3:
        r, g, b = (_channel_to_255(value[i]) for i in range(3))
        return "#%02x%02x%02x" % (r, g, b)
    return "#888888"


def _hex_to_icon_bytes(value) -> bytes:
    """'#rrggbb' -> a raw 3-byte RGB `bytes` object — MeshChat's
    FIELD_ICON_APPEARANCE color shape (see _rgba_to_hex's own doc
    comment for why this app sends MeshChat's convention specifically,
    not Sideband's 0-1-float one: a real, confirmed interop failure
    against MeshChat when this used to send floats). Falls back to
    mid-grey for anything unparseable."""
    if isinstance(value, str):
        s = value.lstrip("#")
        if len(s) == 6:
            try:
                return bytes.fromhex(s)
            except ValueError:
                pass
    return bytes((128, 128, 128))


def _detect_image_mime(data: bytes) -> str:
    if data[:2] == b'\xff\xd8':                              return "image/jpeg"
    if data[:8] == b'\x89PNG\r\n\x1a\n':                     return "image/png"
    if data[:6] in (b'GIF87a', b'GIF89a'):                   return "image/gif"
    if data[:4] == b'RIFF' and data[8:12] == b'WEBP':        return "image/webp"
    if data[:5] == b'<?xml' or data[:4] == b'<svg':          return "image/svg+xml"
    return "image/png"


class MessagingService:
    def __init__(self, storage_path: str, message_store=None, contact_store=None):
        self._storage        = storage_path
        self._msg_store      = message_store
        self._contact_mgr    = contact_store  # ContactStoreManager (param kept for compat)
        self._lock           = threading.Lock()
        self._identity_store = None
        # user_sub -> {"router": LXMRouter, "dest": Destination}
        self._user_routers: dict = {}
        os.makedirs(storage_path, exist_ok=True)

        # user_sub -> unix timestamp of that user's last successful
        # announce (bootstrap or otherwise). Read by orchestrator.py to
        # decide whether a send-in-progress needs a fresh announce
        # first — see do_announce()'s own doc comment for why the
        # decision of *when* lives up there instead of in here.
        self._last_announce_at: dict = {}

    # ------------------------------------------------------------------
    # Setup helpers
    # ------------------------------------------------------------------

    def setup_user(self, user_sub: str) -> None:
        """Ensure a delivery identity is registered for this user.

        Call at login so incoming messages are routed immediately rather
        than waiting for the user's first outbound send.
        """
        data = self._get_user_router(user_sub)
        if data is None:
            log.warning(
                "Could not set up delivery for user %s — "
                "identity may not exist yet",
                user_sub[:16] if user_sub else "?",
            )

    # ------------------------------------------------------------------
    # Delivery setup
    # ------------------------------------------------------------------

    def setup_delivery(self, identity_store) -> None:
        """Register a delivery identity + LXMRouter for every stored user identity."""
        self._identity_store = identity_store
        for entry in identity_store.list_identities():
            self._init_user_router(entry)

    def _init_user_router(self, entry: dict) -> Optional[dict]:
        """Create (or reuse) an LXMRouter for the given identity entry."""
        import LXMF

        identity_id = entry["id"]
        user_sub    = entry.get("user_sub", "")

        # `if user_sub and ...` (here and the store below) used to gate
        # both on user_sub being truthy — meaning a router created for
        # the anonymous/no-auth user_sub="" (nomadportal-android's only
        # real usage — see identity_store.py's create() for the full
        # story) was silently discarded right after creation, never
        # reachable through _get_user_router("") again. Confirmed as a
        # real on-device crash: every single send_message() call raised
        # "No delivery identity registered for this user". An empty
        # string is still a legitimate dict key — no need to special-case
        # it.
        with self._lock:
            if user_sub in self._user_routers:
                return self._user_routers[user_sub]

        if self._identity_store is None:
            return None
        identity = self._identity_store.load_rns_identity(identity_id)
        if identity is None:
            return None

        user_storage = os.path.join(self._storage, f"u_{identity_id[:16]}")
        os.makedirs(user_storage, exist_ok=True)

        try:
            router = LXMF.LXMRouter(storagepath=user_storage)
            # Match MeshChat's LXMRouter job cadence (1s vs the library
            # default of 4s). Faster processing of pending outbound
            # messages and quicker ``clean_links`` runs. Trivial CPU
            # cost. Guarded by try/except in case a future LXMF version
            # moves or removes the attribute — we don't want a Nomad-
            # Portal boot to break on an upstream refactor.
            try:
                router.PROCESSING_INTERVAL = 1
            except Exception:
                pass
            registered = router.register_delivery_identity(
                identity, display_name=entry.get("name", "")
            )
            if registered is None:
                log.warning(
                    "Could not register delivery for %s "
                    "(LXMRouter already has a delivery identity)", identity_id[:16],
                )
                return None

            router.register_delivery_callback(
                lambda msg, sub=user_sub: self._on_delivery(msg, sub)
            )

            data = {"router": router, "dest": registered, "identity": identity}
            with self._lock:
                self._user_routers[user_sub] = data
            log.info(
                "Registered delivery identity %s → LXMF addr %s (user %s)",
                identity_id[:16], registered.hexhash[:16],
                user_sub[:16] if user_sub else "anon",
            )

            # Bootstrap announce — unconditional, regardless of any
            # per-interface auto-announce config (orchestrator.py's
            # concern, not this module's): a freshly-registered identity
            # has never announced before,
            # so no peer on the mesh has a path to it yet. Without this,
            # every message sent *to* a brand-new install would fail
            # with no path known until the user happened to find and
            # tap a manual announce control. Best-effort — failure here
            # (e.g. RNS not fully up yet) just means the periodic loop
            # or a manual announce catches it later; it doesn't block
            # router registration from succeeding.
            try:
                router.announce(registered.hash)
                self._last_announce_at[user_sub] = time.time()
                log.info(
                    "Bootstrap-announced new delivery identity %s",
                    registered.hexhash[:16],
                )
            except Exception as exc:
                log.warning("Bootstrap announce failed for %s: %s", identity_id[:16], exc)

            return data

        except Exception as exc:
            log.warning("Failed to init router for %s: %s", identity_id[:16], exc)
            return None

    def _get_user_router(self, user_sub: str) -> Optional[dict]:
        """Return the router/dest pair for a user, lazily initialising if needed."""
        with self._lock:
            data = self._user_routers.get(user_sub)
        if data is not None:
            return data

        if self._identity_store is None:
            return None

        entry = self._identity_store.get_for_user(user_sub)
        if entry is None:
            return None
        return self._init_user_router(entry)

    def reset_user_router(self, user_sub: str) -> None:
        """Drop the cached router for a user so it is rebuilt on next use.

        Call after the user's RNS identity is regenerated (e.g. admin reset)
        so the new keypair's LXMF address takes effect immediately.
        """
        with self._lock:
            self._user_routers.pop(user_sub, None)

    def active_routers(self) -> list:
        """Return a snapshot list of currently-registered routers as
        ``[(user_sub, {"router": ..., "dest": ..., "identity": ...}), ...]``.

        Consumed by ``PropagationSyncService`` — each tick it iterates
        this list and fires an outbound sync per router. Snapshot
        semantics: safe to iterate outside the lock, but a router
        removed after this call may still get one more sync tick.
        Harmless — the sync operation itself is idempotent and
        LXMRouter handles stale references gracefully.

        Admin's router is always present (created at container
        startup); user routers appear on login and disappear when
        ``reset_user_router`` is called.
        """
        with self._lock:
            return list(self._user_routers.items())

    def lxmf_address(self, user_sub: str = "") -> Optional[str]:
        """Return the hexhash of the user's LXMF delivery destination, or None."""
        data = self._get_user_router(user_sub)
        return data["dest"].hexhash if data else None

    def set_display_name(self, name: str, user_sub: str = "") -> bool:
        """Renames this user's LXMF identity — persisted via
        identity_store.rename() (always succeeds if the identity exists,
        takes effect on next app start regardless), and best-effort
        applied to the *live* router's destination immediately so an
        announce made right after doesn't still carry the old name.

        Real bug, found via a live on-device report ("the announce is
        sending out with the hash and not the assigned name") and fixed
        by reading LXMRouter.py directly: LXMRouter.announce() does
        *not* consult Destination.default_app_data at all — it always
        calls `delivery_destination.announce(app_data=
        self.get_announce_app_data(destination_hash), ...)` with an
        explicit app_data argument, which unconditionally wins over
        default_app_data regardless of what that's set to.
        get_announce_app_data() in turn reads the plain
        `delivery_destination.display_name` attribute (set once at
        `register_delivery_identity(identity, display_name=...)` time —
        see _init_user_router() above). The previous
        `dest.set_default_app_data(...)` call here was therefore
        complete dead code for every do_announce()-driven announce: a
        rename persisted correctly but never actually changed what any
        live announce carried, so peers kept seeing the identity's
        original auto-generated name (identity_store.py's
        `_default_display_name()`, itself hash-derived) forever — which
        is exactly what looked like "sending the hash." The real fix is
        the plain attribute assignment below; `display_name` has no
        dedicated setter, confirmed directly against
        register_delivery_identity()'s own body.
        """
        if self._identity_store is None:
            return False
        entry = self._identity_store.get_for_user(user_sub)
        if entry is None:
            return False
        if not self._identity_store.rename(entry["id"], name):
            return False
        data = self._user_routers.get(user_sub)
        if data is not None:
            try:
                data["dest"].display_name = name
            except Exception as exc:
                log.warning("Renamed identity but couldn't update live display_name: %s", exc)
        return True

    def set_icon_appearance(self, glyph: str, fg_hex: str, bg_hex: str, user_sub: str = "") -> bool:
        """Sets this user's own FIELD_ICON_APPEARANCE descriptor —
        attached to every future outbound message via _deliver()'s
        fields[0x04] construction above. Persisted only; unlike
        set_display_name there's no live router state to refresh
        immediately, since the icon rides along per-outgoing-message
        rather than being baked into the destination itself."""
        if self._identity_store is None:
            return False
        entry = self._identity_store.get_for_user(user_sub)
        if entry is None:
            return False
        return self._identity_store.set_icon_appearance(entry["id"], glyph, fg_hex, bg_hex)

    def do_announce(self, user_sub: str = "") -> tuple[bool, str]:
        """Announce via the user's LXMRouter so app_data (display name) is included.

        Manual trigger (UI "Announce now" button) as well as what the
        auto-announce loop below calls internally — both paths go
        through here so ``_last_announce_at`` is always updated
        consistently regardless of which one fired.
        """
        data = self._get_user_router(user_sub)
        if data is None:
            return False, "No delivery identity registered for this user"
        try:
            data["router"].announce(data["dest"].hash)
            self._last_announce_at[user_sub] = time.time()
            log.info(
                "Announced LXMF delivery destination %s",
                data["dest"].hexhash[:16],
            )
            return True, "Announced"
        except Exception as exc:
            log.error("Announce failed: %s", exc)
            return False, str(exc)

    # ------------------------------------------------------------------
    # Auto-announce: configurable periodic re-announcing
    # ------------------------------------------------------------------

    def get_announce_status(self, user_sub: str = "") -> dict:
        """Raw facts only — no policy (enabled/interval/staleness-window
        decisions live in orchestrator.py, which is the layer that
        actually has interface-state visibility; see its own module-level
        doc comment on the auto-announce section for why). Just this
        user's last-announce timestamp (unix seconds, None if never) and
        LXMF address (None if no router exists yet)."""
        data = self._user_routers.get(user_sub)
        return {
            "last_announce_at": self._last_announce_at.get(user_sub),
            "lxmf_address": data["dest"].hexhash if data else None,
        }

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def send_message(
        self,
        dest_hash_hex: str,
        content: str,
        title: str = "",
        user_sub: str = "",
    ) -> tuple[bool, str]:
        return self._send(
            dest_hash_hex=dest_hash_hex,
            title=title,
            content=content,
            user_sub=user_sub,
        )

    def sent_messages(self) -> list:
        if self._msg_store:
            return self._msg_store.sent_messages()
        return []

    def received_messages(self) -> list:
        if self._msg_store:
            return self._msg_store.received_messages()
        return []

    def mark_read(self, msg_id: str, owner: str = "") -> None:
        if self._msg_store:
            self._msg_store.mark_read(msg_id, owner=owner)

    def delete_conversation(self, hash_hex: str, user_sub: str = "") -> int:
        """Removes all sent+received messages with this counterparty for
        this user — the message-history half of "delete this chat"; the
        contact_store entry (name/icon/favorite) is a separate concern,
        deleted by orchestrator.delete_conversation() alongside this.
        Returns how many messages were actually removed."""
        if self._msg_store:
            return self._msg_store.delete_conversation(hash_hex, owner=user_sub)
        return 0

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _on_delivery(self, message, user_sub: str = "") -> None:
        """Called by a user's LXMRouter when an inbound message arrives."""
        source_hex = message.source_hash.hex() if message.source_hash else ""
        msg_id     = message.hash.hex()         if message.hash         else ""

        def _decode(val) -> str:
            if val is None:
                return ""
            return val.decode("utf-8", errors="replace") if isinstance(val, bytes) else str(val)

        # Extract icon from LXMF fields. A contact can carry either — never
        # both — of two independent descriptors, matching real-world LXMF
        # clients (Sideband/MeshChat):
        #   FIELD_ICON_APPEARANCE (0x04) = [icon_name_str, fg_rgba, bg_rgba]
        #     — a *live* icon descriptor (see _rgba_to_hex's own doc
        #     comment for the real color shape). icon_name_str is meant to
        #     be looked up against a Material-style icon set client-side
        #     (see ContactAvatar.kt) — this layer stores the raw
        #     descriptor, it does not rasterize it.
        #   FIELD_IMAGE (0x06) = [image_type_str, image_bytes]
        #     — an actual bitmap the peer supplied, stored as-is.
        # Appearance is preferred when (implausibly) both are present,
        # since it's the "live" descriptor.
        icon_appearance = None  # (glyph, fg_hex, bg_hex) or None
        icon_image = None       # (b64, mime) or None
        try:
            fields = getattr(message, "fields", None) or {}
            appearance = fields.get(0x04)
            image      = fields.get(0x06)

            if isinstance(appearance, list) and len(appearance) >= 3:
                glyph = appearance[0] if isinstance(appearance[0], str) and appearance[0] else "?"
                icon_appearance = (glyph, _rgba_to_hex(appearance[1]), _rgba_to_hex(appearance[2]))
            elif isinstance(image, list) and len(image) >= 2 and isinstance(image[1], (bytes, bytearray)):
                import base64
                ext = (image[0] or "").lower() if isinstance(image[0], str) else ""
                mime = {
                    "jpg": "image/jpeg", "jpeg": "image/jpeg",
                    "png": "image/png",  "gif":  "image/gif",
                    "webp":"image/webp", "svg":  "image/svg+xml",
                }.get(ext, _detect_image_mime(image[1]))
                icon_image = (base64.b64encode(image[1]).decode("ascii"), mime)
        except Exception as exc:
            log.debug("Icon extraction skipped: %s", exc)

        entry = {
            "id":          msg_id,
            "source":      source_hex,
            "title":       _decode(message.title),
            "content":     _decode(message.content),
            "received_at": time.time(),
            "read":        False,
            "owner":       user_sub,
        }

        log.info(
            "Received LXMF message from %s: %s",
            source_hex[:16] if source_hex else "?",
            entry["title"] or "(no subject)",
        )

        if self._msg_store:
            self._msg_store.save_received(entry)

        # user_sub is "" for this app's single-user design — a bare
        # `and user_sub` truthy check would silently never fire (the same
        # bug pattern already found+fixed elsewhere in this codebase); the
        # actual precondition is source_hex being non-empty, not user_sub.
        if self._contact_mgr and source_hex:
            store = self._contact_mgr.for_user(user_sub)
            if icon_appearance:
                store.set_icon_appearance(source_hex, *icon_appearance)
            elif icon_image:
                store.set_icon(source_hex, *icon_image)

    def _send(
        self,
        dest_hash_hex: str,
        title: str,
        content: str,
        user_sub: str = "",
    ) -> tuple[bool, str]:
        """Queue a message for background delivery and return immediately."""
        import uuid

        user_data = self._get_user_router(user_sub)
        if user_data is None:
            return False, "No delivery identity registered for this user"

        source_dest = user_data["dest"]
        router      = user_data["router"]

        try:
            dest_hash = bytes.fromhex(dest_hash_hex)
        except ValueError:
            return False, "Invalid destination hash"

        msg_id = str(uuid.uuid4())
        entry = {
            "id":      msg_id,
            "dest":    dest_hash_hex,
            "title":   title,
            # Full content, for the sender's own chat-log bubble (mirrors
            # the "content" field _on_delivery() stores for received
            # messages). "preview" alone used to be the only thing stored
            # here — fine for the 120-char conversation-list snippet, but
            # renderChatLog() falls back to it whenever "content" is
            # missing, so every sent message rendered in the open
            # conversation was silently clipped at 120 characters even
            # though the full text was — and still is — what actually
            # went out over LXMF.
            "content": content or "",
            "preview": (content or "")[:120],
            "state":   "queued",
            "sent_at": time.time(),
            "owner":   user_sub,
        }
        if self._msg_store:
            self._msg_store.save_sent(entry)

        def _deliver() -> None:
            import RNS, LXMF
            try:
                # Wait until we can recall the recipient's identity.
                # request_path kicks off path discovery if needed.
                dest_identity = RNS.Identity.recall(dest_hash)
                if dest_identity is None:
                    RNS.Transport.request_path(dest_hash)
                    deadline = time.time() + PATH_WAIT
                    while dest_identity is None:
                        if time.time() > deadline:
                            log.warning(
                                "Identity not recalled for %s after %ss — "
                                "peer may not have announced recently",
                                dest_hash_hex[:16], PATH_WAIT,
                            )
                            if self._msg_store:
                                self._msg_store.update_sent(msg_id, "failed")
                            return
                        time.sleep(0.25)
                        dest_identity = RNS.Identity.recall(dest_hash)

                lxmf_dest = RNS.Destination(
                    dest_identity,
                    RNS.Destination.OUT,
                    RNS.Destination.SINGLE,
                    "lxmf",
                    "delivery",
                )
                # Attach the sender's icon appearance if one is set. Same
                # user_sub="" gate bug as _on_delivery() above — user_sub
                # is a valid (empty-string) key for this app's single-user
                # design, not a falsy "no user" sentinel.
                fields = {}
                if self._identity_store:
                    icon = self._identity_store.get_icon_appearance_for_user(user_sub)
                    if icon:
                        # This app stores/looks up icon names with
                        # underscores internally (IconAppearance.kt's own
                        # map keys, e.g. "music_note") — but real MDI
                        # names (what MeshChat/Sideband actually resolve)
                        # are kebab-case with hyphens, e.g. "music-note"
                        # (confirmed against MeshChat's own frontend:
                        # `.replace(/([a-z])([A-Z])/g, '$1-$2')`). A real
                        # interop bug, found via live testing: MeshChat
                        # received our fg/bg colors correctly but showed
                        # "?" for the icon itself, because "music_note"
                        # isn't a name it has. The receive side already
                        # tolerates both separators (materialIconFor's
                        # own `.replace('-', '_')`), so only the send
                        # side needs converting.
                        glyph = (icon.get("glyph") or "?").replace("_", "-")
                        fields[0x04] = [
                            glyph,
                            _hex_to_icon_bytes(icon.get("fg", "#ffffff")),
                            _hex_to_icon_bytes(icon.get("bg", "#5ba3c9")),
                        ]

                # Prefer OPPORTUNISTIC (single encrypted packet, no link needed).
                # LXMessage automatically falls back to DIRECT if the content
                # is too large for a single packet.
                lxmf_msg = LXMF.LXMessage(
                    lxmf_dest,
                    source_dest,
                    content,
                    title=title or "",
                    fields=fields or None,
                    desired_method=LXMF.LXMessage.OPPORTUNISTIC,
                )

                # Callbacks update the store whenever delivery completes —
                # no fixed wait so long messages don't time out prematurely.
                def _delivered(_m):
                    real_id = _m.hash.hex() if _m.hash else msg_id
                    if self._msg_store:
                        self._msg_store.update_sent(msg_id, "delivered", real_id=real_id)
                    log.info("Delivered %s → %s", msg_id[:8], dest_hash_hex[:16])

                def _failed(_m):
                    if self._msg_store:
                        self._msg_store.update_sent(msg_id, "failed")
                    log.warning("Delivery failed %s → %s", msg_id[:8], dest_hash_hex[:16])

                lxmf_msg.register_delivery_callback(_delivered)
                lxmf_msg.register_failed_callback(_failed)
                router.handle_outbound(lxmf_msg)
                log.info("Queued LXMF message %s → %s", msg_id[:8], dest_hash_hex[:16])

            except Exception:
                log.exception("Async LXMF delivery error for %s", msg_id[:8])
                if self._msg_store:
                    self._msg_store.update_sent(msg_id, "failed")

        threading.Thread(target=_deliver, daemon=True).start()
        return True, msg_id
