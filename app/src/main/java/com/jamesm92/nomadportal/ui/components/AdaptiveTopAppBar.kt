package com.jamesm92.nomadportal.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * Drop-in [TopAppBar] replacement, same three slots
 * (title/navigationIcon/actions) and same call-site shape — swaps to a
 * genuinely compact custom `Row` in landscape instead of Material3's
 * fixed ~64dp app bar, per explicit request ("when the phone is rotated
 * the header rows need to be as small as possible"). Landscape phone
 * screens are short on vertical space in the first place; a portrait-
 * sized header eating a large fraction of it is the actual complaint,
 * not anything wrong with the header in portrait.
 *
 * Zeroing [LocalMinimumInteractiveComponentSize] here (same trick as
 * [SearchField]/`BrowserScreen`'s address bar) means every existing
 * `IconButton` passed in via `navigationIcon`/`actions` shrinks to its
 * icon's own natural size automatically in landscape — none of the
 * call sites below needed to change their icon buttons themselves.
 *
 * No custom landscape height is hard-coded: content sizes itself with
 * minimal padding, rather than guessing a fixed dp value that might
 * clip a taller title (e.g. Home's [AppLogo], which keeps its own
 * explicit `titleLarge` style regardless of orientation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (isLandscape) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navigationIcon()
                Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) { title() }
                actions()
            }
        }
    } else {
        TopAppBar(title = title, navigationIcon = navigationIcon, actions = actions, modifier = modifier)
    }
}
