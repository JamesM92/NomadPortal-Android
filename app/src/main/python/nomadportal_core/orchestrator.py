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
- Connectivity toggles are implemented by adding/removing individual
  `RNS.Interface` objects on the *already-running* `Transport` —
  `RNS.Transport.add_interface()` / `remove_interface()` — which is
  exactly the mechanism `RNS.Reticulum.__init__` itself uses internally
  when loading interfaces from its own config file. `Reticulum()` itself
  is never touched after `start()`.
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
import threading

log = logging.getLogger(__name__)

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
            RNS.Transport.add_interface(iface)
            iface.final_init()
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
    config = {"name": "TCP Client", "target_host": host, "target_port": port}
    return TCPClientInterface(RNS.Transport, config)


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
