"""
nomadportal_core.orchestrator — the Android-side equivalent of the
original Flask app's create_app()/create_wsgi() wiring (see
nomadnet_web/__init__.py in python-core for the source this mirrors),
minus everything Flask-specific and minus SiteServer/hosting (that's
sequencing step 5, a separate task even though the Settings "node
hosting" toggle already exists on the Kotlin side).

Real, tested findings this design is built on — see the
nomadportal-android-orchestration-design memory for the full research
writeup, this is the condensed version:

- `RNS.Reticulum()` must be constructed exactly ONCE per process
  lifetime. It cannot be cleanly reconstructed — a hard singleton guard
  in `__init__` raises `OSError` on a second construction, and
  `exit_handler()` does not reset it (confirmed by direct testing,
  including a failed private-attribute workaround that surfaced a
  *deeper* leftover-state error in `RNS.Transport`). Do not add any
  "restart Reticulum" code path here.
- Connectivity toggles are implemented by adding individual `RNS.Interface`
  objects via the running `Reticulum` instance's own `_add_interface()` —
  **not** a bare `RNS.Transport.add_interface()` + `final_init()`, which
  was tried first and is silently incomplete: `_add_interface()` is also
  where RNS's own config-file loader sets `ifac_size` (and `mode`, `OUT`,
  `announce_cap`, MTU optimization, IFAC key derivation if configured) on
  every interface it builds — none of that happens if you only call
  `Transport.add_interface()` yourself. Confirmed by direct on-device
  repro: a manually-added `TCPClientInterface` connected its socket fine,
  then immediately errored every single receive with `'TCPClientInterface'
  object has no attribute 'ifac_size'` and looped reconnecting forever.
  `_browser.reticulum` (the actual `Reticulum()` singleton — not
  `_browser._rns`, which is just the `RNS` module reference used
  elsewhere in `browser.py`) is what `_add_interface()` is called on.
  Removal still goes through `interface.detach()` +
  `RNS.Transport.remove_interface()` directly — that side was never
  broken, only the setup side.
- `TCPClientInterface` and `RNodeInterface` have real, correct
  `detach()` implementations (socket/radio cleanly closed) — safe to
  add/remove repeatedly.
- `AutoInterface` (the Wi-Fi discovery toggle) has a **broken
  `detach()`** in this RNS version: `def detach(self): self.online =
  False` — it never closes its bound UDP multicast socket, because that
  socket is a local variable inside `final_init()`, never stored on
  `self`, so there is no reference to close it from outside either.
  Confirmed by direct repro: add → remove → add again → `OSError:
  [WinError 10048] Only one usage of each socket address...`, reproduced
  even with a 3-second wait, so it's a genuine leak, not a release-timing
  race. Toggling Wi-Fi discovery off then back on within the same app
  process **will fail** — `set_wifi_discovery_enabled` raises a clear
  `RuntimeError` on that second enable rather than silently pretending
  to succeed. Fixing this for real needs a more invasive monkeypatch of
  `AutoInterface.final_init` to capture and store the UDP server
  reference — not done here; flagged as a known follow-up.
"""

import logging
import os
import re
import sys
import threading
import time
from typing import Optional

log = logging.getLogger(__name__)

_CTRL_RE = re.compile(r"[\r\n\x00-\x08\x0b-\x1f\x7f]")


def _setup_logging() -> None:
    """Equivalent of the original Flask app's app.py:_setup_logging() —
    ported here, not just skipped, because its absence was a real gap:
    nomadnet_web's own log.info()/log.warning() calls (browser.py's node
    discovery, lxmf_tracker's peer announces, etc.) use the stdlib
    `logging` module, which does nothing without a configured handler —
    unlike RNS's own separate RNS.log() mechanism, which writes to
    stdout unconditionally and was the only thing visible here before
    this existed. Found by noticing a real on-device run went completely
    silent (not even the announce-flood nomadnet_web.browser normally
    logs) despite RNS-level activity clearly working.

    Chaquopy redirects Python's sys.stdout to logcat under the
    `python.stdout` tag automatically (confirmed — RNS.log() output
    already appears there), so `stream=sys.stdout` reaches the same
    place on Android that it reaches `docker logs` in the original
    deployment.

    The log-injection guard (CR/LF strip filter) is carried over
    unchanged and is not optional: node names, LXMF sender display
    names, and other untrusted-peer-supplied strings flow into these
    log calls, and CodeQL's py/log-injection rule treats this filter as
    the sanitization barrier for all of them at once, at the root
    logger, rather than needing every call site fixed individually.

    LOG_LEVEL default kept at DEBUG, matching the original app's own
    stated reasoning (diagnostic value has repeatedly earned its keep) —
    revisit before a production release given battery/log-volume
    tradeoffs on a mobile device don't quite match a server deployment;
    not reconsidered here since this app is still pre-release.
    """
    logging.basicConfig(
        level=logging.DEBUG,
        format="%(asctime)s %(levelname)-8s %(name)s: %(message)s",
        stream=sys.stdout,
    )

    class _StripCRLFFilter(logging.Filter):
        def filter(self, record):
            if isinstance(record.msg, str):
                record.msg = _CTRL_RE.sub("?", record.msg)
            if isinstance(record.args, tuple):
                record.args = tuple(_scrub(a) for a in record.args)
            elif isinstance(record.args, dict):
                record.args = {k: _scrub(v) for k, v in record.args.items()}
            return True

    logging.getLogger().addFilter(_StripCRLFFilter())


def _scrub(value):
    if isinstance(value, str):
        return _CTRL_RE.sub("?", value)
    return value

_lock = threading.Lock()
_browser = None
_identity_store = None
_message_store = None
_contact_store = None
_messaging = None
_lxmf_tracker = None
_call_tracker = None
_call_manager = None
_prop_sync = None
# The one active (or connecting, or just-ended) rnsh remote-shell
# session — see rnsh_connect()'s own doc comment for the real
# single-session-at-a-time model this mirrors from call_manager.py.
_rnsh_session = None
_active_interfaces: dict = {}  # toggle name -> RNS.Interface currently attached
_started = False
_base_dir: str = ""
_site_server = None  # nomadnet_web.site_server.SiteServer instance, only while hosting is on

# Multiple, independently addressable TCP connections — replaces the
# original single hardcoded-hub design (see RealInterfaceController.kt's
# old TODO). Each entry: {"id", "name", "host", "port", "enabled"}.
# "id" is a stable uuid4 hex, not host:port — lets a connection be
# edited (future work) without churning its identity, and two entries
# can coexist with the same host:port (e.g. testing a change) without
# colliding.
_tcp_connections: dict = {}
# id -> RNS.Interface, only present for a connection that's actually
# attached right now (master enabled AND that connection's own enabled).
_tcp_ifaces: dict = {}
# The "duplicate toggle" every protocol tab carries, mirroring Main's
# own switch — same InterfaceController.tcpEnabled boolean Kotlin
# already had, just now also gating a *list* of connections instead of
# one. When off, every connection detaches regardless of its own
# enabled flag; individual connections' enabled flags are preserved
# either way, not overwritten.
_tcp_master_enabled = True
# Whether the one-time default-server seeding (see
# _seed_default_tcp_connection_if_needed's own doc comment) has already
# run to a conclusion (successfully added a connection). Persisted
# specifically so a user who deliberately removes the seeded connection
# never has it silently reappear on a later launch — this is a "give a
# fresh install something that works" step, not an ongoing policy.
_tcp_default_seeded = False


def start(base_dir: str) -> None:
    """One-time startup. Idempotent — a second call is a no-op and logs
    instead of raising, since Android may call this more than once
    across Activity/Application lifecycle events.

    `base_dir` must be an app-private, never-backed-up directory
    (`Context.getNoBackupFilesDir()` on the Kotlin side, per this app's
    panic-wipe design — RNS identity material must never leave the
    device via a backup/device-transfer side channel). RNS's own
    config+identity live under `base_dir/reticulum`; nodes.json/
    favorites.json/etc. (NodeBrowser's own state) land directly under
    `base_dir`, matching the original Docker layout's
    config_dir/reticulum convention.
    """
    global _browser, _identity_store, _message_store, _contact_store
    global _messaging, _lxmf_tracker, _call_tracker, _call_manager, _prop_sync, _started, _base_dir

    with _lock:
        if _started:
            log.info("orchestrator.start() called again — already started, ignoring")
            return
        _started = True

    _setup_logging()
    _base_dir = base_dir

    rns_dir = os.path.join(base_dir, "reticulum")
    os.makedirs(rns_dir, exist_ok=True)

    from nomadnet_web.browser import NodeBrowser
    from nomadnet_web.identity_store import IdentityStore
    from nomadnet_web.message_store import MessageStore
    from nomadnet_web.contact_store import ContactStoreManager
    from nomadnet_web.messaging import MessagingService
    from nomadnet_web.lxmf_tracker import LXMFPeerTracker
    from nomadnet_web.call_tracker import CallPeerTracker
    from nomadnet_web.call_manager import CallManager
    from nomadnet_web.lxmf_sync import PropagationSyncService

    # NodeBrowser.__init__ starts RNS.Reticulum() on its own background
    # thread and returns immediately — see this module's docstring and
    # browser.py's own comments for why (RNS.Reticulum() blocks 60-300s
    # on real deployments; running that on the calling thread would hang
    # whatever called start()).
    log.info("Starting NodeBrowser (RNS init runs on its own background thread)")
    _browser = NodeBrowser(config_dir=rns_dir)

    _identity_store = IdentityStore(rns_dir)
    # Single local user (user_sub=""), no auth — the only value every
    # method across browser.py/messaging.py/contact_store.py actually
    # defaults to, and the only one anything here ever passes. Must
    # happen before setup_delivery() below: LXMRouter registration is
    # per-identity, and without an identity existing yet,
    # list_identities() is empty and zero routers get created, silently
    # breaking send/receive until something happens to call this later.
    # Pure local keypair generation — no RNS.Reticulum()/network
    # dependency, safe to do here rather than gating on wait_ready().
    _identity_store.ensure_for_user("")
    _message_store = MessageStore(base_dir)
    _contact_store = ContactStoreManager(base_dir)
    _messaging = MessagingService(
        storage_path=os.path.join(rns_dir, "lxmf"),
        message_store=_message_store,
        contact_store=_contact_store,
    )
    _lxmf_tracker = LXMFPeerTracker(base_dir)
    # Phase 0 of a real voice-call feature — see call_tracker.py's own
    # doc comment. Only tracks "has this identity ever announced
    # call-capability," surfaced as a phone icon on contact cards;
    # nothing about placing/receiving an actual call yet.
    _call_tracker = CallPeerTracker(base_dir)
    # Phase 1a/1b — the real call signalling state machine (ring/answer/
    # hangup) plus the Phase 1b audio-frame relay layered on top of it
    # (send_call_audio_frame/pop_call_audio_frame below). Actually
    # brought up (its own Destination created, ready to receive calls)
    # in _register_call_manager below, once RNS/the local identity are
    # both ready.
    _call_manager = CallManager()
    _prop_sync = PropagationSyncService(rns=_browser._rns, messaging_service=_messaging)

    # Pure local file I/O, no RNS dependency — safe here rather than
    # gating on wait_ready(), same reasoning as ensure_for_user("")
    # above. Actually attaching the loaded connections' interfaces still
    # has to wait for RNS (see _sync_tcp_interfaces in the deferred
    # steps below).
    _load_tcp_connections()

    # Same "pure local file I/O, no RNS dependency" reasoning as
    # _load_tcp_connections() above — disappearing-messages purging
    # never touches the network, so there's no reason to make it wait
    # for RNS to come up via the deferred-setup steps list below.
    start_disappearing_sweep_loop()

    threading.Thread(
        target=_run_deferred_setup, daemon=True, name="nomadportal-deferred-init"
    ).start()


def _run_deferred_setup() -> None:
    """Mirrors the original create_app()'s _run_deferred_rns_init: wait
    for RNS to actually be ready, then run each RNS-dependent setup step
    in order, logging (not raising) on a per-step failure so one bad
    step doesn't block the rest.
    """
    if not _browser.wait_ready(timeout=600):
        log.error("RNS did not become ready within 10 minutes — deferred setup skipped")
        return

    steps = [
        # Each router setup_delivery() registers gets its own one-time
        # bootstrap announce inside _init_user_router. Periodic
        # re-announcing beyond that (per-interface, configurable) is
        # start_announce_loop() below, plus a send-time staleness check
        # in send_message() — see this module's announce-section doc
        # comment for the full design.
        ("LXMF delivery setup", lambda: _messaging.setup_delivery(_identity_store)),
        ("LXMF auto-announce loop", start_announce_loop),
        ("LXMF propagation sync service", _prop_sync.start),
        ("LXMF tracker registration", _register_lxmf_tracker),
        ("Call tracker registration", _register_call_tracker),
        # Fresh installs only (see _tcp_default_seeded's own doc
        # comment) — gives a brand-new user a real, working TCP
        # connection instead of the empty list they'd otherwise start
        # with. Runs before the sync step below on purpose: it calls
        # add_tcp_connection() itself when it finds a reachable
        # candidate, which already attaches it — the explicit sync step
        # right after is then just its normal idempotent no-op pass,
        # not a second attempt.
        ("Default TCP server seeding", _seed_default_tcp_connection_if_needed),
        # Attaches whichever TCP connections were loaded from disk and
        # are (master-enabled AND individually-enabled) — this is what
        # actually makes a persisted "TCP: on" connection live again
        # after an app restart, same role wait_ready()'s own doc comment
        # describes for the old single-connection design.
        ("TCP connections sync", _sync_tcp_interfaces),
        # Call engine startup fires one bootstrap announce (mirrors LXMF
        # delivery setup's own one-time announce) — deliberately placed
        # *after* TCP sync above, not before: a real bug, caught via a
        # real failed test call, was this step originally running before
        # any interface was attached, so its bootstrap announce had
        # nothing to actually transmit over and likely never reached the
        # mesh at all. LXMF's own bootstrap announce sits earlier in this
        # same list (before TCP sync too) but gets away with it because
        # start_announce_loop's periodic re-announcing eventually
        # recovers; the call engine had no equivalent recovery path until
        # the step below was added.
        ("Call engine startup", _start_call_manager),
        ("Call engine auto-announce loop", start_call_announce_loop),
    ]
    for name, fn in steps:
        try:
            fn()
        except Exception as exc:
            log.warning("Deferred setup step '%s' failed: %s", name, exc)
    log.info("nomadportal_core deferred setup complete")


