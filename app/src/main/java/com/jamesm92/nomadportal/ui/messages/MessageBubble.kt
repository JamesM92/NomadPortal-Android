package com.jamesm92.nomadportal.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.messaging.DeliveryState
import com.jamesm92.nomadportal.data.messaging.Message
import com.jamesm92.nomadportal.ui.theme.NomadBg3
import com.jamesm92.nomadportal.ui.theme.NomadBorder
import com.jamesm92.nomadportal.ui.theme.NomadSentBubble
import com.jamesm92.nomadportal.ui.theme.NomadSentBubbleBorder
import com.jamesm92.nomadportal.ui.theme.NomadTextDim

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
                Text(text = message.content, style = MaterialTheme.typography.bodyLarge)
                message.deliveryState?.let { state ->
                    Text(
                        text = when (state) {
                            DeliveryState.QUEUED -> "Queued"
                            DeliveryState.DELIVERED -> "Delivered"
                            DeliveryState.FAILED -> "Failed"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = NomadTextDim,
                    )
                }
            }
        }
    }
}
