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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Drop-in [TopAppBar] replacement, same three slots
 * (title/navigationIcon/actions) and same call-site shape — a genuinely
 * compact custom `Row` in *every* orientation, not Material3's own
 * stock app bar.
 *
 * Real third attempt at solving this specific complaint, not the
 * first two: landscape got a compact custom Row early on (per explicit
 * direction at the time: "when the phone is rotated the header rows
 * need to be as small as possible"), but portrait stayed on the stock
 * `TopAppBar`. A live report measured a genuine ~41px dead zone above
 * the title in portrait beyond the real status bar (`dumpsys window`'s
 * own real statusBars frame vs. the title TextView's own real
 * uiautomator bounds — not screenshot pixel-guessing, which is what
 * misled the first attempt at this into declaring victory prematurely).
 *
 * Two real things had to be fixed together for this to actually work,
 * both found the hard way across two failed attempts:
 *
 * 1. The title needs [ProvideTextStyle] with `titleLarge` explicitly —
 *    a bare custom Row has no ambient text style of its own the way
 *    stock `TopAppBar` provides internally, so without this the title
 *    visibly shrinks (attempt #1's own real regression, confirmed
 *    directly from a live report: "all you did was shrink the text").
 * 2. Every icon button in `navigationIcon`/`actions` has to be
 *    [CompactIconButton], not Material3's own stock `IconButton` — see
 *    that composable's own doc comment for why: `IconButton` bakes in
 *    a real 40dp height floor as of Material3 1.4.0 that no modifier
 *    or CompositionLocal from this app can override, which is what
 *    kept the top bar's own real rendered height tall regardless of
 *    how compact this Row's own padding was (attempt #2's own real
 *    failure — title position barely moved, confirmed by re-measuring
 *    with real uiautomator bounds instead of trusting the first
 *    attempt's own pixel-diffing).
 *
 * Applies [WindowInsets.statusBars] itself via [Modifier.windowInsetsPadding]
 * — the one thing the stock [TopAppBar] was still doing correctly that
 * this compact replacement has to keep doing itself.
 */
@Composable
fun AdaptiveTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navigationIcon()
        Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
            ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
        }
        actions()
    }
}
