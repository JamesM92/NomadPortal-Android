package com.jamesm92.nomadportal.data.messaging

import kotlinx.coroutines.flow.Flow

/**
 * The real interface the Messages screens will be built against —
 * matches the pattern already established by
 * [com.jamesm92.nomadportal.connectivity.InterfaceController]: build the
 * UI against this interface now, swap the implementation for a real
 * LXMF-backed one once the core extraction
 * (nomadportal_android_handoff.md sequencing step 1) lands, without the
 * screens needing to change.
 *
 * [StubMessagingRepository] is the only implementation right now — fake,
 * in-memory, no real LXMF delivery.
 */
interface MessagingRepository {
    fun conversations(): Flow<List<ConversationSummary>>
    fun messages(contactHash: String): Flow<List<Message>>
    suspend fun sendMessage(contactHash: String, content: String)
    suspend fun markRead(contactHash: String)

    /** Synchronous lookup — contact display data (name/icon) for a known hash, or null if not found. */
    fun contact(contactHash: String): Contact?
}
