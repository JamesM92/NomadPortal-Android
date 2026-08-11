package com.jamesm92.nomadportal.ui.messages

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.messaging.Attachment
import com.jamesm92.nomadportal.data.messaging.AttachmentKind
import com.jamesm92.nomadportal.data.messaging.DeliveryState
import com.jamesm92.nomadportal.data.messaging.Message
import com.jamesm92.nomadportal.ui.theme.NomadBg3
import com.jamesm92.nomadportal.ui.theme.NomadBorder
import com.jamesm92.nomadportal.ui.theme.NomadSentBubble
import com.jamesm92.nomadportal.ui.theme.NomadSentBubbleBorder
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sent/received bubble styling matches porting-notes.md §5 exactly: sent
 * = right-aligned, `#173040` background, `#2a5570` border; received =
 * left-aligned, `--bg3`/`--border`. Small radius with a sharp corner on
 * the "speaker" side (bottom-right for sent, bottom-left for received) —
 * called out in that doc specifically as an easy detail to skip, so it's
 * not skipped here.
 */
@Composable
fun MessageBubble(message: Message, modifier: Modifier = Modifier) {
    val bubbleColor = if (message.isSent) NomadSentBubble else NomadBg3
    val borderColor = if (message.isSent) NomadSentBubbleBorder else NomadBorder
    val shape = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp,
        bottomStart = if (message.isSent) 12.dp else 2.dp,
        bottomEnd = if (message.isSent) 2.dp else 12.dp,
    )
    var detailsOpen by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (message.isSent) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(shape)
                .background(bubbleColor)
                .border(1.dp, borderColor, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                message.attachment?.let { attachment ->
                    AttachmentContent(attachment, modifier = Modifier.padding(bottom = if (message.content.isNotBlank()) 4.dp else 0.dp))
                }
                // Attachment-only messages (Sideband allows an empty
                // content string alongside a FIELD_IMAGE/FIELD_FILE_
                // ATTACHMENTS field — see messaging.py's own doc
                // comment) shouldn't render an empty text line.
                if (message.content.isNotBlank()) {
                    // SelectionContainer, not just a plain Text — per a
                    // real on-device report ("we need the ability to
                    // highlight / copy text from a conversation"), scoped
                    // to just the message text (not the whole bubble, so
                    // the attachment chip/timestamp/delivery-state rows
                    // stay purely tap-driven, not swept into a text
                    // selection). Long-press-drag brings up the same
                    // native Cut/Copy/Paste-family toolbar as everywhere
                    // else text is selectable in this app.
                    SelectionContainer {
                        Text(text = message.content, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                // Timestamp + delivery status share one small metadata
                // row below the message text — secondary information, not
                // something that should compete with the content itself
                // for visual weight. labelSmall, not the original half-size
                // multiplier (~8sp) — that computed smaller than any real
                // M3 role goes, a genuine legibility fix picked up for
                // free by moving onto the real type scale, not a
                // deliberate size this row still needs to hit.
                // Tapping this row opens the delivery-details dialog
                // below — per the same "tap opens technical info" pattern
                // as NetworkScreen's own announce rows (a real per-message
                // counterpart to that, not a copy of it: real LXMessage
                // delivery diagnostics, see MessageBubble's own dialog
                // doc comment for what's actually backed by real data).
                Row(
                    modifier = Modifier.clickable { detailsOpen = true },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = formatMessageTimestamp(message.timestampMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = NomadTextDim,
                    )
                    message.deliveryState?.let { state ->
                        Text(
                            text = when (state) {
                                DeliveryState.QUEUED -> "Queued"
                                DeliveryState.DELIVERED -> "Delivered"
                                DeliveryState.FAILED -> "Failed"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = NomadTextDim,
                        )
                    }
                }
            }
        }
    }

    if (detailsOpen) {
        MessageDeliveryDetailsDialog(message = message, onDismiss = { detailsOpen = false })
    }
}

/**
 * Real per-message delivery diagnostics — see [Message]'s own doc comment
 * for exactly which fields are backed by real LXMF/RNS data vs. honestly
 * absent today. Deliberately doesn't hide RSSI/SNR/quality rows when
 * they're null (rather than omitting them entirely) — showing "not
 * reported by this interface" makes clear this is a real, known gap
 * (no active interface reports it yet, not a bug), the same "real data or
 * an honest gap, never faked" rule this app follows everywhere else.
 */
@Composable
private fun MessageDeliveryDetailsDialog(message: Message, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (message.isSent) "Delivery Details" else "Message Details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DetailRow(if (message.isSent) "Sent" else "Received", formatDetailTimestamp(message.timestampMillis))
                if (message.isSent) {
                    message.deliveryState?.let { state ->
                        DetailRow(
                            "State",
                            when (state) {
                                DeliveryState.QUEUED -> "Queued"
                                DeliveryState.DELIVERED -> "Delivered"
                                DeliveryState.FAILED -> "Failed"
                            },
                        )
                    }
                    message.stateChangedAtMillis?.let { DetailRow("Updated", formatDetailTimestamp(it)) }
                    message.deliveryAttempts?.let { DetailRow("Delivery attempts", it.toString()) }
                }
                DetailRow("Method", formatDeliveryMethod(message.deliveryMethod))
                message.transportEncrypted?.let { DetailRow("Transport encrypted", if (it) "Yes" else "No") }
                DetailRow("RSSI", formatRfStat(message.rssi, "dBm"))
                DetailRow("SNR", formatRfStat(message.snr, "dB"))
                DetailRow("Link quality", formatRfStat(message.quality, "%"))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = NomadTextDim)
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** "Opportunistic"/"Direct"/"Propagated"/"Paper" from messaging.py's own
 * lowercase labels (mirroring LXMessage.method's real constants — see
 * that module's `_LXMF_METHOD_NAMES`), title-cased for display. Null
 * means the delivery/receive event that would populate it hasn't
 * happened yet (e.g. still queued) — shown as "Pending", not "Unknown"
 * (which messaging.py reserves for LXMessage.method's own real UNKNOWN
 * constant, a genuinely different case). */
private fun formatDeliveryMethod(method: String?): String =
    method?.replaceFirstChar { it.uppercase() } ?: "Pending"

/** Shared formatter for RSSI/SNR/link-quality — see [MessageDeliveryDetailsDialog]'s
 * own doc comment for why null is expected, not an error, for most
 * messages today. */
private fun formatRfStat(value: Double?, unit: String): String =
    if (value == null) "Not reported by this interface" else "%.1f %s".format(value, unit)

private val detailTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())

private fun formatDetailTimestamp(timestampMillis: Long): String =
    Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()).format(detailTimeFormatter)

