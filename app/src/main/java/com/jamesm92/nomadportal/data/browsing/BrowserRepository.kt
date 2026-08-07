package com.jamesm92.nomadportal.data.browsing

import kotlinx.coroutines.flow.Flow

/**
 * The real interface the browsing screens are built against — same "build
 * the interface now, swap the implementation once the core exists" pattern
 * as [com.jamesm92.nomadportal.connectivity.InterfaceController] and
 * [com.jamesm92.nomadportal.data.messaging.MessagingRepository].
 * [RealBrowserRepository] is the live implementation (Aug 2026), backed by
 * `nomadportal_core.orchestrator`'s browsing bridge — real RNS Link/path
 * requests via `NodeBrowser`, honoring the reliability lessons in
 * porting-notes.md §2 (link caching, stall watchdog, fetch dedup/
 * serialization per destination, announce-driven retry) since those live
 * in `browser.py` itself, not something this layer re-implements.
 * [StubBrowserRepository] predates it and is kept as a minimal reference/
 * test implementation (fake nodes, fake `.mu` content, no network I/O) —
 * same role [com.jamesm92.nomadportal.connectivity.NoopInterfaceController]
 * plays for [com.jamesm92.nomadportal.connectivity.InterfaceController].
 */
interface BrowserRepository {
    fun discoveredNodes(): Flow<List<NodeInfo>>

    /** Raw `.mu` source for [address]. Throws on a simulated fetch failure — callers should catch and show an error state, not assume success.
     * [identify]: identify this device's own RNS identity to the node
     * over the fetch (porting-notes.md §4's "persistent identify-to-
     * this-node toggle, separate from anonymous browsing") — see
     * [com.jamesm92.nomadportal.ui.browser.IdentifySession] for how
     * BrowserScreen tracks the toggle's own session-scoped state. */
    suspend fun fetchPage(address: PageAddress, identify: Boolean = false): String

    suspend fun setFavorite(nodeHash: String, favorite: Boolean)
}
