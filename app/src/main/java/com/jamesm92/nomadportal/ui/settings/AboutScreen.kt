package com.jamesm92.nomadportal.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.CompactIconButton
import com.jamesm92.nomadportal.ui.components.TentPortalMark
import com.jamesm92.nomadportal.ui.messages.AttachmentFileProvider
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val scope = rememberCoroutineScope()
    val packageInfo = remember {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    // Real request: "we need the ability to send the apk file to
    // neighbors over bluetooth" — this app's own whole premise is
    // working without the internet, so getting the app onto a nearby
    // phone that doesn't have it yet shouldn't need a Play Store or a
    // network connection either. Deliberately the plain system share
    // sheet (Intent.ACTION_SEND), not a hardcoded Bluetooth-only
    // component — Android's own Bluetooth-sharing app is already one
    // tap away in that sheet (real, standard OPP/Classic-Bluetooth file
    // transfer, unrelated to this app's own custom BLE mesh transport,
    // which has nowhere near the throughput or size budget for a
    // 100+MB APK), and hardcoding a specific OEM/AOSP component name
    // for "the Bluetooth share target" is exactly the kind of fragile,
    // version-specific assumption this app avoids elsewhere. Sharing
    // is otherwise unrelated to whether Bluetooth mesh itself is
    // currently on — this is Android's own Bluetooth stack, not this
    // app's RNS interface.
    var sharingApk by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    CompactIconButton(onClick = onBack) {
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
            TextButton(
                enabled = !sharingApk,
                onClick = {
                    sharingApk = true
                    scope.launch {
                        shareInstalledApk(context, packageInfo.versionName ?: "unknown")
                        sharingApk = false
                    }
                },
            ) {
                Icon(
                    Icons.Filled.Bluetooth,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                // "Share Installer", not "Share this app" (the original
                // label) or a Bluetooth-only-sounding one like "Bluetooth
                // Share Installer" — per follow-up direction. Names what
                // actually gets handed over (the real .apk installer
                // file, not some abstract "the app") without promising a
                // Bluetooth-only path this button doesn't actually take
                // (the system share sheet — see shareInstalledApk's own
                // doc comment for why that's deliberate); the Bluetooth
                // icon alongside it already signals the real headline
                // use case without the label itself overclaiming.
                Text(if (sharingApk) "Preparing…" else "Share Installer")
            }
        }
    }
}

private const val REPO_URL = "https://github.com/JamesM92/NomadPortal-Android"

/** Copies this device's own currently-installed APK — whatever's
 * actually running right now, not a bundled/downloaded copy — into
 * this app's private cache dir, then opens the system share sheet for
 * it. The copy step matters: `ApplicationInfo.sourceDir` points at a
 * system-owned install location this app can read but that
 * [AttachmentFileProvider]'s own FileProvider declaration isn't
 * guaranteed to serve cleanly (nothing else in this app has ever
 * needed to expose a path outside its own storage roots) — copying
 * into the cache dir first sidesteps that uncertainty entirely, the
 * same real storage root every other FileProvider-shared file in this
 * app already lives under.
 *
 * Runs the actual file copy on [Dispatchers.IO] — a debug build's APK
 * (Chaquopy's bundled Python runtime plus two ABIs) is well over
 * 100MB, a real multi-second blocking copy that has no business
 * running on the composition thread. [context] is used for its
 * `applicationContext` implicitly via [AttachmentFileProvider]/
 * `PackageManager` — safe to hold across the suspend boundary here
 * since this is only ever called from a screen-scoped coroutine (see
 * [AboutScreen]'s own `rememberCoroutineScope`), not stashed anywhere
 * longer-lived.
 */
private suspend fun shareInstalledApk(context: Context, versionName: String) {
    withContext(Dispatchers.IO) {
        try {
            val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
            val displayName = "NomadPortal-Android-v$versionName.apk"
            val destFile = File(context.cacheDir, displayName)
            File(appInfo.sourceDir).copyTo(destFile, overwrite = true)
            AttachmentFileProvider.share(
                context, destFile.path, "application/vnd.android.package-archive", displayName,
            )
        } catch (e: Exception) {
            // Best-effort, same "never crash a share action" posture
            // AttachmentFileProvider's own callers already use — a
            // failed copy (low storage, a locked source file, etc.)
            // just means nothing happens, not a crashed Settings
            // screen.
            Log.w("AboutScreen", "Failed to share installed APK: ${e.message}")
        }
    }
}
