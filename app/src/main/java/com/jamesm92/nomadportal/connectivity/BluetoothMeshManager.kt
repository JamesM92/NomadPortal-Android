package com.jamesm92.nomadportal.connectivity

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.chaquo.python.Python
import com.jamesm92.rnsble.config.RnsBleConfig
import com.jamesm92.rnsble.service.RnsBleForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns [RnsBleForegroundService]'s bind/start/stop lifecycle and the one
 * call across the Chaquopy boundary that hands the resulting
 * `RnsBleBridge` to `orchestrator.py`'s `set_bluetooth_mesh_bridge` — see
 * that function's own doc comment for why Python can't construct a bridge
 * itself (it needs real Android BLE hardware access this app's embedded
 * CPython interpreter has no route to; the bridge has to come from the
 * Kotlin side, already holding a live `Context`).
 *
 * **Mesh-only**: `rnodeEnabled` is always `false` here. RNode-over-BLE/
 * Classic is a distinct, still-unimplemented role in `RNS_BLE_Wrapper`
 * itself (see that repo's own README "Scope of this pass" section) —
 * wiring it in is a separate future integration, not this class's job;
 * [RealInterfaceController.setRNodeEnabled] stays persisted-intent-only.
 *
 * `start()`'s actual Python-side attach happens asynchronously, once
 * [android.content.ServiceConnection.onServiceConnected] fires — there's
 * no synchronous "service is bound" signal Android offers without extra
 * `suspendCancellableCoroutine` plumbing this doesn't add, matching how
 * [com.jamesm92.nomadportal.NomadPortalApp]'s own boot-time toggle calls
 * already treat this class of failure: best-effort, logged, not
 * propagated to a caller that's typically already moved on by the time
 * the bind actually completes. `stop()` is the deterministic half —
 * detaches the Python interface synchronously before this call returns,
 * so the Settings toggle visually flipping off is trustworthy.
 */
class BluetoothMeshManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var bound = false

    private val orchestrator by lazy {
        Python.getInstance().getModule("nomadportal_core.orchestrator")
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bridge = (binder as? RnsBleForegroundService.LocalBinder)?.getBridge()
            if (bridge == null) {
                Log.w(TAG, "Service connected but bridge was null")
                return
            }
            scope.launch(Dispatchers.IO) {
                try {
                    orchestrator.callAttr("set_bluetooth_mesh_bridge", bridge)
                } catch (e: Exception) {
                    // Most likely "RNS is not ready yet" (_set_interface's
                    // own RuntimeError) if the toggle was flipped before
                    // orchestrator.wait_ready() resolved — same "logged,
                    // nothing further to do here at boot time" shape
                    // NomadPortalApp.kt's own node-hosting toggle already
                    // uses for this exact class of timing issue. The
                    // toggle itself already persisted via
                    // SettingsRepository regardless of this outcome.
                    Log.w(TAG, "Failed to attach Bluetooth-mesh interface: ${e.message}")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
        }
    }

    /** Starts the foreground service and binds to it — the actual RNS
     * interface attach happens once [onServiceConnected] fires (see
     * class doc comment). Safe to call again while already running;
     * [RnsBleForegroundService.onStartCommand] itself is idempotent
     * about not re-creating an existing bridge. */
    fun start() {
        val config = RnsBleConfig(meshEnabled = true, rnodeEnabled = false)
        val intent = RnsBleForegroundService.buildStartIntent(context, config)
        context.startForegroundService(intent)
        if (!bound) {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            bound = true
        }
    }

    /** Detaches the Python interface (synchronously, before returning),
     * then unbinds and stops the service. */
    suspend fun stop() {
        withContext(Dispatchers.IO) {
            try {
                orchestrator.callAttr("clear_bluetooth_mesh_bridge")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to detach Bluetooth-mesh interface: ${e.message}")
            }
        }
        if (bound) {
            context.unbindService(serviceConnection)
            bound = false
        }
        context.stopService(Intent(context, RnsBleForegroundService::class.java))
    }

    private companion object {
        const val TAG = "BluetoothMeshManager"
    }
}
