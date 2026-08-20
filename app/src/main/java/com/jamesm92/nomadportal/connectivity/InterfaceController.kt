package com.jamesm92.nomadportal.connectivity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * This device's own hosted NomadNet node — a genuinely different
 * announce schedule from [com.jamesm92.nomadportal.data.messaging.AnnounceStatus]'s
 * `interfaces` map, which governs how often this device's *LXMF
 * identity* announces. The hosted node is a separate RNS destination
 * (`nomadnetwork.node`, not `lxmf.delivery`) with its own independent
 * announce loop — see `nomadnet_web.site_server.SiteServer`.
 */
data class HostedNodeStatus(
    val enabled: Boolean,
    /** Null unless [enabled]. */
    val nodeHash: String?,
    /** Null unless [enabled]. */
    val nodeName: String?,
    /** 0 means auto-announce disabled — no separate enabled flag, same
     * convention as [com.jamesm92.nomadportal.data.messaging.InterfaceAnnounceConfig]. */
    val announceIntervalSeconds: Int,
    /** Null if this node has never announced yet. */
    val lastAnnounceAtMillis: Long?,
) {
    val autoAnnounceEnabled: Boolean get() = announceIntervalSeconds > 0
}

/**
 * Live Bluetooth-mesh neighbor status — real data, not fabricated:
 * [BluetoothMeshManager] derives this from [com.jamesm92.rnsble.interop.PacketEvent.NeighborSeen]
 * events the RNS_BLE_Wrapper transport already emits (RSSI included) but
 * that nothing in this app surfaced anywhere before
 * [com.jamesm92.nomadportal.ui.network.NetworkScreen] existed. A
 * "neighbor" here means a link-layer peer ID sighted within
 * [BluetoothMeshManager]'s own rolling window (see that class's doc
 * comment) — not the same thing as an RNS-level LXMF contact/announce,
 * and not proof of an actual established GATT link, just "heard
 * recently." [NoopInterfaceController]/before Bluetooth mesh starts
 * always reports zero/null here rather than a fabricated count.
 */
data class BluetoothMeshStatus(
    val neighborCount: Int,
    /** Null if no neighbor has been sighted since Bluetooth mesh last started. */
    val lastActivityAtMillis: Long?,
)

/**
 * Real lifetime (across app restarts, not just this session) up/down byte
 * totals for one interface key — the backing for the Network tab's own
 * "lifetime up/down per protocol" stat display, per explicit direction.
 * Not fabricated: orchestrator.py's `get_interface_byte_stats_json` reuses
 * each RNS interface object's own real `rxb`/`txb` counters plus the
 * persisted lifetime base `NodeBrowser` already tracks in
 * `iface_stats.json` (that byte-accounting has existed for a long time —
 * this is the first time it's actually reached the UI). Zero for an
 * interface key with no history yet, never null — there's no meaningful
 * "unknown" state for a byte counter the way there is for e.g. a
 * not-yet-arrived announce timestamp.
 */
data class InterfaceByteStats(
    val rxBytes: Long,
    val txBytes: Long,
)

/**
 * The single authoritative control point for which RNS interfaces are
 * actually live, and whether this device hosts a NomadNet node.
 *
 * "Authoritative" per nomadportal_android_handoff.md's "Main menu /
 * connectivity & privacy controls" section: these calls must actually
 * tear down/bring up the corresponding RNS `Interface` — a Settings
 * toggle that only flips a persisted preference without this actually
 * happening would misrepresent what's live, which is exactly the "fail
 * closed on what you don't know" trust-model bug porting-notes.md §3
 * calls out from the original NomadPortal.
 *
 * [RealInterfaceController] is the current implementation, backed by
 * `nomadportal_core.orchestrator` (only TCP/Wi-Fi discovery are actually
 * wired to live RNS behavior yet — see that class's doc comment).
 * [NoopInterfaceController] (persisted-intent-only, no real RNS calls)
 * predates it and is kept as a minimal reference/test implementation.
 *
 * The connectivity toggles are independent and orthogonal by design — see
 * the handoff doc for why Bluetooth mesh and RNode-over-Bluetooth are NOT
 * the same toggle despite sharing a radio, and why local Wi-Fi discovery
 * is its own toggle rather than folded into TCP.
 */
interface InterfaceController {
    val tcpEnabled: StateFlow<Boolean>
    val bluetoothMeshEnabled: StateFlow<Boolean>
    val rNodeEnabled: StateFlow<Boolean>
    val wifiDiscoveryEnabled: StateFlow<Boolean>
    val nodeHostingEnabled: StateFlow<Boolean>

    /** Enables/disables all internet-based (TCP client/server) RNS interfaces. */
    suspend fun setTcpEnabled(enabled: Boolean)

    /**
     * Enables/disables the BLE-mesh RNS interface specifically. Does NOT
     * affect an RNode connected over Bluetooth — see [setRNodeEnabled].
     */
    suspend fun setBluetoothMeshEnabled(enabled: Boolean)

    /** Enables/disables the RNode interface, regardless of whether that RNode is USB- or Bluetooth-connected. */
    suspend fun setRNodeEnabled(enabled: Boolean)

