"""Tests for ``MessagingService._send()``'s sent-message storage.

Motivation: sent-message entries only ever stored a 120-char ``preview``,
never the full ``content``. The frontend's ``renderChatLog()`` falls back
to ``preview`` whenever ``content`` is missing, so every sent message
shown in the sender's own open conversation was silently clipped at 120
characters — even though the full text was, and still is, what actually
went out over LXMF. This guards the fix: the full content must always be
stored alongside the preview.

``_send()`` spawns a background delivery thread that does real RNS/LXMF
work (path discovery, link establishment) once started — irrelevant to
what changed here, and not something a unit test should exercise. These
tests stub ``threading.Thread`` so that thread is never actually started;
we only assert on the synchronous prefix of ``_send()`` — building the
entry dict and calling ``save_sent`` — which is deterministic and runs
before the (stubbed-out) thread would begin.
"""

import time
import types

import pytest

from nomadnet_web.messaging import MessagingService

LONG_MESSAGE = "x" * 500  # comfortably past the old 120-char preview cutoff
DEST_HASH = "aa" * 16


class _StubMessageStore:
    """Records what's passed to save_sent; nothing else is exercised."""

    def __init__(self):
        self.saved = []

    def save_sent(self, entry):
        self.saved.append(entry)


class _NoOpThread:
    """Stand-in for ``threading.Thread``.

    Captures the target but never runs it — ``_deliver()``'s RNS/LXMF
    network calls have nothing to do with what these tests check, and
    running them for real would need an actual RNS instance.
    """

    def __init__(self, target=None, daemon=None, **kwargs):
        self.target = target

    def start(self):
        pass


@pytest.fixture(autouse=True)
def _no_background_delivery(monkeypatch):
    monkeypatch.setattr("nomadnet_web.messaging.threading.Thread", _NoOpThread)


@pytest.fixture
def service(tmp_path):
    svc = MessagingService(
        storage_path=str(tmp_path), message_store=_StubMessageStore()
    )
    # Bypass real identity/router setup — _send() only needs a dict with
    # "dest" (read via .hexhash inside the stubbed-out delivery thread,
    # never touched by the synchronous path under test) and "router" to
    # get past its early-return guard.
    svc._get_user_router = lambda user_sub: {
        "dest":   types.SimpleNamespace(hexhash=DEST_HASH),
        "router": types.SimpleNamespace(),
    }
    return svc


def test_send_stores_full_content_not_just_preview(service):
    ok, _ = service.send_message(DEST_HASH, LONG_MESSAGE, user_sub="u1")
    assert ok

    saved = service._msg_store.saved
    assert len(saved) == 1
    assert saved[0]["content"] == LONG_MESSAGE
    assert len(saved[0]["content"]) == 500


def test_preview_stays_truncated_for_the_conversation_list(service):
    ok, _ = service.send_message(DEST_HASH, LONG_MESSAGE, user_sub="u1")
    assert ok

    entry = service._msg_store.saved[0]
    assert entry["preview"] == LONG_MESSAGE[:120]
    assert len(entry["preview"]) == 120


def test_short_message_content_and_preview_match(service):
    ok, _ = service.send_message(DEST_HASH, "hello", user_sub="u1")
    assert ok

    entry = service._msg_store.saved[0]
    assert entry["content"] == "hello"
    assert entry["preview"] == "hello"


def test_empty_content_stores_empty_not_none(service):
    # _send() guards content with `content or ""` — verify that survives
    # for both fields rather than storing None (which would break the
    # frontend's `m.content || m.preview || ''` fallback chain).
    ok, _ = service.send_message(DEST_HASH, "", user_sub="u1")
    assert ok

    entry = service._msg_store.saved[0]
    assert entry["content"] == ""
    assert entry["preview"] == ""


# ---------------------------------------------------------------------------
# Disappearing messages
# ---------------------------------------------------------------------------
#
# expires_at is stamped once, synchronously, inside the same _send() prefix
# already under test above — no need for a separate delivery-thread stub.
# The receive-side stamping in _on_delivery() isn't covered here (no
# existing test in this file exercises _on_delivery at all — it needs a
# real-shaped LXMF Message object, out of scope for this synchronous-prefix
# style of test); purge_expired_messages()'s attachment-cleanup path is
# covered separately below, against a real tmp_path file.


class _StubContactStore:
    """Records the one dict test cases care about; .get() mirrors
    contact_store.ContactStore.get()'s "None if never created" contract."""

    def __init__(self, entries: dict):
        self._entries = entries

    def get(self, hash_hex):
        return self._entries.get(hash_hex)


class _StubContactMgr:
    def __init__(self, entries: dict):
        self._store = _StubContactStore(entries)

    def for_user(self, user_sub):
        return self._store


def test_send_stamps_expires_at_when_disappearing_timer_is_on(service):
    service._contact_mgr = _StubContactMgr({DEST_HASH: {"disappearing_seconds": 300}})

    before = time.time()
    ok, _ = service.send_message(DEST_HASH, "hello", user_sub="u1")
    assert ok

    entry = service._msg_store.saved[0]
    assert entry["expires_at"] is not None
    assert before + 300 <= entry["expires_at"] <= time.time() + 300


def test_send_leaves_expires_at_null_when_timer_is_off(service):
    service._contact_mgr = _StubContactMgr({DEST_HASH: {"disappearing_seconds": 0}})

    ok, _ = service.send_message(DEST_HASH, "hello", user_sub="u1")
    assert ok
    assert service._msg_store.saved[0]["expires_at"] is None


