package com.jamesm92.nomadportal.ui.hosting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jamesm92.micron2compose.compose.MicronBlock as RealMicronBlockView
import com.jamesm92.micron2compose.parser.MicronConverter
import com.jamesm92.nomadportal.data.hosting.CharStyle
import com.jamesm92.nomadportal.data.hosting.MicronAlign
import com.jamesm92.nomadportal.data.hosting.MicronBlock
import com.jamesm92.nomadportal.data.hosting.adjustCharStyles
import com.jamesm92.nomadportal.data.hosting.applyBackgroundColor
import com.jamesm92.nomadportal.data.hosting.applyColor
import com.jamesm92.nomadportal.data.hosting.toAnnotatedString
import com.jamesm92.nomadportal.data.hosting.toMicronLine
import com.jamesm92.nomadportal.data.hosting.toggleCharStyle
import com.jamesm92.nomadportal.ui.components.CompactTextField
import com.jamesm92.nomadportal.ui.components.MicronColorPicker
import com.jamesm92.nomadportal.ui.theme.NomadAccent
import com.jamesm92.nomadportal.ui.theme.NomadBg3
import com.jamesm92.nomadportal.ui.theme.NomadMono
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import java.util.UUID

/**
 * WYSIWYG mode for the hosted-page editor — see
 * `com.jamesm92.nomadportal.data.hosting.MicronRichText`'s own doc
 * comment for the underlying block model and exactly what's
 * deliberately not modeled here (tables/literal blocks/comments are
 * round-trip-safe but not toolbar-creatable — raw mode,
 * [SitePageEditorScreen]'s other half, is the escape hatch).
 *
 * **Every block renders through the real `micron2compose` renderer
 * except the one currently being typed into.** Per this app's own
 * node-rendering-parity requirement: a *second*, hand-rolled Compose
 * rendering of the same rules would only ever be an approximation,
 * proven live this session (its heading colors visibly didn't match
 * real NomadNet's own dark-theme palette). So each unfocused block is
 * round-tripped — [MicronBlock.Paragraph.toMicronLine]/`rawText`
 * serialized, then re-parsed by `micron2compose`'s own
 * `MicronConverter` and rendered by its own `MicronBlock` composable —
 * giving an exact, not approximate, preview with zero extra UI mode.
 * This is display-only and carries no round-trip risk: the block's own
 * `text`/`charStyles`/`rawText` (never this parsed copy) stay the one
 * source of truth serialized on save. Only the block currently focused
 * swaps to an editable `BasicTextField`, since real typing needs a
 * cursor/selection/IME that a read-only `Text` can't provide — tapping
 * any block (including a divider or raw-passthrough block) focuses it,
 * shown via a highlighted row background, and requests real keyboard
 * focus for it.
 *
 * The toolbar is organized as **category tabs**, not one flat icon
 * row — Format / foreground color / highlight (background) color /
 * link / align / structure each expand their own panel below the tab
 * row rather than all fighting for space in a single line. Character-
 * level actions (bold/italic/underline/both colors) require an actual
 * text *selection* — there's no "type in bold mode" cursor state,
 * deliberately, since implementing that correctly (tracking a pending
 * style and intercepting exactly which characters an IME/autocomplete
 * insertion actually added) is a much bigger, buggier problem than
 * this editor takes on. Heading level, alignment, and block add/delete
 * are block-level instead — no selection needed.
 *
 * No block reordering in v1 either — blocks stay in document order,
 * only "add at the end" and "delete the focused one" are offered.
 * Simpler, consistent with node hosting's own "keep the phone-hosted
 * site simple" direction elsewhere in this feature.
 *
 * Link insertion is intentionally minimal: it inserts real Micron link
 * markup (`[label`destination]`) as plain text at the selection/cursor.
 * It *does* render as a real, correctly-styled link once the block is
 * unfocused (that's exactly what the real-rendering above gives for
 * free) — it just isn't a richly editable object while focused, only
 * literal text.
 */
