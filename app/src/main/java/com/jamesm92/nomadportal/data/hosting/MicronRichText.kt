package com.jamesm92.nomadportal.data.hosting

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.jamesm92.micron2compose.parser.Align
import com.jamesm92.micron2compose.parser.BlockKind
import com.jamesm92.micron2compose.parser.MicronConverter
import com.jamesm92.micron2compose.parser.TextRun
import java.util.UUID

/**
 * A block-based rich-text model for editing one hosted-node page,
 * bidirectionally convertible to/from real Micron markup — the
 * phase-3 WYSIWYG mode's actual data model (see the
 * nomadportal-android-hosted-node / nomadportal-android-micron-syntax-verified
 * memories for the full design reasoning and the real grammar this was
 * verified against, directly against `MicronParser.py`, not guessed).
 *
 * **Deliberately scoped down, not a full Micron editor**: each
 * [MicronBlock] is exactly one Micron output line — soft-wrapping in
 * the UI is just visual text flow, never an embedded newline — which
 * is what keeps a from-scratch rich-text editor tractable to build
 * correctly in one pass. Constructs this model doesn't specifically
 * understand (links, tables, literal blocks, comments) are preserved
 * as [MicronBlock.RawPassthrough] — literal, editable, byte-for-byte
 * verbatim text, never silently dropped or corrupted, even though
 * WYSIWYG mode can't specially render them. That's the real safety net
 * that makes shipping a deliberately incomplete rich mode reasonable:
 * raw mode (already built, phase 2) is always the full-fidelity escape
 * hatch for anything this model doesn't cover.
 *
 * **No text size in the rendered view, on purpose**: real Micron is a
 * *terminal* markup (NomadNet's own renderer is a urwid text UI), so
 * there is no such thing as a bigger font for a heading — confirmed
 * directly against `MicronParser.py`'s own heading dispatch, which
 * looks up a `"heading1"`/`"heading2"`/`"heading3"` *color* style (fg/
 * bg only, no size/weight) rather than anything font-related. This
 * editor's rendering follows the same rule: every block, heading or
 * not, renders at one single text size — heading depth is conveyed
 * with a background color band instead (see [MicronBlock.Paragraph]'s
 * own `headingLevel` doc comment / the composable that renders it),
 * never a font-size change.
 *
 * **Parsing is delegated to `micron2compose`'s own `MicronConverter`**
 * (see [parseMicronToBlocks]'s own doc comment) — this app's own node-
 * rendering-parity requirement means the grammar (color-escape width,
 * full-reset semantics, heading depth, alignment) has exactly one
 * implementation shared with the node-*browsing* renderer
 * (`BrowserScreen`), not a second hand-written parser kept in sync by
 * hand. This is a one-way reuse, though: `micron2compose` is read-only
 * (Micron text → render), with no serialization direction at all, so
 * [blocksToMicron] below has to stay original code regardless — there
 * is nothing to delegate that half to.
 */

enum class MicronAlign { DEFAULT, CENTER, RIGHT }

/** Per-character inline style — used as a flat array parallel to a
 * paragraph's [MicronBlock.Paragraph.text], one entry per character,
 * rather than an interval/span-tree model. Toggling a style over a
 * selection is then just "flip this boolean for characters
 * [start, end)" and redrawing is "group adjacent equal entries into
 * AnnotatedString spans" — both far simpler and less bug-prone to get
 * right than incrementally splitting/merging overlapping intervals,
 * which is the usual approach real rich-text editors need but isn't
 * worth the complexity at this app's page-sized (not document-sized)
 * content.
 */
data class CharStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val color: Color? = null,
    val background: Color? = null,
)

sealed interface MicronBlock {
    val id: String