def _register_lxmf_tracker() -> None:
    import RNS
    RNS.Transport.register_announce_handler(_lxmf_tracker.register_announce_handler())


def _register_call_tracker() -> None:
    import RNS
    RNS.Transport.register_announce_handler(_call_tracker.register_announce_handler())


def _start_call_manager() -> None:
    """Brings up this device's own lxst.telephony Destination so it can
    receive calls, and fires one bootstrap announce (same "at least once
    at startup" convention as _init_user_router's own LXMF delivery
    announce). start_call_announce_loop (registered as the very next
    deferred step) covers periodic re-announcing beyond that.

    A dedicated Settings toggle for the recurring interval (mirroring
    _interface_announce_config's existing per-interface pattern) is
    still deliberately NOT built — per explicit direction ("eventually
    the call address auto announce will need its own auto announce
    toggle and manual announce toggle"), the underlying mechanism (an
    actual working periodic announce) came first since it's a real
    prerequisite for testing calls at all; the toggle UI is next.
    """
    if _identity_store is None or _call_manager is None:
        return
    entry = _identity_store.get_for_user("")
    if entry is None:
        log.warning("Call engine startup skipped — no local identity yet")
        return
    identity = _identity_store.load_rns_identity(entry["id"])
    if identity is None:
        log.warning("Call engine startup skipped — could not load local identity")
        return
    import RNS
    _call_manager.start(RNS, identity)
    _call_manager.announce()


# Matches LXST's own real Telephone.ANNOUNCE_INTERVAL/ANNOUNCE_INTERVAL_MIN
# defaults (verified against source) — not an arbitrary choice here.
CALL_ANNOUNCE_INTERVAL_S = 60 * 60 * 3   # 3 hours
CALL_ANNOUNCE_INTERVAL_MIN_S = 60 * 5    # 5 minutes
_call_announce_loop_started = False


def _call_announce_loop() -> None:
    while True:
        time.sleep(ANNOUNCE_LOOP_TICK)
        if _call_manager is None or _call_manager._destination is None:
            continue
        last = _call_manager.last_announce_at
        if last is None or time.time() - last >= CALL_ANNOUNCE_INTERVAL_S:
            _call_manager.announce()


def start_call_announce_loop() -> None:
    """Idempotent — a second call is a no-op. Same daemon-thread shape as
    start_announce_loop (LXMF's own equivalent)."""
    global _call_announce_loop_started
    with _lock:
        if _call_announce_loop_started:
            return
        _call_announce_loop_started = True
    threading.Thread(target=_call_announce_loop, daemon=True, name="call-auto-announce").start()


def is_ready() -> bool:
    """Non-blocking readiness check for Kotlin to poll (e.g. before
    allowing a connectivity toggle, or before showing "browsing" UI as
    usable). timeout=0 makes NodeBrowser.wait_ready() check the
    underlying threading.Event without blocking.
    """
    return _browser is not None and _browser.wait_ready(timeout=0)


def wait_ready(timeout: float = 300.0) -> bool:
    """Blocking readiness wait — only safe to call from a background
    thread (Chaquopy calls block that thread, not the JVM main thread,
    but callers must still not invoke this from Android's main thread).
    Used once, at app startup, so the caller knows when it's safe to
    bring up whichever interfaces should start automatically (a
    persisted-"on" toggle from a previous session, or this app's
    TCP-on-by-default) — `start()` itself only constructs `NodeBrowser`/
    RNS, it never adds any `Interface`, so without a caller doing this,
    a persisted "TCP: on" setting would be cosmetic until a user
    manually re-toggled it. Everything else that just needs a quick
    non-blocking check should keep using `is_ready()`.
    """
    return _browser is not None and _browser.wait_ready(timeout=timeout)


# ---------------------------------------------------------------------------
# Connectivity toggles — add/remove RNS.Interface objects on the *running*
# Transport. See this module's docstring for why this is the mechanism,
# not restarting Reticulum() itself.
# ---------------------------------------------------------------------------

def set_rnode_enabled(enabled: bool, serial_port: str) -> None:
    """RNodeInterface has a real, correct detach() — safe to add/remove
    repeatedly. `serial_port` is the USB serial device path; Bluetooth-
    connected RNode support is a separate future integration (see the
    RNS_BLE_Wrapper sibling repo), not this function."""
    _set_interface("rnode", enabled, lambda: _make_rnode(serial_port))


def set_bluetooth_mesh_bridge(bridge) -> None:
    """Attaches the phone-to-phone BLE mesh interface, backed by a live
    `com.jamesm92.rnsble.interop.RnsBleBridge` Kotlin object Chaquopy
    hands across the boundary — see `BluetoothMeshManager.kt`, which
    binds/starts `RnsBleForegroundService` and calls this the moment a
    real bridge exists (there's no way to construct one from the Python
    side; unlike RNode/Wi-Fi-discovery's interfaces, this one has no
    config-only factory).

    Reuses `_set_interface`'s exact same attach/detach machinery
    (`_browser.reticulum._add_interface`/`RNS.Transport.remove_interface`)
    under the `"bluetooth_mesh"` key — the same key
    `AnnounceStatus.INTERFACE_BLUETOOTH` already uses on the Kotlin side
    for this interface's per-interface announce policy, and the same key
    `_interface_announce_config` already has an entry for — nothing
    about the existing announce-policy plumbing needed to change to
    recognize this interface once it's real.

    `RnsBleInterface`'s own `owner` argument is `RNS.Transport` (not
    `None`/this module) — verified against `demo_rns_config.py`'s own
    real usage and against how `process_incoming`'s `self.owner.inbound(
    data, self)` call is used by every bundled RNS interface.
    """
    from rns_ble_interface import RnsBleInterface
    import RNS
    _set_interface("bluetooth_mesh", True, lambda: RnsBleInterface(RNS.Transport, {}, bridge))


def clear_bluetooth_mesh_bridge() -> None:
    """Detaches the Bluetooth-mesh interface — called from
    `BluetoothMeshManager.kt` when the toggle turns off or the service
    unbinds/stops. The `factory` argument to `_set_interface` is never
    actually invoked on this path (only used when turning an interface
    *on*), so `lambda: None` here is just satisfying the signature."""
    _set_interface("bluetooth_mesh", False, lambda: None)


def set_wifi_discovery_enabled(enabled: bool) -> None:
    """See this module's docstring: AutoInterface's detach() is broken
    in this RNS version and never releases its UDP socket. Enabling
    this a SECOND time within the same app process will raise
    RuntimeError (from the underlying OSError) — that is a known,
    documented limitation, not swallowed silently. Disabling still calls
    detach()/remove_interface() (best-effort: stops it from being used
    for routing even though the OS socket leaks until the process
    exits)."""
    _set_interface("wifi_discovery", enabled, _make_auto_interface)


def set_node_hosting_enabled(enabled: bool) -> None:
    """Starts/stops this device's own NomadNet site (`SiteServer`) —
    unlike the interfaces `_set_interface` manages below, hosting isn't
    an `RNS.Interface` at all, it's an `RNS.Destination` serving pages/
    files, so it's wired independently rather than through that
    function's attach/detach machinery.

    Pages/files/identity all live under `_base_dir` (this app's own
    never-backed-up storage root — same reasoning as everywhere else in
    this app: nothing here should leave the device via a backup side
    channel), in their own `site/` subdirectory, parallel to how
    messaging's attachments/ subdirectory works.

    Raises RuntimeError on failure — not swallowed — matching this
    module's other toggle functions' "let the caller find out" contract
    (see set_wifi_discovery_enabled's own doc comment for the same
    convention).
    """
    global _site_server
    with _lock:
        if enabled:
            if _site_server is not None:
                return  # already on
            if not is_ready():
                raise RuntimeError("Cannot start hosting — RNS is not ready yet")
            from nomadnet_web.site_server import SiteServer
            site_dir = os.path.join(_base_dir, "site")
            server = SiteServer(
                pages_dir=os.path.join(site_dir, "pages"),
                files_dir=os.path.join(site_dir, "files"),
                identity_file=os.path.join(_base_dir, "reticulum", "site_identity.id"),
            )
            node_hash = server.start()
            _site_server = server
            if _browser is not None:
                _browser.set_hosted(node_hash, server.node_name())
            log.info("Node hosting enabled")
        else:
            if _site_server is None:
                return  # already off
            _site_server.stop()
            _site_server = None
            if _browser is not None:
                _browser.clear_hosted()
            log.info("Node hosting disabled")


def node_hosting_enabled() -> bool:
    return _site_server is not None


def get_site_status_json() -> str:
    """[SiteStatus] shape: enabled, node_hash (nullable), node_name
    (nullable), pages_dir/files_dir (absolute on-device paths — the file
    nav UI reads/writes these directly, same "hand Kotlin a real path"
    convention as messaging.py's attachments), announce_interval_seconds
    (this hosted node's own independent announce schedule — a different
    thing from AnnounceStatus.interfaces, which governs how often this
    device's *LXMF identity* announces; the hosted node is a separate
    RNS destination with its own announce loop, see site_server.py — 0
    means auto-announce disabled, same no-separate-flag convention as
    every other announce control in this app), last_announce_at (unix
    seconds, nullable)."""
    import json
    if _site_server is None:
        return json.dumps({
            "enabled": False, "node_hash": None, "node_name": None,
            "pages_dir": None, "files_dir": None,
            "announce_interval_seconds": 0, "last_announce_at": None,
        })
    last_announce = _site_server.last_announce_at()
    # 0-means-disabled on the wire, same single-value convention as
    # every other announce control in this app (see
    # set_site_announce_interval's own doc comment) — collapses
    # SiteServer's own two separate fields (auto_announce bool +
    # announce_interval int) into the one Kotlin already expects,
    # rather than exposing both and risking them disagreeing.
    interval = _site_server.announce_interval() if _site_server.auto_announce_enabled() else 0
    return json.dumps({
        "enabled": True,
        "node_hash": _site_server.node_hash(),
        "node_name": _site_server.node_name(),
        "pages_dir": _site_server.pages_dir(),
        "files_dir": _site_server.files_dir(),
        "announce_interval_seconds": interval,
        "last_announce_at": last_announce if last_announce else None,
    })


def set_site_node_name(name: str) -> bool:
    if _site_server is None:
        return False
    _site_server.set_node_name(name)
    if _browser is not None:
        _browser.set_hosted(_site_server.node_hash(), _site_server.node_name())
    return True


def set_site_announce_interval(seconds: int) -> bool:
    """0 means disabled — same convention as messaging.py's
    set_auto_announce_interval (no separate enabled flag). SiteServer
    itself keeps auto_announce and announce_interval as two separate
    internal fields (its own established design, unchanged here) — this
    bridge function is what reconciles that with the single-value "0 =
    off" convention every other announce control in this app already
    uses, rather than exposing SiteServer's two-field shape directly to
    Kotlin."""
    if _site_server is None:
        return False
    enabled = seconds > 0
    _site_server.set_auto_announce(enabled)
    if enabled:
        _site_server.set_announce_interval(seconds)
    return True


def announce_site_now() -> bool:
    if _site_server is None:
        return False
    _site_server.announce()
    return True


# ---------------------------------------------------------------------------
# Hosted-node page management (file nav — phase 2 of the hosting feature).
# Deliberately independent of whether SiteServer is currently running —
# _site_pages_dir() is a plain path under _base_dir, not something
# SiteServer owns exclusively, so a page can be authored/organized
# whether hosting is currently on or off (SiteServer picks up whatever's
# on disk on its next rescan/start — see site_server.py's own
# RESCAN_INTERVAL). Every function here validates the given relative
# path resolves to something still inside the pages directory, same
# realpath-prefix-check SiteServer.fetch_page already uses, and only
# ".mu" is ever accepted for a *page* — per explicit direction ("will
# be requiring it to be .mu only no python or executables"), enforced
# here at the point content is actually created, not just at the
# serving layer this module already hardened.
#
# No folder-creation here, deliberately — per explicit direction, a
# phone-hosted site should stay a simple flat structure, not a nested
# tree an author has to navigate on a small screen. list/rename/delete
# still tolerate a folder if one somehow exists (SiteServer itself has
# always supported nested directories, and there's no reason to make
# those calls actively hostile to one), there's just no UI path left
# that can ever create a new one.
# ---------------------------------------------------------------------------

def _site_pages_dir() -> str:
    path = os.path.join(_base_dir, "site", "pages")
    os.makedirs(path, exist_ok=True)
    from nomadnet_web.site_server import seed_starter_content
    seed_starter_content(path)
    return path


