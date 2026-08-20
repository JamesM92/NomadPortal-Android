package com.jamesm92.nomadportal.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.TentPortalMark
import com.jamesm92.nomadportal.ui.theme.NomadTextDim

/**
 * App identity/version/license/repo — Columba's own real `AboutCard`
 * (verified against its source during this session's own Columba
 * settings audit: "system info, version checking, bug reporting").
 * Version checking/bug-reporting aren't built here — this app has no
 * update-check server or crash-reporting pipeline (a deliberate
 * difference: no telemetry, matching this app's own privacy-first
 * positioning) — just real, static identity: name, version, license,
 * and the real repo link (this app was actually pushed to GitHub
 * earlier this session, so this is a real link, not a placeholder).
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val packageInfo = remember {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TentPortalMark(markSize = 64.dp)
            Text(
                text = "NomadPortal",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = "Version ${packageInfo.versionName} · Beta",
                style = MaterialTheme.typography.bodyMedium,
                color = NomadTextDim,
            )
            Text(
                text = "A native Android app for Reticulum & NomadNet — infrastructure-free " +
                    "mesh messaging, browsing, and hosting, built around the real, embedded " +
                    "Reticulum and LXMF reference implementations.",
                style = MaterialTheme.typography.bodyMedium,
                color = NomadTextDim,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = "License: PolyForm Noncommercial 1.0.0",
                style = MaterialTheme.typography.bodySmall,
                color = NomadTextDim,
                modifier = Modifier.padding(top = 16.dp),
            )
            TextButton(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))
                context.startActivity(intent)
            }) {
                Text(REPO_URL)
            }
        }
    }
}

private const val REPO_URL = "https://github.com/JamesM92/NomadPortal-Android"
