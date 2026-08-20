package com.jamesm92.nomadportal.data.messaging

import android.util.Base64
import androidx.compose.ui.graphics.Color
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.jamesm92.nomadportal.data.SettingsRepository
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
 *
 * [settings] is used for exactly one thing today —
 * [setMessagesContactsOnly]'s real DataStore persistence — not a general
 * dependency every method here needs; every other method is a pure
 * Python-bridge call, same as before this parameter existed.
 */
class RealMessagingRepository(private val settings: SettingsRepository) : MessagingRepository {
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

    override suspend fun sendMessage(
        contactHash: String,
        content: String,
        attachmentFilename: String?,
        attachmentData: ByteArray?,
        attachmentKind: AttachmentKind,
        imageFormat: String?,
    ) {
        withContext(Dispatchers.IO) {
            try {
                orchestrator.callAttr(
                    "send_message",
                    contactHash,
                    content,
                    attachmentFilename,
                    attachmentData,
                    if (attachmentKind == AttachmentKind.IMAGE) "image" else "file",
                    imageFormat,
                )
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

    override suspend fun markUnread(contactHash: String) {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("mark_conversation_unread", contactHash)
        }
    }

    override suspend fun setFavorite(contactHash: String, favorite: Boolean) {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("set_contact_favorite", contactHash, favorite)
        }
    }

    override suspend fun setBlocked(contactHash: String, blocked: Boolean) {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("set_contact_blocked", contactHash, blocked)
        }
    }

