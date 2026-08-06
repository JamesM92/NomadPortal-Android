package com.jamesm92.nomadportal.data.messaging

import androidx.compose.ui.graphics.Color

/**
 * A contact in the LXMF address book. [icon] mirrors LXMF's two
 * appearance fields (porting-notes.md §4): the `0x04` icon-appearance
 * field (glyph name + color, the common case) and the `0x06` raw-image
 * field (an actual bitmap the peer supplied). Real LXMF delivery/parsing
 * isn't wired up yet (still core-extraction work) — [icon] is currently
 * only ever [ContactIcon.Appearance] from
 * [com.jamesm92.nomadportal.data.messaging.StubMessagingRepository]'s
 * fake data.
 */
data class Contact(
    val lxmfHash: String,
    val displayName: String,
    val icon: ContactIcon,
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
