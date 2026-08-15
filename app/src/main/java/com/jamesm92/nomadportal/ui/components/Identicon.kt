package com.jamesm92.nomadportal.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.ui.theme.NomadBg3

/**
 * A GitHub/Columba-style symmetric dot-grid identicon, deterministic from
 * [hash] — real, per-contact-distinguishable fallback for a contact with
 * no sent icon (replaces the old same-grey-circle-for-everyone
 * `InitialsAvatar`; see [ContactAvatar]'s own doc comment).
 *
 * Ported directly from Columba's real `Identicon.kt`
 * (torlando-tech/columba, `app/src/main/java/network/columba/app/ui/
 * components/Identicon.kt` — cloned and read from source, not guessed):
 * `hash[0..2]` become the primary color's RGB, `hash[3..5]` the
 * secondary's (raw byte values, no palette/HSL involved). A 5-row grid
 * only computes its left 3 columns — `hash[(row*3+col) % hash.size]`, a
 * dot is drawn (primary color if that byte is even, secondary if odd)
 * whenever the byte exceeds 127 — then columns 0-1 are mirrored onto
 * columns 4-3 for left-right symmetry (column 2 is the untouched center
 * axis). A [hash] under 6 bytes can't feed both colors, so it renders a
 * plain solid grey circle instead, matching Columba's own degenerate case.
 *
 * One deliberate deviation from Columba: the circle's own background is
 * this app's existing [NomadBg3] avatar-background token (the same one
 * every other avatar in this app already uses) rather than Columba's
 * `MaterialTheme.colorScheme.surface` — keeps the surrounding chrome
 * consistent with the rest of this app while the dot pattern itself
 * stays genuinely Columba-style.
 *
 * [ringColor] (added after this component gained a third real caller —
 * Contacts, Sites/Nodes, and rnsh destinations all render an Identicon
 * now, with no other way to tell at a glance which *kind* of thing
 * you're looking at) draws a thin colored stroke around the circle when
 * non-null — a real, distinct app color per kind, not left to the
 * hash-derived dot colors to carry any of that meaning (those vary
 * unpredictably per hash and were never meant to encode "kind" at all).
 * `null` (the default) draws no ring, the original plain look, so any
 * caller that doesn't care about kind-differentiation is unaffected.
 */
@Composable
fun Identicon(hash: ByteArray, size: Dp, modifier: Modifier = Modifier, ringColor: Color? = null) {
    val ringModifier = if (ringColor != null) {
        Modifier.border(BorderStroke(1.5.dp, ringColor), CircleShape)
    } else {
        Modifier
    }
    if (hash.size < 6) {
        Box(modifier = modifier.size(size).clip(CircleShape).background(Color.Gray).then(ringModifier))
        return
    }

    val primaryColor = remember(hash) {
        Color(
            red = (hash[0].toInt() and 0xFF) / 255f,
            green = (hash[1].toInt() and 0xFF) / 255f,
            blue = (hash[2].toInt() and 0xFF) / 255f,
        )
    }
    val secondaryColor = remember(hash) {
        Color(
            red = (hash[3].toInt() and 0xFF) / 255f,
            green = (hash[4].toInt() and 0xFF) / 255f,
            blue = (hash[5].toInt() and 0xFF) / 255f,
        )
    }

    Box(modifier = modifier.size(size).clip(CircleShape).background(NomadBg3).then(ringModifier)) {
        Canvas(modifier = Modifier.size(size)) {
            val cellSize = this.size.width / 5f
            for (row in 0 until 5) {
                for (col in 0 until 3) {
                    val hashIndex = (row * 3 + col) % hash.size
                    val byteValue = hash[hashIndex].toInt() and 0xFF
                    if (byteValue > 127) {
                        val color = if (byteValue % 2 == 0) primaryColor else secondaryColor
                        drawCircle(
                            color = color,
                            radius = cellSize / 2.5f,
                            center = Offset(x = col * cellSize + cellSize / 2f, y = row * cellSize + cellSize / 2f),
                        )
                        if (col < 2) {
                            drawCircle(
                                color = color,
                                radius = cellSize / 2.5f,
                                center = Offset(x = (4 - col) * cellSize + cellSize / 2f, y = row * cellSize + cellSize / 2f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Turns a hex address string (e.g. [com.jamesm92.nomadportal.data.messaging.Contact.lxmfHash])
 * into the raw bytes [Identicon] wants. No equivalent existed anywhere in
 * this codebase before this feature. Malformed/odd-length input degrades
 * to an empty array rather than throwing — [Identicon] already treats
 * anything under 6 bytes as its own solid-grey degenerate case, so a
 * malformed hash just looks like "no distinguishing data," not a crash.
 */
fun String.hexToByteArray(): ByteArray {
    if (length % 2 != 0) return ByteArray(0)
    return try {
        ByteArray(length / 2) { i -> ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte() }
    } catch (e: NumberFormatException) {
        ByteArray(0)
    }
}
