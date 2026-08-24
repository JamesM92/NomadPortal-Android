package com.jamesm92.nomadportal.data.browsing

import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real [BrowserRepository], backed by `nomadportal_core.orchestrator`'s
 * browsing bridge (`get_nodes_json`/`fetch_page_text`/`set_node_favorite` —
 * see that module's own doc comments for why it hands back JSON strings
 * rather than raw [PyObject]s: walking Python dicts field-by-field via
 * Chaquopy accessors from Kotlin is far more failure-prone than letting
 * `json.dumps`/[org.json.JSONArray] do it on each side).
 *
 * [discoveredNodes] is a **poll-based** [Flow], not a live callback stream
 * — `NodeBrowser` has no push/observer mechanism at all (RNS's own
 * announce handler mutates its in-memory dict directly; nothing external
 * is notified — see the orchestration-design memory). `get_nodes()` is a
 * cheap in-memory read (a dict copy under a lock, no I/O), so polling
 * every few seconds is safe — there's no "correct" interval to copy from
 * the original app, since its JS frontend's own polling logic was never
 * carried into `python-core`.
 */
class RealBrowserRepository : BrowserRepository {
    private val orchestrator by lazy {
        Python.getInstance().getModule("nomadportal_core.orchestrator")
    }

    // Real, live-measured fix — two rounds. Round 1: get_nodes_json() used
    // to be called unconditionally on every tick, doing a full copy of
    // every discovered node plus a json.dumps() of the whole thing —
    // cheap with a handful of nodes, but a real, confirmed source of
    // continuous GC pressure and multi-second frame stalls once a device
    // has a few hundred (real on-device logcat evidence: 30+ "mark
    // compact GC freed ...MB" events and Choreographer reporting
    // hundreds of skipped frames, tightly correlated with opening
    // Sites). A first fix just skipped the rebuild when a cheap version
    // counter hadn't moved — round 2's real finding: on a busy network
    // (~1 real announce/sec measured live on one device, 2000+ nodes and
    // climbing), the version moves on nearly every single tick, so
    // "skip if unchanged" almost never got to skip — the full O(n)
    // rebuild kept firing regardless. get_nodes_delta_json() is the real
    // fix: the Python side tracks which specific node changed at which
    // version (see NodeBrowser.get_nodes_delta's own doc comment), so
    // each tick only pays for what actually changed (typically a
    // handful of nodes even during a busy stretch) instead of
    // re-copying/re-serializing/re-parsing the entire discovered-node
    // history every time. Held in a local map across ticks and merged
    // into, not replaced — NodeListScreen already re-sorts client-side
    // by whichever SortOption the user picked, so the map's own
    // (unordered) iteration order here doesn't matter.
    override fun discoveredNodes(): Flow<List<NodeInfo>> = flow {
        val nodesByHash = LinkedHashMap<String, NodeInfo>()
        var lastVersion = -1
        while (true) {
            val response = JSONObject(orchestrator.callAttr("get_nodes_delta_json", lastVersion).toString())
            val newVersion = response.getInt("version")
            if (newVersion != lastVersion) {
                val changed = parseNodeInfoArray(response.getJSONArray("nodes"))
                if (response.optBoolean("full", false)) {
                    nodesByHash.clear()
                }
                for (node in changed) {
                    nodesByHash[node.hash] = node
                }
                lastVersion = newVersion
                if (changed.isNotEmpty() || response.optBoolean("full", false)) {
                    emit(nodesByHash.values.toList())
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    private fun parseNodeInfoArray(array: JSONArray): List<NodeInfo> {
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val hash = obj.getString("hash")
            NodeInfo(
                hash = hash,
                displayName = obj.optString("name").ifBlank { hash.take(16) },
                // browser.py's `hops` is nullable (unknown until this
                // node's announce carries a hop count) — NodeInfo.hopCount
                // is non-null, so -1 is the "unknown" sentinel here.
                hopCount = if (obj.isNull("hops")) -1 else obj.getInt("hops"),
                lastFetchOk = if (obj.isNull("last_load_ok")) null else obj.getBoolean("last_load_ok"),
                everFetchOk = obj.optBoolean("ever_load_ok", false),
                isFavorite = obj.optBoolean("favorited", false),
                // browser.py's last_seen is unix seconds (float); NodeInfo wants millis.
                lastAnnounceMillis = (obj.optDouble("last_seen", 0.0) * 1000).toLong(),
                announceCount = obj.optInt("announce_count", 0),
                isHosted = obj.optBoolean("is_hosted", false),
                isDefault = obj.optBoolean("is_default", false),
            )
        }
    }

    override suspend fun fetchPage(address: PageAddress, identify: Boolean): String = withContext(Dispatchers.IO) {
        try {
            // address.params -- a link's fieldSpec (e.g. a geomap zoom
            // link's `h=8`), folded into PageAddress by BrowserScreen.kt's
            // handleLink -- as a JSON object string, matching this bridge's
            // established "structured data crosses as JSON" convention
            // (fetch_page_text's own doc comment explains the real bug this
            // fixed: dropping this used to collapse "follow a zoom link"
            // into "no navigation at all" once the base page was cached).
            val fieldDataJson = if (address.params.isEmpty()) null else JSONObject(address.params).toString()
            orchestrator.callAttr(
                "fetch_page_text",
                address.nodeHash,
                address.path,
                identify,
                fieldDataJson,
            ).toString()
        } catch (e: PyException) {
            // Rewrapped as IOException (not left as a raw PyException) to
            // match what StubBrowserRepository already throws and what
            // BrowserScreen.kt's `catch (e: Exception) { loadError =
            // e.message ?: ... }` was written against — PyException's own
            // message is fine here since fetch_page_text raises
            // RuntimeError with browser.py's own clean, user-facing error
            // string (e.g. "Path not found — node may be unreachable"),
            // not a raw traceback.
            throw IOException(e.message, e)
        }
    }

    override suspend fun setFavorite(nodeHash: String, favorite: Boolean) {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("set_node_favorite", nodeHash, favorite)
        }
    }

    override suspend fun seedDefaultFavorite(nodeHash: String, name: String) {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("seed_default_favorite", nodeHash, name)
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 4000L
    }
}
