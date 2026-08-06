package com.jamesm92.nomadportal.data.browsing

/**
 * A node discovered on the mesh (porting-notes.md §4: "discovered-node
 * list, sortable by recency/name/hop-count/announce-frequency, per-node
 * hop count + last-fetch-ok/fail indicator, favorites"). No real RNS
 * announce-listening exists yet — this is populated by
 * [StubBrowserRepository]'s fake data until the core extraction lands.
 */
data class NodeInfo(
    val hash: String,
    val displayName: String,
    val hopCount: Int,
    /** Null = never fetched yet; true/false = last fetch attempt's outcome. */
    val lastFetchOk: Boolean?,
    val isFavorite: Boolean,
    val lastAnnounceMillis: Long,
)

/** A node hash + page path — what an address bar entry and a back/forward history slot both are. */
data class PageAddress(
    val nodeHash: String,
    val path: String = "/page/index.mu",
) {
    /** `hash://<hash><path>` — matches micron2compose's `defaultUrlResolver` canonical form, so parsing it back is symmetric. */
    fun toUrl(): String = "hash://$nodeHash$path"

    companion object {
        /** Parses a `hash://<hash>/<path>` URL, e.g. from a [com.jamesm92.micron2compose.parser.LinkTarget.url]. Null if it isn't one. */
        fun fromUrl(url: String): PageAddress? {
            if (!url.startsWith("hash://")) return null
            val rest = url.removePrefix("hash://")
            val slash = rest.indexOf('/')
            return if (slash < 0) {
                PageAddress(nodeHash = rest)
            } else {
                PageAddress(nodeHash = rest.substring(0, slash), path = rest.substring(slash))
            }
        }
    }
}