    data class Paragraph(
        override val id: String = UUID.randomUUID().toString(),
        /** 0 = body text, 1-3 = heading depth (Micron's own `>`/`>>`/`>>>`).
         * Purely a color-band signal when rendered — see this file's own
         * doc comment on why headings never change text size here. */
        val headingLevel: Int = 0,
        val align: MicronAlign = MicronAlign.DEFAULT,
        val text: String = "",
        /** Same length as [text] — see [CharStyle]'s own doc comment. */
        val charStyles: List<CharStyle> = emptyList(),
    ) : MicronBlock

    /** A horizontal rule. Round-trips correctly if one already exists
     * in a page opened from raw markup, but WYSIWYG mode has no
     * toolbar action to create a new one in v1 — raw mode covers that
     * (see this file's own doc comment on why that's an acceptable
     * scope cut, not a silent gap). */
    data class Divider(override val id: String = UUID.randomUUID().toString()) : MicronBlock

    /** Anything this model doesn't specifically parse — preserved
     * exactly as encountered (may itself span multiple raw lines, e.g.
     * a literal `` `= `` block or a `` `t `` table region). */
    data class RawPassthrough(
        override val id: String = UUID.randomUUID().toString(),
        val rawText: String,
    ) : MicronBlock
}

/** Builds the styled [AnnotatedString] this paragraph's text field
 * displays — groups consecutive equal [CharStyle] runs into spans
 * rather than one span per character. Heading level is deliberately
 * NOT folded into this (no fontWeight/fontSize here for headings) —
 * it's a block-level property applied by the composable rendering this
 * block (as a background color band, never a size change — see this
 * file's own doc comment), kept separate specifically so a user
 * bolding one word inside a heading can never be confused with (or
 * accidentally clear) the heading-ness itself. */
fun MicronBlock.Paragraph.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    append(text)
    if (text.isEmpty()) return@buildAnnotatedString
    var runStart = 0
    var runStyle = charStyles.getOrElse(0) { CharStyle() }
    fun flush(end: Int) {
        if (runStyle != CharStyle()) addStyle(runStyle.toSpanStyle(), runStart, end)
    }
    for (i in 1 until text.length) {
        val style = charStyles.getOrElse(i) { CharStyle() }
        if (style != runStyle) {
            flush(i)
            runStart = i
            runStyle = style
        }
    }
    flush(text.length)
}

private fun CharStyle.toSpanStyle(): SpanStyle = SpanStyle(
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    textDecoration = if (underline) TextDecoration.Underline else null,
    color = color ?: Color.Unspecified,
    background = background ?: Color.Unspecified,
)

/** Toggles [style] over `[start, end)` — off if every character in the
 * range already has it, on otherwise. `field` selects which boolean;
 * see [applyColor]/[applyBackgroundColor] for the (not-a-toggle) color
 * cases. */
fun toggleCharStyle(
    styles: List<CharStyle>,
    start: Int,
    end: Int,
    get: (CharStyle) -> Boolean,
    set: (CharStyle, Boolean) -> CharStyle,
): List<CharStyle> {
    if (start >= end) return styles
    val range = start until end
    val allSet = range.all { get(styles.getOrElse(it) { CharStyle() }) }
    return styles.mapIndexed { i, s -> if (i in range) set(s, !allSet) else s }
}

/** Color isn't boolean — applying always sets it (a real toggle-to-off
 * needs a separate explicit "clear" action, offered in the color
 * picker itself, same as everywhere else this app's own Micron-
 * compatible color picker is used). Pass `null` to clear. */
fun applyColor(styles: List<CharStyle>, start: Int, end: Int, color: Color?): List<CharStyle> {
    if (start >= end) return styles
    val range = start until end
    return styles.mapIndexed { i, s -> if (i in range) s.copy(color = color) else s }
}

/** Background-color counterpart to [applyColor] — Micron's `` `B ``
 * escape (this editor's "highlight" toolbar action). Pass `null` to
 * clear. */
fun applyBackgroundColor(styles: List<CharStyle>, start: Int, end: Int, color: Color?): List<CharStyle> {
    if (start >= end) return styles
    val range = start until end
    return styles.mapIndexed { i, s -> if (i in range) s.copy(background = color) else s }
}

