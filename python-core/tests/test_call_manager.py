"""Tests for CallManager's real-wire-protocol-matching state machine.

CallManager takes its RNS module (and msgpack module) as injected
dependencies rather than importing them at module scope specifically so
this suite can drive the whole signalling flow — outbound call, inbound
call, busy, reject, hangup, timeout — against a lightweight fake that
mirrors just the RNS surface CallManager actually calls, without needing
real RNS or real networking. The fake's shapes (Destination/Link/Packet
constructor signatures, ACTIVE status, identify()/set_*_callback()
methods, and the exact Signalling wire values) mirror real RNS/LXST one-
for-one — verified directly against a real local clone of
markqvist/LXST's source, not guessed (see call_manager.py's own doc
comment for specifics).

The fake msgpack below is NOT wire-compatible with real
RNS.vendor.umsgpack — it doesn't need to be, since these tests only
exercise CallManager's own pack-then-unpack round trip through one
consistent fake, never real network bytes. Wire-format correctness
(the exact msgpack encoding real LXST expects) was verified by reading
LXST's real source directly, not by a test double.
"""

import threading
import time

import pytest

from nomadnet_web.call_manager import CallManager, CallStatus, Signalling


# ---------------------------------------------------------------------
# Fake RNS surface — mirrors real RNS's own API shapes one-for-one for
# exactly the calls CallManager makes.
# ---------------------------------------------------------------------

class FakeMsgpack:
    """packb/unpackb round-trip via plain Python object identity — good
    enough since both sides of every test go through this same fake."""

    @staticmethod
    def packb(obj):
        return obj

    @staticmethod
    def unpackb(obj):
        return obj


class FakeIdentity:
    def __init__(self, hash_hex):
        self.hash = bytes.fromhex(hash_hex)


class FakeDestination:
    IN = "IN"
    OUT = "OUT"
    SINGLE = "SINGLE"
    PROVE_NONE = "PROVE_NONE"

    def __init__(self, identity, direction, dest_type, *aspects):
        self.identity = identity
        self.direction = direction
        self.dest_type = dest_type
        self.aspects = aspects
        # Deterministic fake hash: identity hash + aspects, distinct per
        # aspect combination (mirrors real RNS: destination hash depends
        # on both identity and aspect).
        self.hash = identity.hash + "/".join(aspects).encode()
        self._link_established_callback = None

    def set_proof_strategy(self, strategy):
        self.proof_strategy = strategy

    def set_link_established_callback(self, cb):
        self._link_established_callback = cb

    def announce(self):
        self.announced = True


class FakeLink:
    ACTIVE = "ACTIVE"
    CLOSED = "CLOSED"

    def __init__(self, destination, established_callback=None, closed_callback=None):
        self.destination = destination
        self.status = FakeLink.ACTIVE
        self._established_cb = established_callback
        self._closed_cb = closed_callback
        self._packet_cb = None
        self._remote_identified_cb = None
        self.sent_signals = []  # list of raw signal payload dicts sent over this link
        self.identified_as = None
        self.link_id = id(self)

    def set_packet_callback(self, cb):
        self._packet_cb = cb

    def set_link_closed_callback(self, cb):
        self._closed_cb = cb

    def set_remote_identified_callback(self, cb):
        self._remote_identified_cb = cb

    def identify(self, identity):
        self.identified_as = identity

    def teardown(self):
        if self.status != FakeLink.CLOSED:
            self.status = FakeLink.CLOSED
            if callable(self._closed_cb):
                self._closed_cb(self)

    # Test helpers, not real RNS API -----------------------------------

    def fire_established(self):
        if callable(self._established_cb):
            self._established_cb(self)

    def fire_remote_identified(self, identity):
        if callable(self._remote_identified_cb):
            self._remote_identified_cb(self, identity)

    def receive_signal(self, signal):
        """Simulates the *other* end sending a signal to us over this
        link — delivers it through this link's own registered packet
        callback, exactly as real RNS would."""
        if callable(self._packet_cb):
            fake_packet = FakePacket(self, {0x00: [signal]})
            self._packet_cb(fake_packet.data, fake_packet)


