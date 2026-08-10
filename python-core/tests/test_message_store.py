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
