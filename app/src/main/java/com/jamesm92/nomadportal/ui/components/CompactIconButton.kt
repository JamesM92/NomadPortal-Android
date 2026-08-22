package com.jamesm92.nomadportal.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A real, genuinely small alternative to Material3's own [androidx.compose.material3.IconButton]
 * — same call shape (`onClick` + a trailing `content` lambda, typically
 * a single [androidx.compose.material3.Icon]), but without the 40dp
 * container-height floor stock `IconButton` bakes in.
 *
 * The real reason this exists, not a style preference: Material3
 * 1.4.0's `IconButton` calls `.size(IconButtonDefaults.smallContainerSize())`
 * (40dp, confirmed directly against `IconButtonDefaults.kt`'s real
 * source — `SmallIconButtonTokens.ContainerHeight`) as the *last*
 * modifier in its own internal chain, after
 * `.minimumInteractiveComponentSize()` — meaning that 40dp floor wins
 * regardless of what `LocalMinimumInteractiveComponentSize` is set to,
 * or what size modifier a caller passes in. This app's own
 * [AdaptiveTopAppBar] used to rely on zeroing
 * `LocalMinimumInteractiveComponentSize` to shrink its icon buttons —
 * confirmed, via two real on-device measurement rounds against a real
 * live report ("there is still a 1/4\" dead zone... that is not being
 * used"), to no longer work at all against a modern stock `IconButton`.
 * This is the actual fix: a different composable entirely, not a
 * workaround for the stock one.
 *
 * [size] defaults to 28.dp — comfortably below the 40dp floor this
 * exists to avoid, still large enough for a real icon (typically 20-
 * 24dp) to sit inside with a small amount of breathing room. This
 * app's own top bars are the only place this is meant to be used — a
 * deliberately compact context, not a general-purpose IconButton
 * replacement for content that needs the full accessible touch-target
 * size elsewhere.
 *
 * Real accessibility semantics preserved deliberately
 * (`Role.Button`) — shrinking the container shouldn't also shrink what
 * a screen reader announces this as.
 */
@Composable
fun CompactIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 28.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = size / 2),
                enabled = enabled,
                onClick = onClick,
            )
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
