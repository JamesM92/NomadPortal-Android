package com.jamesm92.nomadportal.data.messaging

import androidx.compose.ui.graphics.Color
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

    /** Explicitly, permanently renames a contact — once set, this name
     * stops tracking their live LXMF-announced name (see orchestrator.py's
     * `_conversation_entries()` own doc comment). Blank names are
     * rejected (returns false). */
    suspend fun setContactName(contactHash: String, name: String): Boolean

    /** Deletes this chat: all message history with this contact plus
     * their saved name/icon/favorite state. They can still reappear
     * under Users/Announces-heard if actively announcing — this only
     * clears this device's own saved data about them. */
    suspend fun deleteConversation(contactHash: String)

    /** Synchronous lookup — contact display data (name/icon) for a known hash, or null if not found. */
    fun contact(contactHash: String): Contact?

    /**
     * Auto-announce status/config, per interface — see [AnnounceStatus]'s
     * own doc comment for why this exists at all (announcing at least
     * once is an LXMF protocol requirement for this identity to be
     * reachable, not just a cosmetic feature).
     */
    fun announceStatus(): Flow<AnnounceStatus>

    /** [interfaceKey] is one of [AnnounceStatus.INTERFACE_TCP]/
     * [AnnounceStatus.INTERFACE_BLUETOOTH]/[AnnounceStatus.INTERFACE_RNODE]/
     * [AnnounceStatus.INTERFACE_WIFI_DISCOVERY]. */
    suspend fun setAnnounceMax(interfaceKey: String, seconds: Int)

    /** 0 disables auto-announce for this interface — no separate enabled flag. */
    suspend fun setAutoAnnounceInterval(interfaceKey: String, seconds: Int)

    /** The single aggregate toggle on top of every interface's own auto-announce interval. */
    suspend fun setAutoAnnounceMaster(enabled: Boolean)

    /** Manual "Announce now" trigger. Returns true on success. */
    suspend fun announceNow(): Boolean

    /** Renames this device's LXMF identity. Returns true on success. */
    suspend fun setDisplayName(name: String): Boolean

    /** Sets this device's own FIELD_ICON_APPEARANCE — [glyphName] should
     * be one of [ICON_APPEARANCE_NAMES] (the Home screen's glyph editor
     * only offers names this app can actually resolve back to a real
     * icon, per [materialIconFor]'s own doc comment). Returns true on
     * success. */
    suspend fun setIconAppearance(glyphName: String, foreground: Color, background: Color): Boolean
}
