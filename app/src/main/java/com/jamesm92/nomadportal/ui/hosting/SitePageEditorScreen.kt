package com.jamesm92.nomadportal.ui.hosting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Visibility
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
import com.jamesm92.micron2compose.compose.MicronPage
import com.jamesm92.micron2compose.parser.MicronConverter
import com.jamesm92.nomadportal.data.hosting.MicronBlock
import com.jamesm92.nomadportal.data.hosting.SiteFileRepository
import com.jamesm92.nomadportal.data.hosting.blocksToMicron
import com.jamesm92.nomadportal.data.hosting.parseMicronToBlocks
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.theme.NomadMono
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import kotlinx.coroutines.launch

/** Which third of the triple-mode editor is currently driving the
 * page's authoritative content. Only one is "live" at a time — the
 * others' state is stale until the next toggle re-derives it (see
 * [SitePageEditorScreen]'s own doc comment). */
private enum class EditorMode { RICH, RAW, PREVIEW }

/**
 * The hosted-page editor — triple-mode per explicit design direction:
 * a rendered WYSIWYG editing mode ([RichTextPageEditor], backed by the
 * [MicronBlock] model), a raw plain-text mode (this screen's own
 * original phase-2 editor), and a true Preview mode, switchable via
 * the toolbar icons at any time.
 *
 * **Preview is a hard parity requirement, not just a nicety**: it
 * renders the current draft through `micron2compose`'s own
 * `MicronConverter`/`MicronPage` — the *exact* library and code path
 * this app's own node-browsing screen (`BrowserScreen`) uses to render
 * every other node's pages, and that any other real NomadNet client
 * would apply too (both verified directly against upstream
 * `MicronParser.py` — see the nomadportal-android-micron-syntax-
 * verified memory). Rich mode's own inline rendering
 * ([RichTextPageEditor]) is a separate, simpler, hand-rolled
 * approximation needed because it has to support live per-character
 * selection/cursor editing, which a read-only renderer can't — so it's
 * good for *editing*, but Preview is the only mode with an actual
 * parity guarantee: what you see there is what a real viewer sees,
 * because it's literally the same renderer, not a second
 * implementation of the same rules kept in sync by hand.
 *
 * Each mode owns its own independent state (`blocks` / `rawDraft` /
 * `previewText`) rather than being derived live from whichever mode is
 * actually being edited — conversion only happens at the mode-switch
 * boundary itself, via [parseMicronToBlocks]/[blocksToMicron]. This
 * matters for raw mode specifically: reparsing into blocks on every
 * keystroke would mint fresh block IDs constantly (see [MicronBlock]'s
 * `id` — used by [RichTextPageEditor] for focus/selection tracking)
 * and fight the text field's own cursor position for no benefit, since
 * raw mode never needs block identity at all. Preview never being
 * directly edited makes it the simplest case: switching *out* of
 * Preview never needs to consult it at all.
 *
 * Whichever mode was most recently edited is the one trusted at save
 * time — always serialized fresh via [blocksToMicron] (rich mode) or
 * used as-is (raw mode); Preview is read-only, so it's never the save
 * source itself.
 *
 * Monospace font in raw mode — this is markup source, not prose; a
 * monospace face makes the `` ` `` escape sequences Micron uses
 * actually legible (matches this app's own NomadMono theme font
 * already used for hash/address display elsewhere).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SitePageEditorScreen(
    repository: SiteFileRepository,
    path: String,
    onBack: () -> Unit,
) {
    var content by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(EditorMode.RICH) }
    var blocks by remember { mutableStateOf<List<MicronBlock>>(emptyList()) }
    var rawDraft by remember { mutableStateOf("") }
    var previewText by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val converter = remember { MicronConverter() }

    LaunchedEffect(path) {
        val loaded = repository.readPage(path) ?: ""
        content = loaded
        rawDraft = loaded
        blocks = parseMicronToBlocks(loaded)
        previewText = loaded
    }

    fun currentMicron(): String = when (mode) {
        EditorMode.RICH -> blocksToMicron(blocks)
        EditorMode.RAW -> rawDraft
        EditorMode.PREVIEW -> previewText // never edited directly, already accurate
    }

    fun switchMode(target: EditorMode) {
        if (target == mode) return
        val text = currentMicron()
        when (target) {
            EditorMode.RICH -> blocks = parseMicronToBlocks(text)
            EditorMode.RAW -> rawDraft = text
            EditorMode.PREVIEW -> previewText = text
        }
        mode = target
    }

    fun save() {
        val current = currentMicron()
        saving = true
        scope.launch {
            val ok = repository.writePage(path, current)
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
                    IconButton(onClick = { switchMode(EditorMode.RICH) }, enabled = content != null) {
                        Icon(
                            Icons.Filled.Brush,
                            contentDescription = "Rich text mode",
                            tint = if (mode == EditorMode.RICH) MaterialTheme.colorScheme.primary else NomadTextDim,
                        )
                    }
                    IconButton(onClick = { switchMode(EditorMode.RAW) }, enabled = content != null) {
                        Icon(
                            Icons.Filled.Code,
                            contentDescription = "Raw markup mode",
                            tint = if (mode == EditorMode.RAW) MaterialTheme.colorScheme.primary else NomadTextDim,
                        )
                    }
                    IconButton(onClick = { switchMode(EditorMode.PREVIEW) }, enabled = content != null) {
                        Icon(
                            Icons.Filled.Visibility,
                            contentDescription = "Preview (real node rendering)",
                            tint = if (mode == EditorMode.PREVIEW) MaterialTheme.colorScheme.primary else NomadTextDim,
                        )
                    }
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
                when (mode) {
                    EditorMode.RICH -> RichTextPageEditor(
                        blocks = blocks,
                        onBlocksChange = { blocks = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    EditorMode.RAW -> OutlinedTextField(
                        value = rawDraft,
                        onValueChange = { rawDraft = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        placeholder = { Text(">Page title\n\nContent goes here.") },
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    )
                    EditorMode.PREVIEW -> {
                        // basePath = this page's own relative path, so any
                        // relative links in the draft resolve the same way
                        // they would once actually served — nodeHash left
                        // blank (this preview isn't a real served node, no
                        // real hash to resolve `hash://` links against
                        // meaningfully); onLinkClick is a no-op since a
                        // preview isn't a real browsing session to navigate
                        // within, only a rendering check.
                        val result = remember(previewText, path) { converter.convert(previewText, basePath = path) }
                        MicronPage(
                            result = result,
                            readOnly = true,
                            fontFamily = NomadMono,
                            monospaceFontFamily = NomadMono,
                            onLinkClick = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
