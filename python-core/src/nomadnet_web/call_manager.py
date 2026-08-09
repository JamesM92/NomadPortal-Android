"""
LXST-compatible voice-call signalling engine — Phase 1a.

Reimplements just the *call state machine* (dial/ring/answer/hangup/
busy/reject) directly against this app's own already-working RNS/LXMF
stack, matching LXST's real wire protocol exactly. Verified against
markqvist/LXST's actual source (a real local clone read directly, not
an AI-summarized fetch — an earlier summarized fetch of this same repo
gave two different, inconsistent aspect names for what turned out to be
two different files), not the `lxst` package itself: importing `lxst`
was tried and confirmed NOT viable in this Chaquopy build (a real pip-
resolution spike hit three independent conflicts — rns version, no
Android numpy>=2.3.4 wheel, no pycodec2 wheel at all — see the
nomadportal-android-competitor-research memory). No audio yet — this
phase validates the highest-interop-risk part (does a call actually
ring/answer/hang-up correctly against a real LXST client — Sideband,
Columba, rnphone) before adding an audio pipeline on top, same staged
approach as call_tracker.py's Phase 0.

Wire protocol (verified directly against LXST/Primitives/Telephony.py +
LXST/Network.py source):

- Destination: ``RNS.Destination(identity, IN/OUT, SINGLE, "lxst",
  "telephony")`` — same aspect Phase 0's CallPeerTracker already listens
  for announces on.
- A call is a plain ``RNS.Link`` to the remote's telephony destination —
  the same primitive this app already uses elsewhere, nothing exotic.
- Every message over an active call Link is a plain ``RNS.Packet`` (NOT
  LXMF, NOT a Resource) with a msgpack-encoded dict payload:
  ``{0x00: [signal_int, ...]}`` for signalling (this phase only),
  ``{0x01: bytes}`` for one audio frame (not sent or handled yet —
  ``bytes[0]`` would be a codec-type header byte: 0x00 Raw / 0x01 Opus /
  0x02 Codec2 / 0xFF Null, ``bytes[1:]`` the encoded frame).
- Signal codes (LXST's real ``Signalling`` class): BUSY=0x00,
  REJECTED=0x01, CALLING=0x02, AVAILABLE=0x03, RINGING=0x04,
  CONNECTING=0x05, ESTABLISHED=0x06.
- Real call flow, confirmed from source:
  1. Callee's Destination sits IN, proof strategy PROVE_NONE,
     link-established callback registered.
  2. Caller resolves the callee's ``RNS.Identity`` (see ``place_call``'s
     own doc comment for how — this is also where manual address entry
     for a never-announced-on-telephony contact plugs in), builds an OUT
     destination from it, ensures a path is known, opens ``RNS.Link``.
  3. Callee's incoming-link callback fires: if not already busy, sends
     ``{0x00:[0x03]}`` (AVAILABLE) over the link.
  4. Caller sees AVAILABLE, calls the link's own ``identify(identity)``
     — RNS.Link's standard identify handshake, not anything custom.
  5. Callee's remote-identified callback fires once that completes: if
     the caller is allowed, sends ``{0x00:[0x04]}`` (RINGING) — the
     "phone is now audibly ringing" moment on both real ends.
  6. Callee answering sends CONNECTING then ESTABLISHED once ready;
     caller mirrors its own local state forward on each.
  7. Either side hanging up before ESTABLISHED sends REJECTED; a busy
     line sends BUSY instead of ever reaching RINGING.
"""

import logging
import threading
import time
from typing import Callable, Optional

log = logging.getLogger(__name__)

APP_NAME = "lxst"
PRIMITIVE_NAME = "telephony"


class Signalling:
    """Verbatim from LXST/Primitives/Telephony.py's real Signalling
    class — these exact integer values are what's exchanged over the
    wire, not an internal choice of ours."""
    STATUS_BUSY        = 0x00
    STATUS_REJECTED    = 0x01
    STATUS_CALLING     = 0x02
    STATUS_AVAILABLE   = 0x03
    STATUS_RINGING     = 0x04
    STATUS_CONNECTING  = 0x05
    STATUS_ESTABLISHED = 0x06


