package com.jamesm92.nomadportal.data.messaging

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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real [MessagingRepository], backed by `nomadportal_core.orchestrator`'s
 * messaging bridge. Same JSON-string rationale as
 * [com.jamesm92.nomadportal.data.browsing.RealBrowserRepository].
 *
 * [conversations] and [messages] are **poll-based**, not live callback
 * streams — `MessagingService`'s inbound-delivery callback
 * (`_on_delivery`) is wired internally to RNS's own background threads
 * with no external hook (see the orchestration-design memory). Same
 * interval as the browsing repository, kept consistent rather than tuned
 * independently.
 *
 * Conversation/contact composition is genuinely new logic on the Python
 * side (`orchestrator._conversation_entries()`), not a thin wrapper —
 * `messaging.py`/`contact_store.py` don't expose anything
 * conversation-shaped themselves (a `ContactStore` entry alone
 * under-reports who you've talked to; message history alone misses
 * saved-but-never-messaged contacts).
 */
class RealMessagingRepository : MessagingRepository {
    private val orchestrator by lazy {
        Python.getInstance().getModule("nomadportal_core.orchestrator")
    }

    override fun conversations(): Flow<List<ConversationSummary>> = flow {
        while (true) {
            emit(fetchConversations())
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    override fun messages(contactHash: String): Flow<List<Message>> = flow {
        while (true) {
            emit(fetchMessages(contactHash))
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun sendMessage(contactHash: String, content: String) {
        withContext(Dispatchers.IO) {
            try {
                orchestrator.callAttr("send_message", contactHash, content)
            } catch (e: PyException) {
                throw IOException(e.message, e)
            }
        }
    }

    override suspend fun markRead(contactHash: String) {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("mark_conversation_read", contactHash)
        }
    }

    override suspend fun setFavorite(contactHash: String, favorite: Boolean) {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("set_contact_favorite", contactHash, favorite)
        }
    }

    // Synchronous per MessagingRepository's own contract — safe here
    // since get_contact_json is a cheap in-memory dict/JSON operation,
    // no RNS network I/O, matching what was already true of
    // StubMessagingRepository's in-memory list lookup.
    override fun contact(contactHash: String): Contact? {
        val json = orchestrator.callAttr("get_contact_json", contactHash).toString()
        if (json.isBlank()) return null
        return parseContact(JSONObject(json))
    }

    private fun fetchConversations(): List<ConversationSummary> {
        val array = JSONArray(orchestrator.callAttr("get_conversations_json").toString())
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            ConversationSummary(
                contact = parseContact(obj),
                lastMessage = obj.optJSONObject("last_message")?.let(::parseMessage),
                unreadCount = obj.optInt("unread_count", 0),
            )
        }
    }

    private fun fetchMessages(contactHash: String): List<Message> {
        val array = JSONArray(orchestrator.callAttr("get_messages_json", contactHash).toString())
        return (0 until array.length()).map { i -> parseMessage(array.getJSONObject(i)) }
    }

    private fun parseContact(obj: JSONObject): Contact {
        val hash = obj.getString("hash")
        val icon = if (obj.isNull("icon")) {
            ContactIcon.None
        } else {
            // messaging.py stores whichever of LXMF's 0x04 (icon-
            // appearance, pre-rendered into a flat SVG server-side —
            // there's no separate glyph-name/color field surviving to
            // read back) or 0x06 (raw image) fields arrived, base64-
            // encoded, under the same `icon` key — see the
            // orchestration-design memory. Both land as RawImage;
            // ContactAvatar.kt doesn't decode/render RawImage bytes yet
            // (falls back to initials) regardless of mime type, so an
            // SVG payload here is inert, not broken — a real Compose SVG
            // decoder is a separate, later piece of work.
            ContactIcon.RawImage(Base64.decode(obj.getString("icon"), Base64.DEFAULT))
        }
        return Contact(
            lxmfHash = hash,
            displayName = obj.optString("name").ifBlank { hash.take(16) },
            icon = icon,
            isFavorite = obj.optBoolean("favorited", false),
            lastAnnounceMillis = if (obj.isNull("last_seen")) 0L else (obj.optDouble("last_seen", 0.0) * 1000).toLong(),
            hopCount = if (obj.isNull("hops")) -1 else obj.optInt("hops", -1),
        )
    }

    private fun parseMessage(obj: JSONObject): Message {
        val isSent = obj.optBoolean("is_sent", false)
        val state = if (isSent && !obj.isNull("state")) obj.optString("state") else null
        return Message(
            id = obj.getString("id"),
            content = obj.getString("content"),
            // orchestrator's ts is unix seconds (float); Message wants millis.
            timestampMillis = (obj.optDouble("ts", 0.0) * 1000).toLong(),
            isSent = isSent,
            deliveryState = when (state) {
                "queued" -> DeliveryState.QUEUED
                "delivered" -> DeliveryState.DELIVERED
                "failed" -> DeliveryState.FAILED
                else -> null
            },
        )
    }

    private companion object {
        const val POLL_INTERVAL_MS = 4000L
    }
}