/** Keeps [oldStyles] aligned with a plain-text edit from [oldText] to
 * [newText] — a real `BasicTextField.onValueChange` hands back the
 * *whole* new text each time, not an incremental diff, so this
 * recovers the actual single edited region via the standard common-
 * prefix/common-suffix trick. Inserted characters inherit the style of
 * whatever character sits immediately before the insertion point
 * (typical rich-editor "newly typed text keeps the surrounding style"
 * behavior) — empty/start-of-text insertions get a bare default style. */
fun adjustCharStyles(oldStyles: List<CharStyle>, oldText: String, newText: String): List<CharStyle> {
    if (oldText == newText) return oldStyles
    val maxPrefix = minOf(oldText.length, newText.length)
    var prefixLen = 0
    while (prefixLen < maxPrefix && oldText[prefixLen] == newText[prefixLen]) prefixLen++
    val maxSuffix = minOf(oldText.length, newText.length) - prefixLen
    var suffixLen = 0
    while (suffixLen < maxSuffix &&
        oldText[oldText.length - 1 - suffixLen] == newText[newText.length - 1 - suffixLen]
    ) {
        suffixLen++
    }
    val oldEditEnd = oldText.length - suffixLen
    val newEditEnd = newText.length - suffixLen

    val prefixStyles = oldStyles.subList(0, prefixLen.coerceAtMost(oldStyles.size))
    val suffixStyles = oldStyles.subList(oldEditEnd.coerceIn(0, oldStyles.size), oldStyles.size)
    val insertedCount = (newEditEnd - prefixLen).coerceAtLeast(0)
    val inheritStyle = if (prefixLen > 0) oldStyles.getOrElse(prefixLen - 1) { CharStyle() } else CharStyle()
    val insertedStyles = List(insertedCount) { inheritStyle }
    return prefixStyles + insertedStyles + suffixStyles
}

// ---------------------------------------------------------------------
// Serialization: List<MicronBlock> -> real Micron markup text.
// ---------------------------------------------------------------------

fun blocksToMicron(blocks: List<MicronBlock>): String =
    blocks.joinToString("\n") { block ->
        when (block) {
            is MicronBlock.Paragraph -> block.toMicronLine()
            is MicronBlock.Divider -> "-"
            is MicronBlock.RawPassthrough -> block.rawText
        }
    }

/** Every paragraph fully re-specifies its own state from a clean
 * baseline (reset, then re-apply exactly what this run needs) rather
 * than trying to track "what's still active from a previous block" —
 * more verbose output, but correct regardless of what a preceding
 * [MicronBlock.RawPassthrough]/[MicronBlock.Divider] might have left
 * active (this model doesn't parse those, so it can't safely assume
 * anything about their trailing state).
 *
 * The double-backtick full reset (`` `` ``) doesn't just clear bold/
 * italic/underline — confirmed directly against MicronParser.py's own
 * dispatch table, it *also* resets foreground color, background color,
 * AND alignment back to their defaults. That means alignment has to be
 * re-applied after every single per-run reset, not just once at the
 * top of the line — otherwise the very first styled run in a
 * paragraph would silently cancel that paragraph's own alignment
 * (this was a real, shipped bug: a right-aligned paragraph rendered
 * left-aligned the moment it had any character styling at all, since
 * run 1's own reset wiped the alignment set two escapes earlier).
 * Headings don't need any special-casing here either: the heading
 * marker (`>`/`>>`/`>>>`) is a separate line-level prefix consumed
 * once, before any of this per-run escape handling even starts.
 *
 * Not `private`: also used by [com.jamesm92.nomadportal.ui.hosting.RichTextPageEditor]
 * to get a real-render preview of an unfocused block, by round-tripping
 * it through this same serializer and `micron2compose`'s own parser —
 * see that file's own doc comment. */