class FakePacket:
    def __init__(self, target, payload_obj, create_receipt=True):
        self.target = target
        self.data = payload_obj
        self.create_receipt = create_receipt
        self.link = target if isinstance(target, FakeLink) else None
        self.sent = False

    def send(self):
        self.sent = True
        if isinstance(self.target, FakeLink):
            if isinstance(self.data, dict) and 0x00 in self.data:
                self.target.sent_signals.extend(self.data[0x00])
        return True


class FakeTransport:
    ACTIVE = "ACTIVE"
    _paths: set = set()

    @classmethod
    def has_path(cls, dest_hash):
        return dest_hash in cls._paths

    @classmethod
    def request_path(cls, dest_hash):
        pass

    @classmethod
    def reset(cls):
        cls._paths = set()

    @classmethod
    def add_path(cls, dest_hash):
        cls._paths.add(dest_hash)


class FakeRNSModule:
    Destination = FakeDestination
    Link = FakeLink
    Packet = FakePacket
    Transport = FakeTransport

    class Identity:
        _by_destination_hash: dict = {}
        _by_identity_hash: dict = {}

        @classmethod
        def recall(cls, target_hash, from_identity_hash=False):
            if from_identity_hash:
                return cls._by_identity_hash.get(target_hash)
            return cls._by_destination_hash.get(target_hash)

        @classmethod
        def reset(cls):
            cls._by_destination_hash = {}
            cls._by_identity_hash = {}

        @classmethod
        def register(cls, identity, destination_hash=None):
            cls._by_identity_hash[identity.hash] = identity
            if destination_hash is not None:
                cls._by_destination_hash[destination_hash] = identity

    @staticmethod
    def prettyhexrep(h):
        return h.hex()


@pytest.fixture(autouse=True)
def reset_fake_state():
    FakeTransport.reset()
    FakeRNSModule.Identity.reset()
    yield


@pytest.fixture
def manager():
    m = CallManager()
    m._rns = FakeRNSModule
    m._msgpack = FakeMsgpack
    m._identity = FakeIdentity("11" * 16)
    # Tiny by default, not the real 15s default — resolve_identity()'s
    # own path-request fallback (added after a real failed test call
    # showed recall() alone isn't enough) means even a plain "unresolvable
    # address" test now exercises a real wait loop; nothing in this suite
    # is testing the *timeout duration* itself, so there's no reason for
    # any of it to run at real-world speed.
    m.path_wait_timeout_s = 0.05
    return m


REMOTE_HASH = "22" * 16


def make_remote_identity():
    return FakeIdentity(REMOTE_HASH)


class TestResolveIdentity:
    def test_resolves_via_destination_hash(self, manager):
        remote = make_remote_identity()
        dest_hash = b"some-dest-hash-1"
        FakeRNSModule.Identity.register(remote, destination_hash=dest_hash)
        found = manager.resolve_identity(dest_hash.hex())
        assert found is remote

    def test_resolves_via_identity_hash_when_destination_lookup_fails(self, manager):
        remote = make_remote_identity()
        FakeRNSModule.Identity.register(remote)  # no destination_hash registered
        found = manager.resolve_identity(REMOTE_HASH)
        assert found is remote

    def test_returns_none_for_completely_unknown_address(self, manager):
        assert manager.resolve_identity("ff" * 16) is None

    def test_returns_none_for_invalid_hex(self, manager):
        assert manager.resolve_identity("not-hex-at-all") is None


class TestNotYetStarted:
    def test_place_call_fails_cleanly_before_start(self):
        # A fresh CallManager whose start() has never run (self._rns is
        # None) — a real reachable state (RNS init can take 60-300s),
        # must fail cleanly, not AttributeError.
        m = CallManager()
        success, message = m.place_call("aa" * 16)
        assert success is False
        assert "not ready" in message.lower()


