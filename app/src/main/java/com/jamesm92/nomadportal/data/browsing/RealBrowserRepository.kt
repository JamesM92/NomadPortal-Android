package com.jamesm92.nomadportal.data.browsing

import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
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
 *
 * [repoScope] is [NomadPortalApp][com.jamesm92.nomadportal.NomadPortalApp]'s
 * own app-lifetime scope, passed in at construction — see [nodesSharedFlow]'s
 * own doc comment for why this class needs one of its own now, not just a
 * per-call [Dispatchers.IO].
 */
class RealBrowserRepository(private val repoScope: CoroutineScope) : BrowserRepository {
    private val orchestrator by lazy {
        Python.getInstance().getModule("nomadportal_core.orchestrator")
    }

    // Real, live-measured fix — three rounds, each one closing a real gap
    // the last round's own live re-test exposed.
    //
    // Round 1: get_nodes_json() was called unconditionally on every tick,
    // doing a full copy of every discovered node plus a json.dumps() of
    // the whole thing — a real, confirmed source of continuous GC
    // pressure and multi-second frame stalls once a device has a few
    // hundred nodes (on-device logcat: 30+ "mark compact GC freed" events,
    // Choreographer reporting hundreds of skipped frames, tightly
    // correlated with opening Sites). Fix: skip the rebuild when a cheap
    // version counter hadn't moved.
    //
    // Round 2: round 1 turned out insufficient on a busy network (~1 real
    // announce/sec measured live on one device) — the version moved on
    // nearly every tick, so "skip if unchanged" almost never got to skip.
    // Fix: get_nodes_delta_json() — the Python side now tracks which
    // specific node changed at which version (see
    // NodeBrowser.get_nodes_delta's own doc comment), so each tick only
    // pays for what actually changed, not the whole history.
    //
    // Round 3: round 2 still didn't fully fix the reported lag — direct
    // logcat evidence (temp instrumentation, since removed) caught the
    // real remaining cause: switching bottom-nav tabs away from Sites and
    // back fully disposes and recreates NodeListScreen's composition
    // (standard Navigation Compose bottom-nav behavior — a torn-down
    // destination's plain `remember{}` blocks don't survive, even with
    // the recommended saveState/restoreState pattern, which only covers
    // rememberSaveable-backed UI state like scroll position). Since the
    // old discoveredNodes() was a genuinely *cold* flow — a fresh
    // instance, fresh nodesByHash map, fresh lastVersion=-1, per call —
    // every single tab switch back to Sites paid the exact same
    // first-ever-poll "full" cost round 2 was supposed to make rare, no
    // matter how good the delta logic itself was.
    //
    // Fix: make discoveredNodes() a genuinely *hot*, shared flow, owned
    // by this repository instance (an app-lifetime singleton — see
    // NomadPortalApp) rather than freshly built per collector.
    // nodesByHash/lastVersion are now instance fields, not local to the
    // flow builder, so they survive regardless of who's currently
    // collecting. shareIn(..., WhileSubscribed(5000), replay = 1) means:
    // a re-subscribing collector (a tab switch back to Sites) gets the
    // last known list *immediately* via replay, with no wait at all, and
    // the poll loop itself only pauses (not resets) after 5s with nobody
    // watching, resuming with a cheap delta catch-up call rather than a
    // full resync when someone looks again.
    private val nodesByHash = LinkedHashMap<String, NodeInfo>()
    private var lastVersion = -1

    private val nodesSharedFlow by lazy {
        flow {
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
            .shareIn(repoScope, SharingStarted.WhileSubscribed(SHARE_STOP_TIMEOUT_MS), replay = 1)
    }

    override fun discoveredNodes(): Flow<List<NodeInfo>> = nodesSharedFlow

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
        // How long the poll loop keeps running with zero active
        // collectors before actually stopping — covers an ordinary quick
        // tab switch away and back without losing the accumulated
        // nodesByHash/lastVersion state's momentum (it wouldn't be lost
        // either way — those are instance fields — but stopping the loop
        // entirely means a delta catch-up call is needed on resume; this
        // just avoids that for the common "glanced at another tab for a
        // few seconds" case). Long enough to cover that, short enough
        // that leaving the Sites tab for good still actually stops the
        // background polling rather than running it forever.
        const val SHARE_STOP_TIMEOUT_MS = 5000L
    }
}
