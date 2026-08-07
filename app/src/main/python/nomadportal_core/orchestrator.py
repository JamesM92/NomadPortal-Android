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
_prop_sync = None
_active_interfaces: dict = {}  # toggle name -> RNS.Interface currently attached
_started = False


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
    global _messaging, _lxmf_tracker, _prop_sync, _started

    with _lock:
        if _started:
            log.info("orchestrator.start() called again — already started, ignoring")
            return
        _started = True

    _setup_logging()

    rns_dir = os.path.join(base_dir, "reticulum")
    os.makedirs(rns_dir, exist_ok=True)

    from nomadnet_web.browser import NodeBrowser
    from nomadnet_web.identity_store import IdentityStore
    from nomadnet_web.message_store import MessageStore
    from nomadnet_web.contact_store import ContactStoreManager
    from nomadnet_web.messaging import MessagingService
    from nomadnet_web.lxmf_tracker import LXMFPeerTracker
    from nomadnet_web.lxmf_sync import PropagationSyncService

    # NodeBrowser.__init__ starts RNS.Reticulum() on its own background
    # thread and returns immediately — see this module's docstring and
    # browser.py's own comments for why (RNS.Reticulum() blocks 60-300s
    # on real deployments; running that on the calling thread would hang
    # whatever called start()).
    log.info("Starting NodeBrowser (RNS init runs on its own background thread)")
    _browser = NodeBrowser(config_dir=rns_dir)

    _identity_store = IdentityStore(rns_dir)
    _message_store = MessageStore(base_dir)
    _contact_store = ContactStoreManager(base_dir)
    _messaging = MessagingService(
        storage_path=os.path.join(rns_dir, "lxmf"),
        message_store=_message_store,
        contact_store=_contact_store,
    )
    _lxmf_tracker = LXMFPeerTracker(base_dir)
    _prop_sync = PropagationSyncService(rns=_browser._rns, messaging_service=_messaging)

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
        ("LXMF delivery setup", lambda: _messaging.setup_delivery(_identity_store)),
        ("LXMF propagation sync service", _prop_sync.start),
        ("LXMF tracker registration", _register_lxmf_tracker),
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

def set_tcp_enabled(enabled: bool, host: str, port: int) -> None:
    """TCPClientInterface has a real, correct detach() — safe to
    add/remove repeatedly."""
    _set_interface("tcp", enabled, lambda: _make_tcp_client(host, port))


def set_rnode_enabled(enabled: bool, serial_port: str) -> None:
    """RNodeInterface has a real, correct detach() — safe to add/remove
    repeatedly. `serial_port` is the USB serial device path; Bluetooth-
    connected RNode support is a separate future integration (see the
    RNS_BLE_Wrapper sibling repo), not this function."""
    _set_interface("rnode", enabled, lambda: _make_rnode(serial_port))


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