    /**
     * Enables/disables local-network peer discovery (RNS's `AutoInterface`
     * — IPv6 link-local multicast on the current LAN/Wi-Fi segment, not a
     * connection to any specific configured address). Distinct from
     * [setTcpEnabled]: TCP is for reaching specific remote/internet hosts;
     * this is for auto-discovering other RNS nodes already on the same
     * local network. Uses multicast on an existing Wi-Fi connection, not
     * Wi-Fi scanning — doesn't touch `BLUETOOTH_SCAN`/location at all.
     */
    suspend fun setWifiDiscoveryEnabled(enabled: Boolean)

    /**
     * Enables/disables whether this device answers page/file requests over
     * whichever interfaces are currently up. Independent of the three
     * interface toggles above and independent of browsing capability —
     * browsing must keep working with this off.
     */
    suspend fun setNodeHostingEnabled(enabled: Boolean)

    /** Live hosted-node status/config — see [HostedNodeStatus]'s own doc
     * comment for why this is separate from [com.jamesm92.nomadportal.data.messaging.AnnounceStatus].
     * Deliberately duplicated across two real surfaces (Home screen +
     * Settings' Node tab), per explicit design direction. */
    fun hostedNodeStatus(): Flow<HostedNodeStatus>

    /** Renames the hosted node — takes effect on the next announce (this
     * app's own display-name-rename convention: persisted immediately,
     * not proactively pushed). Returns false if hosting is currently off. */
    suspend fun setHostedNodeName(name: String): Boolean

    /** 0 disables auto-announce for the hosted node — same convention as
     * [com.jamesm92.nomadportal.data.messaging.MessagingRepository.setAutoAnnounceInterval].
     * Returns false if hosting is currently off. */
    suspend fun setHostedNodeAnnounceInterval(seconds: Int): Boolean

    /** Manual "Announce now" for the hosted node. Returns true on success
     * (false if hosting is currently off). */
    suspend fun announceHostedNodeNow(): Boolean

    /**
     * True whenever TCP is enabled (the master switch) and at least one
     * individually-enabled [com.jamesm92.nomadportal.connectivity.TcpConnection]
     * isn't actually connected right now — surfaced as a visible flag
     * (Settings gear badge, per-row indicator in the TCP connections
     * table) rather than silently retried, per explicit direction: a
     * down server after initial setup should be *noticed*, not
     * automatically replaced with a new pick from the directory pool
     * (see [nomadportal_core.orchestrator]'s default-TCP-seeding doc
     * comment for why that's deliberately a one-time thing, not an
     * ongoing policy).
     *
     * False whenever TCP itself is off — an intentionally-off master
     * switch isn't a problem to flag, and every connection is correctly
     * "not connected" in that state regardless of whether the server on
     * the other end is actually up. Also false if there simply aren't
     * any enabled connections to be down in the first place (an empty
     * list, or every connection individually disabled).
     */
    fun hasDownTcpConnection(): Flow<Boolean>

    /** Live Bluetooth-mesh neighbor status — see [BluetoothMeshStatus]'s
     * own doc comment for exactly what "neighbor" means here and what
     * this does/doesn't prove. Always zero/null when Bluetooth mesh
     * hasn't started (or on [NoopInterfaceController]) — never
     * fabricated. */
    fun bluetoothMeshStatus(): Flow<BluetoothMeshStatus>

    /**
     * Live "which RNS interface currently has the best path" lookup for
     * every currently-known LXMF peer/NomadNet-node hash — the real
     * backing for the Network tab's own "filter announces by network"
     * dimension. Keyed by lowercase hex destination hash; values are one
     * of [com.jamesm92.nomadportal.data.messaging.AnnounceStatus.INTERFACE_TCP]/
     * `INTERFACE_BLUETOOTH`/`INTERFACE_RNODE`/`INTERFACE_WIFI_DISCOVERY`
     * (reusing that same 4-interface taxonomy throughout this app). A
     * hash absent from the map means RNS currently has no known path to
     * it at all (a stale/unreachable announce), not an error.
     *
     * A **live snapshot each poll, not a history** — reflects whichever
     * interface currently has the best known path right now, the same
     * one RNS's own routing would use. Doesn't remember "also seen via
     * a different interface once" the way a real interface-sighting-
     * history table would (confirmed real in Columba's own schema
     * during a fresh audit pass, not attempted here — a genuinely
     * bigger feature, not a corner cut by accident).
     */
    fun announceInterfaces(): Flow<Map<String, String>>

    /**
     * Real lifetime up/down byte totals, keyed by the same 4 interface
     * keys as [announceInterfaces]'s own values (`INTERFACE_TCP`/
     * `_BLUETOOTH`/`_RNODE`/`_WIFI_DISCOVERY`) — the Network tab's own
     * Interfaces section shows one of these per protocol, per explicit
     * direction. A key absent from the map means no interface of that
     * type has ever contributed bytes yet, equivalent to
     * [InterfaceByteStats] `(0, 0)` — callers should default a missing
     * key to that rather than treating it as an error.
     */
    fun interfaceByteStats(): Flow<Map<String, InterfaceByteStats>>
}
