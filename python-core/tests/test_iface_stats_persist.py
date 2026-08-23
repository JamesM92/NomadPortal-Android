"""Regression tests for ``NodeBrowser.get_status()``'s interface byte-stats
persistence.

Real bug, found via a live capture on a Bluetooth-mesh device that showed
"0 B received" on the Network tab despite several actively-connected,
actively-receiving neighbors: ``get_status()`` used to build a throwaway
snapshot containing *only* the interfaces currently in
``RNS.Transport.interfaces`` and persist exactly that dict, wholesale-
overwriting ``iface_stats.json`` on every single call. That's harmless for
one long-lived TCP/AutoInterface object that stays registered for the whole
session, but ``RnsBleNeighborInterface`` is created/destroyed per neighbor
per connect/disconnect (a real, frequent event on Bluetooth mesh) — the
moment any neighbor unlinked, the next poll would drop its name from the
snapshot and overwrite the file without it, permanently erasing that
neighbor's accumulated lifetime bytes.

These tests bypass ``NodeBrowser.__init__`` (which spins up RNS bookkeeping)
using a stub that reuses the real ``get_status``/``_save_iface_stats``/
``_load_iface_stats`` methods, same pattern as ``test_nodes_persist.py``.
"""

import json
import os
import threading

import pytest

from nomadnet_web.browser import NodeBrowser


class _FakeIface:
    def __init__(self, name, rxb=0, txb=0, online=True):
        self.name = name
        self.rxb = rxb
        self.txb = txb
        self.online = online


class _FakeTransport:
    def __init__(self, interfaces):
        self.interfaces = interfaces


class _FakeRNS:
    def __init__(self, interfaces):
        self.Transport = _FakeTransport(interfaces)


class _StubBrowser:
    """Minimum viable NodeBrowser-shaped object for exercising
    ``get_status``'s real interface byte-stats bookkeeping."""

    get_status         = NodeBrowser.get_status
    _save_iface_stats   = NodeBrowser._save_iface_stats
    _load_iface_stats   = NodeBrowser._load_iface_stats

    def __init__(self, storage_dir, interfaces):
        self._iface_stats_file = os.path.join(storage_dir, "iface_stats.json")
        self._lock = threading.Lock()
        self.nodes: dict = {}
        self._total_announces = 0
        self._iface_base: dict = {}
        self._iface_last_live: dict = {}
        self._rns = _FakeRNS(interfaces)


@pytest.fixture
def storage_dir(tmp_path):
    return str(tmp_path)


class TestChurnSurvival:
    """The actual regression: a neighbor's byte history must survive its
    own disconnect, not vanish the moment it drops out of
    RNS.Transport.interfaces."""

    def test_disconnected_neighbor_bytes_stay_in_memory(self, storage_dir):
        neighbor_a = _FakeIface("RnsBleNeighborInterface[peer-a]", rxb=500, txb=100)
        neighbor_b = _FakeIface("RnsBleNeighborInterface[peer-b]", rxb=200, txb=50)
        browser = _StubBrowser(storage_dir, [neighbor_a, neighbor_b])

        browser.get_status()
        # Still live -- tracked in _iface_last_live, not yet committed to
        # the frozen _iface_base (which would double-count it on the next
        # poll while it's still connected -- see get_status()'s own doc).
        assert browser._iface_last_live["RnsBleNeighborInterface[peer-a]"]["rxb"] == 500
        assert browser._iface_last_live["RnsBleNeighborInterface[peer-b]"]["rxb"] == 200

        # peer-a disconnects (its RnsBleNeighborInterface object is torn
        # down and removed from RNS.Transport.interfaces) -- only peer-b
        # is live for this next poll.
        browser._rns.Transport.interfaces = [neighbor_b]
        browser.get_status()

        # peer-a's history must still be there -- this is the whole bug --
        # now committed into the frozen base since it actually disconnected.
        assert browser._iface_base["RnsBleNeighborInterface[peer-a]"]["rxb"] == 500
        assert "RnsBleNeighborInterface[peer-a]" not in browser._iface_last_live
        assert browser._iface_last_live["RnsBleNeighborInterface[peer-b]"]["rxb"] == 200

    def test_disconnected_neighbor_bytes_survive_on_disk(self, storage_dir):
        neighbor_a = _FakeIface("RnsBleNeighborInterface[peer-a]", rxb=500, txb=100)
        neighbor_b = _FakeIface("RnsBleNeighborInterface[peer-b]", rxb=200, txb=50)
        browser = _StubBrowser(storage_dir, [neighbor_a, neighbor_b])
        browser.get_status()

        browser._rns.Transport.interfaces = [neighbor_b]
        browser.get_status()

        with open(browser._iface_stats_file, encoding="utf-8") as fh:
            on_disk = json.load(fh)
        # Real assertion for the bug this closes: the old code overwrote
        # the file with *only* the currently-live subset, so peer-a would
        # be missing here after its disconnect.
        assert on_disk["RnsBleNeighborInterface[peer-a]"]["rxb"] == 500
        assert on_disk["RnsBleNeighborInterface[peer-b]"]["rxb"] == 200

    def test_reconnecting_neighbor_accumulates_not_resets(self, storage_dir):
        neighbor_a = _FakeIface("RnsBleNeighborInterface[peer-a]", rxb=500, txb=100)
        browser = _StubBrowser(storage_dir, [neighbor_a])
        browser.get_status()

        # peer-a disconnects...
        browser._rns.Transport.interfaces = []
        browser.get_status()

        # ...and reconnects as a brand-new RnsBleNeighborInterface object
        # (fresh rxb/txb starting at 0, same name -- exactly what a real
        # reconnect looks like on the Kotlin/Python side).
        neighbor_a_v2 = _FakeIface("RnsBleNeighborInterface[peer-a]", rxb=30, txb=10)
        browser._rns.Transport.interfaces = [neighbor_a_v2]
        status = browser.get_status()

        iface_status = next(
            i for i in status["interfaces"] if i["name"] == "RnsBleNeighborInterface[peer-a]"
        )
        # Lifetime total is the old base (500) plus this new session's own
        # 30 -- not reset back down to 30.
        assert iface_status["life_rxb"] == 530
        # The new object's own session-only counter stays a real, honest
        # 30 (not conflated with the lifetime total).
        assert iface_status["rxb"] == 30


class TestStableInterfaceUnaffected:
    """A long-lived interface (TCP, AutoInterface) that never drops out of
    RNS.Transport.interfaces mid-session behaved correctly before this fix
    too -- this just confirms the fix didn't change that."""

    def test_single_long_lived_interface_accumulates_normally(self, storage_dir):
        tcp = _FakeIface("Client on 1.2.3.4:4242", rxb=1000, txb=200)
        browser = _StubBrowser(storage_dir, [tcp])

        browser.get_status()
        tcp.rxb = 1500
        tcp.txb = 300
        status = browser.get_status()

        iface_status = status["interfaces"][0]
        assert iface_status["life_rxb"] == 1500
        assert iface_status["life_txb"] == 300


class TestNoLiveInterfaces:
    def test_empty_interfaces_does_not_touch_disk(self, storage_dir):
        browser = _StubBrowser(storage_dir, [])
        status = browser.get_status()
        assert status["interfaces"] == []
        assert not os.path.exists(browser._iface_stats_file)