def _resolve_site_path(relative_path: str) -> Optional[str]:
    """None if [relative_path] would escape the pages directory (path
    traversal) or is empty/root when a specific entry was required by
    the caller — callers decide which of those cases apply."""
    pages_root = os.path.realpath(_site_pages_dir())
    candidate = os.path.realpath(os.path.join(pages_root, (relative_path or "").strip("/\\")))
    if candidate != pages_root and not candidate.startswith(pages_root + os.sep):
        return None
    return candidate


def list_site_pages_json(relative_path: str = "") -> str:
    """[SitePageEntry] shape: name, path (relative, forward-slash —
    Kotlin's own join separator, not this OS's), is_directory. Lists
    only the immediate children of [relative_path] (a plain directory
    listing, not a recursive tree) — the file nav UI calls this again
    each time it navigates into a folder, same "no continuous polling
    needed, nothing external mutates this directory" reasoning as
    everywhere else content only ever changes via this app's own
    actions. Empty list (not an error) for a path that doesn't resolve
    or isn't a directory — nothing to show is a valid, unremarkable
    state for a brand new site."""
    import json
    resolved = _resolve_site_path(relative_path)
    if resolved is None or not os.path.isdir(resolved):
        return json.dumps([])
    entries = []
    for entry in sorted(os.listdir(resolved)):
        if entry.startswith("."):
            continue
        full = os.path.join(resolved, entry)
        rel = (relative_path.strip("/\\") + "/" + entry).strip("/") if relative_path else entry
        entries.append({
            "name": entry,
            "path": rel.replace(os.sep, "/"),
            "is_directory": os.path.isdir(full),
        })
    return json.dumps(entries)


def create_site_page(relative_path: str) -> str:
    """[FileOpResult] shape: success, message — same shape as
    announce_now(), this section's own established convention for
    "bool plus a human-readable reason" rather than a raw Python tuple
    (never used elsewhere as a Kotlin-facing return type in this
    module)."""
    import json
    if not relative_path.endswith(".mu"):
        return json.dumps({"success": False, "message": "Pages must end in .mu"})
    resolved = _resolve_site_path(relative_path)
    if resolved is None:
        return json.dumps({"success": False, "message": "Invalid path"})
    if os.path.exists(resolved):
        return json.dumps({"success": False, "message": "Already exists"})
    try:
        os.makedirs(os.path.dirname(resolved), exist_ok=True)
        with open(resolved, "w", encoding="utf-8") as fh:
            fh.write("")
        return json.dumps({"success": True, "message": "Created"})
    except OSError as exc:
        return json.dumps({"success": False, "message": str(exc)})


def rename_site_entry(old_relative_path: str, new_relative_path: str) -> str:
    """Also how a page/folder is *moved* — a move is just a rename to a
    path under a different parent directory, no separate operation."""
    import json
    old_resolved = _resolve_site_path(old_relative_path)
    new_resolved = _resolve_site_path(new_relative_path)
    if old_resolved is None or new_resolved is None:
        return json.dumps({"success": False, "message": "Invalid path"})
    if not os.path.exists(old_resolved):
        return json.dumps({"success": False, "message": "Not found"})
    if os.path.exists(new_resolved):
        return json.dumps({"success": False, "message": "Already exists"})
    # A folder has no extension concept, but a page being renamed/moved
    # must stay a .mu file — silently letting it become e.g. "page.py"
    # here would reopen exactly the hole fetch_page's own hardening
    # closed on the serving side.
    if os.path.isfile(old_resolved) and not new_relative_path.endswith(".mu"):
        return json.dumps({"success": False, "message": "Pages must end in .mu"})
    try:
        os.makedirs(os.path.dirname(new_resolved), exist_ok=True)
        os.rename(old_resolved, new_resolved)
        return json.dumps({"success": True, "message": "Renamed"})
    except OSError as exc:
        return json.dumps({"success": False, "message": str(exc)})


def delete_site_entry(relative_path: str) -> str:
    import json
    resolved = _resolve_site_path(relative_path)
    if resolved is None or resolved == os.path.realpath(_site_pages_dir()):
        return json.dumps({"success": False, "message": "Invalid path"})
    if not os.path.exists(resolved):
        return json.dumps({"success": False, "message": "Not found"})
    try:
        if os.path.isdir(resolved):
            import shutil
            shutil.rmtree(resolved)
        else:
            os.remove(resolved)
        return json.dumps({"success": True, "message": "Deleted"})
    except OSError as exc:
        return json.dumps({"success": False, "message": str(exc)})


def read_site_page_json(relative_path: str) -> str:
    """{"content": str, "error": null} or {"content": null, "error": str}."""
    import json
    resolved = _resolve_site_path(relative_path)
    if resolved is None or not os.path.isfile(resolved):
        return json.dumps({"content": None, "error": "Page not found"})
    try:
        with open(resolved, "r", encoding="utf-8", errors="replace") as fh:
            return json.dumps({"content": fh.read(), "error": None})
    except OSError as exc:
        return json.dumps({"content": None, "error": str(exc)})


def write_site_page(relative_path: str, content: str) -> str:
    import json
    if not relative_path.endswith(".mu"):
        return json.dumps({"success": False, "message": "Pages must end in .mu"})
    resolved = _resolve_site_path(relative_path)
    if resolved is None:
        return json.dumps({"success": False, "message": "Invalid path"})
    try:
        os.makedirs(os.path.dirname(resolved), exist_ok=True)
        with open(resolved, "w", encoding="utf-8") as fh:
            fh.write(content)
        # Belt-and-suspenders on top of never invoking a page as a
        # subprocess (see this module's site_server.py-hardening notes)
        # — a page this app itself just wrote should never carry an
        # executable bit in the first place, but strip it explicitly
        # rather than trust that always holds (e.g. a future platform/
        # umask surprise). No-op on Windows dev machines (os.chmod's
        # execute bits are meaningless there), harmless either way.
        try:
            import stat
            mode = os.stat(resolved).st_mode
            os.chmod(resolved, mode & ~(stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH))
        except OSError:
            pass
        return json.dumps({"success": True, "message": "Saved"})
    except OSError as exc:
        return json.dumps({"success": False, "message": str(exc)})


def _set_interface(key: str, enabled: bool, factory) -> None:
    if not is_ready():
        raise RuntimeError(f"Cannot toggle '{key}' — RNS is not ready yet")

    import RNS
    with _lock:
        existing = _active_interfaces.get(key)
        if enabled:
            if existing is not None:
                return  # already on
            iface = factory()
            # _add_interface(), not a bare Transport.add_interface() +
            # final_init() — see this module's docstring for why the
            # latter silently leaves an interface half-initialized
            # (missing ifac_size and friends).
            _browser.reticulum._add_interface(iface)
            _active_interfaces[key] = iface
            log.info("Interface '%s' enabled", key)
        else:
            if existing is None:
                return  # already off
            existing.detach()
            RNS.Transport.remove_interface(existing)
            del _active_interfaces[key]
            log.info("Interface '%s' disabled", key)


# ---------------------------------------------------------------------------
# TCP: multiple, independently addressable connections — replaces the
# original single-hardcoded-hub design. Persisted to a small JSON file
# under base_dir so connections survive an app restart, same convention
# ui_settings.py already uses in python-core for admin-configurable state.
# ---------------------------------------------------------------------------

def _tcp_config_path() -> str:
    return os.path.join(_base_dir, "tcp_connections.json")


def _load_tcp_connections() -> None:
    import json
    global _tcp_connections, _tcp_master_enabled, _tcp_default_seeded
    try:
        with open(_tcp_config_path(), "r") as f:
            data = json.load(f)
        _tcp_connections = {c["id"]: c for c in data.get("connections", [])}
        _tcp_master_enabled = bool(data.get("master_enabled", True))
        _tcp_default_seeded = bool(data.get("default_seeded", False))
    except (FileNotFoundError, ValueError, OSError, KeyError) as exc:
        log.info("No existing TCP connections config (%s) — starting empty", exc)
        _tcp_connections = {}


def _save_tcp_connections() -> None:
    import json
    try:
        os.makedirs(_base_dir, exist_ok=True)
        with open(_tcp_config_path(), "w") as f:
            json.dump(
                {
                    "master_enabled": _tcp_master_enabled,
                    "connections": list(_tcp_connections.values()),
                    "default_seeded": _tcp_default_seeded,
                },
                f,
            )
    except OSError as exc:
        log.warning("Failed to save TCP connections: %s", exc)


def _sync_tcp_interfaces() -> None:
    """Attach/detach RNS TCPClientInterface objects so the live set
    matches (master_enabled AND each connection's own enabled flag).
    Idempotent — safe to call after every mutation, and once at startup
    once RNS is ready. No-ops entirely (doesn't raise) if RNS isn't
    ready yet — this can run during deferred setup before that's
    guaranteed, unlike _set_interface's toggle path which is only ever
    called from a live Settings-screen tap, after startup."""
    if not is_ready():
        return
    import RNS
    with _lock:
        for conn_id, conn in list(_tcp_connections.items()):
            should_be_up = _tcp_master_enabled and conn.get("enabled", True)
            currently_up = conn_id in _tcp_ifaces
            if should_be_up and not currently_up:
                try:
                    iface = _make_tcp_client(conn["host"], conn["port"])
                    _browser.reticulum._add_interface(iface)
                    _tcp_ifaces[conn_id] = iface
                    log.info("TCP connection '%s' (%s:%d) attached", conn.get("name"), conn["host"], conn["port"])
                except Exception as exc:
                    log.warning("Failed to attach TCP connection '%s': %s", conn.get("name"), exc)
            elif not should_be_up and currently_up:
                iface = _tcp_ifaces.pop(conn_id)
                try:
                    iface.detach()
                    RNS.Transport.remove_interface(iface)
                    log.info("TCP connection '%s' detached", conn.get("name"))
                except Exception as exc:
                    log.warning("Failed to detach TCP connection '%s': %s", conn.get("name"), exc)
        # Keep _active_interfaces["tcp"] presence in sync — the announce
        # section only checks membership (`"tcp" in _active_interfaces`),
        # it never reads the value, so a plain marker is enough; there's
        # no single RNS.Interface to represent "TCP" once there can be
        # several at once.
        if _tcp_ifaces:
            _active_interfaces["tcp"] = True
        else:
            _active_interfaces.pop("tcp", None)


def get_tcp_connections_json() -> str:
    """[TcpConnectionsStatus] shape: master_enabled, connections (list of
    {id, name, host, port, enabled, online}).

    "online" is live status, not persisted config — the real RNS
    `Interface.online` flag for whichever connections are currently
    attached (see _sync_tcp_interfaces), read fresh on every call.
    Always False for a connection that isn't attached at all right now
    (master off, or its own enabled flag off) — the correct/intuitive
    reading either way, not a distinct "unknown" state. Built as fresh
    dict copies rather than mutating the stored connection dicts
    themselves, so this never accidentally persists live status into
    tcp_connections.json on the next save.
    """
    import json
    connections = []
    for conn_id, conn in _tcp_connections.items():
        iface = _tcp_ifaces.get(conn_id)
        online = bool(iface is not None and getattr(iface, "online", False))
        connections.append({**conn, "online": online})
    return json.dumps({
        "master_enabled": _tcp_master_enabled,
        "connections": connections,
    })


def set_tcp_master_enabled(enabled: bool) -> None:
    """The "duplicate toggle" the TCP settings tab carries, mirroring
    Main's own TCP switch. When off, every connection detaches
    regardless of its own enabled flag; each connection's own enabled
    flag is preserved either way, not overwritten, so turning the
    master back on restores exactly the same live set as before."""
    global _tcp_master_enabled
    _tcp_master_enabled = bool(enabled)
    _save_tcp_connections()
    _sync_tcp_interfaces()


def add_tcp_connection(name: str, host: str, port: int) -> str:
    """Returns the new connection's id. New connections start enabled —
    matches the expectation of "I just added this, it should try to
    connect," consistent with how the old single-hub toggle defaulted
    to on."""
    import uuid
    conn_id = uuid.uuid4().hex
    _tcp_connections[conn_id] = {
        "id": conn_id,
        "name": (name or "").strip() or f"{host}:{port}",
        "host": host,
        "port": int(port),
        "enabled": True,
    }
    _save_tcp_connections()
    _sync_tcp_interfaces()
    return conn_id


# Bound on how many candidates a single default-seeding pass will
# actually probe before giving up for this launch -- the directory's
# own "online" status is only a first-pass filter (see
# _fetch_tcp_directory_candidates), so a real probe sweep can still run
# long if that data is stale; this keeps one bad launch from blocking
# the deferred-setup thread for minutes over a large candidate pool.
# Not a promise "only 10 servers are ever eligible" -- just how many
# get *tried* per attempt; a fresh attempt on the next app launch
# starts from the same index and gets its own budget.
_MAX_TCP_SEED_PROBES = 10


