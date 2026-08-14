package com.jamesm92.nomadportal.data.rnsh

import android.util.Base64
import com.chaquo.python.PyException
import com.chaquo.python.Python
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Real [RnshRepository], backed by `nomadportal_core.orchestrator`'s
 * `rnsh_*` bridge functions — see that module's own doc comment
 * (search "rnsh (remote shell over Reticulum) bridge") and
 * `nomadnet_web.rnsh_client`'s doc comment for the full interop/scope
 * details.
 */
class RealRnshRepository : RnshRepository {
    private val orchestrator by lazy {
        Python.getInstance().getModule("nomadportal_core.orchestrator")
    }

    override fun status(): Flow<RnshStatus> = flow {
        while (true) {
            emit(fetchStatus())
            delay(STATUS_POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    override fun outputChunks(): Flow<ByteArray> = flow {
        while (true) {
            emit(fetchOutputChunk())
            delay(OUTPUT_POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun connect(destinationHash: String) {
        withContext(Dispatchers.IO) {
            val obj = JSONObject(orchestrator.callAttr("rnsh_connect", destinationHash).toString())
            if (!obj.optBoolean("success", false)) {
                throw IOException(obj.optString("message", "Could not start connecting"))
            }
        }
    }

    override suspend fun sendInput(data: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                orchestrator.callAttr("rnsh_send_input", data)
            } catch (e: PyException) {
                throw IOException(e.message, e)
            }
        }
    }

    override suspend fun resize(rows: Int, cols: Int) {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("rnsh_resize", rows, cols)
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("rnsh_disconnect")
        }
    }

    private fun fetchStatus(): RnshStatus {
        val obj = JSONObject(orchestrator.callAttr("rnsh_status_json").toString())
        val state = when (obj.optString("state", "idle")) {
            "connecting" -> RnshConnectionState.CONNECTING
            "connected" -> RnshConnectionState.CONNECTED
            "closed" -> RnshConnectionState.CLOSED
            "failed" -> RnshConnectionState.FAILED
            else -> RnshConnectionState.IDLE
        }
        return RnshStatus(
            state = state,
            error = if (obj.isNull("error")) null else obj.optString("error"),
            exitCode = if (obj.isNull("exit_code")) null else obj.optInt("exit_code"),
        )
    }

    private fun fetchOutputChunk(): ByteArray {
        val obj = JSONObject(orchestrator.callAttr("rnsh_read_output_json").toString())
        val b64 = obj.optString("data_b64", "")
        return if (b64.isEmpty()) ByteArray(0) else Base64.decode(b64, Base64.NO_WRAP)
    }

    private companion object {
        // Faster than this app's usual 4s poll — a remote-shell session
        // needs to feel reasonably responsive, not just "eventually
        // consistent." Real network RTT over the mesh will still
        // dominate perceived latency in practice regardless.
        const val STATUS_POLL_INTERVAL_MS = 500L
        const val OUTPUT_POLL_INTERVAL_MS = 120L
    }
}