fun MicronBlock.Paragraph.toMicronLine(): String {
    val sb = StringBuilder()
    if (headingLevel > 0) sb.append(">".repeat(headingLevel.coerceIn(1, 3)))
    if (text.isEmpty()) return sb.toString()

    val alignEscape = when (align) {
        MicronAlign.CENTER -> "`c"
        MicronAlign.RIGHT -> "`r"
        MicronAlign.DEFAULT -> "" // already what a reset leaves it at
    }
    var i = 0
    while (i < text.length) {
        val style = charStyles.getOrElse(i) { CharStyle() }
        var j = i
        while (j < text.length && charStyles.getOrElse(j) { CharStyle() } == style) j++
        sb.append("``") // full reset -- also clears fg/bg color and align, see this fun's own doc comment
        sb.append(alignEscape) // re-apply this paragraph's own alignment every time, for exactly that reason
        if (style.bold) sb.append("`!")
        if (style.italic) sb.append("`*")
        if (style.underline) sb.append("`_")
        // Always the explicit 6-hex truecolor form (`` `FT``/`` `BT``),
        // never the bare 3-char shorthand (`` `F``+3 chars) -- real
        // Micron's default (no "T" marker) reads back exactly 3 raw
        // characters as the color value, confirmed directly against
        // MicronParser.py's own dispatch code (`line[i+1:i+4]`, fixed
        // width, no length sniffing). Writing our 6-digit hex there
        // without the "T" marker would only consume half of it as the
        // color and leak the other 3 hex characters into the visible
        // rendered text on a real Micron client -- the "T" truecolor
        // form is unambiguous and exact instead of relying on a lossy
        // digit-doubled 3-char approximation.
        if (style.color != null) sb.append("`FT").append(style.color.toMicronHex())
        if (style.background != null) sb.append("`BT").append(style.background.toMicronHex())
        sb.append(text, i, j)
        i = j
    }
    return sb.toString()
}

private fun Color.toMicronHex(): String = String.format("%06X", toArgb() and 0xFFFFFF)

// ---------------------------------------------------------------------
// Parsing: real Micron markup text -> List<MicronBlock>.
// ---------------------------------------------------------------------

/**
 * Delegates the actual grammar to `micron2compose`'s own
 * [MicronConverter] — see this file's own top doc comment on why: one
 * parser shared with node *browsing*, not a second hand-written one
 * that could silently drift from it.
 *
 * This file still does its own scan for three constructs whose
 * `micron2compose`-parsed form can't be losslessly turned back into
 * source text: literal blocks (`` `= ``…`` `= ``) and table regions
 * (`` `t ``…`` `t ``) are re-assembled by `MicronConverter` into
 * *rendered* content (e.g. a table's laid-out box-drawing text), not
 * preserved as the original markup; comments (`#`) are dropped
 * entirely by real Micron (and by `MicronConverter`, matching it) but
 * this editor deliberately keeps them instead of silently discarding
 * an author's own notes. All three need the original raw text sliced
 * out by hand, same as before this delegation existed.
 *
 * Every other line goes through [MicronConverter.convert] (called
 * per-line, each an independent single-line "document" — matching
 * this model's own one-block-per-line scope) and its result is mapped
 * onto [MicronBlock]. One more case gets the same verbatim treatment
 * for the same reason as literal/table blocks: a line whose runs
 * include a link, form field, anchor, or partial. `micron2compose`
 * resolves those into structured, context-dependent values (a link's
 * `runs` carry its *resolved* href, not the original `[label`url]`
 * text) — reconstructing the line from that could silently rewrite it
 * on save, so it's kept as [MicronBlock.RawPassthrough] instead. Raw
 * mode is still the full-fidelity way to edit a line like that (see
 * this file's own top doc comment).
 */
