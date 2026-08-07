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
 * [RealMessagingRepository] is the live implementation (Aug 2026), backed
 * by `nomadportal_core.orchestrator`'s messaging bridge — real LXMF
 * send/receive via `MessagingService`. [StubMessagingRepository] predates
 * it and is kept as a minimal reference/test implementation (fake,
 * in-memory, no real LXMF delivery).
 */
interface MessagingRepository {
    /**
     * Every known contact — anyone favorited, anyone with message history,
     * and anyone heard via a bare LXMF peer announce (never messaged) —
     * not just active conversations despite the name. See
     * [com.jamesm92.nomadportal.ui.messages.ConversationListScreen] for
     * how this gets split into Favorites/Messaged/Announces-heard
     * sections, mirroring [com.jamesm92.nomadportal.ui.browser.NodeListScreen].
     */
    fun conversations(): Flow<List<ConversationSummary>>
    fun messages(contactHash: String): Flow<List<Message>>
    suspend fun sendMessage(contactHash: String, content: String)
    suspend fun markRead(contactHash: String)
    suspend fun setFavorite(contactHash: String, favorite: Boolean)

    /** Synchronous lookup — contact display data (name/icon) for a known hash, or null if not found. */
    fun contact(contactHash: String): Contact?

    /**
     * Auto-announce status/config, per interface — see [AnnounceStatus]'s
     * own doc comment for why this exists at all (announcing at least
     * once is an LXMF protocol requirement for this identity to be
     * reachable, not just a cosmetic feature).
     */
    fun announceStatus(): Flow<AnnounceStatus>

    /** [interfaceKey] is one of [AnnounceStatus.INTERFACE_BLUETOOTH]/
     * [AnnounceStatus.INTERFACE_RNODE]/[AnnounceStatus.INTERFACE_TCP]. */
    suspend fun setAnnounceMax(interfaceKey: String, seconds: Int)
    suspend fun setAutoAnnounceEnabled(interfaceKey: String, enabled: Boolean)
    suspend fun setAutoAnnounceInterval(interfaceKey: String, seconds: Int)

    /** Manual "Announce now" trigger. Returns true on success. */
    suspend fun announceNow(): Boolean
}
