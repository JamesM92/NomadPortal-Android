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

import os
import time
import types

import pytest

import RNS
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
# Contacts-only messaging (allowlist mode)
# ---------------------------------------------------------------------------


def test_allows_sender_true_by_default(service):
    # contacts-only mode defaults off — every sender is allowed,
    # regardless of whether a ContactStore entry exists.
    service._contact_mgr = _StubContactMgr({})
    assert service._allows_sender(DEST_HASH, "u1") is True


def test_allows_sender_false_for_unknown_sender_when_contacts_only_enabled(service):
    service.set_contacts_only_messages(True)
    service._contact_mgr = _StubContactMgr({})
    assert service._allows_sender(DEST_HASH, "u1") is False


def test_allows_sender_true_for_known_contact_when_contacts_only_enabled(service):
    service.set_contacts_only_messages(True)
    # A real ContactStore entry exists (favorited, blocked=False, or any
    # other real entry shape) — being *known* is what matters here, not
    # any particular field on the entry.
    service._contact_mgr = _StubContactMgr({DEST_HASH: {"favorited": True}})
    assert service._allows_sender(DEST_HASH, "u1") is True


def test_allows_sender_false_with_no_contact_mgr_when_contacts_only_enabled(tmp_path):
    svc = MessagingService(storage_path=str(tmp_path), message_store=_StubMessageStore())
    svc.set_contacts_only_messages(True)
    assert svc._allows_sender(DEST_HASH, "u1") is False


def test_set_contacts_only_messages_can_be_toggled_back_off(service):
    service.set_contacts_only_messages(True)
    service._contact_mgr = _StubContactMgr({})
    assert service._allows_sender(DEST_HASH, "u1") is False

    service.set_contacts_only_messages(False)
    assert service._allows_sender(DEST_HASH, "u1") is True


def test_get_contacts_only_messages_reflects_current_state(service):
    assert service.get_contacts_only_messages() is False
    service.set_contacts_only_messages(True)
    assert service.get_contacts_only_messages() is True


# ---------------------------------------------------------------------------
# Retry via relay on send failure
#
# _should_retry_via_relay is the real decision logic, kept deliberately
# separate from _attempt_relay_retry's actual LXMessage-construction
# mechanics (which needs a real RNS/LXMF Router, same "not something a
# unit test should exercise" reasoning as _deliver()'s own background
# thread — see this file's own top doc comment) so it's testable here
# with a lightweight fake router instead.
# ---------------------------------------------------------------------------


class _FakeRouter:
    def __init__(self, outbound_propagation_node=None):
        self._node = outbound_propagation_node

    def get_outbound_propagation_node(self):
        return self._node


def test_should_retry_via_relay_false_by_default(service):
    assert service._should_retry_via_relay(_FakeRouter(b"\xaa" * 16)) is False


def test_should_retry_via_relay_false_with_no_propagation_node_even_if_enabled(service):
    service.set_retry_via_relay(True)
    assert service._should_retry_via_relay(_FakeRouter(None)) is False


def test_should_retry_via_relay_true_when_enabled_and_node_available(service):
    service.set_retry_via_relay(True)
    assert service._should_retry_via_relay(_FakeRouter(b"\xaa" * 16)) is True


def test_should_retry_via_relay_false_if_router_raises(service):
    # A malformed/stale router object shouldn't crash the decision —
    # same "never let a diagnostic-only check take down real delivery
    # logic" caution as elsewhere in this file.
    class _BrokenRouter:
        def get_outbound_propagation_node(self):
            raise RuntimeError("boom")

    service.set_retry_via_relay(True)
    assert service._should_retry_via_relay(_BrokenRouter()) is False


def test_get_retry_via_relay_reflects_current_state(service):
    assert service.get_retry_via_relay() is False
    service.set_retry_via_relay(True)
    assert service.get_retry_via_relay() is True


