package com.jamesm92.nomadportal.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Freezes the *screen position* of [items] (already sorted by the
 * caller) against membership changes only — additions/removals reorder
 * immediately, but any item already present keeps its position even as
 * its own fields (timestamps, counts, favorite state, ...) update live
 * in place from a fresh poll. Every returned item is still the live
 * object from [items] — only *where* it renders is stabilized, not
 * *what* it renders.
 *
 * Fixes a real repro from a busy mesh: "Recent"/"Announces" sort options
 * re-sort on every ~4s poll as fresh announces land, which can shift a
 * row out from under an in-flight tap before it registers as a click —
 * confirmed via one tap favoriting two different nodes, neither the one
 * visually targeted (Android resolves a tap by whatever's under the
 * finger at release, not by original touch-down identity). Freezing
 * position between structural changes removes that failure mode:
 * whatever's under a finger at touch-down is still there at release,
 * unless that specific item was actually added/removed mid-gesture.
 *
 * [key] must uniquely identify an item the same way the caller's own
 * `LazyColumn` `items(..., key = ...)` does — same [key] used for both
 * is what keeps this correct.
 */
@Composable
fun <T> rememberStableOrder(items: List<T>, key: (T) -> Any): List<T> {
    val liveOrder = remember(items) { items.map(key) }
    var stableOrder by remember { mutableStateOf(liveOrder) }
    // Set equality is order-independent, so this effect only relaunches
    // (and only then commits a new position order) when membership
    // actually changed — not when only the relative order among
    // already-present keys did.
    LaunchedEffect(liveOrder.toSet()) {
        stableOrder = liveOrder
    }
    val byKey = remember(items) { items.associateBy(key) }
    return remember(stableOrder, byKey) {
        // Items no longer present drop out for free (mapNotNull); items
        // not yet folded into stableOrder (added since the last
        // membership-change commit) are appended in their live order —
        // they'll settle into stableOrder's own ordering once the
        // LaunchedEffect above catches up.
        val known = stableOrder.mapNotNull { byKey[it] }
        val extra = items.filter { key(it) !in stableOrder.toSet() }
        known + extra
    }
}
