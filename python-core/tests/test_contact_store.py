"""Tests for ``ContactStore``'s disappearing-messages timer field.

Every entry-creation path (upsert/set_custom_name/set_icon/
set_icon_appearance) got a new "disappearing_seconds": 0 default
alongside "favorited" — this guards that default across all of them,
plus set_disappearing_timer()'s own set_favorite-mirrored contract.
"""

from nomadnet_web.contact_store import ContactStore

HASH = "aa" * 16


def _make_store(tmp_path):
    return ContactStore(str(tmp_path))


def test_upsert_defaults_disappearing_seconds_to_zero(tmp_path):
    store = _make_store(tmp_path)
    entry = store.upsert(HASH, name="Alice")
    assert entry["disappearing_seconds"] == 0


def test_set_icon_defaults_disappearing_seconds_to_zero(tmp_path):
    store = _make_store(tmp_path)
    store.set_icon(HASH, "base64data")
    assert store.get(HASH)["disappearing_seconds"] == 0


def test_set_icon_appearance_defaults_disappearing_seconds_to_zero(tmp_path):
    store = _make_store(tmp_path)
    store.set_icon_appearance(HASH, "account", "#fff", "#000")
    assert store.get(HASH)["disappearing_seconds"] == 0


def test_set_custom_name_defaults_disappearing_seconds_to_zero(tmp_path):
    store = _make_store(tmp_path)
    store.set_custom_name(HASH, "Alice")
    assert store.get(HASH)["disappearing_seconds"] == 0


def test_set_disappearing_timer_requires_existing_entry(tmp_path):
    # Same contract as set_favorite — False, not an upsert, when there's
    # no entry yet. orchestrator.set_disappearing_timer is what upserts
    # first, mirroring orchestrator.set_contact_favorite exactly.
    store = _make_store(tmp_path)
    assert store.set_disappearing_timer(HASH, 300) is False


def test_set_disappearing_timer_updates_existing_entry(tmp_path):
    store = _make_store(tmp_path)
    store.upsert(HASH, name="Alice")

    assert store.set_disappearing_timer(HASH, 300) is True
    assert store.get(HASH)["disappearing_seconds"] == 300


def test_set_disappearing_timer_rejects_negative_seconds(tmp_path):
    store = _make_store(tmp_path)
    store.upsert(HASH, name="Alice")

    store.set_disappearing_timer(HASH, -50)
    assert store.get(HASH)["disappearing_seconds"] == 0


def test_set_disappearing_timer_persists(tmp_path):
    store = _make_store(tmp_path)
    store.upsert(HASH, name="Alice")
    store.set_disappearing_timer(HASH, 3600)

    reloaded = _make_store(tmp_path)
    assert reloaded.get(HASH)["disappearing_seconds"] == 3600


def test_upsert_defaults_blocked_to_false(tmp_path):
    store = _make_store(tmp_path)
    entry = store.upsert(HASH, name="Alice")
    assert entry["blocked"] is False


def test_set_blocked_requires_existing_entry(tmp_path):
    # Same contract as set_favorite/set_disappearing_timer — False, not
    # an upsert, when there's no entry yet. orchestrator.set_contact_blocked
    # is what upserts first, mirroring orchestrator.set_contact_favorite.
    store = _make_store(tmp_path)
    assert store.set_blocked(HASH, True) is False


def test_set_blocked_updates_existing_entry(tmp_path):
    store = _make_store(tmp_path)
    store.upsert(HASH, name="Alice")

    assert store.set_blocked(HASH, True) is True
    assert store.get(HASH)["blocked"] is True

    assert store.set_blocked(HASH, False) is True
    assert store.get(HASH)["blocked"] is False


def test_set_blocked_persists(tmp_path):
    store = _make_store(tmp_path)
    store.upsert(HASH, name="Alice")
    store.set_blocked(HASH, True)

    reloaded = _make_store(tmp_path)
    assert reloaded.get(HASH)["blocked"] is True
