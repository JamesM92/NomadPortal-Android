package com.jamesm92.nomadportal.data.browsing

import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.jamesm92.nomadportal.data.pollingFlow
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray

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

    override fun discoveredNodes(): Flow<List<NodeInfo>> = pollingFlow(POLL_INTERVAL_MS) { fetchNodes() }

    private fun fetchNodes(): List<NodeInfo> {
        val array = JSONArray(orchestrator.callAttr("get_nodes_json").toString())
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
            orchestrator.callAttr("fetch_page_text", address.nodeHash, address.path, identify).toString()
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

    private companion object {
        const val POLL_INTERVAL_MS = 4000L
    }
}
