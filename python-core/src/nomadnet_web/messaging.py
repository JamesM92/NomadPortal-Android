"""
LXMF messaging service.

Each user gets their own LXMRouter instance (with its own storage path) so
that send and receive both use that user's LXMF delivery address.  Messages
are always accepted for any registered delivery identity — there is no
reception window; the server stores messages on behalf of users regardless
of whether they are currently logged in.
"""

import logging
import mimetypes
import os
import threading
import time
from typing import Optional

log = logging.getLogger(__name__)

PATH_WAIT = 10  # seconds to wait for identity recall after path request

# Real Reticulum links (LoRa in particular) are slow and often
# congested — an attachment this app will actually try to push through
# opportunistically/directly rather than a dedicated resource-transfer
# flow. 10 MiB is generous enough for a typical photo/document while
# still being an explicit, honest limit rather than letting someone
# queue something that could take an unreasonable amount of time (or
# just fail) over a constrained link. Enforced both here (source of
# truth) and client-side in ConversationScreen.kt (so a user finds out
# before waiting on a round trip through Chaquopy).
MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024

# LXMessage.method's real int constants (confirmed directly against the
# installed LXMF package's LXMessage.py, not guessed) — mapped to the
# lowercase labels this app surfaces in delivery-diagnostics. UNKNOWN/
# absent maps to "unknown" via .get()'s own default at each call site,
# not listed here.
_LXMF_METHOD_NAMES = {
    0x00: "unknown",
    0x01: "opportunistic",
    0x02: "direct",
    0x03: "propagated",
    0x05: "paper",
}


