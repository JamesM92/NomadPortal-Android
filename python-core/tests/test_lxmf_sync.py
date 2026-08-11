"""Tests for ``PropagationSyncService`` — node discovery/ranking, and the
manual ``sync_now()``/``sync_status()`` entry points added for a UI-level
"Sync now" action (the earlier Columba-parity-audit finding this closes).

``start()``'s own background thread (real RNS.Transport registration +
an infinite sleep loop) is deliberately not exercised here, same
reasoning as ``test_messaging.py``'s own stated scope for ``_deliver()``'s
background thread — real RNS/LXMF network calls, not something a unit
test should drive. Everything tested here calls the service's own
methods directly, synchronously.
"""

import types

import pytest

from nomadnet_web.lxmf_sync import PropagationSyncService

NODE_A = b"\xaa" * 16
NODE_B = b"\xbb" * 16


class _FakeTransport:
    def __init__(self, hops_by_hash=None):
        self._hops_by_hash = hops_by_hash or {}

    def hops_to(self, destination_hash):
        return self._hops_by_hash.get(destination_hash, 5)

    def register_announce_handler(self, handler):
        pass


class _FakeRNS:
    def __init__(self, hops_by_hash=None):
        self.Transport = _FakeTransport(hops_by_hash)


class _FakeRouter:
    def __init__(self, fail_with=None):
        self._fail_with = fail_with
        self.outbound_propagation_node = None
        self.request_calls = 0
        self.propagation_transfer_state = 0x00
        self.propagation_transfer_progress = 0.0
        self.propagation_transfer_last_result = None

    def set_outbound_propagation_node(self, destination_hash):
        self.outbound_propagation_node = destination_hash

    def request_messages_from_propagation_node(self, identity):
        self.request_calls += 1
        if self._fail_with is not None:
            raise self._fail_with


class _FakeMessagingService:
    def __init__(self, routers=None):
        self._routers = routers or []

    def active_routers(self):
        return list(self._routers)


def _make_service(hops_by_hash=None, routers=None):
    rns = _FakeRNS(hops_by_hash)
    messaging = _FakeMessagingService(routers)
    return PropagationSyncService(rns=rns, messaging_service=messaging), messaging


def test_pick_best_node_returns_none_with_no_known_nodes():
    service, _ = _make_service()
    assert service._pick_best_node() is None


def test_pick_best_node_prefers_fewer_hops():
    service, _ = _make_service(hops_by_hash={NODE_A: 5, NODE_B: 2})
    service._on_propagation_announce(NODE_A, announced_identity=None, app_data=None)
    service._on_propagation_announce(NODE_B, announced_identity=None, app_data=None)

    assert service._pick_best_node() == NODE_B


def test_pick_best_node_excludes_stale_nodes():
    service, _ = _make_service(hops_by_hash={NODE_A: 1})
    service._on_propagation_announce(NODE_A, announced_identity=None, app_data=None)
    # Force it stale by rewriting last_seen directly (real staleness
    # would take NODE_FRESHNESS_S — 6h — to occur naturally).
    with service._known_nodes_lock:
        service._known_nodes[NODE_A]["last_seen"] -= 999999

    assert service._pick_best_node() is None


def test_sync_now_with_no_known_nodes_fails_without_touching_any_router():
    router = _FakeRouter()
    service, _ = _make_service(routers=[("", {"router": router, "identity": object()})])

    ok, message = service.sync_now("")

    assert ok is False
    assert "no propagation node" in message.lower()
    assert router.request_calls == 0


def test_sync_now_picks_a_node_and_syncs_successfully():
    router = _FakeRouter()
    service, _ = _make_service(
        hops_by_hash={NODE_A: 2},
        routers=[("", {"router": router, "identity": object()})],
    )
    service._on_propagation_announce(NODE_A, announced_identity=None, app_data=None)

    ok, message = service.sync_now("")

    assert ok is True
    assert NODE_A.hex()[:16] in message
    assert router.outbound_propagation_node == NODE_A
    assert router.request_calls == 1


def test_sync_now_reports_failure_from_router_exception():
    router = _FakeRouter(fail_with=RuntimeError("no path to destination"))
    service, _ = _make_service(
        hops_by_hash={NODE_A: 2},
        routers=[("", {"router": router, "identity": object()})],
    )
    service._on_propagation_announce(NODE_A, announced_identity=None, app_data=None)

    ok, message = service.sync_now("")

    assert ok is False
    assert "no path to destination" in message


def test_sync_now_fails_for_a_user_with_no_active_router():
    service, _ = _make_service(
        hops_by_hash={NODE_A: 2},
        routers=[("someone-else", {"router": _FakeRouter(), "identity": object()})],
    )
    service._on_propagation_announce(NODE_A, announced_identity=None, app_data=None)

    ok, message = service.sync_now("")

    assert ok is False
    assert "no active messaging identity" in message.lower()


def test_sync_status_reports_known_node_counts_and_live_transfer_state():
    router = _FakeRouter()
    router.propagation_transfer_state = 0x05  # PR_RECEIVING
    router.propagation_transfer_progress = 0.4
    router.propagation_transfer_last_result = None
    service, _ = _make_service(
        hops_by_hash={NODE_A: 2},
        routers=[("", {"router": router, "identity": object()})],
    )
    service._on_propagation_announce(NODE_A, announced_identity=None, app_data=None)
    service.sync_now("")

    status = service.sync_status("")

    assert status["known_nodes"] == 1
    assert status["fresh_nodes"] == 1
    assert status["picked_node_hex"] == NODE_A.hex()
    assert status["consecutive_failures"] == 0
    assert status["last_error"] is None
    assert status["transfer_state"] == "receiving"
    assert status["transfer_progress"] == 0.4


def test_sync_status_with_no_known_nodes_and_no_router_is_all_defaults():
    service, _ = _make_service()

    status = service.sync_status("")

    assert status["known_nodes"] == 0
    assert status["picked_node_hex"] is None
    assert status["transfer_state"] == "idle"
    assert status["transfer_progress"] == 0.0
    assert status["last_synced_at"] is None