    override suspend fun setContactName(contactHash: String, name: String): Boolean =
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("set_contact_name", contactHash, name).toBoolean()
        }

    override suspend fun deleteConversation(contactHash: String) {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("delete_conversation", contactHash)
        }
    }

    override fun announceStatus(): Flow<AnnounceStatus> = flow {
        while (true) {
            emit(fetchAnnounceStatus())
            // Ticks faster than the main POLL_INTERVAL_MS — this backs a
            // live "time since last announce" display that should count
            // up smoothly, not jump in 4s steps.
            delay(1000L)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun setAnnounceMax(interfaceKey: String, seconds: Int) {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("set_announce_max", interfaceKey, seconds)
        }
    }

    override suspend fun setAutoAnnounceInterval(interfaceKey: String, seconds: Int) {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("set_auto_announce_interval", interfaceKey, seconds)
        }
    }

    override suspend fun announceNow(): Boolean = withContext(Dispatchers.IO) {
        val obj = JSONObject(orchestrator.callAttr("announce_now").toString())
        obj.optBoolean("success", false)
    }

    override suspend fun setAutoAnnounceMaster(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("set_auto_announce_master", enabled)
        }
    }

    override suspend fun setDisplayName(name: String): Boolean = withContext(Dispatchers.IO) {
        orchestrator.callAttr("set_display_name", name).toBoolean()
    }

    override suspend fun setIconAppearance(glyphName: String, foreground: Color, background: Color): Boolean =
        withContext(Dispatchers.IO) {
            orchestrator.callAttr(
                "set_icon_appearance",
                glyphName,
                foreground.toHexString(),
                background.toHexString(),
            ).toBoolean()
        }

    override suspend fun setDisappearingTimer(contactHash: String, seconds: Int): Boolean =
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("set_disappearing_timer", contactHash, seconds).toBoolean()
        }

    override fun propagationSyncStatus(): Flow<PropagationSyncStatus> = flow {
        while (true) {
            emit(fetchPropagationSyncStatus())
            // Same faster-than-POLL_INTERVAL_MS reasoning as
            // announceStatus() above — this backs a live in-progress
            // transfer-state display, not just a slow-changing summary.
            delay(1000L)
        }
    }.flowOn(Dispatchers.IO)

    override fun relayNodes(): Flow<List<RelayNode>> = flow {
        while (true) {
            emit(fetchRelayNodes())
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun triggerPropagationSync(): String = withContext(Dispatchers.IO) {
        try {
            orchestrator.callAttr("trigger_propagation_sync").toString()
        } catch (e: PyException) {
            throw IOException(e.message, e)
        }
    }

    override suspend fun importScannedContact(destinationHash: String, publicKeyHex: String) {
        withContext(Dispatchers.IO) {
            try {
                orchestrator.callAttr("import_scanned_contact", destinationHash, publicKeyHex)
            } catch (e: PyException) {
                throw IOException(e.message, e)
            }
        }
    }

    override suspend fun setMessagesContactsOnly(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("set_messages_contacts_only", enabled)
        }
        settings.setMessagesContactsOnly(enabled)
    }

    override suspend fun setCallsContactsOnly(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("set_calls_contacts_only", enabled)
        }
        settings.setCallsContactsOnly(enabled)
    }

    override suspend fun setCallsEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("set_calls_enabled", enabled)
        }
        settings.setCallsEnabled(enabled)
    }

    override suspend fun setRetryViaRelay(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            orchestrator.callAttr("set_retry_via_relay", enabled)
        }
    }

    private fun fetchPropagationSyncStatus(): PropagationSyncStatus {
        val obj = JSONObject(orchestrator.callAttr("get_propagation_sync_status_json").toString())
        return PropagationSyncStatus(
            knownNodes = obj.optInt("known_nodes", 0),
            freshNodes = obj.optInt("fresh_nodes", 0),
            pickedNodeHash = obj.optStringOrNull("picked_node_hex"),
            lastSyncedAtMillis = obj.optDoubleOrNull("last_synced_at")?.let { (it * 1000).toLong() },
            consecutiveFailures = obj.optInt("consecutive_failures", 0),
            lastError = obj.optStringOrNull("last_error"),
            transferState = when (obj.optString("transfer_state", "idle")) {
                "idle" -> PropagationTransferState.IDLE
                "requesting_path" -> PropagationTransferState.REQUESTING_PATH
                "connecting" -> PropagationTransferState.CONNECTING
                "connected" -> PropagationTransferState.CONNECTED
                "request_sent" -> PropagationTransferState.REQUEST_SENT
                "receiving" -> PropagationTransferState.RECEIVING
                "response_received" -> PropagationTransferState.RESPONSE_RECEIVED
                "complete" -> PropagationTransferState.COMPLETE
                "no_path" -> PropagationTransferState.NO_PATH
                "link_failed" -> PropagationTransferState.LINK_FAILED
                "transfer_failed" -> PropagationTransferState.TRANSFER_FAILED
                "no_identity_received" -> PropagationTransferState.NO_IDENTITY_RECEIVED
                "no_access" -> PropagationTransferState.NO_ACCESS
                "failed" -> PropagationTransferState.FAILED
                else -> PropagationTransferState.UNKNOWN
            },
            transferProgress = obj.optDouble("transfer_progress", 0.0).toFloat(),
            transferLastResult = obj.optIntOrNull("transfer_last_result"),
        )
    }

    private fun fetchRelayNodes(): List<RelayNode> {
        val obj = JSONObject(orchestrator.callAttr("get_propagation_nodes_json").toString())
        val array = obj.optJSONArray("nodes") ?: JSONArray()
        return (0 until array.length()).map { i ->
            val n = array.getJSONObject(i)
            RelayNode(
                hash = n.getString("hash"),
                hopCount = n.optInt("hops", -1),
                firstSeenMillis = (n.optDouble("first_seen", 0.0) * 1000).toLong(),
                lastAnnounceMillis = (n.optDouble("last_seen", 0.0) * 1000).toLong(),
                announceCount = n.optInt("announce_count", 0),
            )
        }
    }

    private fun fetchAnnounceStatus(): AnnounceStatus {
        val obj = JSONObject(orchestrator.callAttr("get_announce_status_json").toString())
        val interfacesObj = obj.getJSONObject("interfaces")
        val interfaces = interfacesObj.keys().asSequence().associateWith { key ->
            val cfg = interfacesObj.getJSONObject(key)
            InterfaceAnnounceConfig(
                announceMaxSeconds = cfg.optInt("announce_max_seconds", AnnounceStatus.MAX_SECONDS),
                autoAnnounceIntervalSeconds = cfg.optInt(
                    "auto_announce_interval_seconds",
                    AnnounceStatus.MAX_SECONDS,
                ),
            )
        }
        return AnnounceStatus(
            interfaces = interfaces,
            autoAnnounceMasterEnabled = obj.optBoolean("auto_announce_master_enabled", true),
            lastAnnounceAtMillis = if (obj.isNull("last_announce_at")) {
                null
            } else {
                (obj.optDouble("last_announce_at", 0.0) * 1000).toLong()
            },
            lxmfAddress = if (obj.isNull("lxmf_address")) null else obj.optString("lxmf_address"),
            publicKeyHex = obj.optStringOrNull("public_key"),
            identityHash = obj.optStringOrNull("identity_hash"),
            hostedNodeHash = obj.optStringOrNull("hosted_node_hash"),
            displayName = if (obj.isNull("display_name")) null else obj.optString("display_name"),
            iconAppearance = if (obj.isNull("icon_glyph")) {
                null
            } else {
                ContactIcon.Appearance(
                    glyphName = obj.getString("icon_glyph"),
                    backgroundColor = parseHexColor(obj.optStringOrNull("icon_bg"), Color(0xFF888888)),
                    foregroundColor = parseHexColor(obj.optStringOrNull("icon_fg"), Color.White),
                )
            },
            sendBlocked = obj.optBoolean("send_blocked", false),
            sendBlockedReason = if (obj.isNull("send_blocked_reason")) {
                null
            } else {
                obj.optString("send_blocked_reason")
            },
            messagesContactsOnly = obj.optBoolean("contacts_only_messages", false),
            callsContactsOnly = obj.optBoolean("calls_contacts_only", false),
            callsEnabled = obj.optBoolean("calls_enabled", true),
            retryViaRelay = obj.optBoolean("retry_via_relay", false),
        )
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

    /** `optString(key, null)` reads as a Java-platform `String!` to
     * Kotlin, which infers `Nothing?` for a literal `null` argument and
     * warns — this spells out the same "null when absent" behavior
     * without that inference trap. */
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key)

    /** Same "absent or JSON null → Kotlin null" rule as [optStringOrNull],
     * for the delivery-diagnostic fields (rssi/snr/quality/etc.) that are
     * genuinely absent for most messages today — see [Message]'s own doc
     * comment for why that's expected, not an error. */
    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key)

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
        if (isNull(key)) null else optBoolean(key)

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key)) null else optInt(key)

    private fun parseContact(obj: JSONObject): Contact {
        val hash = obj.getString("hash")
        // messaging.py stores a contact's icon as one of two mutually
        // exclusive descriptors — 0x06 raw image (base64 bytes under
        // `icon`) or 0x04 icon-appearance (structured `icon_glyph`/
        // `icon_fg`/`icon_bg`, never rasterized server-side) — see
        // contact_store.py's own doc comment. Raw image takes priority
        // when (implausibly) both are present, matching messaging.py's
        // own preference.
        val icon = if (!obj.isNull("icon")) {
            ContactIcon.RawImage(Base64.decode(obj.getString("icon"), Base64.DEFAULT))
        } else if (!obj.isNull("icon_glyph")) {
            ContactIcon.Appearance(
                glyphName = obj.getString("icon_glyph"),
                // Grey fallback matches messaging.py's own "#888888"
                // default (_rgba_to_hex) — kept consistent rather than
                // pulling in a UI theme color from this data layer.
                backgroundColor = parseHexColor(obj.optStringOrNull("icon_bg"), Color(0xFF888888)),
                foregroundColor = parseHexColor(obj.optStringOrNull("icon_fg"), Color.White),
            )
        } else {
            ContactIcon.None
        }
        return Contact(
            lxmfHash = hash,
            displayName = obj.optString("name").ifBlank { hash.take(16) },
            icon = icon,
            isFavorite = obj.optBoolean("favorited", false),
            isBlocked = obj.optBoolean("blocked", false),
            disappearingSeconds = obj.optInt("disappearing_seconds", 0),
            lastAnnounceMillis = if (obj.isNull("last_seen")) 0L else (obj.optDouble("last_seen", 0.0) * 1000).toLong(),
            hopCount = if (obj.isNull("hops")) -1 else obj.optInt("hops", -1),
            // Absent entirely from get_contact_json's smaller dict (only
            // get_conversations_json's summary carries it) — optInt's
            // default covers that the same way it already covers a null.
            announceCount = if (obj.isNull("announce_count")) 0 else obj.optInt("announce_count", 0),
            // Phase 0 voice-call support — see call_tracker.py's own doc
            // comment. Present in both get_conversations_json's summary
            // and get_contact_json's smaller dict, unlike announce_count
            // above, so no absent-field fallback is needed here.
            isCallCapable = obj.optBoolean("call_capable", false),
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
            attachment = obj.optJSONObject("attachment")?.let(::parseAttachment),
            expiresAtMillis = if (obj.isNull("expires_at")) null else (obj.optDouble("expires_at", 0.0) * 1000).toLong(),
            deliveryMethod = obj.optStringOrNull("method"),
            transportEncrypted = obj.optBooleanOrNull("transport_encrypted"),
            deliveryAttempts = obj.optIntOrNull("delivery_attempts"),
            rssi = obj.optDoubleOrNull("rssi"),
            snr = obj.optDoubleOrNull("snr"),
            quality = obj.optDoubleOrNull("quality"),
            stateChangedAtMillis = obj.optDoubleOrNull("state_changed_at")?.let { (it * 1000).toLong() },
        )
    }

    private fun parseAttachment(obj: JSONObject): Attachment = Attachment(
        kind = if (obj.optString("kind") == "image") AttachmentKind.IMAGE else AttachmentKind.FILE,
        filename = obj.optString("filename", "attachment"),
        mime = obj.optString("mime", "application/octet-stream"),
        sizeBytes = obj.optLong("size", 0L),
        path = obj.getString("path"),
    )

    private companion object {
        const val POLL_INTERVAL_MS = 4000L
    }
}
