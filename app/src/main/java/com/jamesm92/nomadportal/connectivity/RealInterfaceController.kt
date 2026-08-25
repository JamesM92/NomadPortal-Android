package com.jamesm92.nomadportal.connectivity

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.jamesm92.nomadportal.connectivity.rnode.RnodeDeviceInfo
import com.jamesm92.nomadportal.connectivity.rnode.RnodeUsbManager
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
 * covered by this integration.
 *
 * RNode-over-USB is real too (see [RnodeUsbManager]) — [setRNodeEnabled]
 * only ever attempts a *reconnect* to whatever device/config was last
 * configured via the Settings device-picker screen; it never picks a
 * device on its own (there is no "the" device to pick without asking
 * the user, unlike TCP/Wi-Fi-discovery which have no device selection at
 * all). A `false` toggle-on result (no matching device attached, or
 * nothing configured yet) is deliberately not surfaced as an error —
 * that's the ordinary "toggled on, but nothing to connect to right now"
 * state, not a failure.
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

    // Same "application context only, long-lived singleton" convention
    // as bluetoothMesh above. RnodeUsbManager owns real device
    // discovery and the connect lifecycle; this controller's own
    // rnodeAvailableDevices()/rnodeConnectionState()/connectRnode()/
    // disconnectRnode() below (the InterfaceController-interface surface
    // a Settings device-picker screen actually calls) just delegate to
    // it, plus [setRNodeEnabled] owns the master on/off toggle's
    // reconnect-on-enable behavior.
    private val rnodeUsb = RnodeUsbManager(context.applicationContext, settings, scope)

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
        if (enabled) {
            try {
                rnodeUsb.reconnectPersisted()
            } catch (e: Exception) {
                // Best-effort, same "logged, nothing further to do here"
                // shape BluetoothMeshManager's own boot-time attach
                // failure already uses — the master toggle itself still
                // persists below regardless of whether a reconnect
                // actually succeeded right now.
                Log.w(TAG, "RNode reconnect failed: ${e.message}")
            }
        } else {
            rnodeUsb.disconnect()
        }
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
            totalViews = obj.optInt("total_views", 0),
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

    override fun bluetoothMeshStatus(): Flow<BluetoothMeshStatus> = bluetoothMesh.status

    override fun announceInterfaces(): Flow<Map<String, String>> = flow {
        while (true) {
            emit(fetchAnnounceInterfaces())
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    private fun fetchAnnounceInterfaces(): Map<String, String> {
        val obj = JSONObject(orchestrator.callAttr("get_announce_interfaces_json").toString())
        val result = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val hash = keys.next()
            result[hash] = obj.getString(hash)
        }
        return result
    }

    override fun interfaceByteStats(): Flow<Map<String, InterfaceByteStats>> = flow {
        while (true) {
            emit(fetchInterfaceByteStats())
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    private fun fetchInterfaceByteStats(): Map<String, InterfaceByteStats> {
        val obj = JSONObject(orchestrator.callAttr("get_interface_byte_stats_json").toString())
        val result = mutableMapOf<String, InterfaceByteStats>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = obj.getJSONObject(key)
            result[key] = InterfaceByteStats(
                rxBytes = entry.optLong("rxb", 0L),
                txBytes = entry.optLong("txb", 0L),
            )
        }
        return result
    }

    override fun rnodeAvailableDevices() = rnodeUsb.availableDevices

    override fun rnodeConnectionState() = rnodeUsb.connectionState

    override fun refreshRnodeDevices() = rnodeUsb.refreshDevices()

    override suspend fun connectRnode(device: RnodeDeviceInfo, configJson: String): Boolean =
        rnodeUsb.connect(device, configJson)

    override suspend fun disconnectRnode() = rnodeUsb.disconnect()

    override fun rnodeStatus(): Flow<RnodeStatus> = flow {
        while (true) {
            emit(fetchRnodeStatus())
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    private fun fetchRnodeStatus(): RnodeStatus {
        val obj = JSONObject(orchestrator.callAttr("get_rnode_status_json").toString())
        fun intOrNull(key: String): Int? = if (obj.isNull(key)) null else obj.optInt(key)
        fun longOrNull(key: String): Long? = if (obj.isNull(key)) null else obj.optLong(key)
        return RnodeStatus(
            connected = obj.optBoolean("connected", false),
            online = obj.optBoolean("online", false),
            platform = intOrNull("platform"),
            mcu = intOrNull("mcu"),
            firmwareMajor = intOrNull("firmware_major"),
            firmwareMinor = intOrNull("firmware_minor"),
            frequencyHz = longOrNull("frequency"),
            bandwidthHz = longOrNull("bandwidth"),
            txPowerDbm = intOrNull("txpower"),
            spreadingFactor = intOrNull("spreading_factor"),
            codingRate = intOrNull("coding_rate"),
        )
    }

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
        const val TAG = "RealInterfaceController"
        const val POLL_INTERVAL_MS = 4000L
    }
}