class _FakeLxmMessage:
    """Carries just the attribute names `_record_send_result` reads off
    a real LXMessage (method/transport_encrypted/delivery_attempts/rssi/
    snr/q/hash) — real RNS.Identity/LXMessage construction needs a live
    Reticulum instance this test harness doesn't build, same reasoning
    as every other real-network-dependent path this file leaves
    unstubbed-but-untested."""

    def __init__(self, method=0x01, transport_encrypted=True, delivery_attempts=1,
                 rssi=None, snr=None, q=None, hash_bytes=b"\xbb" * 16):
        self.method = method
        self.transport_encrypted = transport_encrypted
        self.delivery_attempts = delivery_attempts
        self.rssi = rssi
        self.snr = snr
        self.q = q
        self.hash = hash_bytes


def test_record_send_result_delivered_stores_real_id_and_diagnostics(tmp_path):
    store = _StubMessageStore()
    store.updates = []
    store.update_sent = lambda *a, **kw: store.updates.append((a, kw))
    svc = MessagingService(storage_path=str(tmp_path), message_store=store)

    svc._record_send_result("msg1", DEST_HASH, _FakeLxmMessage(), "delivered", via_relay=True)

    assert len(store.updates) == 1
    args, kwargs = store.updates[0]
    assert args[:2] == ("msg1", "delivered")
    assert kwargs["method"] == "opportunistic"
    assert kwargs["transport_encrypted"] is True
    assert kwargs["real_id"] == ("bb" * 16)


def test_record_send_result_failed_does_not_set_real_id(tmp_path):
    store = _StubMessageStore()
    store.updates = []
    store.update_sent = lambda *a, **kw: store.updates.append((a, kw))
    svc = MessagingService(storage_path=str(tmp_path), message_store=store)

    svc._record_send_result("msg1", DEST_HASH, _FakeLxmMessage(), "failed", via_relay=False)

    args, kwargs = store.updates[0]
    assert args[:2] == ("msg1", "failed")
    assert kwargs["real_id"] is None


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


# ---------------------------------------------------------------------------
# import_scanned_contact — real RNS.Identity.remember()/recall() round trip,
# not stubbed. Unlike _deliver()'s background thread (real network I/O, out
# of scope for this style of test — see this file's own top doc comment),
# Identity.remember()/recall() are pure local bookkeeping with no network
# dependency, so this exercises the real RNS call, not a fake of it. The
# `_no_use=True` recall() calls below are a test-only convenience (skips a
# RNS.Reticulum.get_instance() bookkeeping call that needs a live Reticulum
# instance this test harness doesn't construct) — production code paths
# always run with a real Reticulum instance already up, so this differs from
# real usage only in that one always-True flag, not in the mechanism itself.
# ---------------------------------------------------------------------------

def test_import_scanned_contact_rejects_invalid_hex(tmp_path):
    svc = MessagingService(storage_path=str(tmp_path), message_store=_StubMessageStore())

    ok, message = svc.import_scanned_contact("not-hex", "also-not-hex")

    assert ok is False
    assert "hex" in message.lower()


def test_import_scanned_contact_rejects_wrong_length_hash(tmp_path):
    svc = MessagingService(storage_path=str(tmp_path), message_store=_StubMessageStore())
    real_identity = RNS.Identity()

    ok, message = svc.import_scanned_contact("aabb", real_identity.get_public_key().hex())

    assert ok is False
    assert "16 bytes" in message


def test_import_scanned_contact_rejects_wrong_length_public_key(tmp_path):
    svc = MessagingService(storage_path=str(tmp_path), message_store=_StubMessageStore())

    ok, message = svc.import_scanned_contact(DEST_HASH, "aabb")

    assert ok is False
    assert "wrong length" in message.lower()


def test_import_scanned_contact_makes_the_identity_immediately_recallable(tmp_path):
    """The actual point of this feature: after import, RNS.Identity.recall()
    resolves this destination hash without ever having seen a real
    announce for it — confirmed via a real Identity.remember()/recall()
    round trip, not mocked."""
    svc = MessagingService(storage_path=str(tmp_path), message_store=_StubMessageStore())
    real_identity = RNS.Identity()
    dest_hash_hex = os.urandom(16).hex()

    ok, message = svc.import_scanned_contact(dest_hash_hex, real_identity.get_public_key().hex())

    assert ok is True
    recalled = RNS.Identity.recall(bytes.fromhex(dest_hash_hex), _no_use=True)
    assert recalled is not None
    assert recalled.get_public_key() == real_identity.get_public_key()
