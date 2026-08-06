package com.jamesm92.nomadportal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.jamesm92.nomadportal.nav.NomadNavHost
import com.jamesm92.nomadportal.ui.theme.NomadPortalTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as NomadPortalApp
        setContent {
            NomadPortalTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    NomadNavHost(
                        interfaceController = app.interfaceController,
                        messagingRepository = app.messagingRepository,
                    )
                }
            }
        }
    }
}
