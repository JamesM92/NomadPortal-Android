package com.jamesm92.nomadportal.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Bounds [items] down to the first `pageCount * `[pageSize]` entries,
 * growing by one more page each time [WindowedList.loadMore] is called
 * — the caller wires that to "the user scrolled near the end of what's
 * currently shown" (see [LoadMoreTrigger] below for the actual
 * LazyColumn-side detection this pairs with). [resetKeys] should be
 * every criterion the caller's search/filter/sort depends on, listed
 * the same way a plain `remember(key1, key2, ...)` call would — changing
 * any of them snaps back to one page, since a window position that made
 * sense for the old criteria (e.g. "scrolled 400 items into an
 * alphabetical sort") doesn't mean anything once the criteria change
 * out from under it.
 *
 * Real motivation, not a hypothetical: NetworkScreen's Announces
 * browser (and Sites' Announces-heard tab, the same shape) were
 * rendering their *entire* matched/sorted list unconditionally — up to
 * several thousand combined items on one real device — which cost a
 * real, directly measured multi-second main-thread stall (confirmed
 * live: main thread pegged at 100% CPU, ~4s single-frame `Davey!`
 * events, disappearing entirely once the list was collapsed/empty and
 * reproducing again once re-expanded — see the
 * nomadportal-android-sites-gc-storm-fix memory for the full
 * investigation). Per explicit direction: most people only care about
 * the most recent 50-100 of any given search/filter, so only ever
 * compose that many rows at a time, loading another page only once the
 * user actually scrolls for more — not sort/filter/render everything a
 * query could ever match up front.
 *
 * **Live vs. frozen, per explicit direction**: page 1 (the common
 * case — not scrolled down) stays fully live against [items] changing
 * underneath it, so a newly-arrived announce still shows up immediately
 * at the top. The moment the user actually loads a second page, [items]
 * is snapshotted and everything from then on renders against that frozen
 * snapshot instead — a live reorder/insert reshuffling rows the user has
 * already scrolled past would be disorienting in a way
 * [rememberStableOrder]'s own per-row position freeze doesn't cover
 * (that one keeps a row from moving *within* a still-live list; this
 * stops the whole paginated view from moving at all once it's more than
 * one page deep). The underlying data keeps updating regardless — only
 * what's rendered freezes; changing [resetKeys] (a new search/filter/
 * sort) snaps back to page 1 and live tracking resumes.
 */
@Composable
fun <T> rememberWindowedList(items: List<T>, vararg resetKeys: Any?, pageSize: Int = 50): WindowedList<T> {
    var pageCount by remember(*resetKeys) { mutableIntStateOf(1) }
    var frozen by remember(*resetKeys) { mutableStateOf<List<T>?>(null) }
    val effectiveItems = frozen ?: items
    val visible = remember(effectiveItems, pageCount) { effectiveItems.take(pageCount * pageSize) }
    return WindowedList(
        visible = visible,
        hasMore = visible.size < effectiveItems.size,
        loadMore = {
            if (frozen == null) frozen = items
            if (visible.size < effectiveItems.size) pageCount += 1
        },
    )
}

data class WindowedList<T>(
    val visible: List<T>,
    val hasMore: Boolean,
    val loadMore: () -> Unit,
)

/**
 * Drop this as the last `item { }` in a `LazyColumn` whenever
 * [windowedList] still [WindowedList.hasMore] — a `LaunchedEffect` fires
 * [WindowedList.loadMore] the moment this composes, which for a
 * `LazyColumn` only actually happens once it scrolls close enough to
 * become part of the composed window. That's the real trigger — no
 * manual `LazyListState`/`layoutInfo` scroll-position tracking needed;
 * Compose's own lazy-composition already does the "is the user close to
 * the end" detection for free.
 */
@Composable
fun LoadMoreTrigger(windowedList: WindowedList<*>) {
    // Unit, not something derived from windowedList: this composable is
    // disposed and freshly recomposed each time the LazyColumn scrolls
    // it back out of and into the composed window (standard lazy-list
    // behavior for a trailing sentinel item) — a fresh composition is
    // exactly the "the user scrolled near the end again" signal this
    // needs, so LaunchedEffect(Unit) re-firing on each fresh composition
    // is the intended behavior, not a bug to key around.
    LaunchedEffect(Unit) {
        windowedList.loadMore()
    }
}
