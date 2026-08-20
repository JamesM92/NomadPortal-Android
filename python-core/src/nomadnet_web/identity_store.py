"""
Named RNS identity management.

Each identity is an independent RNS keypair stored under /config/identities/.
Identities are used to send fingerprints to NomadNet nodes — browsing itself
remains anonymous regardless of whether identities exist.

Storage layout:
    /config/identities/
        store.yml           — name/node-assignment metadata
        <hexhash>.id        — RNS binary key material
"""

import logging
import os
import time
from typing import Optional

import yaml

log = logging.getLogger(__name__)

ANNOUNCE_COOLDOWN = 3 * 3600  # 3 hours

import re as _re
_HEX_COLOR_RE = _re.compile(r'^#?([0-9a-fA-F]{6})$')

def _normalise_hex(value: str, fallback: str) -> str:
    """Return a #rrggbb-form hex string, or fallback if input is invalid."""
    if not isinstance(value, str):
        return fallback
    m = _HEX_COLOR_RE.match(value.strip())
    return ("#" + m.group(1).lower()) if m else fallback


def _dest_hash_hex(identity) -> str:
    """The identity's LXMF delivery *address*, as hex — computed without
    registering a Destination, so there are no Transport-table side
    effects. Shared by _default_display_name/_default_icon_appearance,
    both of which derive their result from "the hash of the address"
    per explicit design direction — a genuinely different value from
    the identity's own raw hash (identity.hexhash) — see
    AnnounceStatus.identityHash's own doc comment on the Kotlin side for
    why those two are kept distinct elsewhere in this app too.

    Real, found-and-fixed bug (2026-08-19): this used to call
    `RNS.Destination.app_and_aspects_to_name(...)`, which doesn't exist
    on the installed RNS 1.3.9's `Destination` class at all (confirmed
    directly against its source — no such method) — every call silently
    raised `AttributeError`, caught by this function's own broad
    `except Exception`, and fell through to the `identity.hexhash`
    fallback below. That fallback made this function *look* like it was
    working (it always returned a stable, valid-looking 32-hex-char
    string) while actually always returning the wrong value — the
    identity's own raw hash, not its LXMF address — for every identity
    ever created or imported. `RNS.Destination.hash(identity, app_name,
    *aspects)` is the real, correct API (confirmed as exactly what
    `Destination.__init__` itself calls to compute `self.hash`), used
    directly here instead of round-tripping through a name string at
    all. Harmless in practice for this function's own two callers
    (`_default_display_name`/`_default_icon_appearance` only need *some*
    stable per-identity hex string to pick nibbles from, not
    specifically the real address) — but genuinely wrong for any caller
    that actually wants the real LXMF address, which is exactly what
    surfaced this: `list_identities_json()`'s `dest_hash_hex` field
    could never show a correct address until this was found."""
    import RNS
    dest_hash = RNS.Destination.hash(identity, "lxmf", "delivery")
    return RNS.hexrep(dest_hash, delimit=False)


# Fun, deterministic identity flavor — every new identity gets a name and
# icon derived from its own LXMF address hash, not randomly assigned:
# reinstalling against the same keypair reproduces the exact same name/
# icon every time, rather than a fresh roll. Each of these three lists is
# exactly 16 entries so a single hex nibble (0-15) indexes it directly,
# no modulo needed.
_NAME_ADVERBS = [
    "wandering", "roaming", "drifting", "jumping", "chasing", "seeking",
    "hunting", "climbing", "diving", "soaring", "prowling", "running",
    "dashing", "creeping", "gliding", "vanishing",
]
_NAME_ADJECTIVES = [
    "silent", "curious", "restless", "hidden", "wild", "gentle", "fierce",
    "lucky", "ancient", "swift", "clever", "incredible", "quiet", "bold",
    "lone", "skittish",
]
_NAME_ANIMALS = [
    "fox", "wolf", "owl", "hawk", "bear", "raven", "lynx", "otter",
    "falcon", "badger", "dinosaur", "cobra", "panther", "ibex", "coyote",
    "stag",
]


