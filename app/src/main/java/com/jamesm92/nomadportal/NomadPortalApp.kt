package com.jamesm92.nomadportal

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.jamesm92.nomadportal.connectivity.InterfaceController
import com.jamesm92.nomadportal.connectivity.NoopInterfaceController
import com.jamesm92.nomadportal.data.SettingsRepository
import com.jamesm92.nomadportal.data.browsing.BrowserRepository
import com.jamesm92.nomadportal.data.browsing.StubBrowserRepository
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.data.messaging.StubMessagingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Starts the embedded CPython interpreter once, at process start, and
 * wires up the small set of app-wide singletons (settings persistence, the
 * connectivity controller) that don't warrant a full DI framework yet at
 * this project's current size. Revisit with Hilt/Koin if this grows past a
 * handful of dependencies.
 *
 * Python startup is kept deliberately minimal here — this is plumbing for
 * suggested-sequencing step 2 in nomadportal_android_handoff.md ("get
 * RNS/LXMF actually running under Chaquopy"), not the RNS bootstrap
 * itself, which belongs in the extracted core module once it exists (see
 * that doc's "Suggested sequencing", step 1).
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
        // NoopInterfaceController until the RNS core is extracted — see its
        // own doc comment. Swapping this line is the entire integration
        // point for a real controller later.
        interfaceController = NoopInterfaceController(settingsRepository, appScope)
        // Same pattern as interfaceController: StubMessagingRepository is
        // fake/in-memory until real LXMF delivery exists — see its own doc
        // comment. Swapping this line is the entire integration point.
        messagingRepository = StubMessagingRepository(appScope)
        // Same pattern again: StubBrowserRepository is fake nodes/pages
        // until a real RNS Link/path layer exists.
        browserRepository = StubBrowserRepository()
    }
}
