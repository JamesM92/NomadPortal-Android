"""Named contact management with favorites."""

import hashlib
import logging
import os
import threading
import time
from typing import Optional

import yaml

log = logging.getLogger(__name__)


class ContactStore:
    def __init__(self, base_dir: str, filename: str = "contacts.yml"):
        os.makedirs(base_dir, exist_ok=True)
        self._path = os.path.join(base_dir, filename)
        self._lock = threading.Lock()
        self._data: dict = {}  # keyed by hash_hex
        self._load()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def list_contacts(self) -> list:
        with self._lock:
            return sorted(self._data.values(), key=lambda e: e.get("name", "").lower())

    def favorites(self) -> list:
        with self._lock:
            return [c for c in self._data.values() if c.get("favorited")]

    def get(self, hash_hex: str) -> Optional[dict]:
        with self._lock:
            return self._data.get(hash_hex)

    def upsert(self, hash_hex: str, name: str = "", note: str = "") -> dict:
        with self._lock:
            entry = self._data.get(hash_hex)
            if entry is None:
                entry = {
                    "hash":                  hash_hex,
                    "name":                  name or hash_hex[:16],
                    "note":                  note,
                    "favorited":             False,
                    "disappearing_seconds":  0,
                    "created":               time.time(),
                    "updated":               time.time(),
                }
                self._data[hash_hex] = entry
                log.info("Added contact %s", hash_hex[:16])
            else:
                if name:
                    entry["name"] = name
                if note:
                    entry["note"] = note
                entry["updated"] = time.time()
            snapshot = dict(self._data)
        self._persist(snapshot)
        return entry

    def set_custom_name(self, hash_hex: str, name: str) -> bool:
        """Explicitly, permanently rename a contact — sets `custom_name`
        so orchestrator.py's `_conversation_entries()` knows to stop
        preferring the live LXMF-peer-announced name for this hash from
        now on (see that function's own doc comment). Distinct from the
        plain `name` an entry gets auto-created with (upsert()/set_icon()/
        set_icon_appearance() all fall back to the hash prefix), which is
        just a placeholder meant to keep tracking the peer's own
        announced name until the user actually renames them."""
        name = (name or "").strip()
        if not name:
            return False
        with self._lock:
            entry = self._data.get(hash_hex)
            if entry is None:
                entry = {
                    "hash":                  hash_hex,
                    "name":                  name,
                    "note":                  "",
                    "favorited":             False,
                    "disappearing_seconds":  0,
                    "created":               time.time(),
                    "updated":               time.time(),
                }
                self._data[hash_hex] = entry
                log.info("Added contact (custom name) %s", hash_hex[:16])
            entry["name"] = name
            entry["custom_name"] = True
            entry["updated"] = time.time()
            snapshot = dict(self._data)
        self._persist(snapshot)
        return True

    def delete(self, hash_hex: str) -> bool:
        with self._lock:
            if hash_hex not in self._data:
                return False
            del self._data[hash_hex]
            snapshot = dict(self._data)
        self._persist(snapshot)
        log.info("Deleted contact %s", hash_hex[:16])
        return True

    def set_icon(self, hash_hex: str, icon_b64: str, icon_mime: str = "image/png") -> None:
        """Store or update a contact's raw image icon (FIELD_IMAGE, 0x06)."""
        with self._lock:
            entry = self._data.get(hash_hex)
            if entry is None:
                entry = {
                    "hash":                  hash_hex,
                    "name":                  hash_hex[:16],
                    "note":                  "",
                    "favorited":             False,
                    "disappearing_seconds":  0,
                    "created":               time.time(),
                    "updated":               time.time(),
                }
                self._data[hash_hex] = entry
                log.info("Added contact (icon) %s", hash_hex[:16])
            if entry.get("icon") != icon_b64:
                entry["icon"]      = icon_b64
                entry["icon_mime"] = icon_mime
                entry["updated"]   = time.time()
            snapshot = dict(self._data)
        self._persist(snapshot)

    def set_icon_appearance(self, hash_hex: str, glyph: str, fg_hex: str, bg_hex: str) -> None:
        """Store or update a contact's LXMF FIELD_ICON_APPEARANCE (0x04)
        descriptor — an icon name plus two hex colors, meant to be looked
        up against a Material-style icon set client-side (see
        ContactAvatar.kt), not rasterized here."""
        with self._lock:
            entry = self._data.get(hash_hex)
            if entry is None:
                entry = {
                    "hash":                  hash_hex,
                    "name":                  hash_hex[:16],
                    "note":                  "",
                    "favorited":             False,
                    "disappearing_seconds":  0,
                    "created":               time.time(),
                    "updated":               time.time(),
                }
                self._data[hash_hex] = entry
                log.info("Added contact (icon appearance) %s", hash_hex[:16])
            if (
                entry.get("icon_glyph") != glyph
                or entry.get("icon_fg") != fg_hex
                or entry.get("icon_bg") != bg_hex
            ):
                entry["icon_glyph"] = glyph
                entry["icon_fg"]    = fg_hex
                entry["icon_bg"]    = bg_hex
                entry["updated"]    = time.time()
            snapshot = dict(self._data)
        self._persist(snapshot)

    def set_favorite(self, hash_hex: str, favorited: bool) -> bool:
        with self._lock:
            entry = self._data.get(hash_hex)
            if entry is None:
                return False
            entry["favorited"] = favorited
            entry["updated"] = time.time()
            snapshot = dict(self._data)
        self._persist(snapshot)
        return True

    def set_disappearing_timer(self, hash_hex: str, seconds: int) -> bool:
        """Per-conversation disappearing-messages duration — 0 means off.
        Purely a *going-forward* setting: messaging.py reads this once
        per message at send/receive time and stamps that message's own
        `expires_at`, so changing this later never retroactively
        re-times messages already stored (see message_store.py's
        `purge_expired` and messaging.py's own doc comments). Same
        entry-must-already-exist contract as set_favorite — the caller
        (orchestrator.set_disappearing_timer) upserts first, exactly
        like set_contact_favorite already does."""
        with self._lock:
            entry = self._data.get(hash_hex)
            if entry is None:
                return False
            entry["disappearing_seconds"] = max(0, int(seconds))
            entry["updated"] = time.time()
            snapshot = dict(self._data)
        self._persist(snapshot)
        return True

    # ------------------------------------------------------------------
    # Persistence
    # ------------------------------------------------------------------

    def _load(self) -> None:
        if not os.path.exists(self._path):
            return
        try:
            with open(self._path, "r", encoding="utf-8") as fh:
                data = yaml.safe_load(fh) or {}
            with self._lock:
                self._data = data
            log.info("Loaded %d contacts", len(self._data))
        except Exception as exc:
            log.warning("Could not load contacts: %s", exc)

    def _persist(self, snapshot: dict) -> None:
        try:
            os.makedirs(os.path.dirname(self._path) or ".", exist_ok=True)
            tmp = self._path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as fh:
                yaml.dump(snapshot, fh, allow_unicode=True)
            os.replace(tmp, self._path)
        except Exception as exc:
            log.warning("Could not save contacts: %s", exc)


class ContactStoreManager:
    """Manages one ContactStore per user, stored under <base_dir>/contacts/."""

    def __init__(self, base_dir: str):
        self._contacts_dir = os.path.join(base_dir, "contacts")
        os.makedirs(self._contacts_dir, exist_ok=True)
        self._stores: dict = {}
        self._lock = threading.Lock()

    def for_user(self, user_sub: str) -> ContactStore:
        with self._lock:
            store = self._stores.get(user_sub)
        if store is not None:
            return store
        key = hashlib.sha256(user_sub.encode()).hexdigest()[:16]
        store = ContactStore(self._contacts_dir, filename=f"u_{key}.yml")
        with self._lock:
            self._stores[user_sub] = store
        return store
