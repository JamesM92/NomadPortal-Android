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
_prop_sync = None
_active_interfaces: dict = {}  # toggle name -> RNS.Interface currently attached
_started = False
_base_dir: str = ""

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
    global _messaging, _lxmf_tracker, _prop_sync, _started, _base_dir

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
    _prop_sync = PropagationSyncService(rns=_browser._rns, messaging_service=_messaging)

    # Pure local file I/O, no RNS dependency — safe here rather than
    # gating on wait_ready(), same reasoning as ensure_for_user("")
    # above. Actually attaching the loaded connections' interfaces still
    # has to wait for RNS (see _sync_tcp_interfaces in the deferred
    # steps below).
    _load_tcp_connections()

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
        # Attaches whichever TCP connections were loaded from disk and
        # are (master-enabled AND individually-enabled) — this is what
        # actually makes a persisted "TCP: on" connection live again
        # after an app restart, same role wait_ready()'s own doc comment
        # describes for the old single-connection design.
        ("TCP connections sync", _sync_tcp_interfaces),
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
    global _tcp_connections, _tcp_master_enabled
    try:
        with open(_tcp_config_path(), "r") as f:
            data = json.load(f)
        _tcp_connections = {c["id"]: c for c in data.get("connections", [])}
        _tcp_master_enabled = bool(data.get("master_enabled", True))
    except (FileNotFoundError, ValueError, OSError, KeyError) as exc:
        log.info("No existing TCP connections config (%s) — starting empty", exc)
        _tcp_connections = {}


def _save_tcp_connections() -> None:
    import json
    try:
        os.makedirs(_base_dir, exist_ok=True)
        with open(_tcp_config_path(), "w") as f:
            json.dump(
                {"master_enabled": _tcp_master_enabled, "connections": list(_tcp_connections.values())},
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
    {id, name, host, port, enabled})."""
    import json
    return json.dumps({
        "master_enabled": _tcp_master_enabled,
        "connections": list(_tcp_connections.values()),
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
    (nullable), favorited, last_seen (unix seconds — Kotlin multiplies by
    1000). See browser.py's get_nodes() for the full dict shape; unused
    keys are just ignored on the Kotlin side rather than filtered here."""
    import json
    if _browser is None:
        return "[]"
    return json.dumps(_browser.get_nodes(user_sub=""))


