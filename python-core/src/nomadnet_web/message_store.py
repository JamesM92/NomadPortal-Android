"""Persistent sent/received message storage."""

import json
import logging
import os
import threading
import time
from typing import Optional

log = logging.getLogger(__name__)
MAX_MESSAGES = 500


class MessageStore:
    def __init__(self, storage_dir: str):
        self._path = os.path.join(storage_dir, "messages.json")
        self._lock = threading.Lock()
        self._sent:     list = []
        self._received: list = []
        self._load()

    def save_sent(self, entry: dict) -> None:
        with self._lock:
            self._sent.insert(0, entry)
            self._sent = self._sent[:MAX_MESSAGES]
            snapshot = self._snapshot()
        self._persist(snapshot)

    def save_received(self, entry: dict) -> None:
        with self._lock:
            self._received.insert(0, entry)
            self._received = self._received[:MAX_MESSAGES]
            snapshot = self._snapshot()
        self._persist(snapshot)

    def sent_messages(self, owner: Optional[str] = None) -> list:
        """[owner] is a user_sub — None (the default) returns every
        message ever stored, across every identity, matching this
        method's original behavior (kept so existing single-identity
        callers/tests need no change). Pass an explicit owner to scope
        to one identity's own sent messages only — the real fix
        multi-identity support needed: every entry already carries its
        own `owner` field (see `delete_conversation`'s/`mark_read`'s own
        owner filtering below), this method just never filtered by it
        before now."""
        with self._lock:
            all_sent = list(self._sent)
        if owner is None:
            return all_sent
        return [m for m in all_sent if m.get("owner") == owner]

    def received_messages(self, owner: Optional[str] = None) -> list:
        """See `sent_messages`'s own doc comment — same contract."""
        with self._lock:
            all_received = list(self._received)
        if owner is None:
            return all_received
        return [m for m in all_received if m.get("owner") == owner]

    def update_sent(
        self,
        msg_id: str,
        state: str,
        real_id: str = None,
        method: str = None,
        transport_encrypted: bool = None,
        delivery_attempts: int = None,
        rssi: float = None,
        snr: float = None,
        quality: float = None,
    ) -> None:
        """Update state (and optionally final ID) of a queued sent message.

        The diagnostic kwargs (method/transport_encrypted/delivery_attempts/
        rssi/snr/quality) mirror LXMessage's own same-named attributes
        (confirmed directly against the installed LXMF package's
        LXMessage.py — not guessed), captured by messaging.py's delivery/
        failed callbacks at the moment they fire and threaded through here
        so a message's real delivery diagnostics survive into storage, not
        just its final state. Each is `None` by default meaning "don't
        touch this field" (not "clear it") — a `_failed` callback, for
        instance, still knows `method`/`transport_encrypted` even though
        there's no successful delivery to report attempts/RF stats for.

        `state_changed_at` is stamped unconditionally on every call — a
        genuine "when did this reach its current state" timestamp,
        distinct from the entry's own `sent_at` (when it was queued)."""
        with self._lock:
            for m in self._sent:
                if m.get("id") == msg_id:
                    m["state"] = state
                    m["state_changed_at"] = time.time()
                    if real_id and real_id != msg_id:
                        m["id"] = real_id
                    if method is not None:
                        m["method"] = method
                    if transport_encrypted is not None:
                        m["transport_encrypted"] = transport_encrypted
                    if delivery_attempts is not None:
                        m["delivery_attempts"] = delivery_attempts
                    if rssi is not None:
                        m["rssi"] = rssi
                    if snr is not None:
                        m["snr"] = snr
                    if quality is not None:
                        m["quality"] = quality
                    snapshot = self._snapshot()
                    break
            else:
                return
        self._persist(snapshot)

    def delete_conversation(self, hash_hex: str, owner: str = "") -> int:
        """Remove all sent+received messages for a given counterparty hash.

        When owner is provided, only messages belonging to that user are removed.
        """
        with self._lock:
            before = len(self._sent) + len(self._received)
            def _keep_sent(m):
                if m.get("dest") != hash_hex:
                    return True
                return bool(owner) and m.get("owner") != owner
            def _keep_recv(m):
                if m.get("source") != hash_hex:
                    return True
                return bool(owner) and m.get("owner") != owner
            self._sent     = [m for m in self._sent     if _keep_sent(m)]
            self._received = [m for m in self._received if _keep_recv(m)]
            removed = before - len(self._sent) - len(self._received)
            snapshot = self._snapshot()
        self._persist(snapshot)
        return removed

    def delete_owner(self, owner: str) -> list:
        """Removes every sent+received message belonging to [owner],
        regardless of counterparty — the real backing for multi-identity's
        "deleting an identity also deletes its message history" cascade
        (orchestrator.py's `delete_identity()`), a genuinely different
        operation from `delete_conversation` above (that one is scoped
        to a single counterparty; this one is scoped to a single
        identity's entire history). Returns the removed entries, same
        "caller cleans up attachment files" contract as `purge_expired`
        — this module has no concept of attachment files on disk, that's
        `messaging.py`'s job."""
        with self._lock:
            removed = [m for m in self._sent if m.get("owner") == owner]
            removed += [m for m in self._received if m.get("owner") == owner]
            self._sent = [m for m in self._sent if m.get("owner") != owner]
            self._received = [m for m in self._received if m.get("owner") != owner]
            snapshot = self._snapshot()
        self._persist(snapshot)
        return removed

    def purge_expired(self, now: float = None) -> list:
        """Removes every sent/received entry whose `expires_at` (a unix
        timestamp messaging.py stamps at send/receive time, per the
        conversation's disappearing-messages setting at that moment —
        absent/None means "never expires", the default for every
        message stored before this feature existed too, no migration
        needed) has passed. Returns the removed entries so the caller
        can clean up anything else keyed off them — this module has no
        concept of attachment files on disk, that's messaging.py's job
        (see its own purge_expired_messages doc comment for why that
        part matters more here than it does for delete_conversation)."""
        if now is None:
            now = time.time()
        with self._lock:
            def _expired(m):
                exp = m.get("expires_at")
                return exp is not None and exp <= now
            removed = [m for m in self._sent if _expired(m)] + [m for m in self._received if _expired(m)]
            if removed:
                self._sent     = [m for m in self._sent     if not _expired(m)]
                self._received = [m for m in self._received if not _expired(m)]
                snapshot = self._snapshot()
            else:
                snapshot = None
        if snapshot is not None:
            self._persist(snapshot)
        return removed

    def mark_read(self, msg_id: str, owner: str = "") -> None:
        with self._lock:
            for m in self._received:
                if m.get("id") == msg_id:
                    if owner and m.get("owner") != owner:
                        break
                    m["read"] = True
            snapshot = self._snapshot()
        self._persist(snapshot)

    def mark_unread(self, msg_id: str, owner: str = "") -> None:
        """Mirrors mark_read exactly, setting `read` back to False —
        orchestrator.mark_conversation_unread() calls this on a single
        message (the conversation's most recent), not the whole history;
        this method itself has no opinion about that, same as mark_read."""
        with self._lock:
            for m in self._received:
                if m.get("id") == msg_id:
                    if owner and m.get("owner") != owner:
                        break
                    m["read"] = False
            snapshot = self._snapshot()
        self._persist(snapshot)

    def _snapshot(self) -> dict:
        return {"sent": list(self._sent), "received": list(self._received)}

    def _load(self) -> None:
        if not os.path.exists(self._path):
            return
        try:
            with open(self._path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
            self._sent     = data.get("sent", [])
            self._received = data.get("received", [])
            log.info("Loaded %d sent, %d received messages",
                     len(self._sent), len(self._received))
        except Exception as exc:
            log.warning("Could not load messages: %s", exc)

    def _persist(self, snapshot: dict) -> None:
        try:
            os.makedirs(os.path.dirname(self._path) or ".", exist_ok=True)
            tmp = self._path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as fh:
                json.dump(snapshot, fh, indent=2)
            os.replace(tmp, self._path)
        except Exception as exc:
            log.warning("Could not save messages: %s", exc)
