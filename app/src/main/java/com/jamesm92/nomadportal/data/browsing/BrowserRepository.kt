package com.jamesm92.nomadportal.data.browsing

import kotlinx.coroutines.flow.Flow

/**
 * The real interface the browsing screens are built against — same "build
 * the interface now, swap the implementation once the core exists" pattern
 * as [com.jamesm92.nomadportal.connectivity.InterfaceController] and
 * [com.jamesm92.nomadportal.data.messaging.MessagingRepository].
 * [StubBrowserRepository] is the only implementation right now: fake nodes,
 * fake `.mu` content, no real RNS Link/path requests.
 *
 * A real implementation needs to honor the reliability lessons in
 * porting-notes.md §2 that this stub deliberately doesn't model (link
 * caching, stall watchdog, fetch dedup/serialization per destination,
 * announce-driven retry) — those are real-core-extraction concerns, not
 * something to fake here.
 */
interface BrowserRepository {
    fun discoveredNodes(): Flow<List<NodeInfo>>

    /** Raw `.mu` source for [address]. Throws on a simulated fetch failure — callers should catch and show an error state, not assume success. */
    suspend fun fetchPage(address: PageAddress): String

    suspend fun setFavorite(nodeHash: String, favorite: Boolean)
}
