package com.jamesm92.nomadportal.connectivity

import com.chaquo.python.Python
import com.jamesm92.nomadportal.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

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
 * Node hosting stays persisted-intent-only too — it needs `SiteServer`
 * wiring, deliberately out of scope for this orchestration pass
 * (sequencing step 5, not step 1/2's payoff).
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
        withContext(Dispatchers.IO) {
            // TODO(no "configure your hub" UI exists yet): placeholder
            // default target — a public hub documented in RNS's own
            // manual (reticulum.network/manual/interfaces.html), not
            // independently verified reachable from this app. Replace
            // with a real user-configurable hub list before shipping.
            orchestrator.callAttr(
                "set_tcp_enabled", enabled, "amsterdam.connect.reticulum.network", 4965,
            )
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
        // TODO(core extraction step 5, SiteServer wiring): persisted intent only.
        settings.setNodeHostingEnabled(enabled)
    }
}