def _seed_default_tcp_connection_if_needed() -> None:
    """Gives a fresh install a real, working TCP connection by default,
    instead of the empty list _load_tcp_connections() otherwise leaves
    it at — deliberately spread across a pool of independently-run
    public interfaces (fetched live from directory.rns.recipes, not a
    handful of hostnames baked into this app) rather than everyone
    converging on the same one, per explicit direction.

    Deterministic starting pick: this identity's own LXMF address hash
    (see identity_store.py's IdentityStore.create — "dest_hash_hex" in
    its stored entry) indexes into the fetched, filtered candidate list
    via one hex nibble, hex[3] — the same "one hex nibble picks a
    thing" convention _default_display_name/_default_icon_appearance
    already use for the auto-generated name/icon, continued at the
    next position those two haven't already claimed (hex[0:3], then
    hex[4:10]). Different identities land on different starting points
    across a large pool without any coordination needed.

    Real rollover, not just a single deterministic pick: starting at
    that index, each candidate gets a raw TCP reachability probe (see
    _tcp_probe) in turn, wrapping around the list, until one succeeds
    or the probe budget (_MAX_TCP_SEED_PROBES) runs out — "assigned
    server" only means "where the rotation *starts*", never "used even
    if it's down". Only that one, actually-reachable connection gets
    persisted via add_tcp_connection; nothing else in the pool is added
    or retried once one works.

    No ongoing monitoring after that — if the seeded connection later
    goes down, that's the same "user notices and edits it themselves"
    story any other TCP connection already has, not a new background
    retry loop. This function only ever runs a fresh rollover sweep
    again on a *later app launch*, and only if the previous attempt
    never actually got as far as adding a connection (see
    _tcp_default_seeded's own doc comment).

    Never raises — one failed step here must not block the rest of
    deferred setup, same posture as every other step in that list.
    """
    global _tcp_default_seeded
    if _tcp_default_seeded or _tcp_connections:
        # Either already handled, or the user already has connections
        # of their own (e.g. added one manually before this step ever
        # got a chance to run) — don't second-guess either case.
        return

    entry = _identity_store.get_for_user("") if _identity_store else None
    dest_hash_hex = (entry or {}).get("dest_hash_hex")
    if not dest_hash_hex:
        log.info("No LXMF address hash available yet — skipping default TCP seeding for now")
        return

    candidates = _fetch_tcp_directory_candidates()
    if not candidates:
        log.info("Default TCP server directory unavailable or empty — nothing to seed yet")
        return

    start_index = int(dest_hash_hex[3], 16) % len(candidates)
    ordered = candidates[start_index:] + candidates[:start_index]

    for candidate in ordered[:_MAX_TCP_SEED_PROBES]:
        if _tcp_probe(candidate["host"], candidate["port"]):
            add_tcp_connection(candidate["name"], candidate["host"], candidate["port"])
            _tcp_default_seeded = True
            _save_tcp_connections()
            log.info(
                "Seeded default TCP connection '%s' (%s:%d)",
                candidate["name"], candidate["host"], candidate["port"],
            )
            return

    log.info(
        "None of the first %d candidate TCP servers (of %d total) were reachable — will retry next launch",
        min(_MAX_TCP_SEED_PROBES, len(ordered)), len(candidates),
    )


def _fetch_tcp_directory_candidates() -> list:
    """Live TCP-interface entries from directory.rns.recipes's public
    API (community-maintained, not this app's own data) — filtered to
    what this app can actually use: TCPClientInterface-compatible
    ("tcp" type only; "backbone"/BackboneInterface entries need a
    different RNS interface class this app doesn't construct anywhere
    else, out of scope here), clearnet (no Tor/I2P/Yggdrasil transport
    support in this app), and reported online by the directory itself —
    a first-pass filter only, not a substitute for _tcp_probe's own
    real reachability check, since directory status can lag reality
    either direction. Returns [] on any failure (network, parse,
    anything) — never raises, matching every other deferred-setup
    step's own never-block-the-rest posture.
    """
    import json
    import urllib.request

    url = "https://directory.rns.recipes/api/directory/submitted"
    try:
        with urllib.request.urlopen(url, timeout=10) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except Exception as exc:
        log.info("Could not fetch TCP server directory: %s", exc)
        return []

    candidates = []
    for item in payload.get("data", []):
        try:
            if item.get("type") != "tcp":
                continue
            if item.get("network") != "clearnet":
                continue
            if item.get("status") != "online":
                continue
            host = item["host"]
            port = int(item["port"])
            name = item.get("name") or f"{host}:{port}"
        except (KeyError, TypeError, ValueError):
            continue
        candidates.append({"name": name, "host": host, "port": port})

    # Stable order before sharding -- the directory's own JSON order
    # isn't guaranteed consistent between fetches, and a consistent
    # order matters for "different identities land on different
    # starting points" to actually mean anything within a single fetch.
    candidates.sort(key=lambda c: (c["host"], c["port"]))
    return candidates


def _tcp_probe(host: str, port: int, timeout: float = 5.0) -> bool:
    """Raw TCP reachability check — deliberately not a real RNS-level
    handshake (that's what add_tcp_connection's own _sync_tcp_interfaces
    does afterward, for whichever candidate actually gets picked); this
    only needs to answer "is anything listening here right now" cheaply
    enough to walk an entire candidate list per app launch."""
    import socket
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def update_tcp_connection(conn_id: str, name: str, host: str, port: int) -> bool:
    """Edits an existing connection's name/host/port in place — the
    editable-table UI's per-cell commit-on-blur, replacing the old
    remove-and-re-add-only workflow. If the address actually changed,
    any currently-attached live interface for this connection is
    detached first so _sync_tcp_interfaces() recreates it fresh against
    the new host/port on its next call, rather than silently keeping a
    stale connection to the old address alive under the same id."""
    conn = _tcp_connections.get(conn_id)
    if conn is None:
        return False
    host = (host or "").strip()
    if not host:
        return False
    try:
        port = int(port)
    except (TypeError, ValueError):
        return False
    if not (1 <= port <= 65535):
        return False
    address_changed = conn["host"] != host or conn["port"] != port
    conn["name"] = (name or "").strip() or f"{host}:{port}"
    conn["host"] = host
    conn["port"] = port
    if address_changed:
        iface = _tcp_ifaces.pop(conn_id, None)
        if iface is not None:
            import RNS
            try:
                iface.detach()
                RNS.Transport.remove_interface(iface)
            except Exception as exc:
                log.warning("Failed to detach TCP connection '%s' during edit: %s", conn.get("name"), exc)
    _save_tcp_connections()
    _sync_tcp_interfaces()
    return True


def remove_tcp_connection(conn_id: str) -> None:
    conn = _tcp_connections.pop(conn_id, None)
    if conn is None:
        return
    iface = _tcp_ifaces.pop(conn_id, None)
    if iface is not None:
        import RNS
        try:
            iface.detach()
            RNS.Transport.remove_interface(iface)
        except Exception as exc:
            log.warning("Failed to detach TCP connection '%s' during removal: %s", conn.get("name"), exc)
    if not _tcp_ifaces:
        _active_interfaces.pop("tcp", None)
    _save_tcp_connections()


def set_tcp_connection_enabled(conn_id: str, enabled: bool) -> None:
    conn = _tcp_connections.get(conn_id)
    if conn is None:
        return
    conn["enabled"] = bool(enabled)
    _save_tcp_connections()
    _sync_tcp_interfaces()


def _make_tcp_client(host: str, port: int):
    from RNS.Interfaces.TCPInterface import TCPClientInterface
    import RNS
    iface = TCPClientInterface(
        RNS.Transport, {"name": "TCP Client", "target_host": host, "target_port": port},
    )
    # Matches config_gen.py's own documented recommendation (python-core)
    # for busy public hubs: disables this interface's per-link announce
    # rate-limiting, which otherwise holds/drops new-destination announces
    # during a burst — exactly the traffic pattern a hub like michmesh
    # produces. RNS defaults ingress_control=True; there's no constructor
    # config key for it (unlike target_host/target_port), it's a plain
    # attribute set after construction — matches how TCPClientInterface
    # itself only reads it from the config dict for the config-file
    # loading path, not something passable to __init__ directly here.
    iface.ingress_control = False
    return iface


def _make_rnode(serial_port: str):
    from RNS.Interfaces.RNodeInterface import RNodeInterface
    import RNS
    # Field names/defaults match config_gen.py's own RNode section
    # builder (python-core/src/nomadnet_web/config_gen.py) — same
    # defaults the original app uses for an operator-configured RNode.
    config = {
        "name": "RNode",
        "port": serial_port,
        "frequency": 867500000,
        "bandwidth": 125000,
        "txpower": 7,
        "spreadingfactor": 8,
        "codingrate": 5,
    }
    return RNodeInterface(RNS.Transport, config)


def _make_auto_interface():
    from RNS.Interfaces.AutoInterface import AutoInterface
    import RNS
    config = {"name": "Auto Interface"}
    return AutoInterface(RNS.Transport, config)


# ---------------------------------------------------------------------------
# Browsing bridge — backs RealBrowserRepository.kt. Every method here
# returns a JSON string (not a raw PyObject) deliberately: NodeBrowser's
# get_nodes()/etc. return plain dicts, and hand-walking those field-by-field
# via Chaquopy's PyObject accessors from Kotlin (None-handling, int-vs-float,
# nested structures) is far more failure-prone than letting Python's own
# json.dumps do it and parsing on the Kotlin side with org.json. Nothing
# here is push-based (see the orchestration-design memory) — Kotlin polls
# these on an interval.
# ---------------------------------------------------------------------------

def get_nodes_json() -> str:
    """[NodeInfo] shape: hash, name, hops (nullable), last_load_ok
    (nullable), ever_load_ok (bool — true once a fetch has ever
    succeeded, regardless of last_load_ok's current value; lets the UI's
    status dot distinguish "failed just now but has worked before" from
    "never once worked"), favorited, last_seen (unix seconds — Kotlin
    multiplies by 1000). See browser.py's get_nodes() for the full dict
    shape; unused keys are just ignored on the Kotlin side rather than
    filtered here."""
    import json
    if _browser is None:
        return "[]"
    return json.dumps(_browser.get_nodes(user_sub=""))


def fetch_page_text(destination_hash_hex: str, path: str, identify: bool = False) -> str:
    """Raises RuntimeError with browser.py's own error string on failure
    (path not found, link closed, timeout, etc.) — matches
    BrowserRepository.fetchPage's documented "throws on failure"
    contract. Blocking on real network I/O, can legitimately take
    minutes (browser.py's PAGE_HARD_CAP=600s) — callers must run this on
    Dispatchers.IO with no artificial coroutine timeout.

    identify: when True, identifies this device's own RNS identity to
    the node over the fetch Link (browser.py's identify_with param,
    which calls RNS.Link.identify() before the page request) — porting-
    notes.md §4's "address bar with a 'fingerprint' (persistent
    identify-to-this-node) toggle separate from anonymous browsing."
    Resolves to this device's own LXMF/messaging identity (loaded the
    same way messaging.py's _init_user_router does) rather than a
    separate browsing-only identity — there's only one identity per
    install here. Silently falls back to anonymous (identify_with=None)
    if that identity can't be resolved for any reason, since a browse
    should never hard-fail just because the optional identify step
    couldn't be set up."""
    if _browser is None:
        raise RuntimeError("Browser not initialized yet")
    identify_with = None
    if identify and _identity_store is not None:
        entry = _identity_store.get_for_user("")
        if entry is not None:
            identify_with = _identity_store.load_rns_identity(entry["id"])
    content, error = _browser.fetch_page(destination_hash_hex, path, identify_with=identify_with)
    if error is not None:
        raise RuntimeError(error)
    return content.decode("utf-8", errors="replace")


def set_node_favorite(hash_hex: str, value: bool) -> bool:
    """False (not an exception) means browser.py declined the change —
    e.g. the node hasn't been discovered yet. See BrowserRepository's
    setFavorite — currently swallowed as a no-op rather than surfaced,
    since there's no error-toast mechanism in the Settings/Browser UI
    yet; revisit if that gap starts mattering in practice."""
    if _browser is None:
        return False
    return _browser.set_favorite(hash_hex, value, user_sub="")


# ---------------------------------------------------------------------------
# Messaging bridge — backs RealMessagingRepository.kt. Same JSON-string
# rationale as the browsing bridge above.
#
# Conversation/contact composition (get_conversations_json, get_messages_json)
# is genuinely new logic, not a thin wrapper — messaging.py/contact_store.py
# don't expose anything conversation-shaped themselves (contacts and
# messages are two independent stores with no "conversation" concept
# between them; see the orchestration-design memory's "contact/message
# existence is asymmetric" finding). This is Android-UI-shaping glue,
# which is exactly what this module is for — kept out of messaging.py
# itself to keep that file a clean, UI-agnostic port.
# ---------------------------------------------------------------------------

def _recall_announced_name(hash_hex: str) -> str:
    """Fallback name source for _conversation_entries(), tried between
    the live LXMF peer tracker and a stored contact name — see
    LXMFPeerTracker.decode_display_name's own doc comment for the real
    bug this fixes: this device's own tracker only knows a peer's name
    if *this process* directly received their announce via its
    registered handler, which a contact reached purely through relay/
    propagation (e.g. their very first message to us) never triggers.
    RNS.Identity.recall_app_data() reflects any announce RNS's own
    transport layer has processed for this hash at all, handler or not
    — confirmed directly against RNS 1.3.9 source, a real fix, not a
    guess. Never raises — a failed lookup here should just mean "no
    fallback name available," not break the whole conversations list."""
    try:
        import RNS
        from nomadnet_web.lxmf_tracker import LXMFPeerTracker
        app_data = RNS.Identity.recall_app_data(bytes.fromhex(hash_hex))
        return LXMFPeerTracker.decode_display_name(app_data)
    except Exception:
        return ""


