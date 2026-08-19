package com.jamesm92.nomadportal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.ui.theme.NomadPortalPurple

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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = handleTap,
        ),
    ) {
        // Small + tight spacing on purpose — a real on-device check
        // showed the row was right at its width limit for the bare
        // 11-character wordmark alone at titleLarge (NomadMono is a real
        // monospace face, wider per character than a proportional font
        // at the same size), back when Home's top bar also carried the
        // Nodes/Messages/Settings action icons in this same row (since
        // replaced by the bottom NavigationBar — see NomadNavHost.kt —
        // which freed up real width here). titleMedium is smaller still
        // than the size that check landed on, so this keeps comfortable
        // headroom rather than needing to re-derive it.
        TentPortalMark(markSize = 26.dp)
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The small top-right corner mark every screen *other than* Home carries
 * — Home already has [AppLogo] as its title and doesn't need this too.
 * Same triple-tap gesture, same "reachable from anywhere, not just Home"
 * intent as the panic-wipe design always called for, just not wired to
 * every screen until now. The [TentPortalMark], not the full wordmark
 * — this sits in a `TopAppBar`'s `actions` slot alongside back/nav icons,
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
        TentPortalMark(markSize = 20.dp)
    }
}

/**
 * The app's own mark: a camping-tent silhouette (literal "Nomad" — a
 * portable shelter, not a fixed structure), stroked in the app's own
 * brand blue, with its doorway shaped as a rounded arch and filled solid
 * purple — a real gateway/portal, not just a dark triangular tent
 * opening (literal "Portal"). One vivid accent color (the purple) inside
 * an otherwise unfilled/neutral shape, deliberately — draws the eye to
 * the doorway specifically rather than competing for attention with the
 * tent outline itself. Chosen over a chat-bubble glyph (what every
 * competitor's own app icon already is, per the
 * nomadportal-android-competitor-research memory) and over an earlier
 * compass-needle draft (replaced per explicit direction: "warming to the
 * concept of the logo being a camping tent icon with an arch with a
 * purple portal center instead") — the wordplay made directly visible
 * instead of abstracted into a wayfinding metaphor. Replaces the old
 * plain-text "N" monogram in both [AppLogo] and [PanicWipeLogo]. Drawn
 * directly rather than as a static asset so it's trivial to retune the
 * proportions/colors in place once seen live on a real device.
 */
@Composable
// No longer private — OnboardingScreen's own SafetyStep is a second
// real caller (showing this exact mark, larger, with a pointing arrow,
// so a first-run user sees literally which icon to triple-tap), per
// this project's own "promote only after real reuse" convention.
fun TentPortalMark(modifier: Modifier = Modifier, markSize: Dp = 24.dp) {
    val tentColor = MaterialTheme.colorScheme.primary
    val portalColor = NomadPortalPurple
    Canvas(modifier = modifier.size(markSize)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        val apex = Offset(cx, h * 0.08f)
        // Shoulder — where the sloped roof ends and a short vertical wall
        // begins, real on-device correction: "a tent should have short
        // verticals on the sides" (a bare apex-to-base slope reads as a
        // mountain/warning-sign triangle just as easily as a tent).
        val shoulderLeft = Offset(w * 0.20f, h * 0.55f)
        val shoulderRight = Offset(w * 0.80f, h * 0.55f)
        val wallBottomLeft = Offset(w * 0.20f, h * 0.88f)
        val wallBottomRight = Offset(w * 0.80f, h * 0.88f)
        // Guy lines anchor partway up the roof slope (not at the base
        // corners) and run outward-and-down to a ground stake — real
        // on-device correction: "the guide lines extending to the ground
        // from the top slope," not from the base.
        val guyAnchorLeft = Offset((apex.x + shoulderLeft.x) / 2f, (apex.y + shoulderLeft.y) / 2f)
        val guyAnchorRight = Offset((apex.x + shoulderRight.x) / 2f, (apex.y + shoulderRight.y) / 2f)
        // Kept inside [0, w] x [0, h] deliberately — Compose's Canvas
        // doesn't reliably clip content drawn past its own bounds, and
        // whether that content still renders (rather than being cut off
        // by whatever parent container this mark sits in) shouldn't be
        // left to chance.
        val stakeLeft = Offset(0f, h * 0.98f)
        val stakeRight = Offset(w, h * 0.98f)

        // Tent silhouette — a solid filled polygon (per explicit
        // correction: "the tent should be a solid color"), not an
        // outline. Left wall up the left roof slope, up to the apex,
        // down the right roof slope, down the right wall, closed back
        // along the base. The vertical walls are what keep this reading
        // as a real ridge tent rather than a plain triangle.
        drawPath(
            path = Path().apply {
                moveTo(wallBottomLeft.x, wallBottomLeft.y)
                lineTo(shoulderLeft.x, shoulderLeft.y)
                lineTo(apex.x, apex.y)
                lineTo(shoulderRight.x, shoulderRight.y)
                lineTo(wallBottomRight.x, wallBottomRight.y)
                close()
            },
            color = tentColor,
        )
        // A same-color line would vanish against the solid fill above —
        // the ridge seam and guy lines both use a darkened tint of the
        // tent color instead, reading as a seam/stitching detail rather
        // than a flat silhouette edge.
        val seamColor = Color(tentColor.red * 0.6f, tentColor.green * 0.6f, tentColor.blue * 0.6f, 1f)
        // Ridge seam — apex straight down to the doorway's own peak, the
        // single most standard "this is a tent" visual shorthand.
        drawLine(
            color = seamColor,
            start = apex,
            end = Offset(cx, h * 0.62f),
            strokeWidth = w * 0.05f,
            cap = StrokeCap.Round,
        )
        // Guy lines — from partway up each roof slope, out past the
        // tent's own footprint down to a ground stake.
        drawLine(color = seamColor, start = guyAnchorLeft, end = stakeLeft, strokeWidth = w * 0.05f, cap = StrokeCap.Round)
        drawLine(color = seamColor, start = guyAnchorRight, end = stakeRight, strokeWidth = w * 0.05f, cap = StrokeCap.Round)

        // Portal doorway — an arch (flat bottom, semicircular top) sized
        // to sit inside the tent's lower half without its rounded top
        // poking through either sloped roof line at any point along its
        // width. Filled solid purple, the mark's one vivid accent color.
        val archHalfWidth = w * 0.16f
        val archBaseY = h * 0.88f
        val archStraightTopY = h * 0.62f
        drawPath(
            path = Path().apply {
                moveTo(cx - archHalfWidth, archBaseY)
                lineTo(cx - archHalfWidth, archStraightTopY)
                arcTo(
                    rect = Rect(
                        left = cx - archHalfWidth,
                        top = archStraightTopY - archHalfWidth,
                        right = cx + archHalfWidth,
                        bottom = archStraightTopY + archHalfWidth,
                    ),
                    startAngleDegrees = 180f,
                    sweepAngleDegrees = 180f,
                    forceMoveTo = false,
                )
                lineTo(cx + archHalfWidth, archBaseY)
                close()
            },
            color = portalColor,
        )
    }
}
