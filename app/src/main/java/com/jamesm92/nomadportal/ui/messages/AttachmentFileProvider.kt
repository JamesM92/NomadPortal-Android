package com.jamesm92.nomadportal.ui.messages

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

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

    /** Opens the system share sheet for one attachment file — the
     * generic "do something useful with this" action for a file
     * attachment this app has no dedicated viewer for (see
     * MessageBubble.kt's file-chip tap handler). */
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
}
