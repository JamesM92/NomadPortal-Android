package com.jamesm92.nomadportal.connectivity

import com.jamesm92.nomadportal.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Placeholder [InterfaceController]: persists what the user asked for
 * (via [SettingsRepository]) but does not actually start or stop any RNS
 * interface, because there is no RNS core embedded yet
 * (nomadportal_android_handoff.md sequencing step 1). The Settings screen
 * built against this today is not throwaway work — replace this class
 * with one that actually drives the Chaquopy-embedded core when that
 * lands, and nothing above this interface should need to change.
 *
 * Deliberately does NOT pretend the interfaces are live — it exposes
 * exactly the persisted intent and nothing more. Anything that needs to
 * know "is TCP *actually* up" (as opposed to "did the user ask for TCP")
 * should not be answered by this class once a real controller exists.
 */
class NoopInterfaceController(
    private val settings: SettingsRepository,
    scope: CoroutineScope,
) : InterfaceController {

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
        // TODO(core extraction): start/stop the TCP client/server RNS Interface here.
        settings.setTcpEnabled(enabled)
    }

    override suspend fun setBluetoothMeshEnabled(enabled: Boolean) {
        // TODO(bluetooth-mesh interface repo): start/stop the BLE-mesh RNS Interface here.
        settings.setBluetoothMeshEnabled(enabled)
    }

    override suspend fun setRNodeEnabled(enabled: Boolean) {
        // TODO(core extraction): start/stop the RNode Interface here (USB or Bluetooth transport).
        settings.setRNodeEnabled(enabled)
    }

    override suspend fun setWifiDiscoveryEnabled(enabled: Boolean) {
        // TODO(core extraction): start/stop RNS's AutoInterface here.
        settings.setWifiDiscoveryEnabled(enabled)
    }

    override suspend fun setNodeHostingEnabled(enabled: Boolean) {
        // TODO(core extraction): start/stop the request-handler that serves pages/files.
        settings.setNodeHostingEnabled(enabled)
    }

    // Persisted-intent-only, matching this class's own convention
    // throughout — no real SiteServer here, so no real hash/name/
    // announce state to report; enabled alone reflects what was asked
    // for.
    override fun hostedNodeStatus(): Flow<HostedNodeStatus> =
        nodeHostingEnabled.map { enabled ->
            HostedNodeStatus(
                enabled = enabled,
                nodeHash = null,
                nodeName = null,
                announceIntervalSeconds = 0,
                lastAnnounceAtMillis = null,
            )
        }

    override suspend fun setHostedNodeName(name: String): Boolean = false
    override suspend fun setHostedNodeAnnounceInterval(seconds: Int): Boolean = false
    override suspend fun announceHostedNodeNow(): Boolean = false

    // No real TCP connections tracked here — persisted-intent-only,
    // matching this class's own convention throughout.
    override fun hasDownTcpConnection(): Flow<Boolean> = flowOf(false)

    // No real Bluetooth mesh transport here — persisted-intent-only,
    // matching this class's own convention throughout.
    override fun bluetoothMeshStatus(): Flow<BluetoothMeshStatus> =
        flowOf(BluetoothMeshStatus(neighborCount = 0, lastActivityAtMillis = null))
}
