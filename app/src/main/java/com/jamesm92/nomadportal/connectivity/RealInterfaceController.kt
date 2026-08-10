package com.jamesm92.nomadportal.connectivity

import android.content.Context
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
 * **TCP, Wi-Fi discovery, and Bluetooth mesh are wired to real RNS
 * behavior here.** Only RNode remains persisted-intent-only (same as
 * [NoopInterfaceController]) — it needs Android USB host APIs (device
 * enumeration, permission flow, a device picker) that don't exist yet,
 * and `orchestrator.set_rnode_enabled` needs a serial port string this
 * app has no way to obtain.
 *
 * Bluetooth mesh is real as of the `RNS_BLE_Wrapper` sibling repo's
 * integration (see [BluetoothMeshManager], and that repo's own README —
 * its mesh transport has real logic behind it but, per its own stated
 * status, has never been exercised against real Bluetooth radios; this
 * app's own on-device verification has been limited to a single device
 * (service starts, Python interface attaches without error) — real
 * multi-device neighbor discovery/relay is still unverified pending 2
 * physical devices in range of each other). RNode-over-BLE/Classic is a
 * distinct, separately-unimplemented role in that same repo — not
 * covered by this integration; [setRNodeEnabled] stays as it was.
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
    context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) : InterfaceController {

    private val orchestrator by lazy {
        Python.getInstance().getModule("nomadportal_core.orchestrator")
    }

    // Application context only (matches CallAudioEngine's own constructor
    // convention) — this class is a long-lived app-wide singleton
    // (NomadPortalApp), never itself Activity-scoped.
    private val bluetoothMesh = BluetoothMeshManager(context.applicationContext, scope)

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
        if (enabled) {
            bluetoothMesh.start()
        } else {
            bluetoothMesh.stop()
        }
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

    override fun hasDownTcpConnection(): Flow<Boolean> = flow {
        while (true) {
            emit(fetchHasDownTcpConnection())
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    private fun fetchHasDownTcpConnection(): Boolean {
        val obj = JSONObject(orchestrator.callAttr("get_tcp_connections_json").toString())
        if (!obj.optBoolean("master_enabled", true)) return false
        val array = obj.getJSONArray("connections")
        for (i in 0 until array.length()) {
            val c = array.getJSONObject(i)
            if (c.optBoolean("enabled", true) && !c.optBoolean("online", false)) return true
        }
        return false
    }

    private companion object {
        const val POLL_INTERVAL_MS = 4000L
    }
}
