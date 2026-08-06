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
import com.jamesm92.nomadportal.ui.theme.NomadMono

/**
 * The actual page-content browser: address bar, back/forward (an in-screen
 * history stack, separate from Compose Navigation's own back stack — the
 * nav-level back button in the top bar returns to the node list, these
 * arrows move within this node's browsing history), and [MicronPage]
 * rendering (in [NomadMono], per porting-notes.md §5's Braille/box-drawing
 * glyph requirement — micron2compose exposes `fontFamily`/
 * `monospaceFontFamily` for exactly this since v0.1.0).
 *
 * **Link handling covers all three "Link activation safety" cases** from
 * the handoff doc:
 * - Internal (`hash://...`) links navigate within this screen's history.
 * - External (`http(s)://...`) and file-download (`LinkTarget.isFileDownload`)
 *   links both show [LinkWarningDialog] before activating — never silent.
 *
 * File-download confirmation shows the filename but doesn't actually fetch
 * anything yet on "Download" — real file transfer needs the RNS Link layer
 * from the core extraction (sequencing step 1, not started).
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
            // Checked first: a file-download link is still a real hash://
            // (or http(s)://) URL under the hood — isFileDownload is what
            // distinguishes it, not the URL's own prefix.
            target.isFileDownload ->
                pendingWarning = PendingLinkWarning.FileDownload(
                    url = target.url,
                    fileName = target.url.substringAfterLast('/'),
                )

            target.url.startsWith("http://") || target.url.startsWith("https://") ->
                pendingWarning = PendingLinkWarning.ExternalWeb(target.url)

            target.url.startsWith("hash://") -> {
                val address = PageAddress.fromUrl(target.url)
                if (address != null) navigateTo(address)
            }

            target.url.startsWith("#") && target.url.length > 1 ->
                scrollToAnchor = target.url.removePrefix("#")

            // Bare "#" (jump to the next heading after this link) — no
            // block-index-lookup for "next heading from here" is exposed
            // by ConvertResult, so this is a no-op rather than a partial
            // implementation. Low priority: cosmetic, not a safety gap.
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
                when (warning) {
                    is PendingLinkWarning.ExternalWeb -> uriHandler.openUri(warning.url)
                    // TODO(core extraction, sequencing step 1): actually
                    // fetch the file over an RNS Link once that layer
                    // exists. No-op for now — there's nothing to fetch
                    // from yet.
                    is PendingLinkWarning.FileDownload -> Unit
                }
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
                    fontFamily = NomadMono,
                    monospaceFontFamily = NomadMono,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}
