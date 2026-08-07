package com.jamesm92.nomadportal.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import com.jamesm92.nomadportal.panicwipe.PanicWipe
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.AppLogo
import com.jamesm92.nomadportal.ui.theme.NomadPortalTheme
import kotlinx.coroutines.launch

/**
 * Home shell. Owns the app's top bar, including the panic-wipe triple-tap
 * target ([AppLogo]) — kept here rather than duplicated per-screen since
 * Home is this app's "always reachable" root.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenSettings: () -> Unit, onOpenMessages: () -> Unit, onOpenNodes: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pythonStatus by remember { mutableStateOf("Not tested yet") }

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = {
                    AppLogo(onTripleTap = {
                        scope.launch {
                            PanicWipe.perform(context)
                            PanicWipe.restartApp(context)
                        }
                    })
                },
                actions = {
                    IconButton(onClick = onOpenNodes) {
                        Icon(Icons.Filled.Explore, contentDescription = "Browse nodes")
                    }
                    IconButton(onClick = onOpenMessages) {
                        Icon(Icons.AutoMirrored.Filled.Message, contentDescription = "Messages")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = "Android shell — no browsing/hosting/editor screens yet.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "Python bridge: $pythonStatus",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
            Button(onClick = {
                pythonStatus = try {
                    val core = Python.getInstance().getModule("nomadportal_core")
                    core.callAttr("ping").toString()
                } catch (e: Exception) {
                    "FAILED: ${e.message}"
                }
            }) {
                Text("Test Python bridge")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    NomadPortalTheme {
        HomeScreen(onOpenSettings = {}, onOpenMessages = {}, onOpenNodes = {})
    }
}
