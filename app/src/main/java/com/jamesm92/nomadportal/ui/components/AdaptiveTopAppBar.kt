package com.jamesm92.nomadportal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Drop-in [TopAppBar] replacement, same three slots
 * (title/navigationIcon/actions) and same call-site shape — a genuinely
 * compact custom `Row` in *every* orientation, not Material3's own
 * ~64dp-plus-status-bar stock app bar. Landscape got this treatment
 * first, per explicit request at the time ("when the phone is rotated
 * the header rows need to be as small as possible") — landscape phone
 * screens are short on vertical space in the first place, so a
 * portrait-sized header eating a large fraction of it was the real
 * complaint there. Portrait joined it later, per a separate real
 * on-device report/measurement: the stock [TopAppBar]'s own real
 * Material 3 sizing left a visible, measured ~47px dead band above the
 * title on a real device (confirmed directly — not a double-counted-
 * inset bug; `TopAppBarDefaults.windowInsets` only ever consumes the
 * real status bar, confirmed against Material3 1.4.0's own source —
 * this is just how tall the stock component's own content region is by
 * spec). None of this app's top-level screens need that much header
 * chrome: no title subtitle, no scroll-collapse behavior, nothing this
 * screen shape actually uses that the extra height was buying.
 *
 * Applies [WindowInsets.statusBars] itself via [Modifier.windowInsetsPadding]
 * — the one thing the stock [TopAppBar] was still doing correctly that
 * this compact replacement has to keep doing itself, now that it's the
 * only implementation (previously only reachable in landscape, where
 * that gap likely just went unnoticed rather than genuinely not
 * existing).
 *
 * Zeroing [LocalMinimumInteractiveComponentSize] here (same trick as
 * [SearchField]/`BrowserScreen`'s address bar) means every existing
 * `IconButton` passed in via `navigationIcon`/`actions` shrinks to its
 * icon's own natural size automatically, without those call sites
 * needing to change their icon buttons themselves.
 *
 * No custom height is hard-coded: content sizes itself with minimal
 * padding, rather than guessing a fixed dp value that might clip a
 * taller title (e.g. a screen that keeps an explicit `titleLarge`
 * style regardless of orientation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigationIcon()
            Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) { title() }
            actions()
        }
    }
}