@Composable
fun RichTextPageEditor(
    blocks: List<MicronBlock>,
    onBlocksChange: (List<MicronBlock>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focusedBlockId by remember { mutableStateOf<String?>(null) }
    var focusedSelection by remember { mutableStateOf(TextRange.Zero) }
    var activePanel by remember { mutableStateOf<ToolbarPanel?>(null) }

    val focusedParagraph = blocks.filterIsInstance<MicronBlock.Paragraph>()
        .find { it.id == focusedBlockId }
    val hasSelection = focusedSelection.min != focusedSelection.max

    fun updateBlock(id: String, transform: (MicronBlock.Paragraph) -> MicronBlock.Paragraph) {
        onBlocksChange(
            blocks.map { block ->
                if (block is MicronBlock.Paragraph && block.id == id) transform(block) else block
            },
        )
    }

    fun toggleSelectionStyle(get: (CharStyle) -> Boolean, set: (CharStyle, Boolean) -> CharStyle) {
        val id = focusedBlockId ?: return
        val (start, end) = focusedSelection.let { it.min to it.max }
        if (start == end) return
        updateBlock(id) { it.copy(charStyles = toggleCharStyle(it.charStyles, start, end, get, set)) }
    }

    fun applyForeground(color: Color?) {
        val id = focusedBlockId ?: return
        val (start, end) = focusedSelection.let { it.min to it.max }
        if (start == end) return
        updateBlock(id) { it.copy(charStyles = applyColor(it.charStyles, start, end, color)) }
    }

    fun applyBackground(color: Color?) {
        val id = focusedBlockId ?: return
        val (start, end) = focusedSelection.let { it.min to it.max }
        if (start == end) return
        updateBlock(id) { it.copy(charStyles = applyBackgroundColor(it.charStyles, start, end, color)) }
    }

    fun insertLink(destination: String, label: String) {
        val id = focusedBlockId ?: return
        val block = focusedParagraph ?: return
        if (destination.isBlank()) return
        val (start, end) = focusedSelection.let { it.min to it.max }
        val effectiveLabel = label.ifBlank { "link" }
        // Real Micron link syntax needs a *leading backtick* before the
        // bracket ("`[label`url]") -- confirmed directly against
        // micron2compose's own InlineParser.kt dispatch, which only
        // recognizes '[' as a link start when the preceding character is
        // a backtick escape (`c == '`'` then `nc == '['`). A bare
        // "[label`url]" with no leading backtick is just literal visible
        // text to any real Micron parser, which is exactly the "renders
        // as plain text" bug this fixes.
        val linkText = "`[$effectiveLabel`$destination]"
        val newText = block.text.replaceRange(start, end, linkText)
        val newStyles = adjustCharStyles(block.charStyles, block.text, newText)
        updateBlock(id) { it.copy(text = newText, charStyles = newStyles) }
        focusedSelection = TextRange(start + linkText.length)
    }

    fun deleteFocusedBlock() {
        val id = focusedBlockId ?: return
        if (blocks.size <= 1) return
        onBlocksChange(blocks.filterNot { it.id == id })
        focusedBlockId = null
        focusedSelection = TextRange.Zero
    }

    // The focused block always gets scrolled to a fixed clearance below
    // the toolbar rather than letting it land flush against the very
    // top of the list -- real on-device report: Android's own native
    // Cut/Copy/Paste/Translate selection popup was rendering directly
    // on top of (and blocking taps to) this editor's own custom
    // formatting toolbar whenever the selection was near the top of
    // the screen. Compose has no direct way to tell that system popup
    // which side to prefer or where to draw itself, but ensuring
    // there's always genuine vertical room above the focused block
    // means the platform's own above/below placement logic always has
    // somewhere clear to put it, on either side, without needing to
    // predict or fight that placement heuristic directly -- and unlike
    // suppressing the popup outright, Cut/Copy/Paste/Translate all
    // keep working normally.
    // Starts scrolled past the leading spacer (item 0) so the very first
    // block sits flush under the toolbar by default, same as before the
    // spacer existed -- the spacer is scroll *headroom* for the
    // clearance trick below, not something that should visibly push
    // every block down all the time. A plain `LaunchedEffect(Unit) {
    // scrollToItem(1) }` here rather than `rememberLazyListState`'s own
    // `initialFirstVisibleItemIndex` param -- confirmed via real
    // on-device testing that the latter doesn't reliably stick (still
    // showed the spacer's full height as visible content), while an
    // explicit imperative scroll once real items exist does.
    val listState = rememberLazyListState()
    val toolbarClearancePx = with(LocalDensity.current) { TOOLBAR_CLEARANCE.roundToPx() }
    LaunchedEffect(Unit) {
        if (blocks.isNotEmpty()) listState.scrollToItem(1)
    }
    LaunchedEffect(focusedBlockId) {
        val index = blocks.indexOfFirst { it.id == focusedBlockId }
        if (index >= 0) {
            // +1: LazyColumn item index, not `blocks` list index -- the
            // leading Spacer below occupies index 0.
            listState.animateScrollToItem(index + 1, scrollOffset = -toolbarClearancePx)
        }
    }

    Column(modifier = modifier) {
        ToolbarTabRow(
            activePanel = activePanel,
            onSelectPanel = { activePanel = if (activePanel == it) null else it },
            selectionActionsEnabled = hasSelection,
            blockActionsEnabled = focusedBlockId != null,
        )

        when (activePanel) {
            ToolbarPanel.FORMAT -> FormatPanel(
                enabled = hasSelection,
                onBold = { toggleSelectionStyle({ it.bold }, { s, v -> s.copy(bold = v) }) },
                onItalic = { toggleSelectionStyle({ it.italic }, { s, v -> s.copy(italic = v) }) },
                onUnderline = { toggleSelectionStyle({ it.underline }, { s, v -> s.copy(underline = v) }) },
            )
            ToolbarPanel.FOREGROUND -> {
                val current = focusedParagraph?.charStyles?.getOrNull(focusedSelection.min)?.color
                    ?: MaterialTheme.colorScheme.primary
                ColorPanel(
                    enabled = hasSelection,
                    selected = current,
                    onSelect = { applyForeground(it) },
                    onClear = { applyForeground(null) },
                )
            }
            ToolbarPanel.BACKGROUND -> {
                val current = focusedParagraph?.charStyles?.getOrNull(focusedSelection.min)?.background
                    ?: MaterialTheme.colorScheme.secondary
                ColorPanel(
                    enabled = hasSelection,
                    selected = current,
                    onSelect = { applyBackground(it) },
                    onClear = { applyBackground(null) },
                )
            }
            ToolbarPanel.LINK -> {
                val selectedText = focusedParagraph?.let { p ->
                    val (start, end) = focusedSelection.let { it.min to it.max }
                    if (start in 0..p.text.length && end in start..p.text.length) p.text.substring(start, end) else ""
                } ?: ""
                LinkPanel(
                    enabled = focusedParagraph != null,
                    initialLabel = selectedText,
                    onInsert = { destination, label -> insertLink(destination, label); activePanel = null },
                )
            }
            ToolbarPanel.ALIGN -> AlignPanel(
                enabled = focusedBlockId != null,
                current = focusedParagraph?.align ?: MicronAlign.DEFAULT,
                onAlign = { align ->
                    val id = focusedBlockId ?: return@AlignPanel
                    updateBlock(id) { it.copy(align = align) }
                },
            )
            ToolbarPanel.STRUCTURE -> StructurePanel(
                headingEnabled = focusedParagraph != null,
                deleteEnabled = focusedBlockId != null && blocks.size > 1,
                currentHeadingLevel = focusedParagraph?.headingLevel ?: 0,
                onHeading = { level ->
                    val id = focusedBlockId ?: return@StructurePanel
                    updateBlock(id) { it.copy(headingLevel = if (it.headingLevel == level) 0 else level) }
                },
                onAddParagraph = {
                    val newBlock = MicronBlock.Paragraph(id = UUID.randomUUID().toString())
                    onBlocksChange(blocks + newBlock)
                    focusedBlockId = newBlock.id
                    focusedSelection = TextRange.Zero
                },
                onDelete = { deleteFocusedBlock() },
            )
            null -> {}
        }

        HorizontalDivider()

        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            // Always-present leading spacer, exactly TOOLBAR_CLEARANCE
            // tall -- without real scrollable headroom above it, the
            // *first* block on a page (or the only block, on a short
            // one) has nowhere to scroll "into" when focused, so the
            // LaunchedEffect(focusedBlockId) scroll below is a no-op for
            // exactly the case that matters most (a short/new page,
            // confirmed by real on-device testing: the native selection
            // toolbar still collided with this editor's own toolbar for
            // a single-block page before this spacer existed). This
            // makes the clearance trick work uniformly regardless of
            // which block is focused.
            item { Spacer(modifier = Modifier.height(TOOLBAR_CLEARANCE)) }
            items(blocks, key = { it.id }) { block ->
                BlockRow(
                    block = block,
                    isFocused = block.id == focusedBlockId,
                    selection = if (block.id == focusedBlockId) focusedSelection else TextRange.Zero,
                    onFocus = {
                        // Reset selection on every focus-*gain* event (this
                        // only ever fires once per gain, never per-keystroke
                        // -- see this function's own call sites) so a stale
                        // selection range from whichever block was focused
                        // before never carries over into this one.
                        focusedBlockId = block.id
                        focusedSelection = TextRange.Zero
                    },
                    onSelectionChange = { focusedSelection = it },
                    onChange = { updated ->
                        onBlocksChange(blocks.map { if (it.id == updated.id) updated else it })
                    },
                )
                // No per-block HorizontalDivider here, deliberately -- an
                // editor-chrome line between *every* block would be
                // indistinguishable from (and visually clutter) a real
                // Divider block's own actual rendering now that unfocused
                // blocks render through micron2compose for real. The
                // focused-row tint is the only "this is a block boundary"
                // signal left, and only for whichever one is actually
                // being edited.
            }
        }
    }
}