/** Absolute time-of-day, not relative — the standard chat-app convention
 * for an individual message, unlike the "3m ago"/"just now" relative
 * phrasing used elsewhere in this app for last-announce/last-seen times
 * (which suits a constantly-refreshing list, not a fixed sent time). */
private val messageTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

private fun formatMessageTimestamp(timestampMillis: Long): String =
    Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()).format(messageTimeFormatter)

/**
 * Renders one [Attachment] inline in a bubble: an actual decoded
 * thumbnail for [AttachmentKind.IMAGE], a tappable filename/size "chip"
 * for [AttachmentKind.FILE] (including audio files — see
 * [Attachment]'s own doc comment for why those aren't a distinct third
 * case). Tapping either opens a small menu with two genuinely different
 * actions — **Save** ([AttachmentFileProvider.saveToDownloads], a real
 * copy into the device's own Downloads) and **Share** (hand it to
 * another specific app) — not just Share alone, per a real on-device
 * report ("it doesn't give me the option to download, only the option
 * to share"). This app has no built-in file viewer/audio player of its
 * own and shouldn't need one — every Android device already has one for
 * essentially any real file type, once the file is actually saved
 * somewhere the user can get back to it.
 */
@Composable
private fun AttachmentContent(attachment: Attachment, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    @Composable
    fun AttachmentActionMenu() {
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Save") },
                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    val saved = AttachmentFileProvider.saveToDownloads(
                        context, attachment.path, attachment.mime, attachment.filename,
                    )
                    Toast.makeText(
                        context,
                        if (saved) "Saved to Downloads" else "Couldn't save file",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
            DropdownMenuItem(
                text = { Text("Share") },
                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    AttachmentFileProvider.share(context, attachment.path, attachment.mime, attachment.filename)
                },
            )
        }
    }

    if (attachment.kind == AttachmentKind.IMAGE) {
        val bitmap = rememberAttachmentBitmap(attachment.path)
        // Tapping an image opens the full-screen zoomable viewer (per
        // explicit direction) rather than the Save/Share menu directly
        // — that menu's Download action lives inside the viewer itself
        // instead (see FullScreenImageViewer.kt). Only offered once the
        // bitmap has actually decoded — nothing real to view otherwise.
        var viewerOpen by remember { mutableStateOf(false) }
        Box(
            modifier = modifier
                .heightIn(max = 220.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(NomadBg3)
                .clickable(enabled = bitmap != null) { viewerOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = attachment.filename)
            } else {
                // Still loading (decode happens off the main thread —
                // see rememberAttachmentBitmap) or the file is genuinely
                // gone/corrupt — either way, something visible rather
                // than a blank box.
                Icon(
                    imageVector = Icons.Filled.BrokenImage,
                    contentDescription = null,
                    tint = NomadTextDim,
                    modifier = Modifier.size(32.dp).padding(24.dp),
                )
            }
        }
        if (viewerOpen) {
            FullScreenImageViewer(attachment = attachment, onDismiss = { viewerOpen = false })
        }
    } else {
        Box {
            Row(
                modifier = modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(NomadBg3)
                    .clickable { menuExpanded = true }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (attachment.mime.startsWith("audio/")) Icons.Filled.AudioFile else Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = NomadTextDim,
                )
                Column {
                    Text(
                        text = attachment.filename,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatFileSize(attachment.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = NomadTextDim,
                    )
                }
            }
            AttachmentActionMenu()
        }
    }
}

/** Decodes off the main thread (BitmapFactory.decodeFile is blocking
 * disk+CPU work) — re-decodes if [path] changes, cached for free
 * otherwise by Compose's own `produceState` recomposition scoping. Null
 * while loading and if decoding fails (missing/corrupt file). Not
 * `private` — also used by [FullScreenImageViewer]. */
@Composable
fun rememberAttachmentBitmap(path: String): ImageBitmap? {
    val state = produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            try {
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    return state.value
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
