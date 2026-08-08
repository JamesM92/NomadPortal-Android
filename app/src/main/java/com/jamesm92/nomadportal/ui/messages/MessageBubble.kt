package com.jamesm92.nomadportal.ui.messages

import android.graphics.BitmapFactory
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
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
                    Text(text = message.content, style = MaterialTheme.typography.bodyLarge)
                }
                // Timestamp + delivery status share one small metadata
                // row below the message text, both at half the message
                // text's size — they're secondary information, not
                // something that should compete with the content itself
                // for visual weight.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = formatMessageTimestamp(message.timestampMillis),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.5f,
                        ),
                        color = NomadTextDim,
                    )
                    message.deliveryState?.let { state ->
                        Text(
                            text = when (state) {
                                DeliveryState.QUEUED -> "Queued"
                                DeliveryState.DELIVERED -> "Delivered"
                                DeliveryState.FAILED -> "Failed"
                            },
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.5f,
                            ),
                            color = NomadTextDim,
                        )
                    }
                }
            }
        }
    }
}

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
 * case) that hands off to the system share sheet via
 * [AttachmentFileProvider] — this app has no built-in file viewer/audio
 * player of its own, and shouldn't need one when every Android device
 * already has one for essentially any real file type.
 */
@Composable
private fun AttachmentContent(attachment: Attachment, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (attachment.kind == AttachmentKind.IMAGE) {
        val bitmap = rememberAttachmentBitmap(attachment.path)
        Box(
            modifier = modifier
                .heightIn(max = 220.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(NomadBg3)
                .clickable {
                    AttachmentFileProvider.share(context, attachment.path, attachment.mime, attachment.filename)
                },
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
    } else {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(NomadBg3)
                .clickable {
                    AttachmentFileProvider.share(context, attachment.path, attachment.mime, attachment.filename)
                }
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
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.85f,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatFileSize(attachment.sizeBytes),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.6f,
                    ),
                    color = NomadTextDim,
                )
            }
        }
    }
}

/** Decodes off the main thread (BitmapFactory.decodeFile is blocking
 * disk+CPU work) — re-decodes if [path] changes, cached for free
 * otherwise by Compose's own `produceState` recomposition scoping. Null
 * while loading and if decoding fails (missing/corrupt file). */
@Composable
private fun rememberAttachmentBitmap(path: String): ImageBitmap? {
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