private enum class ToolbarPanel { FORMAT, FOREGROUND, BACKGROUND, LINK, ALIGN, STRUCTURE }

@Composable
private fun ToolbarTabRow(
    activePanel: ToolbarPanel?,
    onSelectPanel: (ToolbarPanel) -> Unit,
    selectionActionsEnabled: Boolean,
    blockActionsEnabled: Boolean,
) {
    @Composable
    fun tint(panel: ToolbarPanel) = if (activePanel == panel) MaterialTheme.colorScheme.primary else NomadTextDim

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onSelectPanel(ToolbarPanel.FORMAT) }, enabled = selectionActionsEnabled) {
            Icon(Icons.Filled.FormatBold, contentDescription = "Text style", tint = tint(ToolbarPanel.FORMAT))
        }
        IconButton(onClick = { onSelectPanel(ToolbarPanel.FOREGROUND) }, enabled = selectionActionsEnabled) {
            Icon(Icons.Filled.FormatColorText, contentDescription = "Text color", tint = tint(ToolbarPanel.FOREGROUND))
        }
        IconButton(onClick = { onSelectPanel(ToolbarPanel.BACKGROUND) }, enabled = selectionActionsEnabled) {
            Icon(Icons.Filled.FormatColorFill, contentDescription = "Highlight color", tint = tint(ToolbarPanel.BACKGROUND))
        }
        IconButton(onClick = { onSelectPanel(ToolbarPanel.LINK) }, enabled = blockActionsEnabled) {
            Icon(Icons.Filled.Link, contentDescription = "Insert link", tint = tint(ToolbarPanel.LINK))
        }
        IconButton(onClick = { onSelectPanel(ToolbarPanel.ALIGN) }, enabled = blockActionsEnabled) {
            Icon(Icons.AutoMirrored.Filled.FormatAlignLeft, contentDescription = "Alignment", tint = tint(ToolbarPanel.ALIGN))
        }
        IconButton(onClick = { onSelectPanel(ToolbarPanel.STRUCTURE) }, enabled = blockActionsEnabled) {
            Icon(Icons.Filled.Title, contentDescription = "Heading / block", tint = tint(ToolbarPanel.STRUCTURE))
        }
        // "Add paragraph" stays a direct action outside the tab system --
        // it's the one action that never needs a focused block first.
        IconButton(onClick = { onSelectPanel(ToolbarPanel.STRUCTURE) }) {
            Icon(Icons.Filled.Add, contentDescription = "Add paragraph")
        }
    }
}