def _conversation_entries() -> list:
    """Every hash worth showing on the Messages screen: anyone this user
    has ever exchanged a message with, has a saved contact for, or has
    merely *heard* an LXMF peer announce from (never messaged) — a
    ContactStore entry alone under-reports this (only created from an
    inbound icon field or an explicit upsert, see contact_store.py), and
    message history alone misses both saved-but-never-messaged contacts
    and heard-but-never-messaged peers. Powers the same three-section
    split ConversationListScreen.kt uses (Favorites/Messaged/Announces
    heard), mirroring NodeListScreen's Favorites/Announces-heard —
    `favorited`/`last_seen`/`hops` are included for exactly that.

    Name resolution, per explicit design direction ("give custom names
    to users... but for any we havent renamed to be renamed if they get
    a new announce"): a contact explicitly renamed via set_contact_name()
    (contact.custom_name == True) always keeps that name. Everyone else
    tracks the live LXMF-peer-tracker name, live-updating on every new
    announce, even once a ContactStore entry exists — a contact's `name`
    field gets auto-populated with a hash-prefix placeholder the moment
    an entry is created for any other reason (upsert()/set_icon()/
    set_icon_appearance() all do this), and that placeholder must never
    permanently block the real announced name from ever showing, which
    is exactly what a plain `contact["name"] or peer["name"]` priority
    chain used to do (a real reported bug once already, for the
    favoriting path specifically — see set_contact_favorite()'s own doc
    comment for that history; this generalizes the same fix to every
    entry-creation path, not just that one).

    Full chain, in order: this device's own live LXMF peer tracker
    (announces it directly received) → `RNS.Identity.recall_app_data()`
    (announces RNS's transport layer processed at all, a strictly
    larger set — see `_recall_announced_name`'s own doc comment for the
    real bug this second tier fixes: a contact whose first-ever contact
    with us was a message routed through relay/propagation, with no
    announce our own handler ever directly saw, previously fell straight
    through to their hash) → the stored contact name (itself either a
    real name captured earlier, or the hash-prefix placeholder described
    above) → the hash prefix, as an absolute last resort."""
    if _messaging is None:
        return []
    sent = _messaging.sent_messages()
    received = _messaging.received_messages()
    contacts = _contact_store.for_user("") if _contact_store else None
    contact_list = contacts.list_contacts() if contacts else []
    peers = _lxmf_tracker.get_peers() if _lxmf_tracker else []
    peers_by_hash = {p["hash"]: p for p in peers}
    # Phase 0 voice-call support — see call_tracker.py's own doc comment
    # for why this is keyed by identity hash, not LXMF destination hash.
    call_capable_identity_hashes = (
        _call_tracker.get_call_capable_hashes() if _call_tracker else set()
    )

    hashes = set()
    for m in sent:
        hashes.add(m["dest"])
    for m in received:
        hashes.add(m["source"])
    for c in contact_list:
        hashes.add(c["hash"])
    for p in peers:
        hashes.add(p["hash"])

    entries = []
    for h in hashes:
        contact = contacts.get(h) if contacts else None
        peer = peers_by_hash.get(h)
        my_sent = [
            {"id": m["id"], "content": m["content"], "ts": m["sent_at"], "is_sent": True, "state": m["state"],
             "attachment": m.get("attachment"), "expires_at": m.get("expires_at"),
             # Delivery diagnostics — see message_store.py's update_sent()
             # doc comment for what each of these means and why rssi/snr/
             # quality are honestly None most of the time. state_changed_at
             # is null until the first update_sent() call actually lands
             # (i.e. still genuinely "queued", no delivery/failure yet).
             "method": m.get("method"), "transport_encrypted": m.get("transport_encrypted"),
             "delivery_attempts": m.get("delivery_attempts"),
             "rssi": m.get("rssi"), "snr": m.get("snr"), "quality": m.get("quality"),
             "state_changed_at": m.get("state_changed_at")}
            for m in sent if m["dest"] == h
        ]
        my_received = [
            {"id": m["id"], "content": m["content"], "ts": m["received_at"], "is_sent": False, "state": None,
             "read": m.get("read", False), "attachment": m.get("attachment"), "expires_at": m.get("expires_at"),
             # Same diagnostic fields as a sent message — see
             # messaging.py's _on_delivery() for how these are captured.
             # No "delivery_attempts"/"state_changed_at" equivalent for a
             # received message — those are sent-side delivery-tracking
             # concepts with no receive-side counterpart.
             "method": m.get("method"), "transport_encrypted": m.get("transport_encrypted"),
             "rssi": m.get("rssi"), "snr": m.get("snr"), "quality": m.get("quality")}
            for m in received if m["source"] == h
        ]
        all_msgs = sorted(my_sent + my_received, key=lambda m: m["ts"])
        if contact and contact.get("custom_name"):
            name = contact["name"]
        else:
            name = (
                (peer.get("name") if peer else None)
                or _recall_announced_name(h)
                or (contact["name"] if contact else None)
                or h[:16]
            )
        entries.append({
            "hash": h,
            "name": name,
            "icon": contact.get("icon") if contact else None,
            "icon_mime": contact.get("icon_mime") if contact else None,
            "icon_glyph": contact.get("icon_glyph") if contact else None,
            "icon_fg": contact.get("icon_fg") if contact else None,
            "icon_bg": contact.get("icon_bg") if contact else None,
            "favorited": bool(contact.get("favorited")) if contact else False,
            "blocked": bool(contact.get("blocked")) if contact else False,
            # 0 = off. Purely a going-forward setting — see
            # set_disappearing_timer's own doc comment.
            "disappearing_seconds": contact.get("disappearing_seconds", 0) if contact else 0,
            "last_seen": peer.get("last_seen") if peer else None,
            "hops": peer.get("hops") if peer else None,
            # How many times this peer's LXMF delivery identity has
            # announced, total — lxmf_tracker.py already counts this per
            # peer (same convention as browser.py's node announce_count);
            # only exposed here for the Messages screen's "Announces"
            # sort option, wasn't needed by anything before that.
            "announce_count": peer.get("announce_count") if peer else None,
            # True once this peer's identity has ever announced on the
            # lxst.telephony aspect — Phase 0 (see call_tracker.py's own
            # doc comment): a "this contact supports calls" signal, not
            # an actual call feature yet. peer.get("identity_hash") is
            # None for any peer recorded before this field existed (a
            # pre-upgrade lxmf_peers.json) or if the announce simply
            # never carried a resolvable identity, in which case the
            # lookup below just correctly finds nothing.
            "call_capable": bool(
                peer and peer.get("identity_hash") in call_capable_identity_hashes
            ),
            "messages": all_msgs,
            "unread_count": sum(1 for m in my_received if not m["read"]),
        })
    return entries


def get_conversations_json() -> str:
    """[ConversationSummary] shape: hash, name, icon (base64, nullable),
    icon_mime (nullable), icon_glyph/icon_fg/icon_bg (FIELD_ICON_APPEARANCE
    descriptor, all-or-nothing nullable trio — mutually exclusive with
    icon/icon_mime, see contact_store.py's own doc comment), favorited,
    blocked (inbound messages from a blocked contact are dropped outright
    by messaging.py's `_on_delivery`, before ever reaching message
    storage — see `_is_blocked`'s own doc comment; this flag alone
    doesn't hide the contact from this list, only what they can send),
    disappearing_seconds (0 = off — see set_disappearing_timer's own doc
    comment), last_seen (unix seconds, nullable — last LXMF peer announce, not last
    message), hops (nullable), announce_count (nullable — total announces
    heard from this peer), call_capable (bool — Phase 0 voice-call
    support, see call_tracker.py's own doc comment: true once this
    contact's identity has ever announced on the lxst.telephony aspect),
    last_message (last entry of messages, or None), unread_count. Full
    per-message list is included too (Kotlin ignores it here) purely
    because computing it separately per-conversation would mean
    re-deriving the same sent/received union twice — cheap either way,
    these are in-memory list reads capped at 500+500 total
    (message_store.py's MAX_MESSAGES)."""
    import json
    entries = _conversation_entries()
    summaries = [
        {
            "hash": e["hash"],
            "name": e["name"],
            "icon": e["icon"],
            "icon_mime": e["icon_mime"],
            "icon_glyph": e["icon_glyph"],
            "icon_fg": e["icon_fg"],
            "icon_bg": e["icon_bg"],
            "favorited": e["favorited"],
            "blocked": e["blocked"],
            "disappearing_seconds": e["disappearing_seconds"],
            "last_seen": e["last_seen"],
            "hops": e["hops"],
            "announce_count": e["announce_count"],
            "call_capable": e["call_capable"],
            "last_message": e["messages"][-1] if e["messages"] else None,
            "unread_count": e["unread_count"],
        }
        for e in entries
    ]
    return json.dumps(summaries)


def get_messages_json(contact_hash: str) -> str:
    """[Message] shape for one conversation: id, content, ts (unix
    seconds), is_sent, state ("queued"/"delivered"/"failed", null for
    received). Note (see orchestration-design memory): a sent message's
    id can be rewritten by message_store.py after delivery (client UUID
    -> real LXMF hash) — don't rely on it as a stable diffing key across
    polls for outbound messages.

    `expires_at` (unix seconds, nullable): stamped once at send/receive
    time from the conversation's disappearing_seconds setting at that
    exact moment — null means "never expires" (every message stored
    before this feature existed too). The disappearing-messages sweep
    loop (see start_disappearing_sweep_loop) is what actually removes
    an expired message; this field is just the schedule.

    `attachment`, when present (null otherwise): {kind: "file"|"image",
    filename, mime, size, path}. `path` is an absolute on-device path —
    messaging.py already wrote the real file there (see its own
    `_save_attachment` doc comment for why binary content never lives
    inline in this JSON/messages.json itself); Kotlin reads it directly
    rather than routing bytes through Chaquopy a second time."""
    import json
    for e in _conversation_entries():
        if e["hash"] == contact_hash:
            return json.dumps(e["messages"])
    return "[]"


def get_contact_json(contact_hash: str) -> str:
    """Empty string (not null — Chaquopy/Kotlin string-nullability across
    the bridge is simpler to just avoid) if [contact_hash] isn't even
    valid hex — the one real "doesn't exist" case.

    A syntactically valid hash with no contact/message/announce history
    at all still returns a synthesized minimal entry (name defaults to
    the truncated hash, no icon, not favorited) rather than "" — this is
    what makes the Messages screen's manual "message an address" entry
    point work for an address never seen before: ConversationScreen just
    needs *a* [Contact] to render, sending itself already tolerates an
    unreachable/never-announced destination the normal way (queued,
    fails once path discovery times out — see _send()'s own PATH_WAIT
    handling), so there's no reason to gate opening the screen on prior
    history existing."""
    import json
    for e in _conversation_entries():
        if e["hash"] == contact_hash:
            return json.dumps({
                "hash": e["hash"], "name": e["name"],
                "icon": e["icon"], "icon_mime": e["icon_mime"],
                "icon_glyph": e["icon_glyph"], "icon_fg": e["icon_fg"], "icon_bg": e["icon_bg"],
                "favorited": e["favorited"],
                "blocked": e["blocked"],
                "disappearing_seconds": e["disappearing_seconds"],
                "call_capable": e["call_capable"],
            })
    try:
        bytes.fromhex(contact_hash)
    except (ValueError, TypeError):
        return ""
    if not contact_hash:
        return ""
    return json.dumps({
        "hash": contact_hash, "name": contact_hash[:16],
        "icon": None, "icon_mime": None,
        "icon_glyph": None, "icon_fg": None, "icon_bg": None,
        "favorited": False,
        "blocked": False,
        "disappearing_seconds": 0,
        "call_capable": False,
    })


def set_contact_favorite(hash_hex: str, value: bool) -> bool:
    """Unlike browser.py's set_favorite (node dicts always exist once
    discovered), a hash reaching this call may have no ContactStore entry
    yet at all — a message-history-only or announce-only contact never
    triggers ContactStore.upsert() on its own (see the
    orchestration-design memory's "contact/message existence is
    asymmetric" finding). upsert() first (no-op if it already exists)
    so set_favorite() always has a real entry to flip, rather than
    silently no-op'ing on a contact who genuinely exists from Kotlin's
    point of view (browsing/messaging) but not yet from ContactStore's.

    Passing the real LXMF-peer-tracker name into that upsert() matters,
    not cosmetic: upsert() with no name falls back to the hash prefix
    (contact_store.py's own `name or hash_hex[:16]`), and once that's
    baked into the entry, _conversation_entries()'s `contact["name"] or
    peer["name"] or hash[:16]` priority chain permanently prefers that
    placeholder over the real announced name forever after — a non-empty
    contact["name"] short-circuits the `or` before it ever reaches
    peer["name"]. Confirmed as a real reported bug: newly favorited
    contacts showed their hash instead of their set display name."""
    if _contact_store is None:
        return False
    store = _contact_store.for_user("")
    if store.get(hash_hex) is None:
        best_name = ""
        if _lxmf_tracker is not None:
            peer = next((p for p in _lxmf_tracker.get_peers() if p["hash"] == hash_hex), None)
            if peer:
                best_name = peer.get("name") or ""
        store.upsert(hash_hex, name=best_name)
    return store.set_favorite(hash_hex, value)


def set_contact_blocked(hash_hex: str, value: bool) -> bool:
    """Exact upsert-then-set shape as set_contact_favorite above,
    including the same live-peer-name preservation (see that function's
    own doc comment for the real bug this avoids). Actual enforcement
    (dropping inbound messages from a blocked sender) happens in
    messaging.py's `_on_delivery`/`_is_blocked` — this function only ever
    flips the stored flag those checks read."""
    if _contact_store is None:
        return False
    store = _contact_store.for_user("")
    if store.get(hash_hex) is None:
        best_name = ""
        if _lxmf_tracker is not None:
            peer = next((p for p in _lxmf_tracker.get_peers() if p["hash"] == hash_hex), None)
            if peer:
                best_name = peer.get("name") or ""
        store.upsert(hash_hex, name=best_name)
    return store.set_blocked(hash_hex, value)


