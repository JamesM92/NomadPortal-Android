package com.jamesm92.nomadportal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.messaging.parseHexColor
import com.jamesm92.nomadportal.data.messaging.toHexString
import com.jamesm92.nomadportal.ui.theme.NomadAccent
import com.jamesm92.nomadportal.ui.theme.NomadAccent2
import com.jamesm92.nomadportal.ui.theme.NomadBg3

/** A small curated palette (this app's own accent colors plus basics)
 * rather than a full RGB picker, matching the rest of this app's
 * understated aesthetic. Any '#rrggbb'/Micron hex is valid regardless —
 * this is just what the picker itself offers to tap. */
private val MICRON_COLOR_SWATCHES = listOf(
    Color.White, Color.Black,
    Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835), Color(0xFF43A047),
    Color(0xFF00897B), Color(0xFF00ACC1), Color(0xFF1E88E5), Color(0xFF3949AB),
    Color(0xFF8E24AA), Color(0xFFD81B60), Color(0xFF6D4C41), Color(0xFF757575),
    NomadAccent, NomadAccent2,
)

/**
 * "Micron compatible" per explicit direction: real NomadNet Micron
 * markup's own color model (confirmed directly against
 * `nomadnet/ui/textui/MicronParser.py`) is plain hex — a full 6-digit
 * `` `Fxxxxxx`` or a 3-digit shorthand `` `Fxyz`` (each digit doubled,
 * same convention CSS's own hex shorthand uses) — not a fixed named
 * palette (there is no such thing in real Micron). So this offers 16
 * common colors as fast quick-picks, plus a free-entry hex field for
 * anything else — any 6-digit hex is valid Micron regardless of
 * whether it's one of the 16.
 *
 * Promoted here from HomeScreen.kt's own identity-icon color editor
 * (its original home) once the phase-3 hosted-page rich-text editor
 * needed the identical picker a third time — same promote-after-
 * repeated-need convention as [CompactTextField]/[MinutesField].
 *
 * [onClear], when non-null, renders an extra "Clear" button — for
 * callers where "no color set" is itself a meaningful, distinct state
 * (e.g. the rich-text editor's foreground/background toolbar panels,
 * where clearing removes the `` `F``/`` `B `` escape entirely rather
 * than picking a specific color). Omitted by callers like the identity
 * icon editor, where the icon always has *some* color.
 */
@Composable
fun MicronColorPicker(
    selected: Color,
    onSelect: (Color) -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
) {
    var hexDraft by remember(selected) { mutableStateOf(selected.toHexString()) }

    Column(modifier = modifier) {
        MICRON_COLOR_SWATCHES.chunked(8).forEach { row ->
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { swatch ->
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(swatch)
                            .border(
                                width = if (swatch == selected) 2.dp else 1.dp,
                                color = if (swatch == selected) MaterialTheme.colorScheme.secondary else NomadBg3,
                                shape = CircleShape,
                            )
                            .clickable { onSelect(swatch) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactTextField(
                value = hexDraft,
                onValueChange = { hexDraft = it },
                placeholder = "#rrggbb",
                modifier = Modifier.width(110.dp),
            )
            TextButton(onClick = { onSelect(parseHexColor(hexDraft, selected)) }) {
                Text("Use")
            }
            if (onClear != null) {
                TextButton(onClick = onClear) {
                    Text("Clear")
                }
            }
        }
    }
}
