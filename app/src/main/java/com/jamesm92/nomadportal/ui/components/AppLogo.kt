package com.jamesm92.nomadportal.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

private const val TRIPLE_TAP_WINDOW_MS = 600L
private const val REQUIRED_TAPS = 3

/**
 * The app name/logo shown in top bars — and the panic-wipe trigger.
 * Tapping it 3 times within [TRIPLE_TAP_WINDOW_MS] calls [onTripleTap].
 * No visual affordance that this does anything special: the gesture being
 * unadvertised is part of what makes it hard to trigger by accident (see
 * [com.jamesm92.nomadportal.panicwipe.PanicWipe]'s doc comment for why
 * there's no confirmation dialog either).
 */
@Composable
fun AppLogo(
    modifier: Modifier = Modifier,
    text: String = "NomadPortal",
    onTripleTap: () -> Unit,
) {
    var tapTimestamps by remember { mutableStateOf(emptyList<Long>()) }

    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) {
            val now = System.currentTimeMillis()
            val recent = (tapTimestamps + now).filter { now - it <= TRIPLE_TAP_WINDOW_MS }
            if (recent.size >= REQUIRED_TAPS) {
                tapTimestamps = emptyList()
                onTripleTap()
            } else {
                tapTimestamps = recent
            }
        },
    )
}
