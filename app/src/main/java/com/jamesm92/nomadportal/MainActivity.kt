package com.jamesm92.nomadportal

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.jamesm92.nomadportal.data.SettingsRepository
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as NomadPortalApp
        setContent {
            val textScale by app.settingsRepository.textScale
                .collectAsState(initial = SettingsRepository.DEFAULT_TEXT_SCALE)
            NomadPortalTheme(textScale = textScale) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    NomadNavHost(
                        interfaceController = app.interfaceController,
                        messagingRepository = app.messagingRepository,
                        browserRepository = app.browserRepository,
                        settingsRepository = app.settingsRepository,
                        tcpConnectionsRepository = app.tcpConnectionsRepository,
                        siteFileRepository = app.siteFileRepository,
                        callRepository = app.callRepository,
                        rnshRepository = app.rnshRepository,
                        rnshHistoryRepository = app.rnshHistoryRepository,
                    )
                }
            }
        }
    }
}