class TestOutboundCallFlow:
    def _place_call_with_path(self, manager):
        remote = make_remote_identity()
        FakeRNSModule.Identity.register(remote)
        dest = FakeDestination(remote, FakeDestination.OUT, FakeDestination.SINGLE, "lxst", "telephony")
        FakeTransport.add_path(dest.hash)
        success, message = manager.place_call(REMOTE_HASH)
        assert success is True
        return remote

    def test_fails_for_unresolvable_address(self, manager):
        success, message = manager.place_call("ff" * 16)
        assert success is False
        assert manager.status == CallStatus.IDLE

    def test_fails_when_no_path_found(self, manager):
        remote = make_remote_identity()
        FakeRNSModule.Identity.register(remote)
        manager.path_wait_timeout_s = 0.05  # keep this test fast
        success, message = manager.place_call(REMOTE_HASH)
        assert success is False
        assert "path" in message.lower()

    def test_status_calling_immediately_after_place_call(self, manager):
        self._place_call_with_path(manager)
        assert manager.status in (CallStatus.CALLING, CallStatus.RINGING_OUTGOING)
        assert manager.is_incoming is False
        assert manager.remote_identity_hash == REMOTE_HASH

    def test_identifies_once_remote_signals_available(self, manager):
        self._place_call_with_path(manager)
        manager.link.fire_established()
        manager.link.receive_signal(Signalling.STATUS_AVAILABLE)
        assert manager.link.identified_as is manager._identity

    def test_advances_through_ringing_connecting_established(self, manager):
        self._place_call_with_path(manager)
        manager.link.fire_established()
        manager.link.receive_signal(Signalling.STATUS_AVAILABLE)
        manager.link.receive_signal(Signalling.STATUS_RINGING)
        assert manager.status == CallStatus.RINGING_OUTGOING
        manager.link.receive_signal(Signalling.STATUS_CONNECTING)
        assert manager.status == CallStatus.CONNECTING
        manager.link.receive_signal(Signalling.STATUS_ESTABLISHED)
        assert manager.status == CallStatus.ESTABLISHED
        assert manager.established_at is not None

    def test_busy_signal_ends_call_as_busy(self, manager):
        self._place_call_with_path(manager)
        manager.link.fire_established()
        manager.link.receive_signal(Signalling.STATUS_BUSY)
        assert manager.status == CallStatus.BUSY

    def test_rejected_signal_ends_call_as_rejected(self, manager):
        self._place_call_with_path(manager)
        manager.link.fire_established()
        manager.link.receive_signal(Signalling.STATUS_AVAILABLE)
        manager.link.receive_signal(Signalling.STATUS_RINGING)
        manager.link.receive_signal(Signalling.STATUS_REJECTED)
        assert manager.status == CallStatus.REJECTED

    def test_second_call_while_one_active_is_refused_locally(self, manager):
        self._place_call_with_path(manager)
        success, message = manager.place_call("33" * 16)
        assert success is False
        assert "already" in message.lower()


class TestInboundCallFlow:
    def _incoming_link(self, manager):
        remote_dest = FakeDestination(FakeIdentity("99" * 16), FakeDestination.IN, FakeDestination.SINGLE, "lxst", "telephony")
        link = FakeLink(remote_dest)
        manager._incoming_link_established(link)
        return link

    def test_sends_available_on_incoming_link(self, manager):
        link = self._incoming_link(manager)
        assert Signalling.STATUS_AVAILABLE in link.sent_signals

    def test_busy_when_already_on_a_call(self, manager):
        first_link = self._incoming_link(manager)
        remote = make_remote_identity()
        first_link.fire_remote_identified(remote)
        assert manager.status == CallStatus.RINGING_INCOMING

        second_link = FakeLink(FakeDestination(FakeIdentity("77" * 16), FakeDestination.IN, FakeDestination.SINGLE, "lxst", "telephony"))
        manager._incoming_link_established(second_link)
        assert Signalling.STATUS_BUSY in second_link.sent_signals
        assert second_link.status == FakeLink.CLOSED

    def test_ringing_after_caller_identified(self, manager):
        link = self._incoming_link(manager)
        remote = make_remote_identity()
        link.fire_remote_identified(remote)
        assert manager.status == CallStatus.RINGING_INCOMING
        assert manager.is_incoming is True
        assert manager.remote_identity_hash == REMOTE_HASH
        assert Signalling.STATUS_RINGING in link.sent_signals

    def test_answer_sends_connecting_then_established(self, manager):
        link = self._incoming_link(manager)
        link.fire_remote_identified(make_remote_identity())
        success, message = manager.answer_call()
        assert success is True
        assert manager.status == CallStatus.ESTABLISHED
        assert Signalling.STATUS_CONNECTING in link.sent_signals
        assert Signalling.STATUS_ESTABLISHED in link.sent_signals

    def test_answer_fails_when_nothing_ringing(self, manager):
        success, message = manager.answer_call()
        assert success is False