def _default_display_name(identity) -> str:
    """"<adverb>-<adjective>-<animal>", lowercase, each word chosen by
    one hex nibble (hex[0]/[1]/[2]) of this identity's own LXMF address
    hash — see _dest_hash_hex's own doc comment. Falls back to the old
    plain 'nomadportal-<xyz>' hash-suffix scheme if anything goes
    wrong."""
    h = _dest_hash_hex(identity)
    try:
        adverb = _NAME_ADVERBS[int(h[0], 16)]
        adjective = _NAME_ADJECTIVES[int(h[1], 16)]
        animal = _NAME_ANIMALS[int(h[2], 16)]
        return f"{adverb}-{adjective}-{animal}"
    except Exception:
        return f"nomadportal-{h[-3:]}"


# Same curated icon-name pool ContactAvatar.kt's glyph editor originally
# offered before it grew a search bar and widened to every name
# IconAppearance.kt can resolve (see that file's own doc comment) — kept
# here specifically because every one of these is guaranteed to resolve
# to a real glyph client-side, not just fall back to a letter, which
# matters more for an auto-generated default than for a user's own
# deliberate pick.
_ICON_NAMES = [
    "account", "account_circle", "person", "face", "hiking", "directions_walk",
    "directions_run", "directions_bike", "directions_car", "directions_boat", "home",
    "cabin", "terrain", "forest", "park", "pets", "star", "favorite", "wifi", "signal",
    "router", "radio", "bolt", "lock", "shield", "key", "mail", "coffee", "local_cafe",
    "restaurant", "campaign", "explore", "map", "place", "public", "language", "science",
    "build", "code", "computer", "smartphone", "camera", "music_note", "sports_esports",
    "anchor", "flight", "train", "eco", "flag", "school", "work", "medical_services",
    "security", "visibility", "sunny", "cloud", "nightlight", "ac_unit", "whatshot",
]


def _hex_shorthand_to_rgb(three_hex_digits: str) -> tuple:
    """Micron's own compact 3-digit color shorthand (`` `Bxyz``/`` `Fxyz``
    in .mu source — porting-notes.md §5): each digit is doubled to form
    a full byte, e.g. 'a3f' -> (0xaa, 0x33, 0xff), the same convention
    CSS's 3-digit hex shorthand uses."""
    r = int(three_hex_digits[0] * 2, 16)
    g = int(three_hex_digits[1] * 2, 16)
    b = int(three_hex_digits[2] * 2, 16)
    return r, g, b


def _complementary_rgb(r: int, g: int, b: int) -> tuple:
    """The background's true complementary color — HSL hue+180°, same
    saturation/lightness — not a plain per-channel invert (which tends
    to land on muddy, low-contrast results for mid-tone inputs)."""
    import colorsys
    h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
    h = (h + 0.5) % 1.0
    r2, g2, b2 = colorsys.hls_to_rgb(h, l, s)
    return round(r2 * 255), round(g2 * 255), round(b2 * 255)


def _default_icon_appearance(identity) -> dict:
    """A fun, deterministic default icon derived from the hash of this
    identity's own LXMF address. Per explicit design direction, the
    glyph deliberately **reuses the exact same hex[0:3] nibbles**
    _default_display_name picks the adverb/adjective/animal from
    (not a disjoint range) — the icon and the name are meant to read as
    two views of the same identity, not two independently-rolled
    values. hex[7:10] is the background color in Micron's own
    compact-hex format (see _hex_shorthand_to_rgb), and the foreground
    is that background's true complementary color (see
    _complementary_rgb), computed rather than hash-derived — color
    stays on its own independent slice, only the glyph pick overlaps
    with the name. Shape matches entry["icon"] elsewhere in this file
    ({"glyph", "fg", "bg"}) — this is only ever the *initial* value; the
    user's own later edit via Home's glyph editor (set_icon_appearance)
    overwrites it same as any other rename would.
    """
    h = _dest_hash_hex(identity)
    try:
        glyph = _ICON_NAMES[int(h[0:3], 16) % len(_ICON_NAMES)]
        bg_r, bg_g, bg_b = _hex_shorthand_to_rgb(h[7:10])
        fg_r, fg_g, fg_b = _complementary_rgb(bg_r, bg_g, bg_b)
        return {
            "glyph": glyph,
            "bg": "#%02x%02x%02x" % (bg_r, bg_g, bg_b),
            "fg": "#%02x%02x%02x" % (fg_r, fg_g, fg_b),
        }
    except Exception:
        return {"glyph": "?", "fg": "#ffffff", "bg": "#5ba3c9"}


