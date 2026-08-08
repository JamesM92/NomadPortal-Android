package com.jamesm92.nomadportal.connectivity

import com.chaquo.python.Python
import com.jamesm92.nomadportal.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * First real [InterfaceController] — backed by `nomadportal_core.orchestrator`
 * (see that module's docstring for the full design: interfaces are
 * added/removed on an already-running `RNS.Transport`, `Reticulum()`
 * itself is only ever constructed once).
 *
 * **Only TCP and Wi-Fi discovery are actually wired to real RNS behavior
 * here.** RNode and Bluetooth mesh remain persisted-intent-only (same as
 * [NoopInterfaceController]) because each has its own real, separate
 * prerequisite that doesn't exist yet:
 * - RNode-over-USB needs Android USB host APIs (device enumeration,
 *   permission flow, a device picker) — none of that exists yet, and
 *   `orchestrator.set_rnode_enabled` needs a serial port string this app
 *   has no way to obtain.
 * - Bluetooth mesh needs the separate `RNS_BLE_Wrapper` repo's interface
 *   integrated — that repo isn't wired in yet (see
 *   nomadportal_android_handoff.md's "Relationship to other tracks").
 *
 * Node hosting (`SiteServer`) is real too (Aug 2026) — see
 * `nomadnet_web.site_server`'s own module doc comment for why it's
 * hardened to plain `.mu` markup only, no Python/executables, unlike
 * the original desktop tool it was ported from.
 *
 * The Wi-Fi discovery toggle carries a real, documented limitation from
 * the orchestrator: turning it off then back on within the same app
 * process will throw, because `AutoInterface`'s `detach()` is broken in
 * the pinned RNS version and never releases its UDP socket. This
 * implementation lets that exception surface rather than hiding it —
 * the Settings screen's toggle will visibly fail to re-enable, which is
 * honest given there is currently no real fix.
 */
class RealInterfaceController(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) : InterfaceController {

    private val orchestrator by lazy {
        Python.getInstance().getModule("nomadportal_core.orchestrator")
    }

    override val tcpEnabled: StateFlow<Boolean> =
        settings.tcpEnabled.stateIn(scope, SharingStarted.Eagerly, true)

    override val bluetoothMeshEnabled: StateFlow<Boolean> =
        settings.bluetoothMeshEnabled.stateIn(scope, SharingStarted.Eagerly, false)

    override val rNodeEnabled: StateFlow<Boolean> =
        settings.rNodeEnabled.stateIn(scope, SharingStarted.Eagerly, false)

    override val wifiDiscoveryEnabled: StateFlow<Boolean> =
        settings.wifiDiscoveryEnabled.stateIn(scope, SharingStarted.Eagerly, false)

    override val nodeHostingEnabled: StateFlow<Boolean> =
        settings.nodeHostingEnabled.stateIn(scope, SharingStarted.Eagerly, false)

    override suspend fun setTcpEnabled(enabled: Boolean) {
        // No more hardcoded single hub — TCP is now a user-configurable
        // *list* of named connections (TcpConnectionsRepository), each
        // independently enabled/disabled. This toggle is now the master
        // switch on top of that list (set_tcp_master_enabled): off
        // detaches every connection regardless of its own enabled flag,
        // without touching any connection's own persisted enabled state.
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("set_tcp_master_enabled", enabled)
        }
        settings.setTcpEnabled(enabled)
    }

    override suspend fun setBluetoothMeshEnabled(enabled: Boolean) {
        // TODO(RNS_BLE_Wrapper not integrated yet): persisted intent only.
        settings.setBluetoothMeshEnabled(enabled)
    }

    override suspend fun setRNodeEnabled(enabled: Boolean) {
        // TODO(no Android USB device picker yet): persisted intent only —
        // orchestrator.set_rnode_enabled needs a serial port this app has
        // no way to obtain yet.
        settings.setRNodeEnabled(enabled)
    }

    override suspend fun setWifiDiscoveryEnabled(enabled: Boolean) {
        // Deliberately not caught here — see class doc comment. A caller
        // (the Settings screen) needs to know this can fail, not have it
        // silently swallowed into "looks like it worked."
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("set_wifi_discovery_enabled", enabled)
        }
        settings.setWifiDiscoveryEnabled(enabled)
    }

    override suspend fun setNodeHostingEnabled(enabled: Boolean) {
        // Deliberately not caught here, same reasoning as
        // setWifiDiscoveryEnabled above — a caller needs to know a
        // failed start (e.g. RNS not ready yet) rather than have it
        // silently swallowed into "looks like it worked."
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("set_node_hosting_enabled", enabled)
        }
        settings.setNodeHostingEnabled(enabled)
    }

    override fun hostedNodeStatus(): Flow<HostedNodeStatus> = flow {
        while (true) {
            emit(fetchHostedNodeStatus())
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    private fun fetchHostedNodeStatus(): HostedNodeStatus {
        val obj = JSONObject(orchestrator.callAttr("get_site_status_json").toString())
        return HostedNodeStatus(
            enabled = obj.optBoolean("enabled", false),
            nodeHash = if (obj.isNull("node_hash")) null else obj.optString("node_hash"),
            nodeName = if (obj.isNull("node_name")) null else obj.optString("node_name"),
            announceIntervalSeconds = obj.optInt("announce_interval_seconds", 0),
            lastAnnounceAtMillis = if (obj.isNull("last_announce_at")) {
                null
            } else {
                (obj.optDouble("last_announce_at", 0.0) * 1000).toLong()
            },
        )
    }

    override suspend fun setHostedNodeName(name: String): Boolean = withContext(Dispatchers.IO) {
        orchestrator.callAttr("set_site_node_name", name).toBoolean()
    }

    override suspend fun setHostedNodeAnnounceInterval(seconds: Int): Boolean = withContext(Dispatchers.IO) {
        orchestrator.callAttr("set_site_announce_interval", seconds).toBoolean()
    }

    override suspend fun announceHostedNodeNow(): Boolean = withContext(Dispatchers.IO) {
        orchestrator.callAttr("announce_site_now").toBoolean()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 4000L
    }
}
