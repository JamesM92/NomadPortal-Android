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

    /** Marks this conversation's single most-recently-received message
     * back to unread — not the whole history (see orchestrator.py's
     * `mark_conversation_unread` for why: a one-shot visual "look at
     * this again" nudge, matching Gmail/most real messaging apps' own
     * semantics, not literally re-hiding every message). A no-op if this
     * contact has no received messages at all. */
    suspend fun markUnread(contactHash: String)
    suspend fun setFavorite(contactHash: String, favorite: Boolean)

    /** Blocks or unblocks a contact. A blocked contact's inbound
     * messages are dropped outright server-side (see
     * `nomadnet_web.messaging.MessagingService._on_delivery`) — never
     * stored, never surfaced anywhere, not just hidden client-side. Does
     * not otherwise remove or hide the contact from any list; existing
     * message history is untouched. */
    suspend fun setBlocked(contactHash: String, blocked: Boolean)

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

    /** See [PropagationSyncStatus]'s own doc comment. Polled, same as
     * [conversations]/[messages] — the underlying sync itself runs on a
     * background Python thread independent of any UI observing it. */
    fun propagationSyncStatus(): Flow<PropagationSyncStatus>

    /** Every known propagation node — see [RelayNode]'s own doc comment
     * for why this is a separate list from [conversations], not a
     * filtered view of it. Polled, same convention as [conversations]. */
    fun relayNodes(): Flow<List<RelayNode>>

    /** Manual "Sync now" trigger. Returns a short, UI-displayable
     * success message; throws on failure (e.g. no propagation node
     * discovered yet) with a UI-displayable reason, same contract as
     * [sendMessage]. Only confirms the sync *request* was initiated —
     * [propagationSyncStatus]'s own transferState/transferProgress is
     * what shows the real, in-progress mailbox round trip afterward. */
    suspend fun triggerPropagationSync(): String

    /**
     * Registers a scanned QR contact's real identity (destination hash +
     * public key) immediately, without waiting to hear a real mesh
     * announce from it first — the real reliability benefit of
     * [AnnounceStatus.publicKeyHex] existing at all, see that field's own
     * doc comment. Also favorites the contact, same "a deliberately
     * scanned/entered address is at least as intentional as one typed by
     * hand" convention every other manual-entry path in this app already
     * follows.
     *
     * Throws on failure (malformed hex, wrong key length) with a
     * UI-displayable reason, same contract as [sendMessage] — a caller
     * that doesn't care about the failure reason (e.g. a best-effort call
     * fired alongside filling in a scanned address either way) can just
     * swallow it, same as [AddByAddressDialog]'s own graceful-degradation
     * philosophy for an invalid/unreachable manually-entered address.
     */
    suspend fun importScannedContact(destinationHash: String, publicKeyHex: String)

    /**
     * "Messages from contacts only" allowlist mode — see
     * [AnnounceStatus.messagesContactsOnly]'s own doc comment for what
     * this actually does. Unlike most of this interface's other
     * `suspend fun` setters (Python-bridge only), this one also persists
     * the choice (Kotlin DataStore) so it survives an app restart —
     * deliberately, since a privacy-protective toggle silently resetting
     * to permissive on restart would be a real footgun, not just a minor
     * inconvenience like most of this app's other ephemeral toggles.
     */
    suspend fun setMessagesContactsOnly(enabled: Boolean)

    /**
     * "Calls from contacts only" allowlist mode — see
     * [AnnounceStatus.callsContactsOnly]'s own doc comment for what this
     * actually does. Same persistence rationale as
     * [setMessagesContactsOnly] (a privacy-protective toggle resetting to
     * permissive on restart would be a real footgun), but deliberately a
     * separate persisted setting, not shared with it.
     */
    suspend fun setCallsContactsOnly(enabled: Boolean)

    /**
     * Master "Allow incoming voice calls" toggle — see
     * [AnnounceStatus.callsEnabled]'s own doc comment for what this
     * actually does. Same persistence rationale as
     * [setMessagesContactsOnly]/[setCallsContactsOnly], and deliberately
     * a separate persisted setting from both — independent of, and
     * enforced ahead of, [setCallsContactsOnly]'s own allowlist.
     */
    suspend fun setCallsEnabled(enabled: Boolean)

    /**
     * "Retry via relay on failure" — see [AnnounceStatus.retryViaRelay]'s
     * own doc comment for what this actually does. Unlike
     * [setMessagesContactsOnly], this one is Python-bridge only, no
     * Kotlin-side persistence — a delivery-reliability preference
     * resetting to off on restart is an acceptable minor inconvenience,
     * not the kind of footgun that justified real persistence for
     * contacts-only mode.
     */
    suspend fun setRetryViaRelay(enabled: Boolean)
}
