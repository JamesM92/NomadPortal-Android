package com.jamesm92.nomadportal.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.messaging.AnnounceStatus
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.panicwipe.PanicWipe
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.AppLogo
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import kotlinx.coroutines.launch

/**
 * Home shell — the app's identity-management surface, per explicit
 * design direction: manual announcing and renaming for this device's
 * own LXMF identity live *here*, not in Settings (Settings only owns
 * configuration — thresholds, connection lists, on/off — never a "do it
 * now" action; see that screen's own doc comment). Also owns the app's
 * top bar, including the panic-wipe triple-tap target ([AppLogo]) — kept
 * here rather than duplicated per-screen since Home is this app's
 * "always reachable" root.
 *
 * A hosted-node identity section (rename + manual announce, matching
 * the LXMF one below) is NOT here yet, even though it was requested
 * alongside this — node hosting has no real `SiteServer` behind it yet
 * (see [com.jamesm92.nomadportal.connectivity.RealInterfaceController]'s
 * own doc comment: Settings' hosting toggle is still persisted-intent-
 * only). Building rename/announce controls for an identity that doesn't
 * exist yet would be exactly the "toggle that doesn't actually control
 * what it claims to" anti-pattern this app's connectivity design
 * otherwise goes out of its way to avoid — this needs real SiteServer
 * wiring first (sequencing step 5), not a cosmetic stand-in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    messagingRepository: MessagingRepository,
    onOpenSettings: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenNodes: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val announceStatus by messagingRepository.announceStatus().collectAsState(initial = null)

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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            announceStatus?.let { status ->
                IdentitySection(
                    status = status,
                    onRename = { name -> scope.launch { messagingRepository.setDisplayName(name) } },
                    onAnnounceNow = { scope.launch { messagingRepository.announceNow() } },
                )
            }
        }
    }
}

/**
 * This device's own LXMF identity: an editable display name, its
 * address, how long since it last announced, and a manual "Announce
 * now" trigger — see [HomeScreen]'s own doc comment for why this lives
 * here and not in Settings.
 */
@Composable
private fun IdentitySection(
    status: AnnounceStatus,
    onRename: (String) -> Unit,
    onAnnounceNow: () -> Unit,
) {
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember(status.displayName) { mutableStateOf(status.displayName ?: "") }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Identity",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.secondary,
        )

        if (editingName) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    val trimmed = nameDraft.trim()
                    if (trimmed.isNotEmpty()) onRename(trimmed)
                    editingName = false
                }) {
                    Icon(Icons.Filled.Check, contentDescription = "Save name")
                }
                IconButton(onClick = {
                    nameDraft = status.displayName ?: ""
                    editingName = false
                }) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel")
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = status.displayName ?: "Unnamed",
                    style = MaterialTheme.typography.bodyLarge,
                )
                IconButton(onClick = { editingName = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename")
                }
            }
        }

        Text(
            text = status.lxmfAddress?.let { "LXMF: ${it.take(16)}…" } ?: "LXMF address not ready yet",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.8f,
            ),
            color = NomadTextDim,
        )
        Text(
            text = status.lastAnnounceAtMillis?.let { "Last announced ${formatSince(it)} ago" }
                ?: "Never announced yet",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.8f,
            ),
            color = NomadTextDim,
        )

        Button(onClick = onAnnounceNow, modifier = Modifier.padding(top = 8.dp)) {
            Text("Announce now")
        }

        if (status.sendBlocked) {
            Text(
                text = status.sendBlockedReason ?: "Sending is currently blocked.",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.85f,
                ),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
    }
}

/** Same convention as every other relative-time helper in this app —
 * bare duration, caller appends " ago" itself. */
private fun formatSince(millis: Long): String {
    val diffSeconds = ((System.currentTimeMillis() - millis) / 1000).coerceAtLeast(0)
    return when {
        diffSeconds < 3600 -> "${diffSeconds / 60}m"
        diffSeconds < 86_400 -> "${diffSeconds / 3600}h"
        else -> "${diffSeconds / 86_400}d"
    }
}