/** Shared frame for every expandable panel: dims + explains itself when
 * its actions aren't currently applicable (no selection / no focused
 * block) rather than just silently doing nothing on tap. */
@Composable
private fun PanelFrame(enabled: Boolean, disabledHint: String, content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().background(NomadBg3).padding(12.dp)) {
        if (enabled) {
            content()
        } else {
            Text(disabledHint, style = MaterialTheme.typography.bodyMedium, color = NomadTextDim)
        }
    }
}

@Composable
private fun FormatPanel(enabled: Boolean, onBold: () -> Unit, onItalic: () -> Unit, onUnderline: () -> Unit) {
    PanelFrame(enabled, "Select some text first to format it.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBold) { Icon(Icons.Filled.FormatBold, contentDescription = "Bold") }
            IconButton(onClick = onItalic) { Icon(Icons.Filled.FormatItalic, contentDescription = "Italic") }
            IconButton(onClick = onUnderline) { Icon(Icons.Filled.FormatUnderlined, contentDescription = "Underline") }
        }
    }
}

@Composable
private fun ColorPanel(enabled: Boolean, selected: Color, onSelect: (Color) -> Unit, onClear: () -> Unit) {
    PanelFrame(enabled, "Select some text first to color it.") {
        MicronColorPicker(selected = selected, onSelect = onSelect, onClear = onClear)
    }
}

