package com.jamesm92.nomadportal.data.messaging

import androidx.compose.ui.graphics.Color

/**
 * A contact in the LXMF address book. [icon] mirrors LXMF's two
 * appearance fields (porting-notes.md §4): the `0x04` icon-appearance
 * field (glyph name + color, the common case) and the `0x06` raw-image
 * field (an actual bitmap the peer supplied). As of Aug 2026,
 * [com.jamesm92.nomadportal.data.messaging.RealMessagingRepository]
 * backs this with real LXMF data — but `messaging.py` pre-renders a
 * `0x04` icon-appearance into a flat SVG server-side before it's ever
 * stored, so in practice [icon] only ever comes back as
 * [ContactIcon.RawImage] (SVG or raster) or [ContactIcon.None], never
 * [ContactIcon.Appearance] with a live glyph/color pair — see that
 * repository's own doc comment. [ContactIcon.Appearance] is only real for
 * [com.jamesm92.nomadportal.data.messaging.StubMessagingRepository]'s
 * fake data now.
 */
data class Contact(
    val lxmfHash: String,
    val displayName: String,
    val icon: ContactIcon,
    val isFavorite: Boolean = false,
    /** Last LXMF peer announce heard for this hash, or 0 = never heard
     * one (e.g. a contact known only from message history/manual add,
     * matching [com.jamesm92.nomadportal.data.browsing.NodeInfo]'s same
     * convention for RNS node announces). */
    val lastAnnounceMillis: Long = 0L,
    /** -1 = unknown, matching NodeInfo's sentinel — no live path yet. */
    val hopCount: Int = -1,
    /** Total announces heard from this peer — powers
     * ConversationListScreen's "Announces" sort option, same convention
     * as [com.jamesm92.nomadportal.data.browsing.NodeInfo.announceCount]. */
    val announceCount: Int = 0,
)

sealed interface ContactIcon {
    /** LXMF field `0x04`: an icon glyph name + a color, chosen by the contact. */
    data class Appearance(val glyphName: String, val color: Color) : ContactIcon
    /** LXMF field `0x06`: a raw image the contact supplied, not yet decoded/rendered here. */
    class RawImage(val bytes: ByteArray) : ContactIcon {
        // Not a data class: ByteArray needs content-aware equals/hashCode,
        // which `data class` would generate as reference-based instead.
        override fun equals(other: Any?): Boolean =
            other is RawImage && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }
    /** Neither field present — render initials instead. */
    data object None : ContactIcon
}

enum class DeliveryState { QUEUED, DELIVERED, FAILED }

/**
 * A single message. [content] is always the full message text — never a
 * truncated preview (porting-notes.md §6's "store what you display" bug:
 * a prior implementation stored only a 120-char preview and the full-view
 * silently fell back to that same clipped string when the full field was
 * absent). Any UI-level truncation for a list preview must happen at
 * render time against this full string, never by storing a shorter one.
 */
data class Message(
    val id: String,
    val content: String,
    val timestampMillis: Long,
    val isSent: Boolean,
    /** Only meaningful when [isSent] is true — null for received messages. */
    val deliveryState: DeliveryState? = null,
)

data class ConversationSummary(
    val contact: Contact,
    val lastMessage: Message?,
    val unreadCount: Int,
)

/**
 * One interface's (bluetooth_mesh/rnode/tcp) own announce policy —
 * two independent knobs, per explicit design direction:
 * - [announceMaxSeconds] ("messages announce time max"): how stale this
 *   device's last announce is allowed to get before a *send* needs a
 *   fresh one first.
 * - [autoAnnounceEnabled]/[autoAnnounceIntervalSeconds] ("auto announce
 *   time"): whether/how often this device proactively re-announces on
 *   its own initiative, independent of whether a message happens to be
 *   going out. Can be disabled per interface — but see [AnnounceStatus]'s
 *   own doc comment for what disabling it actually means for sending.
 */
data class InterfaceAnnounceConfig(
    val announceMaxSeconds: Int,
    val autoAnnounceEnabled: Boolean,
    val autoAnnounceIntervalSeconds: Int,
)

/**
 * Auto-announce configuration/status for this device's own LXMF
 * delivery identity — not to be confused with [Contact.lastAnnounceMillis]
 * (someone else's announces, heard by us). This is about *our own*
 * outbound announcing, which the LXMF/RNS protocol requires at least
 * once before any other peer can discover a path to deliver a message
 * to us at all (path discovery is fundamentally announce-based). Every
 * identity already gets one bootstrap announce automatically the moment
 * it's created, regardless of any setting here, so a fresh install is
 * never unreachable out of the box.
 *
 * RNS's own announce() call always broadcasts to every currently-active
 * interface at once — there's no API to target one specific interface,
 * so [interfaces] drives *timing* decisions only (which interfaces
 * being active determines which thresholds apply), never which
 * interface actually carries the announce packet.
 *
 * [sendBlocked]/[sendBlockedReason] is a read-only preview of whether
 * the next [MessagingRepository.sendMessage] call would currently be
 * refused: true when every currently-active interface has
 * [InterfaceAnnounceConfig.autoAnnounceEnabled] off *and* the last
 * announce is older than the strictest active
 * [InterfaceAnnounceConfig.announceMaxSeconds] — this device can't
 * autonomously fix that (auto-announce being off means exactly "don't
 * announce without being asked to"), so sending stops and says why
 * instead of silently going out over a possibly-unreachable identity.
 */
data class AnnounceStatus(
    val interfaces: Map<String, InterfaceAnnounceConfig>,
    /** Null if this identity has never announced yet. */
    val lastAnnounceAtMillis: Long?,
    /** Null before the delivery router exists (e.g. RNS still starting up). */
    val lxmfAddress: String?,
    val sendBlocked: Boolean,
    val sendBlockedReason: String?,
) {
    companion object {
        const val INTERFACE_BLUETOOTH = "bluetooth_mesh"
        const val INTERFACE_RNODE = "rnode"
        const val INTERFACE_TCP = "tcp"
        const val MIN_SECONDS = 60
        const val MAX_SECONDS = 24 * 60 * 60
    }
}
