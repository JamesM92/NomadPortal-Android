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
            "favorited": bool(contact.get("favorited")) if contact else False,
            "last_seen": peer.get("last_seen") if peer else None,
            "hops": peer.get("hops") if peer else None,
            "messages": all_msgs,
            "unread_count": sum(1 for m in my_received if not m["read"]),
        })
    return entries


def get_conversations_json() -> str:
    """[ConversationSummary] shape: hash, name, icon (base64, nullable),
    icon_mime (nullable), favorited, last_seen (unix seconds, nullable —
    last LXMF peer announce, not last message), hops (nullable),
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
            "favorited": e["favorited"],
            "last_seen": e["last_seen"],
            "hops": e["hops"],
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
    the bridge is simpler to just avoid) if no contact or message history
    exists for this hash at all."""
    import json
    for e in _conversation_entries():
        if e["hash"] == contact_hash:
            return json.dumps({
                "hash": e["hash"], "name": e["name"],
                "icon": e["icon"], "icon_mime": e["icon_mime"],
                "favorited": e["favorited"],
            })
    return ""


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


def send_message(dest_hash_hex: str, content: str) -> None:
    """Raises RuntimeError on failure (e.g. no delivery identity
    registered) — matches MessagingRepository.sendMessage's `suspend
    fun` contract of surfacing failure via exception. Fast/non-blocking
    on the Python side (messaging.py queues and spawns its own delivery
    thread), but still run via Dispatchers.IO for the Chaquopy/GIL
    crossing, consistent with every other bridge call here."""
    if _messaging is None:
        raise RuntimeError("Messaging not initialized yet")
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