@Composable
private fun LinkPanel(enabled: Boolean, initialLabel: String, onInsert: (destination: String, label: String) -> Unit) {
    var label by remember(initialLabel) { mutableStateOf(initialLabel) }
    var destination by remember { mutableStateOf("") }
    PanelFrame(enabled, "Tap into a paragraph first to insert a link there.") {
        Column {
            Text(
                "Same-node page (e.g. /page/other.mu) or another node's hash:path address",
                style = MaterialTheme.typography.bodySmall,
                color = NomadTextDim,
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactTextField(value = label, onValueChange = { label = it }, placeholder = "Label", modifier = Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactTextField(value = destination, onValueChange = { destination = it }, placeholder = "Destination", modifier = Modifier.weight(1f))
                TextButton(onClick = { onInsert(destination, label) }, enabled = destination.isNotBlank()) {
                    Text("Insert")
                }
            }
        }
    }
}

@Composable
private fun AlignPanel(enabled: Boolean, current: MicronAlign, onAlign: (MicronAlign) -> Unit) {
    PanelFrame(enabled, "Tap into a paragraph first to align it.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { onAlign(MicronAlign.DEFAULT) }) {
                Icon(
                    Icons.AutoMirrored.Filled.FormatAlignLeft,
                    contentDescription = "Align left",
                    tint = if (current == MicronAlign.DEFAULT) MaterialTheme.colorScheme.primary else NomadTextDim,
                )
            }
            IconButton(onClick = { onAlign(MicronAlign.CENTER) }) {
                Icon(
                    Icons.Filled.FormatAlignCenter,
                    contentDescription = "Align center",
                    tint = if (current == MicronAlign.CENTER) MaterialTheme.colorScheme.primary else NomadTextDim,
                )
            }
            IconButton(onClick = { onAlign(MicronAlign.RIGHT) }) {
                Icon(
                    Icons.AutoMirrored.Filled.FormatAlignRight,
                    contentDescription = "Align right",
                    tint = if (current == MicronAlign.RIGHT) MaterialTheme.colorScheme.primary else NomadTextDim,
                )
            }
        }
    }
}

