package com.jamesm92.nomadportal.connectivity

import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One user-configured TCP connection — replaces the old design's single
 * hardcoded hub (see [RealInterfaceController]'s `setTcpEnabled` doc
 * comment for that history). [id] is a stable server-assigned uuid, not
 * derived from host:port, so a connection survives being edited (not
 * exposed yet — add/remove/enable only) without losing its identity,
 * and two entries can coexist at the same host:port.
 */
data class TcpConnection(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val enabled: Boolean,
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
}

class RealTcpConnectionsRepository : TcpConnectionsRepository {
    private val orchestrator by lazy {
        Python.getInstance().getModule("nomadportal_core.orchestrator")
    }

    override fun connections(): Flow<List<TcpConnection>> = flow {
        while (true) {
            emit(fetchConnections())
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

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
            )
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 4000L
    }
}
