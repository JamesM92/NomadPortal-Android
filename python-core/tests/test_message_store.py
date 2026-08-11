"""Tests for ``MessageStore.purge_expired()`` — the core disappearing-
messages removal logic. ``messaging.py``'s ``purge_expired_messages()``
(the attachment-file-cleanup wrapper around this) has its own coverage in
test_messaging.py; this file only exercises the plain dict-filtering
mechanics against a real on-disk ``messages.json``.
"""

import time

from nomadnet_web.message_store import MessageStore


def _make_store(tmp_path):
    return MessageStore(str(tmp_path))


def test_purge_expired_removes_only_past_due_entries(tmp_path):
    store = _make_store(tmp_path)
    now = time.time()
    store.save_sent({"id": "s1", "dest": "aa", "expires_at": now - 10})
    store.save_sent({"id": "s2", "dest": "aa", "expires_at": now + 1000})
    store.save_received({"id": "r1", "source": "bb", "expires_at": now - 5})
    store.save_received({"id": "r2", "source": "bb", "expires_at": now + 1000})

    removed = store.purge_expired(now=now)

    assert {m["id"] for m in removed} == {"s1", "r1"}
    assert [m["id"] for m in store.sent_messages()] == ["s2"]
    assert [m["id"] for m in store.received_messages()] == ["r2"]


def test_purge_expired_leaves_messages_with_no_expiry_alone(tmp_path):
    store = _make_store(tmp_path)
    store.save_sent({"id": "s1", "dest": "aa", "expires_at": None})
    store.save_sent({"id": "s2", "dest": "aa"})  # absent key entirely — same as None

    removed = store.purge_expired()

    assert removed == []
    assert len(store.sent_messages()) == 2


def test_purge_expired_persists_the_removal(tmp_path):
    store = _make_store(tmp_path)
    now = time.time()
    store.save_sent({"id": "s1", "dest": "aa", "expires_at": now - 1})
    store.purge_expired(now=now)

    # A fresh MessageStore loading the same directory should see the
    # removal too — not just an in-memory-only change.
    reloaded = _make_store(tmp_path)
    assert reloaded.sent_messages() == []


def test_purge_expired_returns_empty_list_and_skips_persist_when_nothing_expired(tmp_path):
    store = _make_store(tmp_path)
    store.save_sent({"id": "s1", "dest": "aa", "expires_at": time.time() + 1000})

    assert store.purge_expired() == []


def test_mark_read_sets_read_true(tmp_path):
    store = _make_store(tmp_path)
    store.save_received({"id": "r1", "source": "bb", "read": False})

    store.mark_read("r1")

    assert store.received_messages()[0]["read"] is True


def test_mark_unread_mirrors_mark_read(tmp_path):
    # orchestrator.mark_conversation_unread's real caller, but this file
    # only exercises the plain dict-flip mechanics — same split as
    # purge_expired/purge_expired_messages (see this file's own doc
    # comment).
    store = _make_store(tmp_path)
    store.save_received({"id": "r1", "source": "bb", "read": True})

    store.mark_unread("r1")

    assert store.received_messages()[0]["read"] is False


def test_mark_read_and_mark_unread_respect_owner_scoping(tmp_path):
    store = _make_store(tmp_path)
    store.save_received({"id": "r1", "source": "bb", "owner": "alice", "read": False})

    store.mark_read("r1", owner="bob")
    assert store.received_messages()[0]["read"] is False

    store.mark_read("r1", owner="alice")
    assert store.received_messages()[0]["read"] is True

    store.mark_unread("r1", owner="bob")
    assert store.received_messages()[0]["read"] is True

    store.mark_unread("r1", owner="alice")
    assert store.received_messages()[0]["read"] is False


def test_mark_read_and_mark_unread_are_a_no_op_for_unknown_id(tmp_path):
    store = _make_store(tmp_path)
    store.save_received({"id": "r1", "source": "bb", "read": False})

    store.mark_read("does-not-exist")
    store.mark_unread("does-not-exist")

    assert store.received_messages()[0]["read"] is False


def test_update_sent_stamps_state_and_diagnostic_fields(tmp_path):
    store = _make_store(tmp_path)
    store.save_sent({"id": "s1", "dest": "aa", "state": "queued"})

    store.update_sent(
        "s1", "delivered",
        method="opportunistic", transport_encrypted=True,
        delivery_attempts=1, rssi=-72.0, snr=8.5, quality=91.0,
    )

    msg = store.sent_messages()[0]
    assert msg["state"] == "delivered"
    assert msg["method"] == "opportunistic"
    assert msg["transport_encrypted"] is True
    assert msg["delivery_attempts"] == 1
    assert msg["rssi"] == -72.0
    assert msg["snr"] == 8.5
    assert msg["quality"] == 91.0
    assert isinstance(msg["state_changed_at"], float)


def test_update_sent_diagnostic_kwargs_default_to_not_touching_existing_values(tmp_path):
    store = _make_store(tmp_path)
    store.save_sent({"id": "s1", "dest": "aa", "state": "queued"})
    store.update_sent("s1", "delivered", method="direct", rssi=-50.0)

    # A second update_sent call with no diagnostic kwargs (e.g. a bare
    # state change) must not clobber the values the first call set —
    # None means "don't touch", not "clear".
    store.update_sent("s1", "delivered")

    msg = store.sent_messages()[0]
    assert msg["method"] == "direct"
    assert msg["rssi"] == -50.0


def test_update_sent_real_id_rewrite_still_works_alongside_diagnostics(tmp_path):
    store = _make_store(tmp_path)
    store.save_sent({"id": "client-uuid", "dest": "aa", "state": "queued"})

    store.update_sent(
        "client-uuid", "delivered", real_id="real-lxmf-hash",
        method="opportunistic",
    )

    msg = store.sent_messages()[0]
    assert msg["id"] == "real-lxmf-hash"
    assert msg["method"] == "opportunistic"
