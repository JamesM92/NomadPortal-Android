package com.jamesm92.nomadportal.ui.hosting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.hosting.SiteFileRepository
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import kotlinx.coroutines.launch

/**
 * Raw-Micron page editor — plain text in, plain text out, no formatting
 * assistance. This is phase 2's bridging piece (see the
 * nomadportal-android-hosted-node memory): it makes the whole browse
 * -> create -> edit -> save -> serve pipeline testable end-to-end
 * before the phase 3 rich-text WYSIWYG mode exists, and it isn't
 * throwaway work either — per explicit design direction, the finished
 * editor is *dual-mode* (switchable rendered/raw), and this screen is
 * exactly what that raw mode still needs to be underneath the rendered
 * one once it's added, not a separate thing to build twice.
 *
 * Monospace font — this is markup source, not prose; a monospace face
 * makes the `` ` `` escape sequences Micron uses actually legible
 * (matches this app's own NomadMono theme font already used for
 * hash/address display elsewhere).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SitePageEditorScreen(
    repository: SiteFileRepository,
    path: String,
    onBack: () -> Unit,
) {
    var content by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(path) {
        val loaded = repository.readPage(path)
        content = loaded ?: ""
        draft = loaded ?: ""
    }

    fun save() {
        saving = true
        scope.launch {
            val ok = repository.writePage(path, draft)
            saving = false
            errorText = if (ok) null else "Couldn't save this page."
        }
    }

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = {
                    Text(text = path.substringAfterLast('/'))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { save() }, enabled = !saving) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding()) {
            errorText?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.85f,
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (content != null) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text(">Page title\n\nContent goes here.") },
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
            }
        }
    }
}