def import_scanned_contact(dest_hash_hex: str, public_key_hex: str) -> None:
    """Registers a scanned QR contact's real identity (hash + public
    key) immediately — see messaging.py's own `import_scanned_contact`
    doc comment for the full "why this beats waiting for an announce"
    rationale. Also favorites the contact, same convention as
    `set_contact_favorite`'s own callers use for manually-entered
    addresses (an address someone deliberately scanned is at least as
    intentional as one typed by hand) — upsert-then-favorite, with the
    same live-peer-name preservation those callers already document, in
    case a real announce from this exact peer arrived first.

    Raises RuntimeError with a UI-displayable reason on failure (bad
    hex, wrong key length, no messaging service yet) — same "raise on
    failure" contract as send_message/trigger_propagation_sync."""
    if _messaging is None:
        raise RuntimeError("Messaging isn't ready yet — try again shortly")
    ok, message = _messaging.import_scanned_contact(dest_hash_hex, public_key_hex)
    if not ok:
        raise RuntimeError(message)
    set_contact_favorite(dest_hash_hex, True)


def set_messages_contacts_only(enabled: bool) -> None:
    """Global "Messages from contacts only" allowlist toggle — per the
    Columba-parity-audit's own real finding (`PrivacyCard.kt`, confirmed
    during a fresh audit pass). Real enforcement lives in messaging.py's
    `_on_delivery`/`_allows_sender` — see that function's own doc
    comment; this is just the bridge setter.

    In-memory on the Python side (mirrors `set_auto_announce_master`'s
    own shape) — the real persisted copy lives in Kotlin's DataStore
    (SettingsRepository), replayed into this at app startup via the
    exact same `NomadPortalApp.kt` boot-sequence pattern the TCP/
    Bluetooth/Wi-Fi/node-hosting toggles already use. Unlike auto-
    announce-master (which is allowed to silently reset to its documented
    default on every restart), a *privacy*-protective toggle silently
    resetting to permissive would be a real footgun — that's specifically
    why this one gets a real boot-time replay rather than being left
    ephemeral like that one."""
    if _messaging is not None:
        _messaging.set_contacts_only_messages(enabled)


def set_retry_via_relay(enabled: bool) -> None:
    """"Retry via relay on failure" — per the Columba-parity-audit's own
    real finding (`MessageDeliveryRetrievalCard.kt`, confirmed during a
    fresh audit pass) — the send-side complement to this app's own
    propagation-node *pull* sync (see [[nomadportal-android-columba-parity-audit]]/
    `lxmf_sync.py`'s own module doc comment). Real enforcement lives in
    messaging.py's `_should_retry_via_relay`/`_attempt_relay_retry` — see
    that module's own doc comments; this is just the bridge setter.

    In-memory only (mirrors `set_auto_announce_master`'s own shape),
    deliberately **not** given the real DataStore persistence
    `set_messages_contacts_only` gets — this is a delivery-reliability
    preference, not a privacy-protective one, so resetting to off on
    restart is an acceptable minor inconvenience, not a footgun."""
    if _messaging is not None:
        _messaging.set_retry_via_relay(enabled)


def _interface_key_for(iface) -> str:
    """Maps a real, live RNS `Interface` *instance* to one of this app's
    own 4 interface-key constants (`AnnounceStatus.INTERFACE_TCP`/
    `_BLUETOOTH`/`_RNODE`/`_WIFI_DISCOVERY` on the Kotlin side) by its
    real class name — confirmed directly against the actual installed
    RNS/RNS_BLE_Wrapper source, not guessed:
    `TCPClientInterface`/`TCPServerInterface` -> tcp, `RnsBleInterface`
    -> bluetooth_mesh, `RNodeInterface` -> rnode, `AutoInterface` ->
    wifi_discovery. Returns None for anything this app doesn't
    recognize (a future/unexpected interface type) rather than
    guessing — see `get_announce_interfaces_json()`'s own doc comment
    for what a missing key means to the caller."""
    name = type(iface).__name__
    if name in ("TCPClientInterface", "TCPServerInterface"):
        return "tcp"
    if name == "RnsBleInterface":
        return "bluetooth_mesh"
    if name == "RNodeInterface":
        return "rnode"
    if name == "AutoInterface":
        return "wifi_discovery"
    return None


def get_announce_interfaces_json() -> str:
    """Live "which RNS interface currently has the best path" lookup,
    for every currently-known LXMF peer + NomadNet node hash — the real
    backing for the Network tab's own "filter announces by network"
    dimension (per explicit direction, one of this app's original
    Columba-parity-audit requests: "filter all the announces by
    network, type, and search, and sort").

    This corrects an earlier, real documentation mistake in this same
    codebase (NetworkScreen.kt's own former doc comment, and
    [[nomadportal-android-columba-parity-audit]]'s memory record):
    "network/interface filtering isn't buildable without new tracking
    plumbing" was wrong. `RNS.Transport.next_hop_interface(destination_hash)`
    is a real, already-existing, public RNS API (confirmed directly
    against the installed RNS source — `Transport.path_table`'s own
    `IDX_PT_RVCD_IF` entry, which RNS already populates and maintains
    for its own routing decisions) — no new tracking needed at all, just
    a live read of state RNS was already keeping.

    Returns `{hash_hex: interface_key}` — a hash with no entry means RNS
    currently has no known path to it at all (a stale/unreachable
    announce), not an error, and is never fabricated.

    **Honest limitation, not glossed over**: this is a LIVE snapshot
    each call, not a history. It reflects whichever interface currently
    has the best known path right now — the same one RNS's own routing
    would use. If a destination's active path later moves to a
    different interface (or is lost), this snapshot changes/empties on
    the next poll too; it does not remember "also seen via interface Y
    once," the way a real interface-sighting-history table would (a
    genuinely bigger feature — confirmed real in Columba's own actual
    schema, `AnnounceInterfaceSightingEntity`, during a fresh audit pass
    — not attempted here)."""
    import json
    import RNS

    hashes = set()
    if _lxmf_tracker is not None:
        for p in _lxmf_tracker.get_peers():
            hashes.add(p["hash"])
    if _browser is not None:
        for n in _browser.get_nodes(user_sub=""):
            hashes.add(n["hash"])

    result = {}
    for h in hashes:
        try:
            dest_hash = bytes.fromhex(h)
        except ValueError:
            continue
        try:
            iface = RNS.Transport.next_hop_interface(dest_hash)
        except Exception:
            iface = None
        if iface is None:
            continue
        key = _interface_key_for(iface)
        if key is not None:
            result[h] = key

    return json.dumps(result)


# ---------------------------------------------------------------------------
# rnsh (remote shell over Reticulum) bridge — backs RnshRepository.kt's
# real implementation. Client (initiator) only, deliberately — see
# nomadnet_web.rnsh_client's own top doc comment and the
# nomadportal-android-rnsh-decision memory for the full reasoning. This
# app never runs an rnsh *listener* (never accepts incoming shell
# sessions), only ever connects OUT to one someone else already runs.
# ---------------------------------------------------------------------------

def rnsh_connect(destination_hash_hex: str) -> str:
    """Starts a new rnsh client session to [destination_hash_hex],
    tearing down any prior session first (single-session-at-a-time,
    same model as call_manager.py's own CallManager — this app never
    holds two remote shells open at once). Returns immediately
    (`{"success": true}`) once the session has been *started*, not once
    it's actually connected — real connection progress is read via
    rnsh_status_json(), polled from Kotlin, same "no push mechanism"
    convention every other real-time-ish status in this app already
    follows.

    `{"success": false, "message": ...}` only for preconditions this
    function itself can check synchronously (no messaging identity
    ready yet) — a bad destination hash or unreachable listener still
    starts a session that fails asynchronously, surfaced the normal way
    through rnsh_status_json()'s own "failed" state, not here."""
    global _rnsh_session
    import json
    if _messaging is None:
        return json.dumps({"success": False, "message": "Not ready yet — try again shortly"})

    identity = None
    try:
        for user_sub, data in _messaging.active_routers():
            if user_sub == "":
                identity = data.get("identity")
                break
    except Exception:
        identity = None
    if identity is None:
        return json.dumps({"success": False, "message": "No delivery identity registered yet"})

    if _rnsh_session is not None:
        try:
            _rnsh_session.disconnect()
        except Exception:
            pass

    from nomadnet_web.rnsh_client import RnshSession
    _rnsh_session = RnshSession(identity=identity, destination_hash_hex=destination_hash_hex)
    _rnsh_session.start()
    return json.dumps({"success": True, "message": "Connecting…"})


def rnsh_status_json() -> str:
    """{"state": "idle"|"connecting"|"connected"|"closed"|"failed",
    "error": nullable str, "exit_code": nullable int} — "idle" means no
    session has ever been started (or the app just launched); every
    other state comes straight from the active RnshSession's own real
    state machine, see that class's own doc comment."""
    import json
    if _rnsh_session is None:
        return json.dumps({"state": "idle", "error": None, "exit_code": None})
    return json.dumps(_rnsh_session.status())


def rnsh_read_output_json() -> str:
    """{"data_b64": "..."} — base64, not raw bytes, matching this
    codebase's own "always JSON string across the Chaquopy bridge"
    convention (see the nomadportal-android-conventions skill) rather
    than relying on Chaquopy's raw-bytes-return marshalling, which this
    app has real prior history of getting wrong (a real Chaquopy
    ByteArray-to-bytes bug found during the voice-call work). Empty
    string when there's no active session or nothing new to report —
    never an error, just "nothing to show yet"."""
    import base64
    import json
    if _rnsh_session is None:
        return json.dumps({"data_b64": ""})
    data = _rnsh_session.read_output()
    return json.dumps({"data_b64": base64.b64encode(data).decode("ascii") if data else ""})


def rnsh_send_input(data: bytes) -> None:
    """[data] is a Kotlin `ByteArray` — Chaquopy bridges that to a
    Python `bytes` object automatically on the way *in* (the direction
    this app already relies on elsewhere, e.g. send_message's own
    attachment_data param — only the *return* direction needed the
    base64 workaround above)."""
    if _rnsh_session is not None:
        _rnsh_session.send_input(bytes(data))


def rnsh_resize(rows: int, cols: int) -> None:
    if _rnsh_session is not None:
        _rnsh_session.resize(rows, cols)


def rnsh_disconnect() -> None:
    if _rnsh_session is not None:
        _rnsh_session.disconnect()


def mark_conversation_unread(contact_hash: str) -> None:
    """The inverse of mark_conversation_read, but deliberately not its
    mirror image: marking every message in a conversation unread again
    would inflate unread_count in a way no real "mark as unread" action
    means (the intent is "remind me to look at this again," a one-shot
    visual nudge, not "actually re-hide the whole history"). Matches
    Gmail/most real messaging apps' own semantics: only the single most
    recently received message gets flipped back to unread. A no-op if
    this contact has no received messages at all (an announce-only
    contact, or a conversation of only messages *we* sent)."""
    if _messaging is None:
        return
    my_received = [m for m in _messaging.received_messages() if m["source"] == contact_hash]
    if not my_received:
        return
    most_recent = max(my_received, key=lambda m: m["received_at"])
    _messaging.mark_unread(most_recent["id"])


def get_propagation_sync_status_json() -> str:
    """[known_nodes]/[fresh_nodes]: how many `lxmf.propagation`-aspect
    announces this device has heard, total vs. within
    lxmf_sync.py's own freshness window (6h) — real network-discovery
    counts, not fabricated.

    [picked_node_hex]: the node currently selected for sync, or null if
    none discovered yet.

    [transfer_state]: one of lxmf_sync.py's own `_TRANSFER_STATE_LABELS`
    values (idle/requesting_path/connecting/connected/request_sent/
    receiving/response_received/complete/no_path/link_failed/
    transfer_failed/no_identity_received/no_access/failed/unknown) —
    read live off the active LXMRouter instance, so this reflects an
    in-progress sync's real state, not just the last completed one.

    [last_synced_at]/[consecutive_failures]/[last_error]: this device's
    own sync history against whichever node is/was picked — updated by
    both the periodic background loop (every 5 min, see lxmf_sync.py's
    own module doc comment for why) and any manual
    [trigger_propagation_sync] call, since both share the same
    PropagationSyncService state."""
    import json
    if _prop_sync is None:
        return json.dumps({
            "known_nodes": 0, "fresh_nodes": 0, "picked_node_hex": None,
            "last_synced_at": None, "consecutive_failures": 0, "last_error": None,
            "transfer_state": "idle", "transfer_progress": 0.0, "transfer_last_result": None,
        })
    return json.dumps(_prop_sync.sync_status(user_sub=""))


def trigger_propagation_sync() -> str:
    """Manual "Sync now" action — the real backing for a UI-level button
    (Columba's own Chats screen has the same affordance, confirmed
    during the Columba parity audit). Returns a short, UI-displayable
    success message; raises RuntimeError with a UI-displayable failure
    reason otherwise (same "raise on failure" contract as send_message,
    for the same reason — MessagingRepository's `suspend fun` callers
    already know how to surface a Chaquopy exception as an error).

    This only confirms the sync *request* was initiated successfully —
    request_messages_from_propagation_node() is itself asynchronous,
    so the actual mailbox round trip's real-time progress is what
    get_propagation_sync_status_json()'s transfer_state/transfer_progress
    are for; poll that after calling this to show live progress."""
    if _prop_sync is None:
        raise RuntimeError("Propagation sync isn't running yet — try again shortly")
    ok, message = _prop_sync.sync_now(user_sub="")
    if not ok:
        raise RuntimeError(message)
    return message


