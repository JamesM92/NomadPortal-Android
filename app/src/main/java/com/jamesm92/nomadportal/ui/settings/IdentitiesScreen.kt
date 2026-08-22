package com.jamesm92.nomadportal.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.identity.Identity
import com.jamesm92.nomadportal.data.identity.IdentityRepository
import com.jamesm92.nomadportal.data.messaging.readAttachmentForSend
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.messages.AttachmentFileProvider
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Multi-identity management — Columba's own real `IdentityManagerScreen`
 * shape (verified against its source): list every identity, switch
 * which one is active, create/rename/delete, export/import raw
 * `.identity` key files. See [IdentityRepository]'s own doc comment for
 * the single-active-identity model this is built around, and
 * [Identity]'s own doc comment for why node hosting's identity is a
 * completely separate, untouched axis not represented here.
 *
 * Switching and deleting are both real, state-changing actions (a
 * switch tears down the previous identity's live router) — both get a
 * confirm dialog, matching this app's own "authoritative action" /
 * "state-changing action needs a real confirm, not a plain toggle"
 * convention elsewhere (e.g. panic wipe, disconnecting an active rnsh
 * session).
 */
@Composable
fun IdentitiesScreen(repository: IdentityRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val identities by repository.identities().collectAsState(initial = null)

    var creating by remember { mutableStateOf(false) }
    var switchTarget by remember { mutableStateOf<Identity?>(null) }
    var deleteTarget by remember { mutableStateOf<Identity?>(null) }
    var renameTarget by remember { mutableStateOf<Identity?>(null) }
    var iconTarget by remember { mutableStateOf<Identity?>(null) }
    var addressTarget by remember { mutableStateOf<Identity?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Separate from errorMessage — a successful "Save to device" still
    // needs to tell the user where the file landed, and reusing the
    // error dialog's "Error" title for that would be actively
    // misleading (a real thing worth avoiding, not just cosmetic).
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val picked = withContext(Dispatchers.IO) { readAttachmentForSend(context, uri) }
            if (picked == null) {
                errorMessage = "Couldn't read that file"
                busy = false
                return@launch
            }
            try {
                repository.importIdentity(picked.bytes, picked.filename.substringBeforeLast('.'))
            } catch (e: Exception) {
                errorMessage = e.message ?: "Import failed"
            }
            busy = false
        }
    }

    // Which identity "Save as…" is exporting — CreateDocument's own
    // launcher callback only gets the destination Uri back, not any
    // context about which row triggered it, so this is set right before
    // launch() and read back once the picker returns.
    var saveAsTarget by remember { mutableStateOf<Identity?>(null) }

    // A real destination picker (ACTION_CREATE_DOCUMENT under the hood),
    // not a hardcoded write into the device's Downloads collection —
    // per explicit direction ("make it a real save as"), matching what
    // importLauncher above already offers on the read side (browse to
    // any folder a document provider can reach, not just one fixed
    // location). Replaces the old "Save to device" behavior entirely;
    // "Share" (below, in IdentityRow) is unrelated and unchanged — it's
    // still the real share-sheet hand-off to another app.
    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val target = saveAsTarget
        saveAsTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = repository.exportIdentity(target.id)
            if (bytes == null) {
                errorMessage = "Couldn't export this identity"
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
                } catch (e: Exception) {
                    false
                }
            }
            if (ok) {
                statusMessage = "Saved \"${target.name}\""
            } else {
                errorMessage = "Couldn't save this identity"
            }
        }
    }

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = { Text("Identities") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // Real, on-device-traced crash this used to hit on
                        // every single launch (not intermittently, as an
                        // earlier version of this comment guessed): the
                        // app's own dependency graph resolved
                        // androidx.fragment down to 1.2.5 (an old
                        // transitive pull from appcompat/camera-core, see
                        // libs.versions.toml's own androidxFragment
                        // comment for the full root-cause trace), whose
                        // FragmentActivity.startActivityForResult
                        // override rejects any requestCode >= 0x10000 —
                        // but ComponentActivity's own
                        // ActivityResultRegistry (what every
                        // rememberLauncherForActivityResult call here
                        // goes through) deliberately generates codes
                        // starting at exactly 0x10000. Fixed at the root
                        // by forcing androidx.fragment up to a modern
                        // version (the checkForValidRequestCode
                        // restriction was removed there) — this catch
                        // stays as defense-in-depth (a file picker
                        // crashing the whole app is far worse than a
                        // friendly failure message), not as the actual
                        // fix.
                        try {
                            importLauncher.launch(arrayOf("*/*"))
                        } catch (e: IllegalArgumentException) {
                            errorMessage = "Couldn't open the file picker — try closing and " +
                                "reopening the app, then try again."
                        }
                    }) {
                        Icon(Icons.Filled.FileUpload, contentDescription = "Import identity")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New identity")
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (val list = identities) {
                null -> {}
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { identity ->
                        IdentityRow(
                            identity = identity,
                            onClick = { if (!identity.isActive) switchTarget = identity },
                            onShowAddresses = { addressTarget = identity },
                            onEditIcon = { iconTarget = identity },
                            onRename = { renameTarget = identity },
                            onSaveToDevice = {
                                val fileName = "${identity.name.ifBlank { identity.id.take(8) }}.identity"
                                saveAsTarget = identity
                                // Same defensive wrapping importLauncher's
                                // own launch() already has, and for the
                                // same reason (see that call site's own
                                // updated doc comment) — the underlying
                                // androidx.fragment bug is fixed at the
                                // root now, so this is real defense-in-
                                // depth, not the actual fix.
                                try {
                                    saveAsLauncher.launch(fileName)
                                } catch (e: IllegalArgumentException) {
                                    saveAsTarget = null
                                    errorMessage = "Couldn't open the save dialog — try closing and " +
                                        "reopening the app, then try again."
                                }
                            },
                            onShare = {
                                scope.launch {
                                    val fileName = "${identity.name.ifBlank { identity.id.take(8) }}.identity"
                                    val bytes = repository.exportIdentity(identity.id)
                                    if (bytes == null) {
                                        errorMessage = "Couldn't export this identity"
                                        return@launch
                                    }
                                    val tempFile = File(context.cacheDir, fileName)
                                    withContext(Dispatchers.IO) { tempFile.writeBytes(bytes) }
                                    AttachmentFileProvider.share(
                                        context = context,
                                        path = tempFile.absolutePath,
                                        mime = "application/octet-stream",
                                        displayName = fileName,
                                    )
                                }
                            },
                            onDelete = { deleteTarget = identity },
                        )
                        HorizontalDivider()
                    }
                }
            }

            if (busy) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    if (creating) {
        CreateIdentityDialog(
            onCreate = { name ->
                scope.launch {
                    busy = true
                    try {
                        repository.createIdentity(name)
                    } catch (e: Exception) {
                        errorMessage = e.message ?: "Could not create identity"
                    }
                    busy = false
                    creating = false
                }
            },
            onDismiss = { creating = false },
        )
    }

    switchTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { switchTarget = null },
            title = { Text("Switch to \"${target.name}\"?") },
            text = {
                Text(
                    "Your current identity's connection will be cleanly disconnected and " +
                        "this one activated instead — only one identity is ever active at a " +
                        "time. Nothing is deleted; you can switch back anytime.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = target.id
                    switchTarget = null
                    scope.launch {
                        busy = true
                        try {
                            repository.switchActiveIdentity(id)
                        } catch (e: Exception) {
                            errorMessage = e.message ?: "Could not switch identity"
                        }
                        busy = false
                    }
                }) { Text("Switch") }
            },
            dismissButton = {
                TextButton(onClick = { switchTarget = null }) { Text("Cancel") }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${target.name}\"?") },
            text = {
                Text(
                    // Real, permanent cascade delete — see
                    // orchestrator.py's delete_identity() own doc
                    // comment: this replaced an earlier "history stays
                    // recoverable via re-import" design, per explicit
                    // direction. Export first (this row's own "Save to
                    // device"/"Share" actions) if the keypair itself is
                    // worth keeping — that only ever preserved the
                    // identity, never a message/contact backup.
                    (if (target.isActive) {
                        "This is your active identity. Deleting it switches you to another " +
                            "identity (creating a fresh one if this was your only one). "
                    } else {
                        ""
                    }) + "All of its messages, contacts, and favorites are permanently " +
                        "deleted too — this can't be undone. Export the identity first if " +
                        "you want to keep the keypair itself.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = target.id
                    deleteTarget = null
                    scope.launch {
                        busy = true
                        try {
                            repository.deleteIdentity(id)
                        } catch (e: Exception) {
                            errorMessage = e.message ?: "Could not delete identity"
                        }
                        busy = false
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    renameTarget?.let { target ->
        RenameIdentityDialog(
            current = target.name,
            onRename = { name ->
                scope.launch { repository.renameIdentity(target.id, name) }
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    iconTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { iconTarget = null },
            title = { Text("Icon for \"${target.name}\"") },
            text = {
                IconAppearanceEditor(
                    current = target.iconAppearance,
                    onSave = { glyph, fg, bg ->
                        scope.launch { repository.setIdentityIcon(target.id, glyph, fg, bg) }
                        iconTarget = null
                    },
                    onCancel = { iconTarget = null },
                )
            },
            confirmButton = {},
        )
    }

    addressTarget?.let { target ->
        // Real content moved here from Settings' now-removed "Addresses"
        // section, per explicit direction — tapping an identity is now
        // where you see its own LXMF address/identity hash/QR, rather
        // than a separate section that only ever showed the *active*
        // identity's addresses. AddressRow/AddressQrDialog promoted
        // (non-private) from SettingsScreen.kt for exactly this reuse.
        var showQrDialog by remember(target.id) { mutableStateOf(false) }
        val canShowQr = target.lxmfAddress != null && target.publicKeyHex != null
        AlertDialog(
            onDismissRequest = { addressTarget = null },
            title = { Text(target.name) },
            text = {
                Column {
                    AddressRow(
                        label = "LXMF address",
                        value = target.lxmfAddress,
                        onShowQr = if (canShowQr) { { showQrDialog = true } } else null,
                    )
                    AddressRow(label = "Identity hash", value = target.id)
                }
            },
            confirmButton = {
                TextButton(onClick = { addressTarget = null }) { Text("Close") }
            },
        )
        if (showQrDialog && canShowQr) {
            AddressQrDialog(
                address = target.lxmfAddress,
                publicKeyHex = target.publicKeyHex,
                identityHash = target.id,
                onDismiss = { showQrDialog = false },
            )
        }
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Error") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) { Text("OK") }
            },
        )
    }

    statusMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { statusMessage = null },
            title = { Text("Done") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { statusMessage = null }) { Text("OK") }
            },
        )
    }
}