def fetch_page_text(destination_hash_hex: str, path: str) -> str:
    """Raises RuntimeError with browser.py's own error string on failure
    (path not found, link closed, timeout, etc.) — matches
    BrowserRepository.fetchPage's documented "throws on failure"
    contract. Blocking on real network I/O, can legitimately take
    minutes (browser.py's PAGE_HARD_CAP=600s) — callers must run this on
    Dispatchers.IO with no artificial coroutine timeout."""
    if _browser is None:
        raise RuntimeError("Browser not initialized yet")
    content, error = _browser.fetch_page(destination_hash_hex, path)
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
    `favorited`/`last_seen`/`hops` are included for exactly that."""
    if _messaging is None:
        return []
    sent = _messaging.sent_messages()
    received = _messaging.received_messages()
    contacts = _contact_store.for_user("") if _contact_store else None
    contact_list = contacts.list_contacts() if contacts else []
    peers = _lxmf_tracker.get_peers() if _lxmf_tracker else []
    peers_by_hash = {p["hash"]: p for p in peers}

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
            {"id": m["id"], "content": m["content"], "ts": m["sent_at"], "is_sent": True, "state": m["state"]}
            for m in sent if m["dest"] == h
        ]
        my_received = [
            {"id": m["id"], "content": m["content"], "ts": m["received_at"], "is_sent": False, "state": None,
             "read": m.get("read", False)}
            for m in received if m["source"] == h
        ]
        all_msgs = sorted(my_sent + my_received, key=lambda m: m["ts"])
        name = (contact["name"] if contact else None) or (peer.get("name") if peer else None) or h[:16]
        entries.append({
            "hash": h,
            "name": name,
            "icon": contact.get("icon") if contact else None,
            "icon_mime": contact.get("icon_mime") if contact else None,
            "icon_glyph": contact.get("icon_glyph") if contact else None,
            "icon_fg": contact.get("icon_fg") if contact else None,
            "icon_bg": contact.get("icon_bg") if contact else None,
            "favorited": bool(contact.get("favorited")) if contact else False,
            "last_seen": peer.get("last_seen") if peer else None,
            "hops": peer.get("hops") if peer else None,
            # How many times this peer's LXMF delivery identity has
            # announced, total — lxmf_tracker.py already counts this per
            # peer (same convention as browser.py's node announce_count);
            # only exposed here for the Messages screen's "Announces"
            # sort option, wasn't needed by anything before that.
            "announce_count": peer.get("announce_count") if peer else None,
            "messages": all_msgs,
            "unread_count": sum(1 for m in my_received if not m["read"]),
        })
    return entries


def get_conversations_json() -> str:
    """[ConversationSummary] shape: hash, name, icon (base64, nullable),
    icon_mime (nullable), icon_glyph/icon_fg/icon_bg (FIELD_ICON_APPEARANCE
    descriptor, all-or-nothing nullable trio — mutually exclusive with
    icon/icon_mime, see contact_store.py's own doc comment), favorited,
    last_seen (unix seconds, nullable — last LXMF peer announce, not last
    message), hops (nullable), announce_count (nullable — total announces
    heard from this peer), last_message (last entry of messages, or
    None), unread_count. Full per-message list is included too (Kotlin
    ignores it here) purely because computing it separately per-
    conversation would mean re-deriving the same sent/received union
    twice — cheap either way, these are in-memory list reads capped at
    500+500 total (message_store.py's MAX_MESSAGES)."""
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
            "last_seen": e["last_seen"],
            "hops": e["hops"],
            "announce_count": e["announce_count"],
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
    polls for outbound messages."""
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
    "tcp": {
        "announce_max_seconds": 3 * 60 * 60,
        "auto_announce_interval_seconds": 6 * 60 * 60,
    },
    "bluetooth_mesh": {
        "announce_max_seconds": 15 * 60,
        "auto_announce_interval_seconds": 30 * 60,
    },
    "rnode": {
        "announce_max_seconds": 3 * 60 * 60,
        "auto_announce_interval_seconds": 6 * 60 * 60,
    },
    "wifi_discovery": {
        "announce_max_seconds": 3 * 60 * 60,
        "auto_announce_interval_seconds": 6 * 60 * 60,
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
# resetting to defaults.
_auto_announce_master_enabled = True
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


def get_announce_status_json() -> str:
    """[AnnounceStatus] shape: interfaces (tcp/bluetooth_mesh/rnode/
    wifi_discovery -> {announce_max_seconds,
    auto_announce_interval_seconds} — always all four keys regardless of
    which are currently active; auto_announce_interval_seconds == 0
    means disabled for that interface, there's no separate enabled
    flag), last_announce_at (unix seconds, nullable), lxmf_address
    (nullable — null before the delivery router exists, e.g. RNS still
    starting up), send_blocked + send_blocked_reason (a read-only
    preview of what _check_send_allowed() would currently decide — lets
    the UI show a warning before the user even tries to send, not just
    react to a failed send afterward)."""
    import json
    lxmf_address = None
    last_announce_at = None
    if _messaging is not None:
        status = _messaging.get_announce_status(user_sub="")
        lxmf_address = status.get("lxmf_address")
        last_announce_at = status.get("last_announce_at")
    display_name = None
    icon_glyph = None
    icon_fg = None
    icon_bg = None
    if _identity_store is not None:
        entry = _identity_store.get_for_user("")
        if entry is not None:
            display_name = entry.get("name")
        icon = _identity_store.get_icon_appearance_for_user("")
        if icon is not None:
            icon_glyph = icon.get("glyph")
            icon_fg = icon.get("fg")
            icon_bg = icon.get("bg")

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


def send_message(dest_hash_hex: str, content: str) -> None:
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
    reach *them*) matters more here than shaving this call's latency."""
    if _messaging is None:
        raise RuntimeError("Messaging not initialized yet")
    allowed, block_reason = _check_send_allowed()
    if not allowed:
        raise RuntimeError(block_reason)
    ok, result = _messaging.send_message(dest_hash_hex, content, "", "")
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