def set_disappearing_timer(hash_hex: str, seconds: int) -> bool:
    """Per-conversation disappearing-messages duration (0 = off) — exact
    upsert-then-set shape as set_contact_favorite above, including the
    same live-peer-name preservation (see that function's own doc
    comment for the real bug this avoids: a bare upsert() with no name
    permanently blocks the real announced name from ever showing).
    Purely forward-looking: messaging.py stamps each message's own
    expires_at once at send/receive time from whatever this is set to
    right then — changing it here never retroactively re-times
    messages already stored."""
    if _contact_store is None:
        return False
    store = _contact_store.for_user("")
    if store.get(hash_hex) is None:
        best_name = ""
        if _lxmf_tracker is not None:
            peer = next((p for p in _lxmf_tracker.get_peers() if p["hash"] == hash_hex), None)
            if peer:
                best_name = peer.get("name") or ""
        store.upsert(hash_hex, name=best_name)
    return store.set_disappearing_timer(hash_hex, seconds)


def set_contact_name(hash_hex: str, name: str) -> bool:
    """Explicitly, permanently rename a contact — see
    _conversation_entries()'s own doc comment for how this interacts
    with the live LXMF-peer-announced name once set. False if name is
    blank."""
    if _contact_store is None:
        return False
    return _contact_store.for_user("").set_custom_name(hash_hex, name)


def delete_conversation(hash_hex: str) -> bool:
    """Deletes a chat: all sent/received message history with this
    counterparty, plus the ContactStore entry itself (name/icon/
    favorite/custom_name all go with it). If this contact is still
    actively announcing on the mesh, they'll still show up again under
    Users/Announces-heard (a live LXMF peer announce is a separate data
    source _conversation_entries() unions in — see that function's own
    doc comment) — this only clears *this device's own* saved history/
    metadata about them, not "block" or "forget they exist on the
    network," which isn't a real operation LXMF supports anyway."""
    if _messaging is not None:
        _messaging.delete_conversation(hash_hex, user_sub="")
    if _contact_store is not None:
        _contact_store.for_user("").delete(hash_hex)
    return True


# ---------------------------------------------------------------------------
# LXMF identity auto-announce — configurable, but announcing at least once
# is an actual LXMF/RNS protocol requirement, not a cosmetic feature: path
# discovery is fundamentally announce-based, so a delivery identity that
# has never announced is unreachable by any other peer, full stop (see
# _init_user_router's bootstrap-announce comment). What's genuinely
# configurable here is only the *periodic re*-announce policy on top of
# that baseline.
#
# Deliberately staleness-checked lazily, right before each send, rather
# than on a fixed background timer — a device that never sends anything
# doesn't need to keep re-announcing on a clock (per explicit design
# direction: "it should only need to do an announce before a message if
# it hasnt done an announce in a time window"). And that time window
# itself isn't one global number: it depends on which of this device's
# *own* interfaces are currently up when the send happens, because they
# carry very different path-staleness risk — a Bluetooth mesh neighbor
# set changes far faster than a stable TCP/internet path. `_active_interfaces`
# (this module's own live interface-toggle state) is what makes that
# possible to compute here rather than in messaging.py, which has no
# visibility into interface state at all by design (see its own "clean
# UI-agnostic port" convention).
# ---------------------------------------------------------------------------

# Per-interface: two independent numbers, per explicit design direction
# (settled into a plain 2-column table, one row per interface —
# "protocol | message | auto" — after a few rounds of iteration) —
# "announce_max_seconds" is how stale the last announce is allowed to
# get before a *send* needs a fresh one first ("message" column); "auto
# _announce_interval_seconds" is how often this device proactively
# re-announces on its own initiative, independent of whether a message
# happens to be going out ("auto" column) — **0 means disabled** for
# that interface, no separate enabled flag. RNS's own announce() call
# always broadcasts to every currently-active interface at once —
# there's no public API to target one specific interface (this was
# explicitly confirmed rather than assumed: no such method found, and it
# can't be verified further without RNS itself being importable in this
# dev environment, only in the Android build's Chaquopy cache). So
# per-interface config here drives *timing* decisions (which interfaces
# being active determines which thresholds apply), never *which
# interface carries the actual announce packet* — that's always
# "however many are active, all of them," by RNS's own design.
_interface_announce_config: dict = {
    # auto_announce_interval_seconds defaults to 0 (off) on every
    # interface, per explicit direction: a freshly created account
    # shouldn't start out proactively broadcasting its presence on a
    # timer before the user has actually decided they want that — same
    # "silent by default" posture node hosting's own auto_announce
    # already has (see nomadnet_web.site_server.SiteServer's docstring).
    # A brand-new identity still announces once regardless (this
    # section's own module doc comment explains why that one is a
    # protocol requirement, not a policy choice) — this only concerns
    # the *periodic re*-announce on top of that baseline.
    # announce_max_seconds (the "Message" column — how stale the last
    # announce may get before a send needs a fresh one first) is a
    # separate concept and unaffected by this.
    "tcp": {
        "announce_max_seconds": 3 * 60 * 60,
        "auto_announce_interval_seconds": 0,
    },
    "bluetooth_mesh": {
        "announce_max_seconds": 15 * 60,
        "auto_announce_interval_seconds": 0,
    },
    "rnode": {
        "announce_max_seconds": 3 * 60 * 60,
        "auto_announce_interval_seconds": 0,
    },
    "wifi_discovery": {
        "announce_max_seconds": 3 * 60 * 60,
        "auto_announce_interval_seconds": 0,
    },
}
# How often the background loop wakes up to check whether any active
# interface's auto_announce_interval_seconds has elapsed.
ANNOUNCE_LOOP_TICK = 30
_announce_loop_started = False

# Master auto-announce switch shown on Settings' Main tab, on top of
# each interface's own auto_announce_interval_seconds. Off zeroes every
# interface's interval (disabling all of them, same 0-means-disabled
# semantics as everywhere else in this section) while remembering each
# one's prior nonzero value in _auto_announce_last_intervals, so turning
# it back on restores exactly what was configured before rather than
# resetting to defaults. Defaults False, matching every per-interface
# interval above defaulting to 0 — the UI's own toggle should honestly
# read "off" from a fresh install, not show "on" while every interface
# underneath it is individually at 0 auto-announce anyway.
_auto_announce_master_enabled = False
_auto_announce_last_intervals: dict = {}


def _active_announce_configs() -> list:
    """Configs for whichever known interfaces (bluetooth_mesh/rnode/tcp)
    are currently active — empty if none of the active interfaces are
    ones this section tracks (e.g. only wifi_discovery is on)."""
    return [
        _interface_announce_config[key]
        for key in _active_interfaces
        if key in _interface_announce_config
    ]


def _seconds_since_last_announce() -> Optional[float]:
    if _messaging is None:
        return None
    last = _messaging.get_announce_status(user_sub="").get("last_announce_at")
    return None if last is None else time.time() - last


def _check_send_allowed() -> tuple:
    """(allowed, block_reason). block_reason is None when allowed.

    Blocking (not just skipping a background announce) is deliberate,
    per explicit design direction: if every currently-active interface
    has auto-announce set to 0 (disabled) and the last announce is older
    than the strictest active threshold, this device can't autonomously
    fix that (0 means exactly that: don't announce without being asked
    to) — so the send itself has to stop and say why, rather than
    silently going out over a possibly-stale/no-path identity. If
    auto-announce IS enabled (> 0) on at least one active interface,
    this announces first (broadcast, per this section's own doc comment)
    and then allows the send through.
    """
    if _messaging is None:
        return True, None  # let send_message's own None-check handle this

    configs = _active_announce_configs()
    if not configs:
        return True, None  # nothing this section tracks is active — no policy to enforce

    max_threshold = min(c["announce_max_seconds"] for c in configs)
    since = _seconds_since_last_announce()
    stale = since is None or since >= max_threshold

    if not stale:
        return True, None

    any_auto_enabled = any(c["auto_announce_interval_seconds"] > 0 for c in configs)
    if any_auto_enabled:
        _messaging.do_announce(user_sub="")
        return True, None

    return False, (
        "Your identity hasn't announced recently enough to reliably reach "
        "this contact, and auto-announce is set to 0 (disabled) for every "
        "currently active connection. Tap Announce now in Settings, or "
        "set an auto-announce interval, then try again."
    )


def _announce_loop() -> None:
    while True:
        time.sleep(ANNOUNCE_LOOP_TICK)
        if _messaging is None:
            continue
        configs = _active_announce_configs()
        due = [c for c in configs if c["auto_announce_interval_seconds"] > 0]
        if not due:
            continue
        since = _seconds_since_last_announce()
        if since is None or since >= min(c["auto_announce_interval_seconds"] for c in due):
            _messaging.do_announce(user_sub="")


def start_announce_loop() -> None:
    """Idempotent — a second call is a no-op. Same daemon-thread shape as
    lxmf_sync.py's PropagationSyncService.start()."""
    global _announce_loop_started
    with _lock:
        if _announce_loop_started:
            return
        _announce_loop_started = True
    threading.Thread(target=_announce_loop, daemon=True, name="lxmf-auto-announce").start()


# Disappearing messages — a periodic local sweep, not tied to any RNS
# event, so a plain sleep-loop (not an RNS callback) is the right shape
# here, same as _announce_loop above. 30s tick: frequent enough that
# even the shortest preset (5 minutes) disappears close to on-schedule,
# without waking up needlessly often for something that's off by
# default in every conversation.
DISAPPEARING_SWEEP_TICK = 30

_disappearing_sweep_started = False


def _disappearing_sweep_loop() -> None:
    while True:
        time.sleep(DISAPPEARING_SWEEP_TICK)
        if _messaging is None:
            continue
        try:
            _messaging.purge_expired_messages()
        except Exception:
            log.exception("Disappearing-messages sweep failed")


def start_disappearing_sweep_loop() -> None:
    """Idempotent — a second call is a no-op. Same daemon-thread shape as
    start_announce_loop above."""
    global _disappearing_sweep_started
    with _lock:
        if _disappearing_sweep_started:
            return
        _disappearing_sweep_started = True
    threading.Thread(
        target=_disappearing_sweep_loop, daemon=True, name="disappearing-messages-sweep",
    ).start()


def get_announce_status_json() -> str:
    """[AnnounceStatus] shape: interfaces (tcp/bluetooth_mesh/rnode/
    wifi_discovery -> {announce_max_seconds,
    auto_announce_interval_seconds} — always all four keys regardless of
    which are currently active; auto_announce_interval_seconds == 0
    means disabled for that interface, there's no separate enabled
    flag), last_announce_at (unix seconds, nullable), lxmf_address
    (nullable — null before the delivery router exists, e.g. RNS still
    starting up), public_key (nullable, hex — this identity's real RNS
    public key; see messaging.py's own get_announce_status doc comment.
    Powers real QR-code identity sharing: encoding this alongside
    lxmf_address, not just the address alone, is what lets a scanned
    contact be immediately reachable without waiting for a mesh
    announce — see import_scanned_contact()'s own doc comment),
    contacts_only_messages (bool — the live, enforced allowlist-mode
    state; see set_messages_contacts_only()'s own doc comment),
    retry_via_relay (bool — the live, enforced retry-on-failure state;
    see set_retry_via_relay()'s own doc comment),
    identity_hash (nullable — the raw RNS Identity hash,
    a genuinely different value from lxmf_address: that's the "lxmf.
    delivery" *destination* hash derived from this identity, not the
    identity's own hash), hosted_node_hash (nullable — null unless
    node hosting is actually on, set via set_node_hosting_enabled(True)
    calling browser.py's set_hosted(); see that method's own doc
    comment), send_blocked + send_blocked_reason (a
    read-only preview of what _check_send_allowed() would currently
    decide — lets the UI show a warning before the user even tries to
    send, not just react to a failed send afterward)."""
    import json
    lxmf_address = None
    last_announce_at = None
    public_key = None
    contacts_only_messages = False
    retry_via_relay = False
    if _messaging is not None:
        status = _messaging.get_announce_status(user_sub="")
        lxmf_address = status.get("lxmf_address")
        last_announce_at = status.get("last_announce_at")
        public_key = status.get("public_key")
        contacts_only_messages = _messaging.get_contacts_only_messages()
        retry_via_relay = _messaging.get_retry_via_relay()
    display_name = None
    identity_hash = None
    icon_glyph = None
    icon_fg = None
    icon_bg = None
    if _identity_store is not None:
        entry = _identity_store.get_for_user("")
        if entry is not None:
            display_name = entry.get("name")
            identity_hash = entry.get("id")
        icon = _identity_store.get_icon_appearance_for_user("")
        if icon is not None:
            icon_glyph = icon.get("glyph")
            icon_fg = icon.get("fg")
            icon_bg = icon.get("bg")
    hosted_node_hash = None
    if _browser is not None and getattr(_browser, "_hosted_hash", ""):
        hosted_node_hash = _browser._hosted_hash

    # Preview only — never triggers an announce itself (unlike the real
    # _check_send_allowed() call send_message() makes), so polling this
    # for UI display can't have side effects.
    configs = _active_announce_configs()
    send_blocked = False
    send_blocked_reason = None
    if configs:
        max_threshold = min(c["announce_max_seconds"] for c in configs)
        since = _seconds_since_last_announce()
        stale = since is None or since >= max_threshold
        if stale and not any(c["auto_announce_interval_seconds"] > 0 for c in configs):
            send_blocked = True
            send_blocked_reason = (
                "Identity announce is stale and auto-announce is set to 0 "
                "(disabled) for the active connection — sends will be "
                "blocked until you announce manually or set an "
                "auto-announce interval."
            )

    return json.dumps({
        "interfaces": dict(_interface_announce_config),
        "auto_announce_master_enabled": _auto_announce_master_enabled,
        "last_announce_at": last_announce_at,
        "lxmf_address": lxmf_address,
        "public_key": public_key,
        "contacts_only_messages": contacts_only_messages,
        "retry_via_relay": retry_via_relay,
        "identity_hash": identity_hash,
        "hosted_node_hash": hosted_node_hash,
        "display_name": display_name,
        "icon_glyph": icon_glyph,
        "icon_fg": icon_fg,
        "icon_bg": icon_bg,
        "send_blocked": send_blocked,
        "send_blocked_reason": send_blocked_reason,
    })


