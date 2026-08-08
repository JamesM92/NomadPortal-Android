package com.jamesm92.nomadportal.data.messaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream

/**
 * Reads the raw bytes + a real display name for a picked file/audio
 * attachment (i.e. anything that isn't going through
 * [compressImageForSend]) — no re-encoding, sent as-is via
 * `FIELD_FILE_ATTACHMENTS` (see [MessagingRepository.sendMessage]'s own
 * doc comment for why audio files go through this path too, not a
 * dedicated audio field). Null on any read failure (revoked permission,
 * provider gone, etc.) — callers surface that as a plain send error,
 * same as any other failed send.
 */
fun readAttachmentForSend(context: Context, uri: Uri): PickedAttachment? {
    return try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val filename = queryDisplayName(context, uri) ?: "attachment"
        val mime = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getFileExtensionFromUrl(filename)?.let {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
            }
            ?: "application/octet-stream"
        PickedAttachment(filename, bytes, mime)
    } catch (e: Exception) {
        null
    }
}

/**
 * A user-facing choice of how much to shrink an outgoing image before
 * sending — per explicit direction ("images should be scaled for
 * reduced bandwidth if needed" + a prompt for which size). [maxDimension]/
 * [quality] values match Sideband's own three real tiers exactly
 * (verified directly against its source — see the
 * nomadportal-android-competitor-research memory), not guessed
 * equivalents, so a size choice here means roughly the same thing it
 * would in Sideband. A raw file attachment (see [readAttachmentForSend])
 * has no equivalent prompt — it's always sent as-is, per the same
 * explicit direction ("if its a file its assumed to be the raw file").
 */
enum class ImageSizeTier(val maxDimension: Int, val quality: Int, val label: String, val description: String) {
    LOW(320, 22, "Low bandwidth", "Smallest file, most compressed — best for slow links (LoRa, weak mesh hops)"),
    MEDIUM(640, 66, "Medium", "Balanced size and quality — good default for most links"),
    HIGH(1280, 75, "High quality", "Largest file, sharpest image — best when bandwidth isn't a concern"),
}

/**
 * Downscales + re-encodes a picked image to WEBP at the chosen [tier]
 * before it ever reaches `send_message` — matching Sideband's own
 * low-bandwidth-link-conscious approach rather than sending an original
 * multi-MB photo over what might be a LoRa link.
 *
 * Downsampled via [BitmapFactory.Options.inSampleSize] computed from the
 * real image bounds first (`inJustDecodeBounds`), not decoded at full
 * resolution then scaled down — avoids an OOM on a large photo from a
 * modern phone camera.
 */
fun compressImageForSend(context: Context, uri: Uri, tier: ImageSizeTier): PickedAttachment? {
    val resolver = context.contentResolver
    val maxDimension = tier.maxDimension
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= maxDimension && bounds.outHeight / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
            ?: return null

        val scale = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            bitmap
        }

        val out = ByteArrayOutputStream()
        val format = if (android.os.Build.VERSION.SDK_INT >= 30) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        scaled.compress(format, tier.quality, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()

        PickedAttachment("image.webp", out.toByteArray(), "image/webp")
    } catch (e: Exception) {
        null
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }
}

/** Bytes ready to hand to [MessagingRepository.sendMessage] as-is. */
data class PickedAttachment(val filename: String, val bytes: ByteArray, val mime: String)

/** Same size cap as `nomadnet_web.messaging.MAX_ATTACHMENT_BYTES` —
 * checked client-side too so a user finds out before waiting on a round
 * trip through Chaquopy for something that was always going to be
 * rejected. Keep in sync with the Python constant if that ever changes. */
const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024
