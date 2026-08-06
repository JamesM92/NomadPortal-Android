package com.jamesm92.nomadportal.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jamesm92.micron2compose.compose.MicronPage
import com.jamesm92.micron2compose.parser.ConvertResult
import com.jamesm92.micron2compose.parser.LinkTarget
import com.jamesm92.micron2compose.parser.MicronConverter
import com.jamesm92.nomadportal.data.browsing.BrowserRepository
import com.jamesm92.nomadportal.data.browsing.PageAddress

/**
 * The actual page-content browser: address bar, back/forward (an in-screen
 * history stack, separate from Compose Navigation's own back stack — the
 * nav-level back button in the top bar returns to the node list, these
 * arrows move within this node's browsing history), and [MicronPage]
 * rendering.
 *
 * **Link handling is real for two of the three cases** the handoff doc's
 * "Link activation safety" section requires:
 * - Internal (`hash://...`) links navigate within this screen's history.
 * - External (`http(s)://...`) links show [LinkWarningDialog] before
 *   opening an external browser — never silent immediate activation.
 * - **File-download links can't be handled yet** — see [LinkWarningDialog]'s
 *   doc comment for why (micron2compose's `defaultUrlResolver` currently
 *   makes them indistinguishable from a bare next-heading-jump `"#"`).
 *   Bare-`"#"` targets are currently treated as inert no-ops rather than
 *   guessed at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    repository: BrowserRepository,
    startAddress: PageAddress,
    onBack: () -> Unit,
) {
    val converter = remember { MicronConverter() }
    val uriHandler = LocalUriHandler.current

    var history by remember { mutableStateOf(listOf(startAddress)) }
    var historyIndex by remember { mutableStateOf(0) }
    val currentAddress = history[historyIndex]

    var addressBarText by remember(currentAddress) { mutableStateOf(currentAddress.toUrl()) }
    var result by remember { mutableStateOf<ConvertResult?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var pendingWarning by remember { mutableStateOf<PendingLinkWarning?>(null) }
    var scrollToAnchor by remember { mutableStateOf<String?>(null) }

    fun navigateTo(address: PageAddress) {
        history = history.take(historyIndex + 1) + address
        historyIndex = history.lastIndex
    }

    fun handleLink(target: LinkTarget) {
        when {
            target.url.startsWith("http://") || target.url.startsWith("https://") ->
                pendingWarning = PendingLinkWarning.ExternalWeb(target.url)

            target.url.startsWith("hash://") -> {
                val address = PageAddress.fromUrl(target.url)
                if (address != null) navigateTo(address)
            }

            target.url.startsWith("#") && target.url.length > 1 ->
                scrollToAnchor = target.url.removePrefix("#")

            // Bare "#" (next-heading-jump) and anything else (including a
            // /file/ link masquerading as bare "#" — see this file's doc
            // comment) — no-op rather than guessing.
            else -> Unit
        }
    }

    LaunchedEffect(currentAddress) {
        loadError = null
        result = null
        try {
            val source = repository.fetchPage(currentAddress)
            result = converter.convert(source, nodeHash = currentAddress.nodeHash, basePath = currentAddress.path)
        } catch (e: Exception) {
            loadError = e.message ?: "Failed to load page"
        }
    }

    pendingWarning?.let { warning ->
        LinkWarningDialog(
            warning = warning,
            onConfirm = {
                if (warning is PendingLinkWarning.ExternalWeb) uriHandler.openUri(warning.url)
                pendingWarning = null
            },
            onDismiss = { pendingWarning = null },
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(currentAddress.nodeHash.take(12) + "…") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to nodes")
                        }
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = { if (historyIndex > 0) historyIndex-- }, enabled = historyIndex > 0) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back in history")
                    }
                    IconButton(
                        onClick = { if (historyIndex < history.lastIndex) historyIndex++ },
                        enabled = historyIndex < history.lastIndex,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward in history")
                    }
                    OutlinedTextField(
                        value = addressBarText,
                        onValueChange = { addressBarText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            PageAddress.fromUrl(addressBarText)?.let(::navigateTo)
                        }),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                loadError != null -> Text(
                    text = "Couldn't load this page: $loadError",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
                result != null -> MicronPage(
                    result = result!!,
                    readOnly = false,
                    scrollToAnchor = scrollToAnchor,
                    onLinkClick = ::handleLink,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}