def set_display_name(name: str) -> bool:
    """Renames this device's LXMF identity — see
    MessagingService.set_display_name's own doc comment for the
    persisted-vs-live-app_data split."""
    if _messaging is None:
        return False
    return _messaging.set_display_name(name, user_sub="")


def set_icon_appearance(glyph: str, fg_hex: str, bg_hex: str) -> bool:
    """Sets this device's own FIELD_ICON_APPEARANCE descriptor — attached
    to every future outbound LXMF message. See
    MessagingService.set_icon_appearance's own doc comment; glyph is an
    icon name looked up client-side against Kotlin's Material Icons
    Extended mapping (IconAppearance.kt), fg_hex/bg_hex are '#rrggbb'."""
    if _messaging is None:
        return False
    return _messaging.set_icon_appearance(glyph, fg_hex, bg_hex, user_sub="")


def set_auto_announce_master(enabled: bool) -> None:
    """The single aggregate toggle Settings' Main tab carries, on top of
    each interface's own auto_announce_interval_seconds (Settings'
    per-interface tabs). Off zeroes every interface's interval —
    disabling all of them via the same 0-means-disabled convention used
    everywhere else in this section — while remembering each one's
    prior nonzero value so turning it back on restores exactly what was
    configured before, not a reset to defaults."""
    global _auto_announce_master_enabled
    _auto_announce_master_enabled = bool(enabled)
    if enabled:
        for key, cfg in _interface_announce_config.items():
            if cfg["auto_announce_interval_seconds"] == 0 and key in _auto_announce_last_intervals:
                cfg["auto_announce_interval_seconds"] = _auto_announce_last_intervals[key]
    else:
        for key, cfg in _interface_announce_config.items():
            if cfg["auto_announce_interval_seconds"] > 0:
                _auto_announce_last_intervals[key] = cfg["auto_announce_interval_seconds"]
            cfg["auto_announce_interval_seconds"] = 0


def set_announce_max(interface_key: str, seconds: int) -> None:
    """`interface_key` must be one of _interface_announce_config's keys
    ("tcp"/"bluetooth_mesh"/"rnode"/"wifi_discovery") — silently ignored
    otherwise rather than raising, so a future Kotlin-side typo/
    version-skew can't crash this call. Clamped to [1 minute, 24 hours]
    — below a minute risks flooding, beyond 24h risks peers' paths aging
    out regardless. Unlike set_auto_announce_interval, 0 has no special
    meaning here — a send-blocking "message max" of 0 would mean every
    send always requires a fresh announce, which is legal but almost
    certainly not what typing 0 into this field means to a user, so it's
    clamped to the same [1min, 24h] floor as anything else."""
    if interface_key not in _interface_announce_config:
        log.warning("Ignoring announce_max for unknown interface '%s'", interface_key)
        return
    _interface_announce_config[interface_key]["announce_max_seconds"] = max(
        60, min(24 * 60 * 60, int(seconds))
    )


def set_auto_announce_interval(interface_key: str, seconds: int) -> None:
    """0 means disabled for this interface — no separate enabled flag
    (see this section's module-level doc comment). Any nonzero value is
    clamped to [1 minute, 24 hours], same reasoning as set_announce_max."""
    if interface_key not in _interface_announce_config:
        log.warning("Ignoring auto_announce_interval for unknown interface '%s'", interface_key)
        return
    clamped = 0 if seconds <= 0 else max(60, min(24 * 60 * 60, int(seconds)))
    _interface_announce_config[interface_key]["auto_announce_interval_seconds"] = clamped


def announce_now() -> str:
    """[AnnounceResult] shape: success, message. Manual trigger for the
    UI's "Announce now" control — goes through the same do_announce()
    _check_send_allowed() uses internally, so last_announce_at (and
    therefore staleness for both the send-block check and the auto-
    announce loop) updates consistently either way."""
    import json
    if _messaging is None:
        return json.dumps({"success": False, "message": "Messaging not initialized yet"})
    success, message = _messaging.do_announce(user_sub="")
    return json.dumps({"success": success, "message": message})


def send_message(
    dest_hash_hex: str,
    content: str,
    attachment_filename: str = None,
    attachment_data: bytes = None,
    attachment_kind: str = "file",
    image_format: str = None,
) -> None:
    """Raises RuntimeError on failure — e.g. no delivery identity
    registered, OR _check_send_allowed() blocked this send because the
    identity's announce is stale and auto-announce is disabled on every
    currently-active interface (see that function's own doc comment;
    matches what get_announce_status_json()'s send_blocked/
    send_blocked_reason already let the UI warn about proactively,
    before the user even tries). Matches MessagingRepository.sendMessage's
    `suspend fun` contract of surfacing failure via exception either way.

    _check_send_allowed() runs first, synchronously — when it decides an
    announce is due, that's a real (if usually fast) RNS announce call,
    not just a state check, so this can occasionally add real latency to
    a send. Accepted trade-off: correctness (the recipient actually
    having a path to reach us back, or a relay having a fresh path to
    reach *them*) matters more here than shaving this call's latency.

    [attachment_data] is a Java `byte[]` on the Kotlin side — Chaquopy
    bridges that to a Python `bytes` object automatically, no manual
    marshalling needed (same as every other Chaquopy call site in this
    module). None (the default) means "no attachment", matching
    MessagingService.send_message's own optional-attachment contract —
    see its doc comment for [attachment_kind]'s "file" vs "image"
    meaning and why an arbitrary audio file is sent as "file", not
    LXMF's dedicated (codec-specific) audio field."""
    if _messaging is None:
        raise RuntimeError("Messaging not initialized yet")
    allowed, block_reason = _check_send_allowed()
    if not allowed:
        raise RuntimeError(block_reason)
    ok, result = _messaging.send_message(
        dest_hash_hex, content, "", "",
        attachment_filename=attachment_filename,
        attachment_data=bytes(attachment_data) if attachment_data is not None else None,
        attachment_kind=attachment_kind,
        image_format=image_format,
    )
    if not ok:
        raise RuntimeError(result)


def mark_conversation_read(contact_hash: str) -> None:
    """message_store.py's mark_read() is per-message only — no "mark
    conversation" batch API exists below this, so this loops one call
    per currently-unread message in the conversation."""
    if _messaging is None:
        return
    for m in _messaging.received_messages():
        if m["source"] == contact_hash and not m.get("read", False):
            _messaging.mark_read(m["id"])


# ---------------------------------------------------------------------
# Voice calls (Phase 1a signalling + Phase 1b audio relay). See
# call_manager.py's own doc comment for the real, source-verified LXST
# wire protocol this implements, and the nomadportal-android-
# competitor-research memory for why the `lxst` package itself isn't
# used directly (a real pip-resolution spike showed it isn't installable
# in this Chaquopy build).
# ---------------------------------------------------------------------

def place_call_json(address_hex: str) -> str:
    """address_hex may be a destination hash (a contact's already-
    familiar LXMF address, typed/pasted by hand for someone who's never
    announced call-capability specifically — real on-device request:
    "we need the ability to manually enter a call address, if somebody
    hasnt annoucned it") or an identity hash (what the Phase 0 phone-
    icon tap already has on hand for a confirmed call-capable contact).
    See CallManager.resolve_identity()'s own doc comment for why both
    shapes just work without the caller needing to know which one it's
    passing.

    Blocking — does real path-lookup network I/O, same "don't call this
    from Android's main thread" rule as every other network-touching
    bridge function here."""
    import json
    if _call_manager is None:
        return json.dumps({"success": False, "message": "Call engine not ready yet"})
    success, message = _call_manager.place_call(address_hex)
    return json.dumps({"success": success, "message": message})


def answer_call_json() -> str:
    import json
    if _call_manager is None:
        return json.dumps({"success": False, "message": "Call engine not ready yet"})
    success, message = _call_manager.answer_call()
    return json.dumps({"success": success, "message": message})


def hang_up_call_json() -> str:
    import json
    if _call_manager is None:
        return json.dumps({"success": False, "message": "Call engine not ready yet"})
    success, message = _call_manager.hang_up()
    return json.dumps({"success": success, "message": message})


def dismiss_call_json() -> str:
    """Clears a terminal call state (ended/busy/rejected/failed) back to
    idle — a separate step from hang_up_call_json deliberately, so the
    UI can show "call ended"/"busy"/"rejected" for a moment rather than
    the state instantly disappearing the instant the call actually
    ends. Kotlin calls this once the user's dismissed that screen."""
    import json
    if _call_manager is not None:
        _call_manager.reset_after_end()
    return json.dumps({"success": True})


def announce_call_address_json() -> str:
    """Manual announce trigger for this device's own telephony
    Destination — the "manual announce toggle" half of what's still
    deliberately deferred (see _start_call_manager's own doc comment
    for the recurring-schedule half, not yet built)."""
    import json
    if _call_manager is None:
        return json.dumps({"success": False, "message": "Call engine not ready yet"})
    _call_manager.announce()
    return json.dumps({"success": True, "message": "Announced"})


def _resolve_call_remote_name(remote_identity_hash) -> str | None:
    """Shared by get_call_status_json (the live call) and
    get_call_history_json (past calls) — same fallback chain
    _conversation_entries() uses (live peer tracker →
    RNS.Identity.recall_app_data → stored contact name → hash prefix) so
    a call shows a real name whenever one's resolvable, not just a hash,
    matching this app's messaging screens.

    remote_identity_hash is an *identity* hash (CallManager's own
    domain — see call_manager.py's doc comment on why calls are
    identity-keyed, not destination-keyed). _recall_announced_name needs
    a *destination* hash instead, so it can only be tried once a
    matching LXMF peer record supplies one (peer["hash"] is that peer's
    own lxmf.delivery destination hash) — with no matching peer, there's
    no destination hash to look up at all, not just an empty result, so
    that fallback tier is skipped entirely rather than called with the
    wrong hash kind."""
    if not remote_identity_hash:
        return None
    peers = _lxmf_tracker.get_peers() if _lxmf_tracker else []
    peer = next((p for p in peers if p.get("identity_hash") == remote_identity_hash), None)
    if not peer:
        return None
    return peer.get("name") or _recall_announced_name(peer["hash"]) or None


def get_call_status_json() -> str:
    """Polled by Kotlin's CallRepository — status is one of
    CallManager.CallStatus's string values ("idle", "calling",
    "ringing_outgoing", "ringing_incoming", "connecting", "established",
    "ended", "busy", "rejected", "failed"). started_at/established_at
    are unix-seconds (nullable); Kotlin multiplies by 1000, same
    convention as every other timestamp field already crossing this
    bridge."""
    import json
    if _call_manager is None:
        return json.dumps({
            "status": "idle", "is_incoming": False, "remote_identity_hash": None,
            "remote_name": None, "started_at": None, "established_at": None,
            "ended_reason": None,
        })
    status = _call_manager.status_dict()
    status["remote_name"] = _resolve_call_remote_name(status.get("remote_identity_hash"))
    return json.dumps(status)


def get_call_history_json() -> str:
    """[CallHistoryEntry] shape: is_incoming, remote_identity_hash,
    remote_name (nullable, same resolution as get_call_status_json),
    started_at/established_at/ended_at (unix seconds, established_at
    nullable — never reached ESTABLISHED for a missed/rejected/busy
    call), status (a terminal CallStatus value: ended/busy/rejected/
    failed), reason. Most recent call first — see CallManager.history's
    own doc comment for why this is in-memory only (not yet persisted
    across an app restart) and capped at HISTORY_MAX entries."""
    import json
    if _call_manager is None:
        return json.dumps([])
    entries = []
    for entry in _call_manager.get_history():
        entry = dict(entry)
        entry["remote_name"] = _resolve_call_remote_name(entry.get("remote_identity_hash"))
        entries.append(entry)
    return json.dumps(entries)


# Phase 1b audio relay — CallAudioEngine (Kotlin) is the only caller of
# either of these. Deliberately NOT JSON-string-returning like every
# other bridge function above: these two run up to ~50x/sec for the
# duration of a call (one per ~20ms Opus frame), and passing raw bytes
# avoids base64-encoding + JSON-parsing overhead on a real-time path
# for no benefit (there's no other consumer of this data that needs it
# as text). Both frame arguments/return values are fully opaque here —
# see call_manager.py's own doc comment for the codec-header-byte
# convention Kotlin owns.
#
# A real gotcha found via an actual failed on-device call: Chaquopy
# does NOT convert an incoming Kotlin ByteArray to a native Python
# bytes automatically at this call boundary — it arrives here as a
# java.jarray('B') proxy object, which msgpack.packb() (inside
# CallManager.send_audio_frame) can't serialize as-is. See that
# method's own comment for the bytes(...) fix; kept here too since it's
# the actual reason this pair of functions isn't JSON-based like
# everything else, and matters if either signature ever changes.

def send_call_audio_frame(frame: bytes) -> bool:
    if _call_manager is None:
        return False
    return _call_manager.send_audio_frame(frame)


def pop_call_audio_frame(timeout_s: float = 0.5):
    """Returns bytes, or None if nothing arrived within timeout_s.
    Blocks the calling (Kotlin) thread for up to timeout_s — this is
    CallAudioEngine's actual playback pull mechanism, not a poll loop
    Kotlin has to pace itself."""
    if _call_manager is None:
        return None
    return _call_manager.pop_audio_frame(timeout_s)
