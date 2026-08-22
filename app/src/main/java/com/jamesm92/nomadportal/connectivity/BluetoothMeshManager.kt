package com.jamesm92.nomadportal.connectivity

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.chaquo.python.Python
import com.jamesm92.rnsble.config.RnsBleConfig
import com.jamesm92.rnsble.interop.PacketEvent
import com.jamesm92.rnsble.service.RnsBleForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 *
 * Also owns [status] — real neighbor-sighting data from the bridge's own
 * [PacketEvent.NeighborSeen] events (a Columba UI/UX parity-audit
 * follow-up: [com.jamesm92.nomadportal.ui.network.NetworkScreen]'s
 * original pass had nothing but on/off toggle state for this interface,
 * since nothing subscribed to these events before). Deliberately a
 * light, local rolling-window aggregation ([NEIGHBOR_STALE_AFTER_MS]) —
 * this class has no access to `MeshTransport`'s own internal
 * `NeighborTracker` state across the Chaquopy/service boundary, only the
 * flattened event stream, so it keeps its own small last-seen map rather
 * than trying to mirror that class exactly.
 */
class BluetoothMeshManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var bound = false

    private val orchestrator by lazy {
        Python.getInstance().getModule("nomadportal_core.orchestrator")
    }

    private var eventsJob: Job? = null

    // Neighbor peer ID -> (last time it was sighted, most recent RSSI).
    // Plain, not thread-safe-hardened (ConcurrentHashMap etc.) — every
    // read/write here happens on `scope`'s own dispatcher via the single
    // events-collecting coroutine and the sweep loop below, never
    // concurrently from two different threads.
    private val neighborInfo = mutableMapOf<String, NeighborInfo>()
    // Separate from neighborInfo's own max — that map gets pruned of
    // stale entries (see the sweep loop below), but "when did activity
    // last happen at all" shouldn't reset to null just because every
    // neighbor has since gone quiet.
    private var lastActivityAtMillis: Long? = null

    // Every distinct neighbor id ever sighted, across every past
    // Bluetooth-mesh session on this device — see BluetoothMeshStatus.
    // lifetimeUniqueNeighborCount's own doc comment for why this is a
    // real, separate metric from neighborInfo's own rolling window.
    // Plain SharedPreferences, not this app's usual DataStore
    // (SettingsRepository) — this class doesn't otherwise take a
    // SettingsRepository dependency at all, and a single growing
    // Set<String> is exactly what SharedPreferences.getStringSet/
    // putStringSet already exists for; no reason to widen this class's
    // constructor just to route through DataStore for one field.
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lifetimeNeighborIds: MutableSet<String> =
        prefs.getStringSet(KEY_LIFETIME_NEIGHBOR_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()

    private val _status = MutableStateFlow(
        BluetoothMeshStatus(
            neighborCount = 0,
            lastActivityAtMillis = null,
            lifetimeUniqueNeighborCount = lifetimeNeighborIds.size,
            neighbors = emptyList(),
        ),
    )
    val status: StateFlow<BluetoothMeshStatus> = _status.asStateFlow()

    private data class NeighborInfo(val lastSeenAtMillis: Long, val rssi: Int)

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
            eventsJob?.cancel()
            eventsJob = scope.launch {
                launch {
                    bridge.events.collect { event ->
                        if (event is PacketEvent.NeighborSeen) {
                            val now = System.currentTimeMillis()
                            neighborInfo[event.neighborId] = NeighborInfo(now, event.rssi)
                            lastActivityAtMillis = now
                            recordLifetimeNeighborIfNew(event.neighborId)
                            publishStatus()
                        }
                    }
                }
                // Periodic sweep so a neighbor that's gone quiet eventually
                // drops out of the count, rather than only ever growing —
                // same shape as MeshTransport's own stale-reassembly sweep
                // (15s tick) on the RNS_BLE_Wrapper side, not copied
                // exactly since that class's internals aren't reachable
                // from here.
                while (true) {
                    delay(NEIGHBOR_SWEEP_INTERVAL_MS)
                    val cutoff = System.currentTimeMillis() - NEIGHBOR_STALE_AFTER_MS
                    val staleRemoved = neighborInfo.entries.removeIf { it.value.lastSeenAtMillis < cutoff }
                    if (staleRemoved) publishStatus()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            eventsJob?.cancel()
            eventsJob = null
            neighborInfo.clear()
            lastActivityAtMillis = null
            publishStatus()
        }
    }

    /** Persists [neighborId] into [lifetimeNeighborIds]/SharedPreferences
     * the first time this device ever sights it — a no-op (no write at
     * all) for every subsequent sighting of an already-known neighbor,
     * so this isn't hitting disk on every single announce, only on a
     * real first-time addition. */
    private fun recordLifetimeNeighborIfNew(neighborId: String) {
        if (lifetimeNeighborIds.add(neighborId)) {
            prefs.edit().putStringSet(KEY_LIFETIME_NEIGHBOR_IDS, lifetimeNeighborIds).apply()
        }
    }

    private fun publishStatus() {
        _status.value = BluetoothMeshStatus(
            neighborCount = neighborInfo.size,
            lastActivityAtMillis = lastActivityAtMillis,
            lifetimeUniqueNeighborCount = lifetimeNeighborIds.size,
            neighbors = neighborInfo.map { (id, info) ->
                BluetoothNeighbor(id = id, rssi = info.rssi, lastSeenAtMillis = info.lastSeenAtMillis)
            },
        )
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
        // unbindService() doesn't reliably trigger onServiceDisconnected
        // for a normal client-initiated unbind (that callback is really
        // for unexpected disconnection/process death) — reset status
        // explicitly here too, rather than depending on it.
        eventsJob?.cancel()
        eventsJob = null
        neighborInfo.clear()
        lastActivityAtMillis = null
        // lifetimeNeighborIds deliberately NOT cleared here — stopping
        // Bluetooth mesh (or the service disconnecting) isn't "this
        // device has never met these neighbors," the whole point of
        // this counter is that it survives exactly this kind of
        // session boundary.
        publishStatus()
    }

    private companion object {
        const val TAG = "BluetoothMeshManager"
        const val PREFS_NAME = "bluetooth_mesh_manager"
        const val KEY_LIFETIME_NEIGHBOR_IDS = "lifetime_neighbor_ids"
        const val NEIGHBOR_SWEEP_INTERVAL_MS = 15_000L
        // A neighbor not re-sighted within this window drops out of
        // status's neighborCount — deliberately not trying to match
        // RnsBleConfig's own neighborRollingWindowMs exactly (that config
        // isn't reachable from this side of the Chaquopy/service
        // boundary), just a reasonable, honestly-approximate window.
        const val NEIGHBOR_STALE_AFTER_MS = 60_000L
    }
}