class IdentityStore:
    def __init__(self, base_dir: str):
        self._dir = os.path.join(base_dir, "identities")
        self._store_file = os.path.join(self._dir, "store.yml")
        self._data: dict = {}
        # See get_active_user_sub()/_load()'s own doc comments.
        self._active_user_sub: str = ""
        os.makedirs(self._dir, exist_ok=True)
        self._load()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def list_identities(self) -> list:
        return sorted(self._data.values(), key=lambda e: e["created"])

    def get(self, identity_id: str) -> Optional[dict]:
        return self._data.get(identity_id)

    def create(self, name: str = "", user_sub: Optional[str] = "") -> dict:
        """Generate a new RNS keypair, store it, return the metadata entry.

        If `name` is empty, defaults to a fun "<Verb>-<Adjective>-
        <Animal>" name deterministically derived from the new identity's
        own LXMF address hash (see _default_display_name) — likewise the
        icon appearance is seeded the same way (_default_icon_appearance)
        rather than left unset. Both are just *initial* values — renaming
        or picking a different icon later overwrites them exactly like
        any other edit would.

        [user_sub] of `None` (distinct from the default `""`) means "use
        this new identity's own hexhash as its user_sub" — multi-identity
        support's real convention for every identity beyond this app's
        original single default one (see orchestrator.py's
        `_active_user_sub` doc comment): a natural, collision-free key
        that decouples `user_sub` from any real multi-tenant meaning.
        Resolved here (not by the caller reaching into this store's own
        internals afterward) since the value isn't known until the
        keypair is actually generated below.
        """
        import RNS
        identity = RNS.Identity()
        key_file = os.path.join(self._dir, f"{identity.hexhash}.id")
        identity.to_file(key_file)
        dest_hash_hex = _dest_hash_hex(identity)
        if user_sub is None:
            user_sub = identity.hexhash
        if not name:
            name = _default_display_name(identity)
        entry = {
            "id":        identity.hexhash,
            "name":      name,
            "icon":      _default_icon_appearance(identity),
            "key_file":  key_file,
            "nodes":     [],
            "created":   time.time(),
            # The LXMF address hash (not identity.hexhash above -- a
            # genuinely different value, see _dest_hash_hex's own doc
            # comment), persisted so callers can reuse the exact same
            # "one hex nibble picks a thing" convention _default_display_name/
            # _default_icon_appearance already use, without needing to
            # reload the RNS.Identity from disk just to recompute it.
            # hex[3] is the next unused nibble after name (hex[0:3]) and
            # icon (hex[4:10]) -- orchestrator.py's default-TCP-server
            # sharding uses it.
            "dest_hash_hex": dest_hash_hex,
        }
        # Always tag with user_sub, even "" — nomadportal-android has no
        # auth and uses user_sub="" as its one real, meaningful user
        # throughout (browser.py, contact_store.py, messaging.py all key
        # off it the same way). The `if user_sub:` guard this replaced
        # meant get_for_user("") could never find an identity created
        # via ensure_for_user(""), since entry.get("user_sub") stayed
        # None (key absent) forever, never equal to the "" being searched
        # for — confirmed as the root cause of a real on-device crash:
        # every send_message() call failed with "No delivery identity
        # registered for this user" because _get_user_router("") always
        # missed. Harmless for the original Flask app's real-user_sub
        # case (unaffected — a truthy value was always stored either way).
        entry["user_sub"] = user_sub
        self._data[identity.hexhash] = entry
        self._save()
        log.info("Created identity '%s' (%s)", name, identity.hexhash[:16])
        return entry

    def import_identity(self, key_bytes: bytes, name: str = "", user_sub: Optional[str] = None) -> dict:
        """Import an existing RNS keypair from raw bytes (a `.identity`
        file's real on-disk contents, or bytes read from another
        device's export). Same on-disk format `RNS.Identity.to_file()`
        writes and `create()` already uses — confirmed cross-compatible
        with Columba's own `.identity` export/import (its
        `IdentityFileReader.kt` expects the identical raw private-key
        byte layout), so a file exported from either app imports
        cleanly into the other.

        Raises ValueError if [key_bytes] isn't a valid RNS identity
        (wrong size / unparseable) — same "let the caller find out"
        contract as this module's other real-failure paths, so the
        Kotlin-side importIdentity() call can surface a real reason
        rather than silently no-opping on a corrupt/wrong file.

        If an identity with this exact hexhash already exists, its
        existing entry is returned unchanged rather than creating a
        duplicate — importing a file you already have is a no-op, not
        an error.
        """
        import RNS
        identity = RNS.Identity.from_bytes(key_bytes)
        if identity is None:
            raise ValueError("Not a valid Reticulum identity file")
        if user_sub is None:
            user_sub = identity.hexhash

        existing = self._data.get(identity.hexhash)
        if existing is not None:
            return existing

        key_file = os.path.join(self._dir, f"{identity.hexhash}.id")
        identity.to_file(key_file)
        dest_hash_hex = _dest_hash_hex(identity)
        entry = {
            "id":            identity.hexhash,
            "name":          name or _default_display_name(identity),
            "icon":          _default_icon_appearance(identity),
            "key_file":      key_file,
            "nodes":         [],
            "created":       time.time(),
            "dest_hash_hex": dest_hash_hex,
            "user_sub":      user_sub,
        }
        self._data[identity.hexhash] = entry
        self._save()
        log.info("Imported identity '%s' (%s)", entry["name"], identity.hexhash[:16])
        return entry

    def export_key_bytes(self, identity_id: str) -> Optional[bytes]:
        """Raw bytes of this identity's own `.id` key file, for sharing
        as a real `.identity` export — the counterpart to
        `import_identity` above. None if the identity or its key file
        doesn't exist."""
        entry = self._data.get(identity_id)
        if not entry:
            return None
        key_file = entry.get("key_file", "")
        if not key_file or not os.path.exists(key_file):
            return None
        with open(key_file, "rb") as fh:
            return fh.read()

    def get_active_user_sub(self) -> str:
        """The user_sub of the identity multi-identity switching should
        treat as active on this and future app starts — persisted here
        (not Kotlin-side DataStore) since every other piece of
        identity-related persisted state already lives in this same
        store.yml. Defaults to "" (this app's original single-identity
        default) for any store that predates this field, so an existing
        install's one identity is still correctly "active" with no
        migration step needed — see _load()'s own doc comment for the
        actual on-disk migration."""
        return self._active_user_sub

    def set_active_user_sub(self, user_sub: str) -> None:
        self._active_user_sub = user_sub
        self._save()

    def ensure_for_user(self, user_sub: str, display_name: str = "") -> dict:
        """Return the identity for this user, creating one if none exists yet.

        `display_name` is accepted for API compatibility but no longer used
        for the default name — new identities auto-name based on their LXMF
        address.
        """
        for entry in self._data.values():
            if entry.get("user_sub") == user_sub:
                return entry
        return self.create("", user_sub=user_sub)

    def get_for_user(self, user_sub: str) -> Optional[dict]:
        for entry in self._data.values():
            if entry.get("user_sub") == user_sub:
                return entry
        return None

    def reset(self, identity_id: str) -> Optional[dict]:
        """Delete an identity and immediately generate a fresh keypair for the same user.

        The new identity gets a freshly auto-generated default name (different
        LXMF address → different suffix). Reset implies starting clean.
        """
        entry = self._data.get(identity_id)
        if entry is None:
            return None
        user_sub = entry.get("user_sub", "")
        self.delete(identity_id)
        return self.create("", user_sub=user_sub)

    def delete(self, identity_id: str) -> bool:
        entry = self._data.pop(identity_id, None)
        if entry is None:
            return False
        key_file = entry.get("key_file", "")
        if key_file and os.path.exists(key_file):
            os.remove(key_file)
        self._save()
        log.info("Deleted identity %s", identity_id[:16])
        return True

    def rename(self, identity_id: str, new_name: str) -> bool:
        entry = self._data.get(identity_id)
        if not entry:
            return False
        entry["name"] = new_name
        self._save()
        return True

    # ------------------------------------------------------------------
    # User icon (LXMF FIELD_ICON_APPEARANCE — vector descriptor)
    # ------------------------------------------------------------------

    def set_icon_appearance(self, identity_id: str, glyph: str, fg_hex: str, bg_hex: str) -> bool:
        """Store this identity's icon descriptor: an icon name (looked up
        against a Material-style icon set client-side — see
        ContactAvatar.kt/IconAppearance.kt, not a literal single
        character) and two hex colors."""
        entry = self._data.get(identity_id)
        if not entry:
            return False
        glyph  = (glyph or "?").strip()[:40] or "?"
        fg_hex = _normalise_hex(fg_hex, "#ffffff")
        bg_hex = _normalise_hex(bg_hex, "#5ba3c9")
        entry["icon"] = {"glyph": glyph, "fg": fg_hex, "bg": bg_hex}
        self._save()
        return True

    def get_icon_appearance(self, identity_id: str) -> Optional[dict]:
        entry = self._data.get(identity_id)
        return entry.get("icon") if entry else None

    def get_icon_appearance_for_user(self, user_sub: str) -> Optional[dict]:
        entry = self.get_for_user(user_sub)
        return entry.get("icon") if entry else None

    def load_rns_identity(self, identity_id: str):
        """Return the RNS.Identity object for a stored identity, or None."""
        import RNS
        entry = self._data.get(identity_id)
        if not entry:
            return None
        key_file = entry.get("key_file", "")
        if not os.path.exists(key_file):
            log.warning("Key file missing for identity %s: %s", identity_id[:16], key_file)
            return None
        try:
            return RNS.Identity.from_file(key_file)
        except Exception as exc:
            log.error("Could not load identity %s: %s", identity_id[:16], exc)
            return None

    def get_dest_hash_hex(self, identity_id: str) -> Optional[str]:
        """The real LXMF delivery address for a stored identity — loads
        it (`load_rns_identity`) and computes it live (`_dest_hash_hex`)
        rather than trusting `entry["dest_hash_hex"]`, which is real but
        stale for any identity created before that helper's own bug fix
        (see its own doc comment) and is never retroactively corrected
        on disk. The real public entry point for anything outside this
        module that needs an identity's LXMF address —
        orchestrator.py's `list_identities_json()` is the one real
        caller so far. None if the identity doesn't exist or its key
        file can't be loaded."""
        identity = self.load_rns_identity(identity_id)
        if identity is None:
            return None
        return _dest_hash_hex(identity)

    def check_cooldown(self, identity_id: str) -> tuple[bool, str, float]:
        """Check the announce cooldown and update last_announced if allowed.

        Returns (ok, message, next_allowed_timestamp).  Does NOT actually
        send an announce — the caller is responsible for that.
        """
        entry = self._data.get(identity_id)
        if not entry:
            return False, "Identity not found", 0.0

        now = time.time()
        last = entry.get("last_announced", 0.0)
        next_allowed = last + ANNOUNCE_COOLDOWN
        if now < next_allowed:
            remaining = int(next_allowed - now)
            h, m = divmod(remaining // 60, 60)
            return False, f"Cooldown active — next announce in {h}h {m}m", next_allowed

        entry["last_announced"] = now
        self._save()
        next_allowed = now + ANNOUNCE_COOLDOWN
        log.info("Announce cooldown cleared for '%s' (%s)", entry["name"], identity_id[:16])
        return True, "ok", next_allowed

    def announce(self, identity_id: str) -> tuple[bool, str, float]:
        """Backward-compat: check cooldown then send a raw announce.

        Prefer using check_cooldown() + MessagingService.do_announce() so that
        the display name is included in app_data via the LXMRouter.
        """
        import RNS
        import RNS.vendor.umsgpack as msgpack

        ok, message, next_allowed = self.check_cooldown(identity_id)
        if not ok:
            return ok, message, next_allowed

        entry = self._data.get(identity_id)
        identity = self.load_rns_identity(identity_id)
        if identity is None:
            return False, "Identity not found or key file missing", 0.0

        try:
            dest = RNS.Destination(
                identity,
                RNS.Destination.IN,
                RNS.Destination.SINGLE,
                "lxmf",
                "delivery",
            )
            dest.set_proof_strategy(RNS.Destination.PROVE_ALL)
            app_data = msgpack.packb([entry["name"].encode("utf-8"), 0])
            dest.announce(app_data=app_data)
            log.info("Announced identity '%s' (%s)", entry["name"], identity_id[:16])
            return True, "Announced successfully", next_allowed
        except Exception as exc:
            log.error("Announce failed for %s: %s", identity_id[:16], exc)
            return False, str(exc), 0.0

    # ------------------------------------------------------------------
    # Auto-identify (sticky toggle): which nodes should every page fetch
    # from this user identify with link.identify(). Set explicitly via
    # the address-bar fingerprint button — never automatic.
    # ------------------------------------------------------------------

    def is_identified_to(self, identity_id: str, node_hash: str) -> bool:
        entry = self._data.get(identity_id)
        if not entry:
            return False
        return node_hash.lower() in (entry.get("identified_nodes") or [])

    def set_identified(self, identity_id: str, node_hash: str, value: bool) -> bool:
        entry = self._data.get(identity_id)
        if not entry:
            return False
        nh = node_hash.lower()
        nodes = entry.setdefault("identified_nodes", [])
        if value and nh not in nodes:
            nodes.append(nh)
        elif not value and nh in nodes:
            nodes.remove(nh)
        else:
            return True
        self._save()
        return True

    def get_identified_nodes(self, identity_id: str) -> list:
        entry = self._data.get(identity_id)
        return list(entry.get("identified_nodes") or []) if entry else []

    def clear_identified_nodes(self, identity_id: str) -> None:
        """Reset the identify-on-fetch list to empty.

        Called at every login so the fingerprint toggle defaults to off
        per session — never carried over from a previous browsing window.
        """
        entry = self._data.get(identity_id)
        if not entry:
            return
        if entry.get("identified_nodes"):
            entry["identified_nodes"] = []
            self._save()

    def assign_node(self, identity_id: str, node_hash: str) -> bool:
        """Mark an identity as associated with a node (for display purposes)."""
        entry = self._data.get(identity_id)
        if not entry:
            return False
        if node_hash not in entry["nodes"]:
            entry["nodes"].append(node_hash)
            self._save()
        return True

    def unassign_node(self, identity_id: str, node_hash: str) -> bool:
        entry = self._data.get(identity_id)
        if not entry:
            return False
        if node_hash in entry["nodes"]:
            entry["nodes"].remove(node_hash)
            self._save()
        return True

    # ------------------------------------------------------------------
    # Persistence
    # ------------------------------------------------------------------

    def _load(self) -> None:
        if not os.path.exists(self._store_file):
            return
        with open(self._store_file, "r", encoding="utf-8") as fh:
            raw = yaml.safe_load(fh) or {}
        if "identities" in raw and isinstance(raw.get("identities"), dict):
            # Current on-disk shape: {"identities": {hexhash: entry,
            # ...}, "active_user_sub": "..."}.
            self._data = raw["identities"]
            self._active_user_sub = raw.get("active_user_sub", "") or ""
        else:
            # Every store.yml written before active-identity tracking
            # existed is just the flat {hexhash: entry, ...} dict
            # directly at the top level — [raw] itself, no wrapper.
            # Loaded as-is (no explicit migration step needed);
            # _active_user_sub stays "" (this app's original
            # single-identity default, __init__'s own initial value),
            # and the next _save() call naturally rewrites the file in
            # the current wrapped shape.
            self._data = raw
            self._active_user_sub = ""

    def _save(self) -> None:
        with open(self._store_file, "w", encoding="utf-8") as fh:
            yaml.dump({"identities": self._data, "active_user_sub": self._active_user_sub}, fh)
