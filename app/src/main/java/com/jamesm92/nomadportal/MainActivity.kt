package com.jamesm92.nomadportal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.jamesm92.nomadportal.data.SettingsRepository
import com.jamesm92.nomadportal.nav.NomadNavHost
import com.jamesm92.nomadportal.ui.theme.NomadPortalTheme

class MainActivity : ComponentActivity() {
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
