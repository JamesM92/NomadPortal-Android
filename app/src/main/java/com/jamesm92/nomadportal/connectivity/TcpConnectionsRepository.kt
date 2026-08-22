package com.jamesm92.nomadportal.connectivity

import com.chaquo.python.Python
import com.jamesm92.nomadportal.data.pollingFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One user-configured TCP connection — replaces the old design's single
 * hardcoded hub (see [RealInterfaceController]'s `setTcpEnabled` doc
 * comment for that history). [id] is a stable server-assigned uuid, not
 * derived from host:port, so a connection survives being edited
 * ([updateConnection]) without losing its identity, and two entries can
 * coexist at the same host:port.
 */
data class TcpConnection(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val enabled: Boolean,
    /** Live status, not a persisted setting — the real RNS interface's
     * own connected/disconnected state, polled fresh each time (see
     * [RealTcpConnectionsRepository.fetchConnections]). Always false
     * for a connection that isn't currently attached at all (its own
     * [enabled] is off, or the TCP master switch is off) — matches
     * [InterfaceController.hasDownTcpConnection]'s own reasoning for
     * why that's the correct reading rather than a distinct "unknown". */
    val online: Boolean,
    /** This session's own up/down byte totals — real per-connection
     * data (see orchestrator.py's get_tcp_connections_json's own doc
     * comment for why this used to only ever exist as one shared
     * aggregate across every TCP connection at once, and the real
     * .name-collision bug that caused). 0 while detached, per explicit
     * direction ("the lifetime data up and down should be per
     * connection in tcp"). */
    val rxBytes: Long,
    val txBytes: Long,
    /** Lifetime — across app restarts too, not just this session
     * (base + [rxBytes]/[txBytes]). Keeps advancing forever once a
     * connection is ever attached; stops (doesn't reset) while
     * detached. */
    val lifetimeRxBytes: Long,
    val lifetimeTxBytes: Long,
    /** Real, live instantaneous throughput — bytes/sec, computed
     * server-side from the delta since the *previous* poll (see
     * get_tcp_connections_json's own doc comment). 0 on the very first
     * reading for a connection and whenever it's detached — there's no
     * real "current speed" for a connection carrying no traffic. Per
     * explicit direction ("...and should show current speed"). */
    val rxBytesPerSecond: Double,
    val txBytesPerSecond: Double,
)

/**
 * Manages the list of TCP connections — separate from
 * [InterfaceController.setTcpEnabled], which is now the *master* switch
 * sitting on top of this whole list (off detaches every connection
 * regardless of its own [TcpConnection.enabled], without touching that
 * flag). Poll-based like every other orchestrator-backed repository in
 * this app (`RealBrowserRepository`/`RealMessagingRepository`) — no
 * push mechanism exists on the Python side for this either.
 */
interface TcpConnectionsRepository {
    fun connections(): Flow<List<TcpConnection>>
    suspend fun addConnection(name: String, host: String, port: Int)
    suspend fun removeConnection(id: String)
    suspend fun setConnectionEnabled(id: String, enabled: Boolean)

    /** Edits an existing connection's name/host/port in place — no
     * longer remove-and-re-add-only. Returns true on success (false if
     * [id] doesn't exist, [host] is blank, or [port] is out of
     * `1..65535`). */
    suspend fun updateConnection(id: String, name: String, host: String, port: Int): Boolean
}

class RealTcpConnectionsRepository : TcpConnectionsRepository {
    private val orchestrator by lazy {
        Python.getInstance().getModule("nomadportal_core.orchestrator")
    }

    override fun connections(): Flow<List<TcpConnection>> = pollingFlow(POLL_INTERVAL_MS) { fetchConnections() }

    override suspend fun addConnection(name: String, host: String, port: Int) {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("add_tcp_connection", name, host, port)
        }
    }

    override suspend fun removeConnection(id: String) {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("remove_tcp_connection", id)
        }
    }

    override suspend fun setConnectionEnabled(id: String, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("set_tcp_connection_enabled", id, enabled)
        }
    }

    override suspend fun updateConnection(id: String, name: String, host: String, port: Int): Boolean =
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("update_tcp_connection", id, name, host, port).toBoolean()
        }

    private fun fetchConnections(): List<TcpConnection> {
        val obj = JSONObject(orchestrator.callAttr("get_tcp_connections_json").toString())
        val array = obj.getJSONArray("connections")
        return (0 until array.length()).map { i ->
            val c = array.getJSONObject(i)
            TcpConnection(
                id = c.getString("id"),
                name = c.optString("name").ifBlank { "${c.getString("host")}:${c.getInt("port")}" },
                host = c.getString("host"),
                port = c.getInt("port"),
                enabled = c.optBoolean("enabled", true),
                online = c.optBoolean("online", false),
                rxBytes = c.optLong("rxb", 0L),
                txBytes = c.optLong("txb", 0L),
                lifetimeRxBytes = c.optLong("life_rxb", 0L),
                lifetimeTxBytes = c.optLong("life_txb", 0L),
                rxBytesPerSecond = c.optDouble("rx_bps", 0.0),
                txBytesPerSecond = c.optDouble("tx_bps", 0.0),
            )
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 4000L
    }
}
