package com.jamesm92.nomadportal.connectivity

import com.jamesm92.nomadportal.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    override suspend fun setNodeHostingEnabled(enabled: Boolean) {
        // TODO(core extraction): start/stop the request-handler that serves pages/files.
        settings.setNodeHostingEnabled(enabled)
    }
}
