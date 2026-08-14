package com.jamesm92.nomadportal

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.jamesm92.nomadportal.audio.CallAudioEngine
import com.jamesm92.nomadportal.connectivity.InterfaceController
import com.jamesm92.nomadportal.connectivity.RealInterfaceController
import com.jamesm92.nomadportal.connectivity.RealTcpConnectionsRepository
import com.jamesm92.nomadportal.connectivity.TcpConnectionsRepository
import com.jamesm92.nomadportal.data.SettingsRepository
import com.jamesm92.nomadportal.data.calling.CallRepository
import com.jamesm92.nomadportal.data.calling.RealCallRepository
import com.jamesm92.nomadportal.data.browsing.BrowserRepository
import com.jamesm92.nomadportal.data.browsing.RealBrowserRepository
import com.jamesm92.nomadportal.data.hosting.RealSiteFileRepository
import com.jamesm92.nomadportal.data.hosting.SiteFileRepository
import com.jamesm92.nomadportal.data.messaging.MdiIconRepository
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.data.messaging.RealMessagingRepository
import com.jamesm92.nomadportal.data.rnsh.RealRnshHistoryRepository
import com.jamesm92.nomadportal.data.rnsh.RealRnshRepository
import com.jamesm92.nomadportal.data.rnsh.RnshHistoryRepository
import com.jamesm92.nomadportal.data.rnsh.RnshRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts the embedded CPython interpreter once, at process start, and
 * wires up the small set of app-wide singletons (settings persistence, the
 * connectivity controller) that don't warrant a full DI framework yet at
 * this project's current size. Revisit with Hilt/Koin if this grows past a
 * handful of dependencies.
 */
class NomadPortalApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob())

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var interfaceController: InterfaceController
        private set
    lateinit var tcpConnectionsRepository: TcpConnectionsRepository
        private set
    lateinit var messagingRepository: MessagingRepository
        private set
    lateinit var browserRepository: BrowserRepository
        private set
    lateinit var siteFileRepository: SiteFileRepository
        private set
    lateinit var callRepository: CallRepository
        private set
    lateinit var callAudioEngine: CallAudioEngine
        private set
    lateinit var rnshRepository: RnshRepository
        private set
    lateinit var rnshHistoryRepository: RnshHistoryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        // Pre-warms the real ~7400-icon MDI catalog in the background
        // (see MdiIconRepository's own doc comment) so it's likely
        // already loaded by the time a contact icon actually needs it —
        // get() is safe to call before this finishes regardless, it
        // just returns null (same as any unresolved name) until ready.
        MdiIconRepository.initialize(this, appScope)

        settingsRepository = SettingsRepository(this)
        // RealInterfaceController, backed by nomadportal_core.orchestrator
        // (app/src/main/python/nomadportal_core/orchestrator.py — see its
        // docstring for the RNS-interface-lifecycle design). TCP, Wi-Fi
        // discovery, node hosting, and Bluetooth mesh are all wired to
        // real behavior; only RNode remains persisted-intent-only pending
        // its own separate prerequisite — see RealInterfaceController's own doc
        // comment for exactly why.
        interfaceController = RealInterfaceController(this, settingsRepository, appScope)
        tcpConnectionsRepository = RealTcpConnectionsRepository()
        siteFileRepository = RealSiteFileRepository()

        // noBackupFilesDir, not filesDir: RNS identity material must never
        // leave the device via a cloud-backup/device-transfer side channel
        // (same reasoning as data_extraction_rules.xml and the panic-wipe
        // design). orchestrator.start() is idempotent and does its own
        // heavy lifting (RNS.Reticulum() init) on its own background
        // thread — this call itself is fast, but still off the main
        // thread here since it does synchronous file I/O (loading
        // nodes.json/favorites.json/etc.) before returning.
        appScope.launch(Dispatchers.IO) {
            val orchestrator = Python.getInstance().getModule("nomadportal_core.orchestrator")
            orchestrator.callAttr("start", noBackupFilesDir.absolutePath)

            // start() only constructs NodeBrowser/RNS — it never adds any
            // Interface itself. Without this, a persisted "TCP: on" (the
            // default) would just sit there as an un-acted-on preference
            // until a user happened to manually re-toggle it in Settings,
            // which is exactly the "toggle that doesn't actually control
            // what it claims to" failure this app's connectivity design
            // (Authoritative toggles) exists to avoid. Blocks this IO
            // thread (not the main thread) until RNS finishes its own
            // init — real deployments can take 60-300s per the
            // orchestrator's own docstring.
            val ready = orchestrator.callAttr("wait_ready", 300.0).toBoolean()
            if (!ready) {
                return@launch
            }
            if (settingsRepository.tcpEnabled.first()) {
                interfaceController.setTcpEnabled(true)
            }
            if (settingsRepository.wifiDiscoveryEnabled.first()) {
                interfaceController.setWifiDiscoveryEnabled(true)
            }
            if (settingsRepository.bluetoothMeshEnabled.first()) {
                // Same best-effort shape as node hosting below — the
                // foreground service start can fail (e.g. the Bluetooth
                // permission grant was somehow lost since this was last
                // turned on), and that shouldn't crash app startup.
                try {
                    interfaceController.setBluetoothMeshEnabled(true)
                } catch (e: Exception) {
                    // Logged inside BluetoothMeshManager already; nothing
                    // further to do here at boot time.
                }
            }
            if (settingsRepository.nodeHostingEnabled.first()) {
                // Best-effort — a failure here (e.g. a corrupt site
                // identity file) shouldn't crash app startup; the
                // Settings screen's toggle will visibly show it's off
                // if this doesn't succeed, same honesty-over-silent-
                // success convention as everywhere else in this app.
                try {
                    interfaceController.setNodeHostingEnabled(true)
                } catch (e: Exception) {
                    // Logged inside orchestrator.py already; nothing
                    // further to do here at boot time.
                }
            }
            // Replays the persisted "Messages from contacts only" choice
            // into messaging.py's own in-memory enforcement flag — same
            // pattern as the four toggles above, and for the same reason
            // (see SettingsRepository.messagesContactsOnly's own doc
            // comment: this one specifically needs a real boot-time
            // replay, unlike Settings' Auto Announce master toggle,
            // which is allowed to stay ephemeral). Called directly
            // against `orchestrator` rather than through
            // `messagingRepository` — that repository isn't constructed
            // until just below this block, and there's no need to wait
            // for it when the plain bridge call already does the job.
            if (settingsRepository.messagesContactsOnly.first()) {
                orchestrator.callAttr("set_messages_contacts_only", true)
            }
        }

        // Real, orchestrator-backed repositories (Aug 2026) — replaced
        // Stub{Browser,Messaging}Repository now that orchestrator.py
        // exposes real browsing/messaging bridge functions. Both poll
        // Python on an interval rather than reacting to a push callback
        // (neither NodeBrowser nor MessagingService expose one — see
        // RealBrowserRepository/RealMessagingRepository's own doc
        // comments) — safe to construct before orchestrator.start()
        // finishes, since every bridge function degrades to an empty
        // result rather than erroring while _browser/_messaging are
        // still None.
        messagingRepository = RealMessagingRepository(settingsRepository)
        browserRepository = RealBrowserRepository()
        // Phase 1a/1b of a real voice-call feature (signalling +
        // audio — see python-core's call_manager.py). Same
        // safe-before-orchestrator.start()-finishes reasoning as the two
        // repositories above: every bridge function degrades to an
        // idle/no-op result rather than erroring while _call_manager is
        // still None.
        callRepository = RealCallRepository()
        // Starts/stops itself automatically off callRepository's own
        // state — see CallAudioEngine's own doc comment. No further
        // wiring needed; constructing it is enough.
        callAudioEngine = CallAudioEngine(this, callRepository, appScope)
        // Client-only remote shell over Reticulum (rnsh) — see
        // RnshRepository's own doc comment for the interop/scope
        // decisions. Same safe-before-orchestrator.start()-finishes
        // reasoning as the repositories above; rnsh_connect degrades to
        // a "not ready yet" failure rather than erroring while
        // _messaging is still None.
        rnshRepository = RealRnshRepository()
        // Purely local Kotlin-side "recent destinations" bookkeeping —
        // see RnshHistoryRepository's own doc comment. Independent of
        // orchestrator readiness (plain DataStore, no Chaquopy involved).
        rnshHistoryRepository = RealRnshHistoryRepository(this)
    }
}