@Composable
private fun StructurePanel(
    headingEnabled: Boolean,
    deleteEnabled: Boolean,
    currentHeadingLevel: Int,
    onHeading: (Int) -> Unit,
    onAddParagraph: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().background(NomadBg3).padding(12.dp)) {
        Column {
            if (headingEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Body", 1 to "H1", 2 to "H2", 3 to "H3").forEach { (level, label) ->
                        TextButton(onClick = { onHeading(level) }) {
                            Text(
                                label,
                                color = if (level == currentHeadingLevel) MaterialTheme.colorScheme.primary else NomadTextDim,
                            )
                        }
                    }
                }
            } else {
                Text(
                    "Tap into a paragraph first to set its heading level.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NomadTextDim,
                )
            }
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onAddParagraph) { Text("Add paragraph") }
                TextButton(onClick = onDelete, enabled = deleteEnabled) { Text("Delete block") }
            }
        }
    }
}

/** Subtle tint marking whichever row currently owns the toolbar's
 * block-level actions and (for a Paragraph/RawPassthrough) is the one
 * block rendered as an editable text field instead of real-rendered
 * content — replaces the old always-visible per-row delete icon, which
 * the actual on-device experience showed was just visual clutter on
 * every single line. */
private val FOCUSED_ROW_TINT = NomadAccent.copy(alpha = 0.08f)

/** How much vertical clearance the focused block is scrolled to keep
 * below the toolbar row (see [RichTextPageEditor]'s own doc comment on
 * why) — generous enough to clear the tab row plus a typical expanded
 * panel underneath it, not just the tab row alone, since a panel can
 * be open at the same time a block is focused. */
private val TOOLBAR_CLEARANCE = 160.dp

@Composable
private fun BlockRow(
    block: MicronBlock,
    isFocused: Boolean,
    selection: TextRange,
    onFocus: () -> Unit,
    onSelectionChange: (TextRange) -> Unit,
    onChange: (MicronBlock) -> Unit,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .background(if (isFocused) FOCUSED_ROW_TINT else Color.Transparent)

    when (block) {
        is MicronBlock.Paragraph -> ParagraphBlockRow(block, isFocused, selection, onFocus, onSelectionChange, onChange, rowModifier)
        // A divider has no content to edit at all -- always real-rendered,
        // "focused" only means "selected as the toolbar's delete target".
        is MicronBlock.Divider -> RealRenderedBlockRow(rawText = "-", onFocus = onFocus, modifier = rowModifier)
        is MicronBlock.RawPassthrough -> RawPassthroughBlockRow(block, isFocused, onFocus, onChange, rowModifier)
    }
}

/** Renders [rawText] through `micron2compose`'s own real parser +
 * renderer — see this file's own top doc comment on why (exact parity,
 * not a second approximation) and why it's safe (display-only; never
 * feeds back into the editable model). Tapping anywhere in the row
 * focuses its owning block instead of doing anything link-specific,
 * even if the content happens to include a real link — this is an
 * editing surface, not a browsing one. */
