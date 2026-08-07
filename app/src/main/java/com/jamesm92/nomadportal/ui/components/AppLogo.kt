package com.jamesm92.nomadportal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

private const val TRIPLE_TAP_WINDOW_MS = 600L
private const val REQUIRED_TAPS = 3

/**
 * Shared triple-tap-within-[TRIPLE_TAP_WINDOW_MS] counting logic, used by
 * both [AppLogo] (Home's full title) and [PanicWipeLogo] (the small
 * top-right corner mark every other screen carries) so there's exactly
 * one implementation of the gesture itself.
 */
@Composable
private fun rememberTripleTapHandler(onTripleTap: () -> Unit): () -> Unit {
    var tapTimestamps by remember { mutableStateOf(emptyList<Long>()) }
    return {
        val now = System.currentTimeMillis()
        val recent = (tapTimestamps + now).filter { now - it <= TRIPLE_TAP_WINDOW_MS }
        if (recent.size >= REQUIRED_TAPS) {
            tapTimestamps = emptyList()
            onTripleTap()
        } else {
            tapTimestamps = recent
        }
    }
}

/**
 * The app name/logo shown in Home's top bar — and the panic-wipe trigger.
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
    val handleTap = rememberTripleTapHandler(onTripleTap)
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = handleTap,
        ),
    )
}

/**
 * The small top-right corner mark every screen *other than* Home carries
 * — Home already has [AppLogo] as its title and doesn't need this too.
 * Same triple-tap gesture, same "reachable from anywhere, not just Home"
 * intent as the panic-wipe design always called for, just not wired to
 * every screen until now. A plain "N" monogram, not the full wordmark —
 * this sits in a `TopAppBar`'s `actions` slot alongside back/nav icons,
 * where the full "NomadPortal" text wouldn't fit.
 */
@Composable
fun PanicWipeLogo(modifier: Modifier = Modifier, onTripleTap: () -> Unit) {
    val handleTap = rememberTripleTapHandler(onTripleTap)
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = handleTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "N",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