class CallStatus:
    """This app's own local state names (not wire values) for the
    status polled by orchestrator.py's get_call_status_json() /
    Kotlin's CallRepository — deliberately distinct from Signalling's
    wire codes so a future audio-bearing phase can add states (e.g.
    "reconnecting") without colliding with wire semantics."""
    IDLE              = "idle"
    CALLING            = "calling"            # outgoing, path/link establishing
    RINGING_OUTGOING   = "ringing_outgoing"    # outgoing, their end is ringing
    RINGING_INCOMING   = "ringing_incoming"    # incoming, our end is ringing
    CONNECTING         = "connecting"
    ESTABLISHED        = "established"
    ENDED              = "ended"               # normal hangup, either side
    BUSY               = "busy"
    REJECTED           = "rejected"
    FAILED             = "failed"              # no path / identity unresolvable / timeout


# Real ring/connect timeouts — chosen to be generous (mesh RTT can be
# high) rather than tight, since a false timeout looks identical to a
# genuinely unreachable peer from the UI's point of view.
RING_TIMEOUT_S = 60
CONNECT_TIMEOUT_S = 20
PATH_WAIT_TIMEOUT_S = 15

# History is in-memory only (see CallManager.__init__'s own doc comment)
# — a fixed cap keeps a long-running process from accumulating this
# forever, same defensive shape as LXMFPeerTracker's own real-world
# bound (though that one's persisted and keyed by peer, not a flat list).
HISTORY_MAX = 50


