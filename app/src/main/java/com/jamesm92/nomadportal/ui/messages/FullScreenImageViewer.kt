package com.jamesm92.nomadportal.ui.messages

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jamesm92.nomadportal.data.messaging.Attachment

/**
 * Full-screen, pinch-zoomable/pannable image viewer — per explicit
 * direction ("clicking an image in a conversation should allow you to
 * see it full screen, and zoom it, from there you can be given the
 * option to download"). Same full-screen [Dialog] pattern already
 * established for HomeScreen.kt's icon picker
 * (`usePlatformDefaultWidth = false`, its own window rather than
 * expanding inline within a scrollable parent) — reused here for the
 * same reason: a real window, not fighting a parent's layout bounds.
 *
 * Zoom/pan is a plain [Modifier.pointerInput] +
 * [detectTransformGestures] + [Modifier.graphicsLayer] — Compose
 * foundation already covers this, no new dependency needed (same
 * "don't reach for a library Compose itself already provides" pattern
 * as the MDI icon catalog's [androidx.compose.ui.graphics.vector.PathParser]
 * use).
 */
@Composable
fun FullScreenImageViewer(attachment: Attachment, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bitmap = rememberAttachmentBitmap(attachment.path)

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = attachment.filename,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale, scaleY = scale,
                                translationX = offset, translationY = offsetY,
                            )
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    // Clamped rather than left unbounded —
                                    // an accidental pinch-past-zero would
                                    // otherwise flip the image inside out,
                                    // and unbounded zoom-in just pushes the
                                    // image off past any useful detail.
                                    scale = (scale * zoom).coerceIn(1f, 6f)
                                    // Panning only matters once zoomed in —
                                    // at scale 1 it would just drag the
                                    // whole image off-screen with nothing
                                    // to see past its own edge.
                                    if (scale > 1f) {
                                        offset += pan.x
                                        offsetY += pan.y
                                    } else {
                                        offset = 0f
                                        offsetY = 0f
                                    }
                                }
                            },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                    }
                    Row {
                        IconButton(onClick = {
                            val saved = AttachmentFileProvider.saveToDownloads(
                                context, attachment.path, attachment.mime, attachment.filename,
                            )
                            Toast.makeText(
                                context,
                                if (saved) "Saved to Downloads" else "Couldn't save file",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }) {
                            Icon(Icons.Filled.Download, contentDescription = "Save", tint = Color.White)
                        }
                        IconButton(onClick = {
                            AttachmentFileProvider.share(context, attachment.path, attachment.mime, attachment.filename)
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}
