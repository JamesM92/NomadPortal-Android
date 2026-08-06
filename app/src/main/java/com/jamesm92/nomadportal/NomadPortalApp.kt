package com.jamesm92.nomadportal

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.jamesm92.nomadportal.connectivity.InterfaceController
import com.jamesm92.nomadportal.connectivity.RealInterfaceController
import com.jamesm92.nomadportal.data.SettingsRepository
import com.jamesm92.nomadportal.data.browsing.BrowserRepository
import com.jamesm92.nomadportal.data.browsing.StubBrowserRepository
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.data.messaging.StubMessagingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    lateinit var messagingRepository: MessagingRepository
        private set
    lateinit var browserRepository: BrowserRepository
        private set

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        settingsRepository = SettingsRepository(this)
        // RealInterfaceController, backed by nomadportal_core.orchestrator
        // (app/src/main/python/nomadportal_core/orchestrator.py — see its
        // docstring for the RNS-interface-lifecycle design). Only TCP and
        // Wi-Fi discovery are actually wired to real RNS behavior right
        // now; RNode/Bluetooth-mesh/hosting remain persisted-intent-only
        // pending their own separate prerequisites — see
        // RealInterfaceController's own doc comment for exactly why each
        // one isn't done yet.
        interfaceController = RealInterfaceController(settingsRepository, appScope)

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
        }

        // Same pattern as interfaceController: StubMessagingRepository is
        // fake/in-memory until real LXMF delivery exists — see its own doc
        // comment. Swapping this line is the entire integration point.
        messagingRepository = StubMessagingRepository(appScope)
        // Same pattern again: StubBrowserRepository is fake nodes/pages
        // until a real RNS Link/path layer exists.
        browserRepository = StubBrowserRepository()
    }
}
