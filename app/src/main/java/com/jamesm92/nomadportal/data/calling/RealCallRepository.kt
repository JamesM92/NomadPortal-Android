package com.jamesm92.nomadportal.data.calling

import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real [CallRepository], backed by `nomadportal_core.orchestrator`'s
 * call bridge (`place_call_json`/`answer_call_json`/`hang_up_call_json`/
 * `dismiss_call_json`/`get_call_status_json`) — same JSON-string bridge
 * convention as every other repository in this app.
 */
class RealCallRepository : CallRepository {
    private val orchestrator by lazy {
        Python.getInstance().getModule("nomadportal_core.orchestrator")
    }

    override fun callState(): Flow<CallState> = flow {
        while (true) {
            emit(fetchState())
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    private fun fetchState(): CallState {
        val obj = JSONObject(orchestrator.callAttr("get_call_status_json").toString())
        return CallState(
            status = CallStatusValue.fromWireValue(obj.optString("status", "idle")),
            isIncoming = obj.optBoolean("is_incoming", false),
            remoteIdentityHash = obj.optStringOrNull("remote_identity_hash"),
            remoteName = obj.optStringOrNull("remote_name"),
            startedAtMillis = obj.optTimestampMillisOrNull("started_at"),
            establishedAtMillis = obj.optTimestampMillisOrNull("established_at"),
            endedReason = obj.optStringOrNull("ended_reason"),
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key)

    // Python's started_at/established_at are unix seconds (nullable
    // float); Kotlin wants millis, same convention as every other
    // timestamp field crossing this bridge (see RealMessagingRepository/
    // RealBrowserRepository's identical *1000 conversions).
    private fun JSONObject.optTimestampMillisOrNull(key: String): Long? =
        if (isNull(key)) null else (optDouble(key, 0.0) * 1000).toLong()

    override fun callHistory(): Flow<List<CallHistoryEntry>> = flow {
        while (true) {
            emit(fetchHistory())
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    private fun fetchHistory(): List<CallHistoryEntry> {
        val array = JSONArray(orchestrator.callAttr("get_call_history_json").toString())
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            CallHistoryEntry(
                isIncoming = obj.optBoolean("is_incoming", false),
                remoteIdentityHash = obj.optStringOrNull("remote_identity_hash"),
                remoteName = obj.optStringOrNull("remote_name"),
                startedAtMillis = obj.optTimestampMillisOrNull("started_at"),
                establishedAtMillis = obj.optTimestampMillisOrNull("established_at"),
                endedAtMillis = obj.optTimestampMillisOrNull("ended_at"),
                status = CallStatusValue.fromWireValue(obj.optString("status", "ended")),
                reason = obj.optStringOrNull("reason"),
            )
        }
    }

    override suspend fun placeCall(addressHex: String): Boolean = withContext(Dispatchers.IO) {
        val obj = JSONObject(orchestrator.callAttr("place_call_json", addressHex).toString())
        obj.optBoolean("success", false)
    }

    override suspend fun answerCall(): Boolean = withContext(Dispatchers.IO) {
        val obj = JSONObject(orchestrator.callAttr("answer_call_json").toString())
        obj.optBoolean("success", false)
    }

    override suspend fun hangUp(): Boolean = withContext(Dispatchers.IO) {
        val obj = JSONObject(orchestrator.callAttr("hang_up_call_json").toString())
        obj.optBoolean("success", false)
    }

    override suspend fun announceCallAddress(): Boolean = withContext(Dispatchers.IO) {
        val obj = JSONObject(orchestrator.callAttr("announce_call_address_json").toString())
        obj.optBoolean("success", false)
    }

    override suspend fun dismiss() {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("dismiss_call_json")
        }
    }

    companion object {
        // Much shorter than messaging/browsing's 4000ms — a call's own
        // state (ringing, answered, hung up by the remote) needs to feel
        // near-instant, not "catches up within a few seconds." 500ms is
        // imperceptible as a delay for a phone-call UI while still being
        // a cheap in-memory poll on the Python side (status_dict() is
        // just a dict read under a lock, no I/O), same "cheap enough to
        // poll often" reasoning RealBrowserRepository's own doc comment
        // gives for its interval.
        const val POLL_INTERVAL_MS = 500L
    }
}
