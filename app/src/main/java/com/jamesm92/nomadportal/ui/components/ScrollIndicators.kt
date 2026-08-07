package com.jamesm92.nomadportal.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.ui.theme.NomadTextDim

/**
 * Custom-drawn (not a built-in Compose scrollbar API — there wasn't one
 * when this was first built for `BrowserScreen`'s page viewer) thumb
 * along the right edge, sized/positioned from [LazyListState.layoutInfo]
 * — invisible (no track drawn at all) once everything fits on-screen,
 * since there's nothing to indicate then. Shared across any screen with
 * a scrollable [androidx.compose.foundation.lazy.LazyColumn] that could
 * use a visible "how much more is there" cue — originally
 * `BrowserScreen`-only, promoted here once Settings needed the same
 * thing.
 */
@Composable
fun VerticalScrollIndicator(listState: LazyListState, modifier: Modifier = Modifier) {
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleCount = layoutInfo.visibleItemsInfo.size
    if (totalItems == 0 || visibleCount == 0) return
    val fractionVisible = (visibleCount.toFloat() / totalItems).coerceIn(0.04f, 1f)
    if (fractionVisible >= 0.999f) return

    BoxWithConstraints(modifier = modifier.padding(vertical = 2.dp)) {
        val trackHeight = maxHeight
        val thumbHeight = trackHeight * fractionVisible
        val maxFirstIndex = (totalItems - visibleCount).coerceAtLeast(1)
        val scrollFraction = (listState.firstVisibleItemIndex.toFloat() / maxFirstIndex).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = (trackHeight - thumbHeight) * scrollFraction)
                .width(4.dp)
                .height(thumbHeight)
                .background(NomadTextDim.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
        )
    }
}

/** Same idea as [VerticalScrollIndicator], along the bottom edge, for a
 * horizontally-scrolling [androidx.compose.foundation.horizontalScroll]
 * region instead of a [LazyListState]-backed list. */
@Composable
fun HorizontalScrollIndicator(scrollState: ScrollState, modifier: Modifier = Modifier) {
    val viewportSize = scrollState.viewportSize
    val totalSize = viewportSize + scrollState.maxValue
    if (totalSize <= 0) return
    val fractionVisible = (viewportSize.toFloat() / totalSize).coerceIn(0.04f, 1f)
    if (fractionVisible >= 0.999f) return

    BoxWithConstraints(modifier = modifier.padding(horizontal = 2.dp)) {
        val trackWidth = maxWidth
        val thumbWidth = trackWidth * fractionVisible
        val scrollFraction = if (scrollState.maxValue > 0) scrollState.value.toFloat() / scrollState.maxValue else 0f
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (trackWidth - thumbWidth) * scrollFraction)
                .height(4.dp)
                .width(thumbWidth)
                .background(NomadTextDim.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
        )
    }
}
