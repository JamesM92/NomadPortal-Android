package com.jamesm92.nomadportal.data.browsing

import android.net.Uri

/**
 * A node discovered on the mesh (porting-notes.md §4: "discovered-node
 * list, sortable by recency/name/hop-count/announce-frequency, per-node
 * hop count + last-fetch-ok/fail indicator, favorites"). Populated from
 * real RNS announce-listening via [RealBrowserRepository]/`NodeBrowser`
 * as of Aug 2026 — [StubBrowserRepository]'s fake data is the reference/
 * test fallback now, not the primary path.
 */
data class NodeInfo(
    val hash: String,
    val displayName: String,
    val hopCount: Int,
    /** Null = never fetched yet; true/false = last fetch attempt's outcome. */
    val lastFetchOk: Boolean?,
    /** True once a fetch has ever succeeded for this node, regardless of
     * [lastFetchOk]'s current value — lets the status dot distinguish
     * "failed just now, but has worked before" (yellow) from "never once
     * worked" (red), instead of collapsing both into the same red state. */
    val everFetchOk: Boolean = false,
    val isFavorite: Boolean,
    val lastAnnounceMillis: Long,
    /** Total announces heard from this node — powers NodeListScreen's
     * "Announces" sort option, per this doc comment's original
     * "sortable by ... announce-frequency" design note. */
    val announceCount: Int = 0,
    /** True for this device's own hosted node. browser.py forces
     * `favorited = true` server-side and `set_favorite()` no-ops for
     * this node's own index — NodeRow must not offer a togglable
     * favorite control for it (there's nothing to toggle; it's always
     * favorited), or an optimistic UI override gets applied that the
     * server can never reconcile, leaving the star stuck wrong. */
    val isHosted: Boolean = false,
    /** True for the configured default node. Same server-forced-favorite
     * treatment as [isHosted] — see browser.py's `get_nodes()`. */
    val isDefault: Boolean = false,
)

/**
 * A node hash + page path + request params — what an address bar entry
 * and a back/forward history slot both are.
 *
 * [params] carries a Micron link's third backtick-component (e.g. a
 * geomap "zoom link" — `` `[8`:/page/geomap/geomap.mu`h=8] `` — see
 * [com.jamesm92.micron2compose.parser.LinkTarget.fieldSpec] and the
 * nomadnet-app-auth skill's link-parameter convention), forwarded
 * server-side as var_<key>. This has to be a real field on [PageAddress]
 * itself, not threaded around it separately: [PageAddress] is a plain
 * data class, so two addresses that differ only by [params] must compare
 * unequal, or Compose's `remember(currentAddress)` keying (BrowserScreen.kt)
 * and [PageCacheStore]'s cache key (both keyed off this type / [toUrl])
 * would silently collapse "same base page, different zoom link" into "no
 * navigation happened" — the real, confirmed bug this fixed.
 */
data class PageAddress(
    val nodeHash: String,
    val path: String = "/page/index.mu",
    val params: Map<String, String> = emptyMap(),
) {
    /**
     * `hash://<hash><path>[?k=v&...]` — the bare-address form matches
     * micron2compose's `defaultUrlResolver` canonical form, so parsing it
     * back is symmetric. The `?...` suffix is this app's own addition
     * (never produced by micron2compose, which has no params concept),
     * sorted by key so the string is deterministic regardless of [params]'
     * insertion order — [PageCacheStore] hashes this string as its cache
     * key, so two logically-identical addresses must always serialize
     * identically.
     */
    fun toUrl(): String {
        val base = "hash://$nodeHash$path"
        if (params.isEmpty()) return base
        val query = params.toSortedMap().entries.joinToString("&") { (k, v) ->
            "${Uri.encode(k)}=${Uri.encode(v)}"
        }
        return "$base?$query"
    }

    companion object {
        /** Parses a `hash://<hash>/<path>[?k=v&...]` URL, e.g. from a [com.jamesm92.micron2compose.parser.LinkTarget.url]. Null if it isn't one. */
        fun fromUrl(url: String): PageAddress? {
            if (!url.startsWith("hash://")) return null
            val rest = url.removePrefix("hash://")
            val queryIndex = rest.indexOf('?')
            val withoutQuery = if (queryIndex >= 0) rest.substring(0, queryIndex) else rest
            val params = if (queryIndex >= 0) {
                rest.substring(queryIndex + 1)
                    .split("&")
                    .filter { it.isNotEmpty() }
                    .associate { pair ->
                        val eq = pair.indexOf('=')
                        if (eq >= 0) {
                            Uri.decode(pair.substring(0, eq)) to Uri.decode(pair.substring(eq + 1))
                        } else {
                            Uri.decode(pair) to ""
                        }
                    }
            } else {
                emptyMap()
            }
            val slash = withoutQuery.indexOf('/')
            return if (slash < 0) {
                PageAddress(nodeHash = withoutQuery, params = params)
            } else {
                PageAddress(
                    nodeHash = withoutQuery.substring(0, slash),
                    path = withoutQuery.substring(slash),
                    params = params,
                )
            }
        }
    }
}

/**
 * Parses a Micron link's third backtick-component (e.g. `"h=8"` or
 * `"a=1|tkn=abc123"`) into a param map — later params are pipe-separated
 * per NomadNet's own link-parameter convention (see the nomadnet-app-auth
 * skill). A key with no `=` is kept with an empty value rather than
 * dropped, matching NomadNet's own permissive field parsing.
 */
fun parseLinkFieldSpec(spec: String): Map<String, String> =
    spec.split("|").filter { it.isNotEmpty() }.associate { pair ->
        val eq = pair.indexOf('=')
        if (eq >= 0) pair.substring(0, eq) to pair.substring(eq + 1) else pair to ""
    }
