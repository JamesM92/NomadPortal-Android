package com.jamesm92.nomadportal.ui.hosting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.hosting.SiteFileEntry
import com.jamesm92.nomadportal.data.hosting.SiteFileRepository
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import kotlinx.coroutines.launch

/**
 * File nav for the hosted node's pages directory — phase 2 of the
 * hosting feature (see the nomadportal-android-hosted-node memory).
 * Plain directory browsing (not a recursive tree view): [currentPath]
 * is the folder currently open, "up" pops one segment, matching a
 * normal mobile file manager rather than an all-at-once tree.
 *
 * [repository] has no push mechanism (see its own doc comment — nothing
 * outside this app's actions ever mutates this directory), so this
 * screen re-lists explicitly after every mutating action rather than
 * polling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteFilesScreen(
    repository: SiteFileRepository,
    onOpenPage: (path: String) -> Unit,
    onBack: () -> Unit,
) {
    var currentPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<SiteFileEntry>>(emptyList()) }
    var refreshToken by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentPath, refreshToken) {
        entries = repository.listEntries(currentPath)
    }
    fun refresh() { refreshToken++ }

    var addMenuExpanded by remember { mutableStateOf(false) }
    var creatingPage by remember { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SiteFileEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<SiteFileEntry?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun joinPath(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = {
                    Text(
                        text = if (currentPath.isEmpty()) "Pages" else currentPath.substringAfterLast('/'),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentPath.isEmpty()) {
                            onBack()
                        } else {
                            currentPath = currentPath.substringBeforeLast('/', "")
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { addMenuExpanded = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "New")
                        }
                        DropdownMenu(expanded = addMenuExpanded, onDismissRequest = { addMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("New page") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null) },
                                onClick = { addMenuExpanded = false; creatingPage = true },
                            )
                            DropdownMenuItem(
                                text = { Text("New folder") },
                                leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                                onClick = { addMenuExpanded = false; creatingFolder = true },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            errorText?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.85f,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (entries.isEmpty()) {
                Text(
                    text = "Nothing here yet — use + to add a page or folder.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NomadTextDim,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.path }) { entry ->
                    SiteFileRow(
                        entry = entry,
                        onClick = {
                            if (entry.isDirectory) currentPath = entry.path else onOpenPage(entry.path)
                        },
                        onRename = { renaming = entry },
                        onDelete = { pendingDelete = entry },
                    )
                }
            }
        }
    }

    if (creatingPage) {
        NameEntryDialog(
            title = "New page",
            placeholder = "page-name",
            onDismiss = { creatingPage = false },
            onConfirm = { name ->
                creatingPage = false
                val filename = if (name.endsWith(".mu")) name else "$name.mu"
                scope.launch {
                    if (repository.createPage(joinPath(currentPath, filename))) {
                        refresh()
                    } else {
                        errorText = "Couldn't create that page — name may already be in use."
                    }
                }
            },
        )
    }

    if (creatingFolder) {
        NameEntryDialog(
            title = "New folder",
            placeholder = "folder-name",
            onDismiss = { creatingFolder = false },
            onConfirm = { name ->
                creatingFolder = false
                scope.launch {
                    if (repository.createFolder(joinPath(currentPath, name))) {
                        refresh()
                    } else {
                        errorText = "Couldn't create that folder — name may already be in use."
                    }
                }
            },
        )
    }

    renaming?.let { entry ->
        NameEntryDialog(
            title = "Rename",
            placeholder = entry.name,
            initialValue = entry.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                val newName = if (!entry.isDirectory && !name.endsWith(".mu")) "$name.mu" else name
                val newPath = joinPath(currentPath, newName)
                scope.launch {
                    if (repository.rename(entry.path, newPath)) {
                        refresh()
                    } else {
                        errorText = "Couldn't rename — a file/folder with that name may already exist."
                    }
                }
            },
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${if (entry.isDirectory) "folder" else "page"}?") },
            text = {
                Text(
                    if (entry.isDirectory) {
                        "\"${entry.name}\" and everything inside it will be permanently removed from this device — this can't be undone."
                    } else {
                        "\"${entry.name}\" will be permanently removed from this device — this can't be undone."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (repository.delete(entry.path)) refresh() else errorText = "Couldn't delete that."
                    }
                    pendingDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SiteFileRow(
    entry: SiteFileEntry,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.secondary else NomadTextDim,
        )
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                    onClick = { menuExpanded = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = { menuExpanded = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun NameEntryDialog(
    title: String,
    placeholder: String,
    initialValue: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