class CallManager:
    """One call at a time (matches LXST's own single-line-busy model,
    and this app's single-identity design throughout). All RNS calls
    are made through the ``rns`` module object passed to ``start()`` —
    injected rather than imported at module scope, so this class's own
    state-machine logic is unit-testable against a lightweight fake
    without needing real RNS/networking (see test_call_manager.py).
    """

    def __init__(self, on_state_change: Optional[Callable[[], None]] = None):
        self._rns = None
        self._msgpack = None
        self._identity = None
        self._destination = None
        # RLock, not Lock: hang_up()/_incoming_link_established() etc.
        # call link.teardown() while holding this lock, and RNS's own
        # Link.teardown() may invoke the closed-callback (_link_closed,
        # which itself acquires this same lock) synchronously on the
        # calling thread rather than deferring it — confirmed as a real
        # deadlock against the fake test Link (whose teardown() fires
        # its callback inline); RLock makes the code correct either way
        # regardless of which real RNS actually does.
        self._lock = threading.RLock()
        self._on_state_change = on_state_change
        self.last_announce_at: Optional[float] = None

        # Instance attributes (not bare module constants) specifically
        # so tests can shrink them — a real 15s path-wait would make
        # the "no path found" test suite take 15 real seconds otherwise.
        self.path_wait_timeout_s = PATH_WAIT_TIMEOUT_S
        self.ring_timeout_s = RING_TIMEOUT_S

        self.status = CallStatus.IDLE
        self.link = None
        self.is_incoming = False
        self.remote_identity_hash: Optional[str] = None
        self.started_at: Optional[float] = None
        self.established_at: Optional[float] = None
        self.ended_reason: Optional[str] = None

        # Set True once the callee has actually tapped Answer — used to
        # tell a stray late signalling packet from the real answer path,
        # same guard LXST's own Telephone.answer() has.
        self._answered = False

        # Real call history — a real on-device incoming call that ended
        # near-instantly (the remote's own client apparently closed the
        # link right after RINGING, with nothing further ever logged)
        # showed two gaps at once: no visibility into *why* it ended,
        # and no record of it having happened at all once the overlay
        # dismissed. _end_call() is the one choke point every terminal
        # transition already funnels through (hangup, remote hangup,
        # busy, rejected, timeout) — recording there covers all of them
        # with no risk of missing a path. In-memory only for now, capped
        # at HISTORY_MAX; real persistence (surviving an app restart)
        # can layer on once this shape has proven out.
        self.history: list = []

    # ------------------------------------------------------------------
    # Setup
    # ------------------------------------------------------------------

    def start(self, rns_module, identity, msgpack_module=None) -> None:
        """Brings up this device's own telephony Destination so it can
        receive calls. rns_module is the real ``RNS`` module (passed in,
        not imported here, for testability — see this class's own doc
        comment); identity is this device's real RNS.Identity.
        msgpack_module defaults to a real ``import RNS.vendor.umsgpack``
        here (not at module scope) so this file stays importable without
        RNS installed at all — same lazy-import convention every other
        RNS-touching function in this codebase already follows."""
        if msgpack_module is None:
            import RNS.vendor.umsgpack as msgpack_module
        self._rns = rns_module
        self._msgpack = msgpack_module
        self._identity = identity
        self._destination = rns_module.Destination(
            identity, rns_module.Destination.IN, rns_module.Destination.SINGLE,
            APP_NAME, PRIMITIVE_NAME,
        )
        self._destination.set_proof_strategy(rns_module.Destination.PROVE_NONE)
        self._destination.set_link_established_callback(self._incoming_link_established)
        log.info("Call engine listening on %s", rns_module.prettyhexrep(self._destination.hash))

    def announce(self) -> None:
        if self._destination:
            self._destination.announce()
            self.last_announce_at = time.time()

    # ------------------------------------------------------------------
    # Outbound
    # ------------------------------------------------------------------

    def resolve_identity(self, address_hex: str, allow_path_request: bool = True):
        """Turns a hex address the UI hands us into a real RNS.Identity,
        or None if it genuinely can't be resolved.

        Tries both of RNS.Identity.recall()'s real modes first, since the
        caller may be handing us either shape and shouldn't have to know
        which:
        - A destination hash (from_identity_hash=False, the default) —
          what a user pastes in for manual address entry (per explicit
          request: "we need the ability to manually enter a call
          address, if somebody hasnt annoucned it") is naturally their
          already-familiar LXMF address, not a dedicated "call address"
          LXST has no convention for sharing separately. Once resolved
          to an Identity this way, a telephony Destination is built
          from that same Identity regardless of which aspect the hash
          we resolved it from actually announced under — this is real
          RNS.Identity.recall() behavior, confirmed against source, not
          an assumption: an Identity object isn't aspect-specific.
        - An identity hash (from_identity_hash=True) — what
          CallPeerTracker/LXMFPeerTracker's own identity_hash field
          gives us for a contact already known to be call-capable (the
          Phase 0 phone-icon tap path).

        recall() is a *pure local cache lookup* — it returns None for
        any hash RNS has never seen an announce or path-response for,
        regardless of whether the address is real and reachable. A real
        on-device test with two devices that had genuinely never
        announced to each other hit exactly this: both recall() modes
        failed even though the address was correct. request_path()
        (treating the raw address as a destination hash) fixes it — RNS's
        own path-response protocol carries the identity's public key
        even when nothing proactively announced, the same mechanism this
        app's own "message a never-seen address" flow already relies on
        (see messaging.py's PATH_WAIT handling). allow_path_request=False
        is for call sites that only want the cheap cache check (none
        currently — kept as an explicit opt-out, not a guess at a future
        need)."""
        try:
            address = bytes.fromhex(address_hex)
        except (ValueError, TypeError):
            return None
        identity = self._rns.Identity.recall(address, from_identity_hash=False)
        if identity is None:
            identity = self._rns.Identity.recall(address, from_identity_hash=True)
        if identity is not None or not allow_path_request:
            return identity

        if not self._rns.Transport.has_path(address):
            log.info("resolve_identity: no cached identity/path for %s, requesting path...", address_hex)
            self._rns.Transport.request_path(address)
            deadline = time.time() + self.path_wait_timeout_s
            poll_interval = min(0.2, self.path_wait_timeout_s)
            while not self._rns.Transport.has_path(address) and time.time() < deadline:
                time.sleep(poll_interval)
        return self._rns.Identity.recall(address, from_identity_hash=False)

    def place_call(self, address_hex: str) -> tuple:
        """Returns (success: bool, message: str). address_hex may be a
        destination hash or an identity hash — see resolve_identity()'s
        own doc comment. Blocks the calling thread while a path is
        looked up if one isn't already known (mirrors LXST's own call()
        exactly) — callers must not invoke this from Android's main
        thread, same standing rule as every other Chaquopy call that
        touches the network (see orchestrator.py's wait_ready() doc
        comment for the same rule elsewhere)."""
        log.info("place_call(%s)", address_hex)
        with self._lock:
            if self._rns is None:
                # start() hasn't run yet — a real, reachable state, not
                # a bug: RNS init can take 60-300s on a real deployment
                # (see orchestrator.py's wait_ready() doc comment), and
                # nothing stops the UI from trying to place a call
                # before that finishes. Fail cleanly rather than
                # AttributeError-ing on self._rns.Destination below.
                log.warning("place_call failed: engine not started yet")
                return False, "Call engine not ready yet"
            self._clear_terminal_state_locked()
            if self.status != CallStatus.IDLE:
                log.warning("place_call failed: already in status %s", self.status)
                return False, "Already on a call"
            identity = self.resolve_identity(address_hex)
            if identity is None:
                log.warning("place_call failed: could not resolve identity for %s", address_hex)
                return False, "Unknown address — no path/identity known for it yet"
            log.info("place_call: resolved identity %s for address %s", identity.hash.hex(), address_hex)

            call_destination = self._rns.Destination(
                identity, self._rns.Destination.OUT, self._rns.Destination.SINGLE,
                APP_NAME, PRIMITIVE_NAME,
            )
            if not self._rns.Transport.has_path(call_destination.hash):
                log.info(
                    "place_call: no path yet to telephony destination %s, requesting...",
                    call_destination.hash.hex(),
                )
                self._rns.Transport.request_path(call_destination.hash)
                deadline = time.time() + self.path_wait_timeout_s
                poll_interval = min(0.2, self.path_wait_timeout_s)
                while not self._rns.Transport.has_path(call_destination.hash) and time.time() < deadline:
                    time.sleep(poll_interval)
                if not self._rns.Transport.has_path(call_destination.hash):
                    log.warning(
                        "place_call failed: no path found to telephony destination %s within %ss",
                        call_destination.hash.hex(), self.path_wait_timeout_s,
                    )
                    return False, "No path found to that address"
                log.info("place_call: path found to %s", call_destination.hash.hex())

            self.status = CallStatus.CALLING
            self.is_incoming = False
            self.remote_identity_hash = identity.hash.hex()
            self.started_at = time.time()
            self.ended_reason = None
            self._answered = False
            self._notify()

            self.link = self._rns.Link(
                call_destination,
                established_callback=self._outgoing_link_established,
                closed_callback=self._link_closed,
            )
            self._start_timeout(self.ring_timeout_s, expected_status_below=CallStatus.ESTABLISHED)
            return True, "Calling"

    def _outgoing_link_established(self, link) -> None:
        link.set_packet_callback(self._packet_received)
        # Real signal exchange starts once the callee's own
        # link-established handler sends AVAILABLE — handled in
        # _packet_received below, matching LXST's own flow exactly.

    # ------------------------------------------------------------------
    # Inbound
    # ------------------------------------------------------------------

    def _incoming_link_established(self, link) -> None:
        with self._lock:
            self._clear_terminal_state_locked()
            if self.status != CallStatus.IDLE:
                log.info("Incoming call while line busy, signalling BUSY")
                self._send_signal(link, Signalling.STATUS_BUSY)
                link.teardown()
                return
            link.set_remote_identified_callback(self._caller_identified)
            link.set_link_closed_callback(self._link_closed)
            link.set_packet_callback(self._packet_received)
            self._send_signal(link, Signalling.STATUS_AVAILABLE)

    def _caller_identified(self, link, identity) -> None:
        with self._lock:
            self._clear_terminal_state_locked()
            if self.status != CallStatus.IDLE:
                log.info("Caller identified while line busy, signalling BUSY")
                self._send_signal(link, Signalling.STATUS_BUSY)
                link.teardown()
                return
            log.info("Incoming call from %s", self._rns.prettyhexrep(identity.hash))
            self.link = link
            self.is_incoming = True
            self.remote_identity_hash = identity.hash.hex()
            self.status = CallStatus.RINGING_INCOMING
            self.started_at = time.time()
            self.ended_reason = None
            self._answered = False
            self._send_signal(link, Signalling.STATUS_RINGING)
            self._start_timeout(self.ring_timeout_s, expected_status_below=CallStatus.ESTABLISHED)
            self._notify()

    def answer_call(self) -> tuple:
        """Callee accepts a RINGING_INCOMING call. Returns (success, message)."""
        with self._lock:
            if self.status != CallStatus.RINGING_INCOMING or self.link is None:
                return False, "No incoming call to answer"
            self._answered = True
            self.status = CallStatus.CONNECTING
            self._send_signal(self.link, Signalling.STATUS_CONNECTING)
            self.status = CallStatus.ESTABLISHED
            self.established_at = time.time()
            self._send_signal(self.link, Signalling.STATUS_ESTABLISHED)
            self._notify()
            return True, "Answered"

    # ------------------------------------------------------------------
    # Signalling receive (both directions share this)
    # ------------------------------------------------------------------

    def _packet_received(self, data, packet) -> None:
        try:
            unpacked = self._msgpack.unpackb(data)
        except Exception as exc:
            log.warning("Could not decode call signalling packet: %s", exc)
            return
        if not isinstance(unpacked, dict) or 0x00 not in unpacked:
            return  # 0x01 (audio frames) intentionally ignored this phase
        signals = unpacked[0x00]
        if not isinstance(signals, list):
            signals = [signals]
        for signal in signals:
            self._handle_signal(signal, packet.link if hasattr(packet, "link") else self.link)

    def _handle_signal(self, signal: int, source) -> None:
        with self._lock:
            if source != self.link:
                log.info("Ignoring signal 0x%02x from a non-active link", signal)
                return
            if signal == Signalling.STATUS_BUSY:
                log.info("Remote signalled BUSY")
                self._end_call(CallStatus.BUSY, "Remote is busy")
            elif signal == Signalling.STATUS_REJECTED:
                log.info("Remote signalled REJECTED")
                self._end_call(CallStatus.REJECTED, "Call was rejected")
            elif signal == Signalling.STATUS_AVAILABLE:
                # Caller side only: line is free, identify ourselves —
                # RNS.Link's own standard handshake, not custom.
                if not self.is_incoming and self._identity is not None:
                    self.link.identify(self._identity)
            elif signal == Signalling.STATUS_RINGING:
                if not self.is_incoming:
                    self.status = CallStatus.RINGING_OUTGOING
                    self._notify()
            elif signal == Signalling.STATUS_CONNECTING:
                if not self.is_incoming:
                    self.status = CallStatus.CONNECTING
                    self._notify()
            elif signal == Signalling.STATUS_ESTABLISHED:
                if not self.is_incoming:
                    self.status = CallStatus.ESTABLISHED
                    self.established_at = time.time()
                    self._notify()

    # ------------------------------------------------------------------
    # Teardown
    # ------------------------------------------------------------------

    def hang_up(self) -> tuple:
        """User-initiated end — from either the caller or the callee,
        at any point in the call. Returns (success, message)."""
        with self._lock:
            if self.status == CallStatus.IDLE:
                return False, "No active call"
            was_ringing_incoming_unanswered = (
                self.status == CallStatus.RINGING_INCOMING and not self._answered
            )
            link = self.link
            if link is not None:
                if was_ringing_incoming_unanswered:
                    self._send_signal(link, Signalling.STATUS_REJECTED)
                try:
                    if link.status == self._rns.Link.ACTIVE:
                        link.teardown()
                except Exception:
                    pass
            # link.teardown() above can synchronously invoke
            # _link_closed on the calling thread (confirmed real against
            # the fake test Link — RLock is what makes that safe at all,
            # see __init__'s own comment) — which already calls
            # _end_call and clears self.link. A real bug this exact test
            # suite caught: without this guard, hang_up() unconditionally
            # called _end_call again right after, double-recording one
            # hangup as two history entries. _end_call always clears
            # self.link, so its absence here means the callback already
            # ran; only call it ourselves if it didn't (covers real RNS
            # deferring the callback instead, if it does).
            if self.link is not None:
                self._end_call(CallStatus.ENDED, "Call ended")
            return True, "Hung up"

    def _link_closed(self, link) -> None:
        with self._lock:
            if link != self.link:
                return
            log.info("Link closed (was in status %s)", self.status)
            if self.status not in (CallStatus.ENDED, CallStatus.BUSY, CallStatus.REJECTED, CallStatus.FAILED):
                self._end_call(CallStatus.ENDED, "Remote hung up")

    def _end_call(self, status: str, reason: str) -> None:
        # Caller must already hold self._lock.
        log.info(
            "Call ended: status=%s reason=%s remote=%s incoming=%s",
            status, reason, self.remote_identity_hash, self.is_incoming,
        )
        self._record_history(status, reason)
        self.status = status
        self.ended_reason = reason
        self.link = None
        self._notify()

    def _record_history(self, status: str, reason: str) -> None:
        # Caller must already hold self._lock.
        self.history.insert(0, {
            "is_incoming": self.is_incoming,
            "remote_identity_hash": self.remote_identity_hash,
            "started_at": self.started_at,
            "established_at": self.established_at,
            "ended_at": time.time(),
            "status": status,
            "reason": reason,
        })
        del self.history[HISTORY_MAX:]

    def get_history(self) -> list:
        with self._lock:
            return list(self.history)

    def reset_after_end(self) -> None:
        """Clears a terminal state (ENDED/BUSY/REJECTED/FAILED) back to
        IDLE so the *UI* can stop showing it — deliberately not called
        automatically from _end_call itself: CallOverlay needs a moment
        to actually show "call ended" rather than the state instantly
        disappearing back to idle. This is a distinct concern from
        _clear_terminal_state_locked(), which every call-starting path
        also calls on its own regardless of whether the UI has gotten
        around to this yet — see that method's own doc comment."""
        with self._lock:
            if self._clear_terminal_state_locked():
                self._notify()

    def _clear_terminal_state_locked(self) -> bool:
        """Caller must already hold self._lock. Returns True if a
        terminal state was actually cleared.

        A real bug this fixes: place_call()/_incoming_link_established()/
        _caller_identified() all used to gate on `status != IDLE` alone
        to decide "line busy" — but a terminal status (ENDED/BUSY/
        REJECTED/FAILED) means the *previous* call is over, not that the
        line is still busy. Without this, any new call arriving (or
        being placed) in the short window before the UI calls
        reset_after_end() (CallOverlay's own auto-dismiss delay) was
        incorrectly signalled BUSY / refused locally, even though
        nothing was actually happening on this end anymore. Called from
        every call-starting path directly (not just reset_after_end(),
        which only runs on the UI's own timer) so a new call is never
        stuck waiting on that timer to get through."""
        if self.status in (CallStatus.ENDED, CallStatus.BUSY, CallStatus.REJECTED, CallStatus.FAILED):
            self.status = CallStatus.IDLE
            self.is_incoming = False
            self.remote_identity_hash = None
            self.started_at = None
            self.established_at = None
            self._answered = False
            return True
        return False

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _send_signal(self, link, signal: int) -> None:
        try:
            packet = self._rns.Packet(link, self._msgpack.packb({0x00: [signal]}), create_receipt=False)
            packet.send()
        except Exception as exc:
            log.warning("Could not send call signal 0x%02x: %s", signal, exc)

    def _start_timeout(self, timeout_s: float, expected_status_below: str) -> None:
        call_link = self.link

        def job():
            time.sleep(timeout_s)
            with self._lock:
                if self.link is call_link and self.status not in (
                    CallStatus.ESTABLISHED, CallStatus.ENDED, CallStatus.BUSY,
                    CallStatus.REJECTED, CallStatus.FAILED,
                ):
                    log.info("Call timed out in status %s", self.status)
                    if self.link is not None:
                        try:
                            if self.link.status == self._rns.Link.ACTIVE:
                                self.link.teardown()
                        except Exception:
                            pass
                    self._end_call(CallStatus.FAILED, "Timed out")

        threading.Thread(target=job, daemon=True).start()

    def _notify(self) -> None:
        if callable(self._on_state_change):
            try:
                self._on_state_change()
            except Exception:
                pass

    def status_dict(self) -> dict:
        with self._lock:
            return {
                "status": self.status,
                "is_incoming": self.is_incoming,
                "remote_identity_hash": self.remote_identity_hash,
                "started_at": self.started_at,
                "established_at": self.established_at,
                "ended_reason": self.ended_reason,
            }
