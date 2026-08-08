package com.jamesm92.nomadportal.ui.messages

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

/**
 * Wraps the `androidx.core.content.FileProvider` declared in
 * AndroidManifest.xml (backed by res/xml/file_paths.xml) — the only way
 * to hand another app (the system share sheet, an image viewer, an audio
 * player) a real path this app's own private storage without exposing a
 * bare `file://` URI, which modern Android just blocks
 * (`FileUriExposedException`).
 */
object AttachmentFileProvider {
    private fun uriFor(context: Context, path: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))

    /** Opens the system share sheet for one attachment file — for
     * handing it to another specific app (a messaging app, an editor,
     * etc.), not for keeping a copy on this device — see [saveToDownloads]
     * for that, a genuinely different action a real on-device report
     * flagged as missing ("it doesn't give me the option to download,
     * only the option to share"). */
    fun share(context: Context, path: String, mime: String, displayName: String) {
        val uri = uriFor(context, path)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, displayName))
    }

    /**
     * Copies the attachment into the device's own Downloads collection
     * via [MediaStore] — no `WRITE_EXTERNAL_STORAGE` permission needed
     * (or requested anywhere in this app — see AndroidManifest.xml)
     * since minSdk 31 is always on scoped storage, where an app can
     * freely insert its own new entries into MediaStore's Downloads
     * collection without broad storage access. Returns true on success.
     *
     * A duplicate filename doesn't overwrite or fail — MediaStore
     * itself auto-suffixes it (e.g. "photo (1).webp"), the same
     * behavior a browser's own download manager gives you.
     */
    fun saveToDownloads(context: Context, path: String, mime: String, displayName: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val destUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(destUri)?.use { out ->
                FileInputStream(File(path)).use { input -> input.copyTo(out) }
            } ?: return false
            true
        } catch (e: Exception) {
            resolver.delete(destUri, null, null) // Don't leave a half-written entry behind on failure.
            false
        }
    }
}
