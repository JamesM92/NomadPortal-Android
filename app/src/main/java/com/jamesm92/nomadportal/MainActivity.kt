package com.jamesm92.nomadportal

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.jamesm92.nomadportal.data.SettingsRepository
import com.jamesm92.nomadportal.data.ThemeMode
import com.jamesm92.nomadportal.nav.NomadNavHost
import com.jamesm92.nomadportal.ui.theme.NomadPortalTheme

/**
 * [FragmentActivity], not the plain [androidx.activity.ComponentActivity]
 * this app used before — a real, safe widening (FragmentActivity already
 * extends ComponentActivity, so every existing `LocalContext.current as
 * ComponentActivity`-style cast elsewhere in the app still works
 * unchanged), needed specifically because
 * [androidx.biometric.BiometricPrompt]'s real constructor requires a
 * FragmentActivity host — see
 * [com.jamesm92.nomadportal.security.DeviceCredentialGate]'s own doc
 * comment for what that's for (rnsh's real device-credential gate).
 */
class MainActivity : FragmentActivity() {
    // Held as an Activity field (not local to onCreate's setContent
    // lambda) specifically so onNewIntent (fired for an already-running
    // Activity — e.g. tapping a notification while the app is already
    // open) can update it after onCreate has already run; Compose's
    // mutableStateOf works as a plain observable holder outside a
    // composable just as well as inside one.
    private var pendingConversationHash by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingConversationHash = intent?.getStringExtra(EXTRA_CONVERSATION_HASH)

        val app = application as NomadPortalApp
        setContent {
            val textScale by app.settingsRepository.textScale
                .collectAsState(initial = SettingsRepository.DEFAULT_TEXT_SCALE)
            // Matches SettingsRepository.themeMode's own real default
            // (DARK, not SYSTEM — see that property's own doc comment) —
            // this initial value only covers the single frame before the
            // real DataStore-backed Flow first emits; using anything else
            // here would flash the wrong theme for a frame on every cold
            // start, not just first-ever launch.
            val themeMode by app.settingsRepository.themeMode
                .collectAsState(initial = ThemeMode.DARK)
            NomadPortalTheme(themeMode = themeMode, textScale = textScale) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    NomadNavHost(
                        interfaceController = app.interfaceController,
                        messagingRepository = app.messagingRepository,
                        browserRepository = app.browserRepository,
                        pageCacheStore = app.pageCacheStore,
                        settingsRepository = app.settingsRepository,
                        tcpConnectionsRepository = app.tcpConnectionsRepository,
                        siteFileRepository = app.siteFileRepository,
                        callRepository = app.callRepository,
                        rnshRepository = app.rnshRepository,
                        rnshHistoryRepository = app.rnshHistoryRepository,
                        identityRepository = app.identityRepository,
                        pendingConversationHash = pendingConversationHash,
                        onConsumedPendingConversation = { pendingConversationHash = null },
                    )
                }
            }
        }
    }

    // Fires when the app is already running and a new notification tap
    // (or any other Intent) arrives — onCreate alone would miss this,
    // since Android reuses the existing Activity instance instead of
    // recreating it (this app doesn't set launchMode, so the default
    // "standard" mode plus FLAG_ACTIVITY_CLEAR_TOP/NEW_TASK on the
    // notification's own PendingIntent is what routes back here).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingConversationHash = intent.getStringExtra(EXTRA_CONVERSATION_HASH)
    }

    companion object {
        /** Notification-tap deep link — see
         * [com.jamesm92.nomadportal.notifications.MessageNotifier]'s own
         * doc comment for where this is set. */
        const val EXTRA_CONVERSATION_HASH = "conversation_hash"
    }
}
