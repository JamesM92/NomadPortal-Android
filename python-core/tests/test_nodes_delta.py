"""Tests for ``NodeBrowser``'s incremental node-list sync
(``_touch_node``/``get_nodes_delta``).

Real motivation, not a hypothetical: RealBrowserRepository.kt's Sites
poll used to rebuild its entire node list from scratch every 4s
regardless of whether anything changed. A first fix added a cheap
"skip if nothing changed" version check, but a busy network (~1 real
announce/sec observed live on one device, 2000+ discovered nodes) meant
the version moved on nearly every tick anyway — the full O(n) rebuild
kept firing regardless. ``get_nodes_delta`` is the real fix: only ship
what actually changed since the client's last known version.

Same stub-reuse pattern as ``test_nodes_persist.py`` (bypasses
``NodeBrowser.__init__``, which spins up real RNS bookkeeping) —
``_hop_count`` isn't stubbed at all: it references ``self._rns``, which
this stub never sets, so the real method's own broad
``except Exception: return None`` naturally makes every hop lookup
report "unknown" without any special-casing needed here.
"""

import threading

import pytest

from nomadnet_web.browser import NodeBrowser


class _StubBrowser:
    """Minimum viable NodeBrowser-shaped object for get_nodes()/
    get_nodes_delta() — rebinds the real methods so tests exercise the
    actual implementation, not a copy."""

    get_nodes         = NodeBrowser.get_nodes
    get_nodes_delta    = NodeBrowser.get_nodes_delta
    _annotate_node     = NodeBrowser._annotate_node
    _touch_node        = NodeBrowser._touch_node
    _mark_nodes_dirty  = NodeBrowser._mark_nodes_dirty
    _bump_nodes_version = NodeBrowser._bump_nodes_version
    get_nodes_version  = NodeBrowser.get_nodes_version
    _hop_count         = NodeBrowser._hop_count

    def __init__(self):
        self._lock = threading.Lock()
        self.nodes: dict = {}
        self._favorites: dict = {}
        self._hosted_hash = ""
        self._hosted_name = ""
        self._nodes_dirty = False
        self._nodes_dirty_lock = threading.Lock()
        self._nodes_version = 0
        self._nodes_version_lock = threading.Lock()
        self._node_last_changed_version: dict = {}

    def add_node(self, hash_hex: str, **fields) -> None:
        """Test helper — a real caller would go through _register_node/
        _record_fetch/etc., all of which end in _touch_node(); this
        skips straight to that so tests can set up arbitrary node state
        without RNS's announce machinery."""
        record = {
            "hash": hash_hex, "name": hash_hex[:8], "first_seen": 0.0,
            "last_seen": 0.0, "announce_count": 1, "view_count": 0,
            "rx_bytes": 0, "last_load_ms": None, "avg_load_ms": None,
            "last_ping_ms": None, "last_load_ok": None,
            "ever_load_ok": False, "favorited": False,
        }
        record.update(fields)
        self.nodes[hash_hex] = record
        self._touch_node(hash_hex)


@pytest.fixture
def browser():
    return _StubBrowser()


class TestTouchNode:
    def test_bumps_global_version(self, browser):
        before = browser.get_nodes_version()
        browser._touch_node("aa" * 16)
        assert browser.get_nodes_version() == before + 1

    def test_records_per_hash_version(self, browser):
        browser._touch_node("aa" * 16)
        v1 = browser.get_nodes_version()
        browser._touch_node("bb" * 16)
        v2 = browser.get_nodes_version()
        assert browser._node_last_changed_version["aa" * 16] == v1
        assert browser._node_last_changed_version["bb" * 16] == v2

    def test_sets_dirty_flag(self, browser):
        assert browser._nodes_dirty is False
        browser._touch_node("aa" * 16)
        assert browser._nodes_dirty is True


class TestGetNodesDelta:
    def test_unknown_baseline_returns_full(self, browser):
        browser.add_node("aa" * 16)
        browser.add_node("bb" * 16)
        result = browser.get_nodes_delta(since_version=0)
        assert result["full"] is True
        assert {n["hash"] for n in result["nodes"]} == {"aa" * 16, "bb" * 16}

    def test_future_baseline_falls_back_to_full(self, browser):
        browser.add_node("aa" * 16)
        current = browser.get_nodes_version()
        result = browser.get_nodes_delta(since_version=current + 100)
        assert result["full"] is True

    def test_no_changes_since_baseline_returns_empty_non_full(self, browser):
        browser.add_node("aa" * 16)
        current = browser.get_nodes_version()
        result = browser.get_nodes_delta(since_version=current)
        assert result["full"] is False
        assert result["nodes"] == []

    def test_only_changed_node_is_returned(self, browser):
        browser.add_node("aa" * 16)
        baseline = browser.get_nodes_version()
        browser.add_node("bb" * 16)
        result = browser.get_nodes_delta(since_version=baseline)
        assert result["full"] is False
        assert [n["hash"] for n in result["nodes"]] == ["bb" * 16]

    def test_re_touching_an_existing_node_surfaces_it_again(self, browser):
        browser.add_node("aa" * 16)
        baseline = browser.get_nodes_version()
        # Simulate a re-announce of the same node (RealBrowserRepository's
        # real trigger, e.g. _register_node on an existing hash).
        browser._touch_node("aa" * 16)
        result = browser.get_nodes_delta(since_version=baseline)
        assert [n["hash"] for n in result["nodes"]] == ["aa" * 16]

    def test_delta_entries_get_the_same_annotation_as_get_nodes(self, browser):
        """The whole point of factoring out _annotate_node was one
        implementation, not two that could drift — check a delta entry
        actually got is_hosted/favorited/hops set, not just passed
        through raw."""
        browser.add_node("aa" * 16)
        baseline = 0
        result = browser.get_nodes_delta(since_version=baseline)
        node = result["nodes"][0]
        assert "is_hosted" in node
        assert "is_default" in node
        assert "hops" in node
        assert node["favorited"] is False