@Composable
private fun IdentityRow(
    identity: Identity,
    onClick: () -> Unit,
    onShowAddresses: () -> Unit,
    onEditIcon: () -> Unit,
    onRename: () -> Unit,
    onSaveToDevice: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            IdentityIconPreview(appearance = identity.iconAppearance, onClick = onEditIcon)
            // "Active" as a small badge on the avatar itself (top-start
            // corner — IdentityIconPreview's own edit-pencil badge
            // already owns bottom-end), not inline text next to the
            // name — a real on-device bug found+fixed: inline text had
            // no defined width to avoid competing with the name for
            // space, so a longer identity name pushed "Active" into
            // wrapping one character per line.
            if (identity.isActive) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .align(Alignment.TopStart)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Active",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onShowAddresses),
        ) {
            Text(
                text = identity.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = identity.lxmfAddress ?: identity.id,
                style = MaterialTheme.typography.labelSmall,
                color = NomadTextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!identity.isActive) {
            TextButton(onClick = onClick) { Text("Switch") }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                // Two separate real actions: "Save as…" opens a real
                // destination picker (ACTION_CREATE_DOCUMENT) so the file
                // lands wherever the user actually chooses, matching
                // import's own "browse to wherever" picker on the read
                // side — per explicit direction ("make it a real save
                // as"), replacing an earlier version that always wrote
                // straight into Downloads with no choice in the matter.
                // "Share" is a separate, unrelated real action — still
                // the share sheet, for handing the file to another app
                // directly rather than saving a local copy first.
                DropdownMenuItem(text = { Text("Save as…") }, onClick = { menuOpen = false; onSaveToDevice() })
                DropdownMenuItem(text = { Text("Share") }, onClick = { menuOpen = false; onShare() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
            }
        }
    }
}

@Composable
private fun CreateIdentityDialog(onCreate: (name: String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New identity") },
        text = {
            Column {
                Text(
                    "Leave blank for a fun auto-generated name — you can rename it anytime.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NomadTextDim,
                )
                Spacer(modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name (optional)") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RenameIdentityDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename identity") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { val trimmed = name.trim(); if (trimmed.isNotEmpty()) onRename(trimmed) },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
