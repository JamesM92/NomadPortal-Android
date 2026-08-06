package com.jamesm92.nomadportal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import com.jamesm92.nomadportal.ui.theme.NomadPortalTheme

/**
 * Placeholder single-screen shell (nomadportal_android_handoff.md,
 * "Suggested sequencing", step 2). Its only real job is proving the
 * Chaquopy-embedded Python interpreter is reachable from Compose before any
 * real browsing/hosting/editor screens are built on top of it.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NomadPortalTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PlaceholderScreen()
                }
            }
        }
    }
}

@Composable
fun PlaceholderScreen() {
    var pythonStatus by remember { mutableStateOf("Not tested yet") }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = "NomadPortal",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
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
fun PlaceholderScreenPreview() {
    NomadPortalTheme {
        PlaceholderScreen()
    }
}