@Composable
private fun RealRenderedBlockRow(rawText: String, onFocus: () -> Unit, modifier: Modifier = Modifier) {
    val converter = remember { MicronConverter() }
    val parsedBlocks = remember(rawText) { converter.convert(rawText).blocks }
    Column(
        modifier = modifier.clickable(onClick = onFocus).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (parsedBlocks.isEmpty()) {
            // A blank line or a standalone "``" reset line -- micron2compose
            // emits no Block for either, but the row still needs a visible/
            // tappable presence.
            Text(" ", fontFamily = NomadMono, style = MaterialTheme.typography.bodyLarge)
        } else {
            parsedBlocks.forEach { parsed ->
                RealMicronBlockView(
                    block = parsed,
                    readOnly = true,
                    fontFamily = NomadMono,
                    monospaceFontFamily = NomadMono,
                    onLinkClick = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ParagraphBlockRow(
    block: MicronBlock.Paragraph,
    isFocused: Boolean,
    selection: TextRange,
    onFocus: () -> Unit,
    onSelectionChange: (TextRange) -> Unit,
    onChange: (MicronBlock) -> Unit,
    modifier: Modifier,
) {
    if (!isFocused) {
        RealRenderedBlockRow(rawText = block.toMicronLine(), onFocus = onFocus, modifier = modifier)
        return
    }

    // Always freshly derived from `block` (the single source of truth,
    // owned by the parent's `blocks` list) rather than held as its own
    // `remember`ed copy -- avoids needing to manually keep a local
    // TextFieldValue in sync whenever the toolbar (not this field
    // itself) changes this block's styling. Selection is the one piece
    // of UI-only state that can't come from `block` (it's not part of
    // the persisted document), tracked by the parent instead.
    val fieldValue = TextFieldValue(
        annotatedString = block.toAnnotatedString(),
        selection = selection.coerceIn(block.text.length),
    )
    val textAlign = when (block.align) {
        MicronAlign.CENTER -> TextAlign.Center
        MicronAlign.RIGHT -> TextAlign.Right
        MicronAlign.DEFAULT -> TextAlign.Start
    }
    // This block just became the focused one (including the instant a
    // brand-new block is created already-focused) -- grab real keyboard
    // focus for it. Without this, swapping from the real-rendered (read-
    // only Text) view into this BasicTextField wouldn't itself bring up
    // the keyboard; the user would have to tap a second time.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Row(modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
        BasicTextField(
            value = fieldValue,
            onValueChange = { new ->
                // No onFocus() here, deliberately -- onValueChange fires on
                // *every* keystroke and every selection-only change (e.g.
                // dragging to highlight text, no text edit at all), and
                // onFocus() resets focusedSelection to zero (needed when
                // actually switching to a *different* block, see this
                // field's own onFocusChanged below, which is the real
                // focus-gain signal and only fires once). Calling it here
                // too meant every keystroke briefly zeroed the selection
                // right before this function's own onSelectionChange call
                // set the correct value moments later -- a real regression
                // that broke both continued typing (past the first
                // character) and drag-to-select, confirmed on-device.
                val newText = new.annotatedString.text
                onSelectionChange(new.selection)
                if (newText != block.text) {
                    val updatedStyles = adjustCharStyles(block.charStyles, block.text, newText)
                    onChange(block.copy(text = newText, charStyles = updatedStyles))
                }
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                textAlign = textAlign,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = NomadMono, // matches the real-rendered (unfocused) view's own font, no swap-flicker
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) onFocus() },
        )
    }
}

private fun TextRange.coerceIn(maxLength: Int): TextRange =
    TextRange(start.coerceIn(0, maxLength), end.coerceIn(0, maxLength))

@Composable
private fun RawPassthroughBlockRow(
    block: MicronBlock.RawPassthrough,
    isFocused: Boolean,
    onFocus: () -> Unit,
    onChange: (MicronBlock) -> Unit,
    modifier: Modifier,
) {
    if (!isFocused) {
        RealRenderedBlockRow(rawText = block.rawText, onFocus = onFocus, modifier = modifier)
        return
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            text = "Editing raw markup — a table/literal block/comment/link this editor doesn't richly model",
            style = MaterialTheme.typography.labelSmall,
            color = NomadTextDim,
        )
        BasicTextField(
            value = block.rawText,
            onValueChange = { onChange(block.copy(rawText = it)) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = NomadMono, color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) onFocus() },
        )
    }
}
