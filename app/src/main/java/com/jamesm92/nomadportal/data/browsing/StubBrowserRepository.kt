package com.jamesm92.nomadportal.data.browsing

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

private const val RELAY_NORTH = "3f8a9b2c1d4e5f6a7b8c9d0e1f2a3b4c"
private const val RELAY_SOUTH = "7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a"
private const val EDGE_CACHE = "9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d"

/**
 * Fake, in-memory [BrowserRepository] — no real RNS Link/path requests.
 * Content is real, hand-written Micron exercising headings, inline
 * bold/color, internal cross-node links, an anchor jump, an external web
 * link, and a table — deliberately more than the minimum, since this is
 * also the first real content [micron2compose] renders in this app and is
 * worth actually looking at.
 */
class StubBrowserRepository : BrowserRepository {
    private val nodes = MutableStateFlow(
        listOf(
            NodeInfo(RELAY_NORTH, "relay-north", hopCount = 2, lastFetchOk = true, isFavorite = true, lastAnnounceMillis = System.currentTimeMillis() - 60_000),
            NodeInfo(RELAY_SOUTH, "relay-south", hopCount = 3, lastFetchOk = null, isFavorite = false, lastAnnounceMillis = System.currentTimeMillis() - 300_000),
            // everFetchOk = true here deliberately, despite lastFetchOk =
            // false — exercises FetchStatusDot's "worked before, failed
            // just now" yellow state, not just green/red/never-fetched.
            NodeInfo(EDGE_CACHE, "edge-cache", hopCount = 5, lastFetchOk = false, everFetchOk = true, isFavorite = false, lastAnnounceMillis = System.currentTimeMillis() - 3_600_000),
        )
    )

    private val pages: Map<String, Map<String, String>> = mapOf(
        RELAY_NORTH to mapOf(
            "/page/index.mu" to """
                >Relay North

                Welcome to `!relay-north`!, a community relay node.

                `[Browse the directory`hash://$RELAY_NORTH/page/directory.mu]
                `[Visit relay-south`hash://$RELAY_SOUTH/page/index.mu]
                `[NomadNet project site`https://github.com/markqvist/NomadNet]

                `:about
                >>About this node
                Solar-powered, uptime best-effort.

                `[Jump to About`#about]
            """.trimIndent(),
            "/page/directory.mu" to """
                >Directory

                `t
                | Name | Hops | Status |
                | ---- | :--: | :----: |
                | relay-south | 3 | Up |
                | edge-cache | 5 | Unknown |
                `t

                `[Back`hash://$RELAY_NORTH/page/index.mu]
            """.trimIndent(),
        ),
        RELAY_SOUTH to mapOf(
            "/page/index.mu" to """
                >Relay South

                `Faaf`!Southbound relay`!`f — mirrors relay-north's directory nightly.

                `[Back to relay-north`hash://$RELAY_NORTH/page/index.mu]
            """.trimIndent(),
        ),
    )

    override fun discoveredNodes(): StateFlow<List<NodeInfo>> = nodes.asStateFlow()

    override suspend fun fetchPage(address: PageAddress, identify: Boolean): String {
        delay(400) // simulates real link-establishment latency (porting-notes.md §2)
        val nodePages = pages[address.nodeHash]
            ?: throw IOException("No path to ${address.nodeHash} (simulated — edge-cache is deliberately unreachable in this stub)")
        return nodePages[address.path]
            ?: throw IOException("404: ${address.path} not found on ${address.nodeHash}")
    }

    override suspend fun setFavorite(nodeHash: String, favorite: Boolean) {
        nodes.value = nodes.value.map { if (it.hash == nodeHash) it.copy(isFavorite = favorite) else it }
    }

    override suspend fun seedDefaultFavorite(nodeHash: String, name: String) {
        val existing = nodes.value.find { it.hash == nodeHash }
        nodes.value = if (existing != null) {
            nodes.value.map { if (it.hash == nodeHash) it.copy(isFavorite = true) else it }
        } else {
            // No real announce concept in this stub -- mimic seed_default_favorite's
            // "create a minimal record if none exists yet" behavior directly.
            nodes.value + NodeInfo(
                hash = nodeHash,
                displayName = name,
                hopCount = -1,
                lastFetchOk = null,
                isFavorite = true,
                lastAnnounceMillis = 0L,
            )
        }
    }
}
