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

    /**
     * [attachmentData] is the already-final bytes to send — for an image
     * that means already downscaled/re-encoded client-side (see
     * ConversationScreen.kt's `compressImageForSend`, matching Sideband's
     * own low-bandwidth-link-conscious approach — see the
     * nomadportal-android-competitor-research memory) before this is
     * ever called, this layer does no transcoding of its own.
     * [attachmentKind] governs which LXMF field carries it
     * (FIELD_IMAGE vs FIELD_FILE_ATTACHMENTS — see
     * `nomadnet_web.messaging.MessagingService.send_message`'s own doc
     * comment for exactly why an audio file is [AttachmentKind.FILE],
     * not a dedicated audio kind). [imageFormat] (e.g. "webp") is only
     * consulted when [attachmentKind] is [AttachmentKind.IMAGE].
     *
     * Throws on failure — including a rejected oversized attachment
     * (`nomadnet_web.messaging.MAX_ATTACHMENT_BYTES`) — same contract as
     * every other failure mode here.
     */
    suspend fun sendMessage(
        contactHash: String,
        content: String,
        attachmentFilename: String? = null,
        attachmentData: ByteArray? = null,
        attachmentKind: AttachmentKind = AttachmentKind.FILE,
        imageFormat: String? = null,
    )
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

    /**
     * Sets this conversation's disappearing-messages duration —
     * [seconds] of 0 turns it off. Purely forward-looking: only messages
     * sent/received *after* this call get a timer, matching Signal's
     * own "not retroactive" behavior; nothing about already-stored
     * messages changes.
     *
     * **Local-only, not enforced with the recipient** — LXMF has no
     * protocol mechanism to communicate a disappearing timer to the
     * other party's device at all, so their own copy of this
     * conversation is entirely outside this app's control regardless
     * of what's set here. Any UI exposing this must make that
     * unmistakable, not just imply Signal-equivalent behavior.
     *
     * Returns true on success.
     */
    suspend fun setDisappearingTimer(contactHash: String, seconds: Int): Boolean
}