fun parseMicronToBlocks(raw: String): List<MicronBlock> {
    val converter = MicronConverter()
    val lines = raw.split("\n")
    val blocks = mutableListOf<MicronBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            // Literal block: `= ... `= (verbatim, opaque -- see this
            // function's own doc comment).
            line == "`=" -> {
                val start = i
                i++
                while (i < lines.size && lines[i] != "`=") i++
                if (i < lines.size) i++
                blocks.add(MicronBlock.RawPassthrough(rawText = lines.subList(start, i).joinToString("\n")))
                continue
            }
            // Table region: `t ... `t (same treatment).
            line.startsWith("`t") -> {
                val start = i
                i++
                while (i < lines.size && !lines[i].startsWith("`t")) i++
                if (i < lines.size) i++
                blocks.add(MicronBlock.RawPassthrough(rawText = lines.subList(start, i).joinToString("\n")))
                continue
            }
            // Comments and partials -- single-line, preserved verbatim
            // rather than silently dropped (real Micron -- and
            // micron2compose, matching it -- drops `#` comments
            // entirely; this editor doesn't, on purpose, so an
            // author's own notes never quietly vanish under them).
            line.startsWith("#") || line.startsWith("`{") -> {
                blocks.add(MicronBlock.RawPassthrough(rawText = line))
            }
            else -> blocks.add(parseOrdinaryLine(line, converter))
        }
        i++
    }
    return blocks
}

/** Parses one line that isn't part of a literal/table/comment region
 * (see [parseMicronToBlocks]'s own doc comment) via `micron2compose`'s
 * [MicronConverter] — this is what actually understands headings,
 * dividers, blank lines, alignment, and inline bold/italic/underline/
 * color escapes, real-Micron-verified. */
private fun parseOrdinaryLine(line: String, converter: MicronConverter): MicronBlock {
    val block = converter.convert(line).blocks.firstOrNull()
        ?: return MicronBlock.Paragraph(text = "") // e.g. a standalone "``" line, which resets state but emits no row

    return when (block.kind) {
        BlockKind.DIVIDER -> MicronBlock.Divider()
        BlockKind.BLANK -> MicronBlock.Paragraph(text = "")
        BlockKind.HEADING, BlockKind.TEXT -> {
            val runs = block.runs
            if (runs.all { it is TextRun }) {
                val text = StringBuilder()
                val styles = mutableListOf<CharStyle>()
                for (run in runs) {
                    run as TextRun
                    val style = CharStyle(
                        bold = run.bold,
                        italic = run.italic,
                        underline = run.underline,
                        color = run.fgColor?.let { parseHex(it.removePrefix("#")) },
                        background = run.bgColor?.let { parseHex(it.removePrefix("#")) },
                    )
                    text.append(run.text)
                    repeat(run.text.length) { styles.add(style) }
                }
                MicronBlock.Paragraph(
                    headingLevel = block.headingLevel,
                    align = block.align.toMicronAlign(),
                    text = text.toString(),
                    charStyles = styles,
                )
            } else {
                // Contains a link/field/anchor/partial -- see this
                // file's own doc comment on why that's not safely
                // reconstructible from micron2compose's parsed form.
                MicronBlock.RawPassthrough(rawText = line)
            }
        }
        // LITERAL/TABLE/PARTIAL shouldn't reach here -- parseMicronToBlocks
        // already slices those out by hand before calling this function.
        // Defensive-only fallback, never silently drops the line either way.
        else -> MicronBlock.RawPassthrough(rawText = line)
    }
}

private fun Align.toMicronAlign(): MicronAlign = when (this) {
    Align.LEFT -> MicronAlign.DEFAULT
    Align.CENTER -> MicronAlign.CENTER
    Align.RIGHT -> MicronAlign.RIGHT
}

/** `TextRun.fgColor`/`bgColor` are already-validated `"#rrggbb"`
 * strings (`micron2compose`'s own `parseColor` guarantees this — see
 * its doc comment), but kept defensive here rather than trusting that
 * unconditionally, consistent with the rest of this codebase's "never
 * crash on content" posture. */
private fun parseHex(hex6: String): Color? = try {
    Color(android.graphics.Color.parseColor("#$hex6"))
} catch (e: IllegalArgumentException) {
    null
}