class TestHangUp:
    def test_hangup_incoming_ringing_unanswered_sends_rejected(self, manager):
        remote_dest = FakeDestination(FakeIdentity("99" * 16), FakeDestination.IN, FakeDestination.SINGLE, "lxst", "telephony")
        link = FakeLink(remote_dest)
        manager._incoming_link_established(link)
        link.fire_remote_identified(make_remote_identity())

        success, message = manager.hang_up()
        assert success is True
        assert Signalling.STATUS_REJECTED in link.sent_signals
        assert manager.status == CallStatus.ENDED
        assert link.status == FakeLink.CLOSED

    def test_hangup_established_call_tears_down_link(self, manager):
        remote_dest = FakeDestination(FakeIdentity("99" * 16), FakeDestination.IN, FakeDestination.SINGLE, "lxst", "telephony")
        link = FakeLink(remote_dest)
        manager._incoming_link_established(link)
        link.fire_remote_identified(make_remote_identity())
        manager.answer_call()

        success, message = manager.hang_up()
        assert success is True
        assert manager.status == CallStatus.ENDED
        assert link.status == FakeLink.CLOSED

    def test_hangup_with_no_active_call_fails(self, manager):
        success, message = manager.hang_up()
        assert success is False

    def test_remote_hangup_ends_call_locally(self, manager):
        remote_dest = FakeDestination(FakeIdentity("99" * 16), FakeDestination.IN, FakeDestination.SINGLE, "lxst", "telephony")
        link = FakeLink(remote_dest)
        manager._incoming_link_established(link)
        link.fire_remote_identified(make_remote_identity())
        manager.answer_call()
        assert manager.status == CallStatus.ESTABLISHED

        link.teardown()  # simulates the remote end closing the link
        assert manager.status == CallStatus.ENDED


class TestResetAfterEnd:
    def test_reset_clears_terminal_state_to_idle(self, manager):
        remote_dest = FakeDestination(FakeIdentity("99" * 16), FakeDestination.IN, FakeDestination.SINGLE, "lxst", "telephony")
        link = FakeLink(remote_dest)
        manager._incoming_link_established(link)
        link.fire_remote_identified(make_remote_identity())
        manager.hang_up()
        assert manager.status == CallStatus.ENDED

        manager.reset_after_end()
        assert manager.status == CallStatus.IDLE
        assert manager.remote_identity_hash is None

    def test_reset_is_a_no_op_when_not_in_a_terminal_state(self, manager):
        remote_dest = FakeDestination(FakeIdentity("99" * 16), FakeDestination.IN, FakeDestination.SINGLE, "lxst", "telephony")
        link = FakeLink(remote_dest)
        manager._incoming_link_established(link)
        link.fire_remote_identified(make_remote_identity())
        assert manager.status == CallStatus.RINGING_INCOMING

        manager.reset_after_end()
        assert manager.status == CallStatus.RINGING_INCOMING


class TestStatusDict:
    def test_reflects_current_state(self, manager):
        remote_dest = FakeDestination(FakeIdentity("99" * 16), FakeDestination.IN, FakeDestination.SINGLE, "lxst", "telephony")
        link = FakeLink(remote_dest)
        manager._incoming_link_established(link)
        link.fire_remote_identified(make_remote_identity())

        d = manager.status_dict()
        assert d["status"] == CallStatus.RINGING_INCOMING
        assert d["is_incoming"] is True
        assert d["remote_identity_hash"] == REMOTE_HASH


class TestStateChangeNotification:
    def test_on_state_change_called_on_transitions(self):
        calls = []
        m = CallManager(on_state_change=lambda: calls.append(1))
        m._rns = FakeRNSModule
        m._msgpack = FakeMsgpack
        m._identity = FakeIdentity("11" * 16)

        remote_dest = FakeDestination(FakeIdentity("99" * 16), FakeDestination.IN, FakeDestination.SINGLE, "lxst", "telephony")
        link = FakeLink(remote_dest)
        m._incoming_link_established(link)
        link.fire_remote_identified(make_remote_identity())
        assert len(calls) >= 1
