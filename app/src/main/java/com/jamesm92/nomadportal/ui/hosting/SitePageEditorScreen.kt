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
import com.jamesm92.nomadportal.data.hosting.MicronBlock
import com.jamesm92.nomadportal.data.hosting.SiteFileRepository
import com.jamesm92.nomadportal.data.hosting.blocksToMicron
import com.jamesm92.nomadportal.data.hosting.parseMicronToBlocks
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import kotlinx.coroutines.launch

/** Which half of the dual-mode editor is currently driving the page's
 * authoritative content. Only one is "live" at a time — the other's
 * state is stale until the next toggle re-derives it (see
 * [SitePageEditorScreen]'s own doc comment). */
private enum class EditorMode { RICH, RAW }

/**
 * The hosted-page editor — dual-mode: a rendered WYSIWYG editing mode
 * ([RichTextPageEditor], backed by the [MicronBlock] model) and a raw
 * plain-text mode (this screen's own original phase-2 editor),
 * switchable via the toolbar icons at any time.
 *
 * There's no separate Preview mode — an earlier version of this screen
 * had one, backed by `micron2compose`'s own real renderer, specifically
 * because [RichTextPageEditor] used to paint everything itself (a
 * second, hand-rolled approximation of real Micron rendering). That
 * approximation is gone now: [RichTextPageEditor] renders every block
 * *except* the one currently being typed into through `micron2compose`
 * directly, so rich mode itself already carries the real-rendering
 * parity guarantee a dedicated Preview mode existed to provide — see
 * that composable's own doc comment. Nothing left for a third mode to
 * add.
 *
 * Each mode owns its own independent state (`blocks` / `rawDraft`)
 * rather than one being derived live from the other on every keystroke
 * — conversion only happens at the mode-switch boundary itself, via
 * [parseMicronToBlocks]/[blocksToMicron]. This matters for raw mode
 * specifically: reparsing into blocks on every keystroke would mint
 * fresh block IDs constantly (see [MicronBlock]'s `id` — used by
 * [RichTextPageEditor] for focus/selection tracking) and fight the
 * text field's own cursor position for no benefit, since raw mode
 * never needs block identity at all.
 *
 * Whichever mode is currently active is the one trusted at save time —
 * always serialized fresh via [blocksToMicron] (rich mode) or used
 * as-is (raw mode), never the other mode's possibly-stale copy.
 *
 * Monospace font in raw mode — this is markup source, not prose; a
 * monospace face makes the `` ` `` escape sequences Micron uses
 * actually legible (matches this app's own NomadMono theme font
 * already used for hash/address display elsewhere, and now also what
 * rich mode itself renders with — see [RichTextPageEditor]).
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
    var saving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(path) {
        val loaded = repository.readPage(path) ?: ""
        content = loaded
        rawDraft = loaded
        blocks = parseMicronToBlocks(loaded)
    }

    fun switchMode(target: EditorMode) {
        if (target == mode) return
        when (target) {
            EditorMode.RAW -> rawDraft = blocksToMicron(blocks)
            EditorMode.RICH -> blocks = parseMicronToBlocks(rawDraft)
        }
        mode = target
    }

    fun save() {
        val current = if (mode == EditorMode.RICH) blocksToMicron(blocks) else rawDraft
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
                    style = MaterialTheme.typography.bodyMedium,
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
                }
            }
        }
    }
}
