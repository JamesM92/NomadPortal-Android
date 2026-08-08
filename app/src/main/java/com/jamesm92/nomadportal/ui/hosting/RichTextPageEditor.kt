package com.jamesm92.nomadportal.ui.hosting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.hosting.CharStyle
import com.jamesm92.nomadportal.data.hosting.MicronAlign
import com.jamesm92.nomadportal.data.hosting.MicronBlock
import com.jamesm92.nomadportal.data.hosting.adjustCharStyles
import com.jamesm92.nomadportal.data.hosting.applyBackgroundColor
import com.jamesm92.nomadportal.data.hosting.applyColor
import com.jamesm92.nomadportal.data.hosting.toAnnotatedString
import com.jamesm92.nomadportal.data.hosting.toggleCharStyle
import com.jamesm92.nomadportal.ui.components.CompactTextField
import com.jamesm92.nomadportal.ui.components.MicronColorPicker
import com.jamesm92.nomadportal.ui.theme.NomadAccent
import com.jamesm92.nomadportal.ui.theme.NomadBg3
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import java.util.UUID

/**
 * WYSIWYG mode for the hosted-page editor — see
 * `com.jamesm92.nomadportal.data.hosting.MicronRichText`'s own doc
 * comment for the underlying block model and exactly what's
 * deliberately not modeled here (tables/literal blocks/comments are
 * round-trip-safe but not toolbar-creatable — raw mode,
 * [SitePageEditorScreen]'s other half, is the escape hatch). Text size
 * never varies here either, headings included — see that same doc
 * comment for why: real Micron is a terminal markup with no font-size
 * concept at all, only color.
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
 * are block-level instead — they apply to whichever block has focus,
 * no selection needed (tapping any block, including a divider or raw-
 * passthrough block, focuses it — visually indicated by a highlighted
 * row background — so "delete this block" always has an unambiguous
 * target).
 *
 * No block reordering in v1 either — blocks stay in document order,
 * only "add at the end" and "delete the focused one" are offered.
 * Simpler, consistent with node hosting's own "keep the phone-hosted
 * site simple" direction elsewhere in this feature.
 *
 * Link insertion is intentionally minimal: it inserts real Micron link
 * markup (`[label`destination]`) as plain text at the selection/cursor
 * — it does not render as an interactive chip in rich mode (this
 * model doesn't parse `[...]` specially, by design, so an inserted
 * link is just literal text that happens to be valid Micron once
 * saved). That's enough to make links usable from rich mode without
 * taking on full inline-link rendering as part of this pass.
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
        val linkText = "[$effectiveLabel`$destination]"
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

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(blocks, key = { it.id }) { block ->
                BlockRow(
                    block = block,
                    isFocused = block.id == focusedBlockId,
                    selection = if (block.id == focusedBlockId) focusedSelection else TextRange.Zero,
                    onFocus = { focusedBlockId = block.id },
                    onSelectionChange = { focusedSelection = it },
                    onChange = { updated ->
                        onBlocksChange(blocks.map { if (it.id == updated.id) updated else it })
                    },
                )
                HorizontalDivider()
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
            Text(disabledHint, style = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.85f), color = NomadTextDim)
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
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.75f),
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
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.85f),
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

@Composable
private fun BlockRow(
    block: MicronBlock,
    isFocused: Boolean,
    selection: TextRange,
    onFocus: () -> Unit,
    onSelectionChange: (TextRange) -> Unit,
    onChange: (MicronBlock) -> Unit,
) {
    when (block) {
        is MicronBlock.Paragraph -> ParagraphBlockRow(block, isFocused, selection, onFocus, onSelectionChange, onChange)
        is MicronBlock.Divider -> DividerBlockRow(isFocused, onFocus)
        is MicronBlock.RawPassthrough -> RawPassthroughBlockRow(block, isFocused, onFocus, onChange)
    }
}

/** Subtle tint marking whichever row currently owns the toolbar's
 * block-level actions — replaces the old always-visible per-row delete
 * icon, which the actual on-device experience showed was just visual
 * clutter on every single line. */
private val FOCUSED_ROW_TINT = NomadAccent.copy(alpha = 0.08f)

@Composable
private fun ParagraphBlockRow(
    block: MicronBlock.Paragraph,
    isFocused: Boolean,
    selection: TextRange,
    onFocus: () -> Unit,
    onSelectionChange: (TextRange) -> Unit,
    onChange: (MicronBlock) -> Unit,
) {
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
    // Single fixed text size for every block, heading or not -- real
    // Micron has no font-size concept at all (see this file's own top
    // doc comment). Heading depth shows as a background color band
    // instead, mirroring how MicronParser.py's own dark-theme heading
    // styles are pure fg/bg color, never bold or a bigger face.
    val headingTint = when (block.headingLevel) {
        1 -> NomadAccent.copy(alpha = 0.30f)
        2 -> NomadAccent.copy(alpha = 0.18f)
        3 -> NomadAccent.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    val textAlign = when (block.align) {
        MicronAlign.CENTER -> TextAlign.Center
        MicronAlign.RIGHT -> TextAlign.Right
        MicronAlign.DEFAULT -> TextAlign.Start
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isFocused) FOCUSED_ROW_TINT else Color.Transparent)
            .background(headingTint)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        BasicTextField(
            value = fieldValue,
            onValueChange = { new ->
                val newText = new.annotatedString.text
                val updatedStyles = if (newText != block.text) {
                    adjustCharStyles(block.charStyles, block.text, newText)
                } else {
                    block.charStyles
                }
                onFocus()
                onSelectionChange(new.selection)
                onChange(block.copy(text = newText, charStyles = updatedStyles))
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                textAlign = textAlign,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .onFocusChanged { if (it.isFocused) onFocus() },
        )
    }
}

private fun TextRange.coerceIn(maxLength: Int): TextRange =
    TextRange(start.coerceIn(0, maxLength), end.coerceIn(0, maxLength))

@Composable
private fun DividerBlockRow(isFocused: Boolean, onFocus: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isFocused) FOCUSED_ROW_TINT else Color.Transparent)
            .clickable(onClick = onFocus)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f).padding(vertical = 4.dp))
        Text(
            text = "divider",
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.6f),
            color = NomadTextDim,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun RawPassthroughBlockRow(
    block: MicronBlock.RawPassthrough,
    isFocused: Boolean,
    onFocus: () -> Unit,
    onChange: (MicronBlock) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isFocused) FOCUSED_ROW_TINT else Color.Transparent)
            .clickable(onClick = onFocus)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "Raw markup (not richly editable — see raw mode for full control)",
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.6f),
            color = NomadTextDim,
        )
        BasicTextField(
            value = block.rawText,
            onValueChange = { onChange(block.copy(rawText = it)) },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