def _sanitize_attachment_filename(name: str) -> str:
    """Strip any path component/traversal sequence from a peer- or
    user-supplied filename before it's ever used to build an on-disk
    path — same defensive pattern Sideband's own attachment-save code
    uses (`.replace("../", "").replace("..\\\\", "")`, confirmed
    directly against its source, sbapp/ui/messages.py's
    `gen_save_attachment` — note it's specifically "`..` + a separator",
    not bare `..`, so a legitimate filename that happens to contain a
    literal `".."` with no separator, e.g. "v1..2.txt", survives
    untouched), followed by an explicit split on both possible
    separators to take just the final segment.

    Deliberately NOT `os.path.basename()` for that last step — a real
    bug caught by this module's own tests: `ntpath.basename()` (what
    `os.path` resolves to on this project's Windows dev/build machine —
    see build.gradle.kts's `buildPython`) mishandles a leading `//`
    (produced by stripping `"../../etc/passwd"` down to `"//etc/passwd"`)
    as a UNC-path prefix and returns `""` instead of `"passwd"`. The
    shipped app always runs under Android/POSIX, where `posixpath`
    doesn't have this quirk — but the algorithm should be correct on its
    own terms, not merely lucky about which OS happens to run it."""
    name = (name or "attachment").replace("../", "").replace("..\\", "")
    for sep in ("/", "\\"):
        name = name.rsplit(sep, 1)[-1]
    return name or "attachment"


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
        # See set_contacts_only_messages()'s own doc comment.
        self._contacts_only_messages = False
        # See set_retry_via_relay()'s own doc comment.
        self._retry_via_relay = False
        os.makedirs(storage_path, exist_ok=True)

        # user_sub -> unix timestamp of that user's last successful
        # announce (bootstrap or otherwise). Read by orchestrator.py to
        # decide whether a send-in-progress needs a fresh announce
        # first — see do_announce()'s own doc comment for why the
        # decision of *when* lives up there instead of in here.
        self._last_announce_at: dict = {}

        # Attachment binary content lives on disk as real files, not
        # inline in messages.json — that file is fully re-serialized on
        # every single save_sent()/save_received() call (message_store.py),
        # so embedding even base64'd multi-MB blobs there would mean
        # rewriting gigabytes of unrelated old attachment data on every
        # unrelated new message. Kept under this same never-backed-up
        # storage root (not a second directory) — matches this app's
        # existing privacy stance that nothing user-facing here should
        # ever leave the device via a backup side channel, now the
        # app's explicit committed position (see the
        # nomadportal-android-product-positioning memory), not just an
        # identity-material-specific concern.
        self._attachments_dir = os.path.join(self._storage, "attachments")

    def _disappearing_seconds_for(self, hash_hex: str, user_sub: str) -> int:
        """The conversation's current disappearing-messages duration
        (0 = off) — read once at message-creation time so `_send`/
        `_on_delivery` can stamp that message's own `expires_at`. Same
        None-safe "no ContactStore entry yet" handling as every other
        contact lookup in this file (a message-history-only or
        announce-only contact has no entry until something explicitly
        creates one — see set_contact_favorite's own doc comment for
        the general shape of this gap)."""
        if not self._contact_mgr or not hash_hex:
            return 0
        entry = self._contact_mgr.for_user(user_sub).get(hash_hex)
        return entry.get("disappearing_seconds", 0) if entry else 0

    def _is_blocked(self, hash_hex: str, user_sub: str) -> bool:
        """Consulted once, right at the top of `_on_delivery`, before any
        of that function's icon/attachment/content processing — a blocked
        sender's message is dropped outright, never stored, never
        surfaced to the UI in any form (not even a suppressed/hidden
        entry). Same None-safe "no ContactStore entry yet" handling as
        `_disappearing_seconds_for`: a peer with no entry at all can't be
        blocked (set_contact_blocked always upserts first, so "blocked"
        only ever appears on a real entry)."""
        if not self._contact_mgr or not hash_hex:
            return False
        entry = self._contact_mgr.for_user(user_sub).get(hash_hex)
        return bool(entry.get("blocked")) if entry else False

    def set_contacts_only_messages(self, enabled: bool) -> None:
        """Global allowlist mode — per the Columba-parity-audit's own
        "Messages from contacts only" finding (confirmed real against its
        source, `PrivacyCard.kt`, during a fresh audit pass): when on,
        `_on_delivery` silently discards any inbound message from a
        sender who isn't already a known contact, the same "dropped
        outright, never stored, never surfaced" enforcement `_is_blocked`
        already uses — this is a stronger, proactive complement to that
        reactive per-sender block list, not a replacement for it (both
        checks run; either one dropping a message is final).

        In-memory only, same as `orchestrator.py`'s own
        `_auto_announce_master_enabled` — the real persisted source of
        truth lives in Kotlin's DataStore (SettingsRepository), replayed
        into this flag once at app startup via
        `set_messages_contacts_only()`
        (`NomadPortalApp.kt`'s own boot sequence, mirroring exactly how
        the TCP/Bluetooth/Wi-Fi/node-hosting toggles already get replayed
        the same way) — unlike auto-announce-master, a *privacy*-
        protective toggle silently resetting to permissive on every
        restart would be a real footgun, so this one deliberately does
        get a real persisted replay, not left ephemeral."""
        self._contacts_only_messages = bool(enabled)

    def _allows_sender(self, hash_hex: str, user_sub: str) -> bool:
        """True unless contacts-only mode is on and this sender isn't a
        known contact — see `set_contacts_only_messages`'s own doc
        comment for the real enforcement point and rationale. "Known
        contact" means a real ContactStore entry exists (favorited,
        manually added, or previously named) — matching every other
        contact-identity check in this file, not "has ever messaged
        before" (which would make an allowlist meaningless: the very
        first message from anyone would already satisfy it)."""
        if not self._contacts_only_messages:
            return True
        if not self._contact_mgr or not hash_hex:
            return False
        return self._contact_mgr.for_user(user_sub).get(hash_hex) is not None

    def get_contacts_only_messages(self) -> bool:
        """Current in-memory state — orchestrator.py's own status getter
        reads this to report the live, enforced value back to the UI
        (distinct from Kotlin's persisted DataStore copy, which is only
        the source of truth *at boot replay time*, not afterward)."""
        return self._contacts_only_messages

    def set_retry_via_relay(self, enabled: bool) -> None:
        """Per the Columba-parity-audit's own "retry via relay on
        failure" finding (confirmed real against its source,
        `MessageDeliveryRetrievalCard.kt`, during a fresh audit pass) —
        the send-side complement to this app's own propagation-node
        *pull* sync (lxmf_sync.py's `PropagationSyncService`, which only
        ever retrieves messages queued *for* this device). When on, a
        failed direct/opportunistic send automatically gets one retry
        through a propagation node instead — see
        `_should_retry_via_relay`/`_attempt_relay_retry`'s own doc
        comments for the real mechanics.

        Python-side ephemeral, same shape as `set_contacts_only_messages`
        — but unlike that one, deliberately **not** given real Kotlin
        DataStore persistence: this is a delivery-reliability preference,
        not a privacy-protective one, so resetting to its default (off)
        on restart is an acceptable minor inconvenience, not the kind of
        footgun that justified extra persistence machinery for contacts-
        only mode. Matches `_auto_announce_master_enabled`'s own already-
        accepted ephemeral precedent."""
        self._retry_via_relay = bool(enabled)

    def get_retry_via_relay(self) -> bool:
        return self._retry_via_relay

    def _should_retry_via_relay(self, router) -> bool:
        """The real decision logic, kept separate from
        `_attempt_relay_retry`'s actual LXMessage-construction mechanics
        so it's unit-testable without needing a live RNS/LXMF Router
        (same "policy vs mechanism" split this module already uses
        elsewhere, e.g. `_allows_sender` vs `_on_delivery`).

        False whenever [set_retry_via_relay] is off, or when the router
        has no outbound propagation node configured yet — real LXMF
        (`LXMRouter.handle_outbound`, confirmed directly against its
        source) raises immediately on a PROPAGATED-method send with none
        set, so this check is what keeps a retry attempt from ever being
        pointless/guaranteed-to-fail. `router.get_outbound_propagation_node()`
        is populated as a side effect of `PropagationSyncService`'s own
        already-running periodic sync loop (`set_outbound_propagation_node`,
        called every tick once any propagation node is discovered) — this
        method doesn't need to know anything about that service directly,
        just read the router's own already-real field."""
        if not self._retry_via_relay:
            return False
        try:
            return router.get_outbound_propagation_node() is not None
        except Exception:
            return False

    def _attempt_relay_retry(
        self, msg_id: str, dest_hash_hex: str, lxmf_dest, source_dest,
        content: str, title: str, fields: dict, router,
        dest_hash: Optional[bytes] = None,
    ) -> None:
        """Builds and queues a second delivery attempt for the same
        message content, this time with `desired_method=PROPAGATED` —
        called once, from `_send()`'s own `_failed` callback, only after
        [_should_retry_via_relay] has already confirmed a real
        propagation node is configured. Updates the *same* `msg_id`
        entry in message_store (not a duplicate message) once this
        second attempt itself resolves — `_record_send_result`'s own
        `via_relay=True` just changes the log line, the storage shape is
        identical either way (so the UI's own delivery-diagnostics
        dialog needs no special-casing for a relay-retried message).

        [lxmf_dest] may be None — the second, real call site (`_deliver`'s
        own recall/path-discovery timeout branch, added after a live
        report of "can receive messages but can't send, even after a
        fresh announce") never resolved the recipient's `RNS.Identity`
        in the first place, so it has no `RNS.Destination` to build
        `lxmf_dest` from at all. `LXMF.LXMessage` (confirmed directly
        against the installed LXMF source) accepts `destination=None`
        together with a raw `destination_hash` for exactly this case —
        PROPAGATED delivery only needs the propagation node to route by
        hash, not a pre-resolved identity on this end. [dest_hash] (raw
        bytes) is required whenever [lxmf_dest] is None."""
        import LXMF
        try:
            if lxmf_dest is not None:
                relay_msg = LXMF.LXMessage(
                    lxmf_dest, source_dest, content,
                    title=title or "", fields=fields or None,
                    desired_method=LXMF.LXMessage.PROPAGATED,
                )
            else:
                relay_msg = LXMF.LXMessage(
                    None, source_dest, content,
                    title=title or "", fields=fields or None,
                    desired_method=LXMF.LXMessage.PROPAGATED,
                    destination_hash=dest_hash,
                )
            relay_msg.register_delivery_callback(
                lambda m: self._record_send_result(msg_id, dest_hash_hex, m, "delivered", via_relay=True)
            )
            relay_msg.register_failed_callback(
                lambda m: self._record_send_result(msg_id, dest_hash_hex, m, "failed", via_relay=True)
            )
            router.handle_outbound(relay_msg)
            log.info("Retrying %s via relay after direct delivery failed", msg_id[:8])
        except Exception:
            log.exception("Retry-via-relay failed to queue for %s", msg_id[:8])

    def _record_send_result(self, msg_id: str, dest_hash_hex: str, m, state: str, via_relay: bool) -> None:
        """Shared storage-update logic for both the initial delivery
        attempt and a relay retry's own delivered/failed outcome — see
        `_attempt_relay_retry`'s own doc comment for why both write to
        the same `msg_id` entry rather than creating a second one."""
        if self._msg_store:
            self._msg_store.update_sent(
                msg_id, state,
                real_id=(m.hash.hex() if state == "delivered" and m.hash else None),
                method=_LXMF_METHOD_NAMES.get(m.method, "unknown"),
                transport_encrypted=m.transport_encrypted,
                delivery_attempts=m.delivery_attempts,
                rssi=m.rssi, snr=m.snr, quality=m.q,
            )
        suffix = " (via relay)" if via_relay else ""
        if state == "delivered":
            log.info("Delivered %s → %s%s", msg_id[:8], dest_hash_hex[:16], suffix)
        else:
            log.warning("Delivery failed %s → %s%s", msg_id[:8], dest_hash_hex[:16], suffix)

    def purge_expired_messages(self) -> int:
        """Sweeps every disappearing message whose timer has elapsed —
        called periodically by orchestrator.py's disappearing-messages
        sweep loop. Unlike delete_conversation (a known, pre-existing,
        out-of-scope gap: it drops the messages.json entry but never
        unlinks the attachment file on disk), this genuinely removes
        the backing attachment bytes too — a message advertised to the
        user as "disappearing" has to actually disappear, not just stop
        showing up in the list. Returns the number of messages purged."""
        if not self._msg_store:
            return 0
        removed = self._msg_store.purge_expired()
        for entry in removed:
            attachment = entry.get("attachment")
            path = attachment.get("path") if attachment else None
            if not path:
                continue
            try:
                os.remove(path)
            except OSError as exc:
                log.debug("Could not remove expired attachment: %s", exc)
        if removed:
            log.info("Disappearing-messages sweep: purged %d message(s)", len(removed))
        return len(removed)

    def _save_attachment(
        self, msg_id: str, filename: str, data: bytes, kind: str,
        image_format: Optional[str] = None,
    ) -> dict:
        """Writes attachment bytes to a real file and returns the small
        metadata dict stored inline in the message entry
        (message_store.py) — `path` is an absolute on-device path Kotlin
        reads directly (BitmapFactory.decodeFile / File.readBytes /
        FileProvider-wrapped share intent), not routed back through
        Chaquopy a second time. Raises ValueError if `data` exceeds
        MAX_ATTACHMENT_BYTES."""
        if len(data) > MAX_ATTACHMENT_BYTES:
            raise ValueError(
                f"Attachment too large ({len(data)} bytes, "
                f"max {MAX_ATTACHMENT_BYTES})"
            )
        os.makedirs(self._attachments_dir, exist_ok=True)
        safe_name = _sanitize_attachment_filename(filename)
        on_disk_name = f"{msg_id}_{safe_name}"
        path = os.path.join(self._attachments_dir, on_disk_name)
        with open(path, "wb") as fh:
            fh.write(data)
        if kind == "image":
            mime = f"image/{(image_format or 'png').lower()}"
        else:
            mime = mimetypes.guess_type(safe_name)[0] or "application/octet-stream"
        return {
            "kind": kind,
            "filename": safe_name,
            "mime": mime,
            "size": len(data),
            "path": path,
        }

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

    def setup_delivery(self, identity_store, only_user_sub: Optional[str] = None) -> None:
        """Register a delivery identity + LXMRouter.

        [only_user_sub] is None by default — registers every stored
        identity, the original multi-tenant-web-app behavior (every
        logged-in user's router comes up at server start, since any of
        them might be offline from the UI but still needs to receive).

        nomadportal-android's real multi-identity feature passes its
        active identity's user_sub explicitly instead: this app's own
        model is single-active-identity (see `deactivate_user`'s own
        doc comment) — only the currently-active identity should have a
        live router at boot, not every identity ever created on this
        device. Any identity matching [only_user_sub] gets initialized;
        every other stored identity is left inactive until switched to.
        """
        self._identity_store = identity_store
        for entry in identity_store.list_identities():
            if only_user_sub is not None and entry.get("user_sub", "") != only_user_sub:
                continue
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

        Note: unlike `deactivate_user` below, this does NOT call the
        popped router's own `exit_handler()` — it was written for the
        "this identity's keypair no longer exists, a fresh one is about
        to replace it" case, where the old router's own background
        threads/links being left running was never actually exercised
        for long (the identity behind it was gone). Multi-identity
        switching is a different case — the deactivated identity is
        still real and may be reactivated later — so it needs the real
        teardown `deactivate_user` does instead of this method.
        """
        with self._lock:
            self._user_routers.pop(user_sub, None)

    def deactivate_user(self, user_sub: str) -> None:
        """Cleanly stops one identity's LXMRouter — the real mechanism
        behind multi-identity's single-active-identity switch (see the
        nomadportal-android multi-identity plan/memory for the full
        design). `LXMRouter.exit_handler()` (verified directly against
        the installed LXMF/LXMRouter.py source) tears down that
        router's own delivery destination/links and flips
        `exit_handler_running`, which its own background job-loop
        threads check to stop themselves — it only touches that
        router's own state, so this is safe to call while
        `RNS.Reticulum()` and any other identity's router keep running.

        A no-op if this user_sub has no live router (e.g. it was never
        activated this run, or is already deactivated) — same
        "tolerates being called on nothing" contract as
        `reset_user_router`.

        Does NOT delete the identity or its stored messages/contacts —
        those live in `IdentityStore`/`ContactStoreManager`/`MessageStore`
        independent of whether a router is currently running for it, so
        the identity can be reactivated later via `_init_user_router`
        with its full history intact. `register_delivery_identity`
        refuses a second identity on an already-used router instance,
        so reactivation always builds a fresh `LXMRouter` rather than
        resuming this exited one — `_init_user_router` already does
        exactly that once this user_sub is gone from `_user_routers`.
        """
        with self._lock:
            data = self._user_routers.pop(user_sub, None)
        if data is None:
            return
        try:
            data["router"].exit_handler()
        except Exception:
            log.exception(
                "deactivate_user: exit_handler() raised for %s — "
                "router is still removed from the active set either way",
                user_sub[:16] if user_sub else "anon",
            )

    def activate_user(self, entry: dict) -> Optional[dict]:
        """Public wrapper over `_init_user_router` — the other half of
        `deactivate_user`'s pair, for the multi-identity switch flow
        (orchestrator.py's `switch_active_identity`). [entry] is an
        `IdentityStore` entry dict (has "id"/"user_sub"/"name"). Safe to
        call for an identity that's already active — `_init_user_router`
        itself already tolerates that (returns the existing router)."""
        return self._init_user_router(entry)

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
        self.refresh_router_display_name(user_sub, name)
        return True

    def refresh_router_display_name(self, user_sub: str, name: str) -> None:
        """Applies a rename to the *live* router's destination
        immediately (if one is running for [user_sub]) — the same
        "an announce made right after doesn't still carry the old
        name" fix [set_display_name]'s own doc comment describes,
        factored out so a caller that persists a rename through a
        different path (orchestrator.py's `rename_identity()`, for
        multi-identity support — it calls `IdentityStore.rename()`
        directly rather than through this method) can still get the
        same live-refresh guarantee for whichever identity happens to
        be active. No-op if no live router exists for [user_sub] (e.g.
        renaming an identity that isn't currently active — nothing live
        to refresh)."""
        data = self._user_routers.get(user_sub)
        if data is not None:
            try:
                data["dest"].display_name = name
            except Exception as exc:
                log.warning("Renamed identity but couldn't update live display_name: %s", exc)

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
        user's last-announce timestamp (unix seconds, None if never),
        LXMF address (None if no router exists yet), and this identity's
        own public key (hex, `identity.get_public_key()` — encryption +
        signing public keys concatenated, 64 bytes/128 hex chars; None
        if no identity exists yet). The public key is what makes real
        QR-code identity sharing reliable — see
        `import_scanned_contact()`'s own doc comment for why."""
        data = self._user_routers.get(user_sub)
        identity = data["identity"] if data else None
        return {
            "last_announce_at": self._last_announce_at.get(user_sub),
            "lxmf_address": data["dest"].hexhash if data else None,
            "public_key": identity.get_public_key().hex() if identity else None,
        }

    def import_scanned_contact(self, dest_hash_hex: str, public_key_hex: str) -> tuple[bool, str]:
        """Registers a scanned/imported identity directly, without
        waiting to hear a real announce from it first — the real
        reliability benefit of encoding the public key in a QR code, not
        just the destination hash (confirmed real against Columba's own
        source during the Columba-parity-audit fresh pass: its QR format
        is `lxma://<dest_hash_hex>:<public_key_hex>`, exactly this same
        shape, for exactly this reason).

        `RNS.Identity.remember(packet_hash, destination_hash, public_key,
        app_data=None)` is the same call RNS's own announce-processing
        path uses internally to populate `Identity.known_destinations` —
        calling it directly here just skips waiting for a real announce
        packet to trigger it. `packet_hash=b""` is fine: `Identity.recall()`
        never reads it back (see its own source — the stored `packet_hash`
        entry only exists for `Reticulum._used_destination_data()`
        bookkeeping, harmless as an empty placeholder here).

        Without this, a freshly-scanned contact (someone you haven't yet
        heard a real mesh announce from) would still fail to receive a
        message until path discovery independently succeeds — the exact
        gap this closes. Returns (False, reason) for malformed input
        rather than raising, matching this module's other "validate,
        don't crash on bad input from a scan" methods."""
        try:
            dest_hash = bytes.fromhex(dest_hash_hex)
            public_key = bytes.fromhex(public_key_hex)
        except ValueError:
            return False, "Not valid hex"
        if len(dest_hash) != 16:
            return False, "Destination hash must be 16 bytes"
        try:
            import RNS
            if len(public_key) != RNS.Identity.KEYSIZE // 8:
                return False, "Public key is the wrong length for this identity type"
            RNS.Identity.remember(packet_hash=b"", destination_hash=dest_hash, public_key=public_key)
            return True, "Contact imported"
        except Exception as exc:
            return False, f"Could not import: {exc}"

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def send_message(
        self,
        dest_hash_hex: str,
        content: str,
        title: str = "",
        user_sub: str = "",
        attachment_filename: Optional[str] = None,
        attachment_data: Optional[bytes] = None,
        attachment_kind: str = "file",
        image_format: Optional[str] = None,
    ) -> tuple[bool, str]:
        """[attachment_kind] is "file" (LXMF FIELD_FILE_ATTACHMENTS,
        0x05 — any attached file, including audio: real audio playback
        via FIELD_AUDIO requires an exact Opus/Codec2 codec tag real
        clients decode against, which an arbitrary picked audio file
        isn't guaranteed to be — see messaging.py module notes/the
        nomadportal-android-competitor-research memory for the real
        Sideband-verified field shapes this app deliberately doesn't
        try to fake) or "image" (LXMF FIELD_IMAGE, 0x06 — [image_format,
        bytes], already resized/re-encoded client-side before this call;
        this layer stores it as given, it does not itself transcode)."""
        return self._send(
            dest_hash_hex=dest_hash_hex,
            title=title,
            content=content,
            user_sub=user_sub,
            attachment_filename=attachment_filename,
            attachment_data=attachment_data,
            attachment_kind=attachment_kind,
            image_format=image_format,
        )

    def sent_messages(self, user_sub: Optional[str] = None) -> list:
        """[user_sub] is None by default — preserves the original
        "every message, every identity" behavior for any existing
        caller. Multi-identity support passes the active identity's
        user_sub explicitly (see orchestrator.py's `_conversation_entries()`)
        to get that identity's own sent messages only — see
        `message_store.MessageStore.sent_messages`'s own doc comment for
        the underlying filter."""
        if self._msg_store:
            return self._msg_store.sent_messages(owner=user_sub)
        return []

    def received_messages(self, user_sub: Optional[str] = None) -> list:
        """See `sent_messages`'s own doc comment — same contract."""
        if self._msg_store:
            return self._msg_store.received_messages(owner=user_sub)
        return []

    def mark_read(self, msg_id: str, owner: str = "") -> None:
        if self._msg_store:
            self._msg_store.mark_read(msg_id, owner=owner)

    def mark_unread(self, msg_id: str, owner: str = "") -> None:
        if self._msg_store:
            self._msg_store.mark_unread(msg_id, owner=owner)

    def delete_conversation(self, hash_hex: str, user_sub: str = "") -> int:
        """Removes all sent+received messages with this counterparty for
        this user — the message-history half of "delete this chat"; the
        contact_store entry (name/icon/favorite) is a separate concern,
        deleted by orchestrator.delete_conversation() alongside this.
        Returns how many messages were actually removed.

        Also removes any attachment files those messages left on disk —
        without this, deleting a chat would silently leave orphaned
        attachment files behind forever (message_store.py's own
        delete_conversation only touches messages.json, it has no
        knowledge of the attachments directory, which is this class's
        concern, not its own)."""
        if self._msg_store is None:
            return 0
        for m in self._msg_store.sent_messages():
            if m.get("dest") == hash_hex and (not user_sub or m.get("owner") == user_sub):
                self._delete_attachment_file(m.get("attachment"))
        for m in self._msg_store.received_messages():
            if m.get("source") == hash_hex and (not user_sub or m.get("owner") == user_sub):
                self._delete_attachment_file(m.get("attachment"))
        return self._msg_store.delete_conversation(hash_hex, owner=user_sub)

    def delete_identity_data(self, user_sub: str) -> int:
        """Permanently deletes ALL of one identity's message history
        (every conversation, not just one counterparty — see
        `delete_conversation`'s own doc comment for that narrower,
        per-counterparty operation) plus the attachment files those
        messages left on disk. The real backing for multi-identity's
        "deleting an identity also deletes its message history" cascade
        (orchestrator.py's `delete_identity()`, which separately handles
        the identity's own contacts/favorites via
        `ContactStoreManager.delete_user()` — this method's job is only
        the message-history half). Returns how many messages were
        removed. A no-op (returns 0) if message storage isn't ready."""
        if self._msg_store is None:
            return 0
        removed = self._msg_store.delete_owner(user_sub)
        for m in removed:
            self._delete_attachment_file(m.get("attachment"))
        return len(removed)

    @staticmethod
    def _delete_attachment_file(attachment: Optional[dict]) -> None:
        if not attachment:
            return
        path = attachment.get("path")
        if not path:
            return
        try:
            os.remove(path)
        except OSError:
            pass  # Already gone, or never existed — not worth failing the delete over.

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _on_delivery(self, message, user_sub: str = "") -> None:
        """Called by a user's LXMRouter when an inbound message arrives."""
        source_hex = message.source_hash.hex() if message.source_hash else ""
        msg_id     = message.hash.hex()         if message.hash         else ""

        # Blocked senders are dropped outright, before any of the
        # icon/attachment/content processing below runs — never stored,
        # never surfaced to the UI in any form (not even a suppressed/
        # hidden entry). This is the actual enforcement point;
        # set_contact_blocked() only ever flips a flag in ContactStore.
        if self._is_blocked(source_hex, user_sub):
            log.info("Dropped inbound message from blocked contact %s", source_hex[:16])
            return

        # Same "dropped outright, before any content processing" shape
        # as the blocked-sender check above — see _allows_sender's own
        # doc comment for what "known contact" means here.
        if not self._allows_sender(source_hex, user_sub):
            log.info(
                "Dropped inbound message from non-contact sender %s (contacts-only mode)",
                source_hex[:16],
            )
            return

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
        # Read once, safely (getattr with a default can't raise), so
        # both this block and the message-attachment extraction below
        # can rely on it existing even if something inside either try
        # block fails.
        fields = getattr(message, "fields", None) or {}
        appearance = fields.get(0x04)
        image      = fields.get(0x06)
        try:
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

        # Message-content attachment — a genuinely different concern
        # from the icon/avatar-fallback extraction above even though
        # FIELD_IMAGE (0x06) is the same field number: real clients
        # (Sideband, confirmed directly against its source — see the
        # nomadportal-android-competitor-research memory) use 0x06 as
        # an actual per-message photo, not an avatar. Both behaviors are
        # kept rather than one replacing the other: an inbound image
        # still updates this contact's avatar-fallback (existing,
        # unchanged, low risk to touch), and separately — new — is
        # attached to *this* message so it renders inline in the
        # conversation. Only the first entry of FIELD_FILE_ATTACHMENTS
        # is kept (this app only ever sends one attachment per message
        # itself — see _send()/_deliver() — so there's nothing to
        # gain from modeling a list here too).
        attachment_meta = None
        try:
            file_attachments = fields.get(0x05)
            if isinstance(image, list) and len(image) >= 2 and isinstance(image[1], (bytes, bytearray)):
                ext = (image[0] or "png").lower() if isinstance(image[0], str) else "png"
                attachment_meta = self._save_attachment(
                    msg_id, f"image.{ext}", bytes(image[1]), "image", image_format=ext,
                )
            elif isinstance(file_attachments, list) and len(file_attachments) > 0:
                first = file_attachments[0]
                if isinstance(first, (list, tuple)) and len(first) >= 2 and isinstance(first[1], (bytes, bytearray)):
                    fname = first[0] if isinstance(first[0], str) and first[0] else "attachment"
                    attachment_meta = self._save_attachment(msg_id, fname, bytes(first[1]), "file")
        except ValueError as exc:
            # Oversized inbound attachment (shouldn't normally happen —
            # this is a receive-side safety net, not the primary
            # enforcement point, which is the sender's own size check)
            # — message content/text still arrives, just without the
            # attachment rather than dropping the whole message.
            log.warning("Inbound attachment rejected: %s", exc)
        except Exception as exc:
            log.debug("Attachment extraction skipped: %s", exc)

        disappearing_seconds = self._disappearing_seconds_for(source_hex, user_sub)
        received_at = time.time()
        entry = {
            "id":          msg_id,
            "source":      source_hex,
            "title":       _decode(message.title),
            "content":     _decode(message.content),
            "received_at": received_at,
            "read":        False,
            "owner":       user_sub,
            "attachment":  attachment_meta,
            # Stamped once, here, from the conversation's setting at
            # this exact moment — not retroactive to a later setting
            # change. None (the default for every message that predates
            # this feature too, no migration needed) means "never
            # expires" — see message_store.py's purge_expired.
            "expires_at":  received_at + disappearing_seconds if disappearing_seconds > 0 else None,
            # Same delivery-diagnostic fields as a sent message's
            # update_sent() call — see that call site's own doc comment
            # for why rssi/snr/quality are honestly None for essentially
            # every message today. Known immediately here (no async
            # delivery step to wait on the way a sent message has), so
            # stamped directly into the entry rather than needing a
            # later update.
            "method":              _LXMF_METHOD_NAMES.get(message.method, "unknown"),
            "transport_encrypted": message.transport_encrypted,
            "rssi": message.rssi, "snr": message.snr, "quality": message.q,
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
        attachment_filename: Optional[str] = None,
        attachment_data: Optional[bytes] = None,
        attachment_kind: str = "file",
        image_format: Optional[str] = None,
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

        # Saved to disk (and its metadata attached to the sent entry)
        # up front, synchronously, before queueing the async delivery —
        # so the sender's own chat bubble can render the attachment
        # immediately rather than waiting on delivery, and so a
        # too-large attachment fails fast with a real error instead of
        # queuing a message that can never actually go out.
        attachment_meta = None
        if attachment_data is not None:
            try:
                attachment_meta = self._save_attachment(
                    msg_id, attachment_filename or "attachment",
                    attachment_data, attachment_kind, image_format,
                )
            except ValueError as exc:
                return False, str(exc)

        disappearing_seconds = self._disappearing_seconds_for(dest_hash_hex, user_sub)
        sent_at = time.time()
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
            "sent_at": sent_at,
            "owner":   user_sub,
            "attachment": attachment_meta,
            # See _on_delivery()'s identical field for the full rationale
            # — stamped once from this conversation's setting right now,
            # never retroactively re-timed.
            "expires_at": sent_at + disappearing_seconds if disappearing_seconds > 0 else None,
        }
        if self._msg_store:
            self._msg_store.save_sent(entry)

        def _deliver() -> None:
            import RNS, LXMF

            # Attach the sender's icon appearance if one is set. Same
            # user_sub="" gate bug as _on_delivery() above — user_sub
            # is a valid (empty-string) key for this app's single-user
            # design, not a falsy "no user" sentinel.
            #
            # Built up front, before identity recall even runs below —
            # none of this depends on the *recipient*'s identity being
            # resolved, only the sender's own data, and the recall-
            # timeout branch now needs it too (see that branch's own
            # comment for why: it can attempt a relay-retry with no
            # resolved identity at all, so it has to have fields ready
            # the same way the normal path does).
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

            # Attachment field shapes verified directly against
            # Sideband's own source (sbapp/sideband/core.py /
            # sbapp/main.py) — not guessed — see the
            # nomadportal-android-competitor-research memory:
            #   FIELD_FILE_ATTACHMENTS (0x05) = [[filename, bytes], ...]
            #   FIELD_IMAGE (0x06) = [format_str, bytes]  (single, not a list)
            if attachment_data is not None:
                if attachment_kind == "image":
                    fields[0x06] = [image_format or "png", attachment_data]
                else:
                    fields[0x05] = [[
                        _sanitize_attachment_filename(attachment_filename or "attachment"),
                        attachment_data,
                    ]]

            try:
                # Wait until we can recall the recipient's identity.
                # request_path kicks off path discovery if needed.
                dest_identity = RNS.Identity.recall(dest_hash)
                if dest_identity is None:
                    RNS.Transport.request_path(dest_hash)
                    deadline = time.time() + PATH_WAIT
                    while dest_identity is None:
                        if time.time() > deadline:
                            # Real, on-device-reported gap this branch used
                            # to have: a peer whose identity/path this
                            # device has never directly resolved — even one
                            # that *just* announced, if that announce
                            # hasn't actually propagated all the way back
                            # here yet (a real, live "I can receive from
                            # them but can't send to them" report) — used
                            # to just fail outright here. _failed() below
                            # already has a real retry-via-relay mechanic
                            # for when a resolved OPPORTUNISTIC/DIRECT
                            # attempt fails, but that callback never fires
                            # for this branch at all, since it returns
                            # before ever constructing an LXMessage or
                            # calling router.handle_outbound() — the relay
                            # fallback was silently unreachable for
                            # exactly the "no path resolved at all" case
                            # it would matter most for. PROPAGATED delivery
                            # doesn't need a resolved identity on this end
                            # (see _attempt_relay_retry's own doc comment
                            # for the real LXMF.LXMessage(destination=None,
                            # destination_hash=...) mechanism this relies
                            # on), so it's attempted here too now, not just
                            # from _failed().
                            log.warning(
                                "Identity not recalled for %s after %ss — "
                                "peer may not have announced recently",
                                dest_hash_hex[:16], PATH_WAIT,
                            )
                            if self._msg_store:
                                self._msg_store.update_sent(msg_id, "failed")
                            if self._should_retry_via_relay(router):
                                self._attempt_relay_retry(
                                    msg_id, dest_hash_hex, None, source_dest,
                                    content, title, fields, router,
                                    dest_hash=dest_hash,
                                )
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
                #
                # Both callbacks also capture `_m`'s own delivery-
                # diagnostic attributes (method/transport_encrypted/
                # delivery_attempts/rssi/snr/q — confirmed real fields on
                # LXMessage directly against the installed LXMF source,
                # not guessed) into storage. rssi/snr/q are honestly None
                # for essentially every message on this app today: RNS
                # only populates them when the *receiving* interface
                # reports real radio stats (confirmed against RNS's own
                # Transport.py — gated on an interface exposing
                # `r_stat_rssi`/`r_stat_snr`/`r_stat_q`), and only
                # RNodeInterface does that; TCP and this app's own
                # Bluetooth-mesh interface don't (RNode itself isn't wired
                # up in this app yet either — see RealInterfaceController's
                # own doc comment). Stored as None rather than fabricated,
                # same "real data or an honest gap" rule as everywhere
                # else in this app — the UI is expected to show "not
                # reported by this interface" rather than a fake number,
                # and this will start populating for real the moment a
                # radio interface that reports it is actually attached.
                def _delivered(_m):
                    self._record_send_result(msg_id, dest_hash_hex, _m, "delivered", via_relay=False)

                def _failed(_m):
                    self._record_send_result(msg_id, dest_hash_hex, _m, "failed", via_relay=False)
                    # Real "retry via relay" mechanic — see
                    # _should_retry_via_relay's own doc comment for the
                    # real precondition (a propagation node must already
                    # be configured; this never guesses/fabricates one).
                    if self._should_retry_via_relay(router):
                        self._attempt_relay_retry(
                            msg_id, dest_hash_hex, lxmf_dest, source_dest,
                            content, title, fields, router,
                        )

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