def test_send_leaves_expires_at_null_with_no_contact_store_entry(service):
    # No ContactStore entry yet at all for this hash (message-history-only
    # contact) — same None-safe handling as every other contact lookup in
    # this file, not a crash.
    service._contact_mgr = _StubContactMgr({})

    ok, _ = service.send_message(DEST_HASH, "hello", user_sub="u1")
    assert ok
    assert service._msg_store.saved[0]["expires_at"] is None


class _StubMessageStoreWithExpiry(_StubMessageStore):
    """Adds purge_expired() on top of the existing save_sent()-only stub —
    a real MessageStore.purge_expired() shape is exercised separately in
    test_message_store.py-equivalent coverage; this just hands back
    whatever the test pre-seeds, so purge_expired_messages()'s own
    attachment-cleanup logic can be tested in isolation."""

    def __init__(self, to_remove):
        super().__init__()
        self._to_remove = to_remove

    def purge_expired(self):
        return self._to_remove


def test_purge_expired_messages_removes_attachment_file(tmp_path):
    attachment_path = tmp_path / "expired_photo.jpg"
    attachment_path.write_bytes(b"fake image bytes")
    assert attachment_path.exists()

    svc = MessagingService(
        storage_path=str(tmp_path),
        message_store=_StubMessageStoreWithExpiry(
            [{"id": "m1", "attachment": {"path": str(attachment_path)}}]
        ),
    )
    removed_count = svc.purge_expired_messages()

    assert removed_count == 1
    assert not attachment_path.exists()


def test_purge_expired_messages_tolerates_missing_attachment_file(tmp_path):
    # Already gone (or never existed) — must not raise, just skip it.
    svc = MessagingService(
        storage_path=str(tmp_path),
        message_store=_StubMessageStoreWithExpiry(
            [{"id": "m1", "attachment": {"path": str(tmp_path / "already_gone.jpg")}}]
        ),
    )
    assert svc.purge_expired_messages() == 1


def test_purge_expired_messages_handles_no_attachment(tmp_path):
    svc = MessagingService(
        storage_path=str(tmp_path),
        message_store=_StubMessageStoreWithExpiry([{"id": "m1", "attachment": None}]),
    )
    assert svc.purge_expired_messages() == 1


def test_purge_expired_messages_with_nothing_expired(tmp_path):
    svc = MessagingService(
        storage_path=str(tmp_path),
        message_store=_StubMessageStoreWithExpiry([]),
    )
    assert svc.purge_expired_messages() == 0


# ---------------------------------------------------------------------------
# Contact blocking
# ---------------------------------------------------------------------------
#
# `_is_blocked` is tested directly (same style as `_disappearing_seconds_for`
# being exercised via send_message's stamping, just without an equivalent
# public side effect to observe it through) rather than via `_on_delivery` —
# that needs a real-shaped LXMF Message object, same "out of scope for this
# style of test" reasoning already noted above for the receive-side
# expires_at stamping. The actual drop-the-message behavior this flag drives
# is a single early-return at the top of `_on_delivery`, reviewed by hand
# against `_is_blocked`'s own real return value here.


def test_is_blocked_true_for_blocked_contact(service):
    service._contact_mgr = _StubContactMgr({DEST_HASH: {"blocked": True}})
    assert service._is_blocked(DEST_HASH, "u1") is True


def test_is_blocked_false_for_unblocked_contact(service):
    service._contact_mgr = _StubContactMgr({DEST_HASH: {"blocked": False}})
    assert service._is_blocked(DEST_HASH, "u1") is False


def test_is_blocked_false_with_no_contact_store_entry(service):
    # Same None-safe handling as _disappearing_seconds_for — a hash with
    # no ContactStore entry at all can't be blocked (set_contact_blocked
    # always upserts first, so "blocked" only ever appears on a real entry).
    service._contact_mgr = _StubContactMgr({})
    assert service._is_blocked(DEST_HASH, "u1") is False


def test_is_blocked_false_with_no_contact_mgr(tmp_path):
    svc = MessagingService(storage_path=str(tmp_path), message_store=_StubMessageStore())
    assert svc._is_blocked(DEST_HASH, "u1") is False


# ---------------------------------------------------------------------------
# mark_unread
# ---------------------------------------------------------------------------


class _StubMessageStoreWithMarking(_StubMessageStore):
    """Adds mark_read/mark_unread recording on top of the existing
    save_sent()-only stub — real dict-flip mechanics are covered
    separately in test_message_store.py; this just records calls so
    MessagingService's own thin wrapper methods can be verified in
    isolation, same split as purge_expired/purge_expired_messages."""

    def __init__(self):
        super().__init__()
        self.mark_read_calls = []
        self.mark_unread_calls = []

    def mark_read(self, msg_id, owner=""):
        self.mark_read_calls.append((msg_id, owner))

    def mark_unread(self, msg_id, owner=""):
        self.mark_unread_calls.append((msg_id, owner))


def test_mark_unread_delegates_to_message_store(tmp_path):
    store = _StubMessageStoreWithMarking()
    svc = MessagingService(storage_path=str(tmp_path), message_store=store)

    svc.mark_unread("r1", owner="u1")

    assert store.mark_unread_calls == [("r1", "u1")]
    assert store.mark_read_calls == []


def test_mark_read_delegates_to_message_store(tmp_path):
    store = _StubMessageStoreWithMarking()
    svc = MessagingService(storage_path=str(tmp_path), message_store=store)

    svc.mark_read("r1", owner="u1")

    assert store.mark_read_calls == [("r1", "u1")]
    assert store.mark_unread_calls == []
