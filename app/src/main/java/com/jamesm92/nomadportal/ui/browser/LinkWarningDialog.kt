package com.jamesm92.nomadportal.ui.browser

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * The two link-activation warnings from nomadportal_android_handoff.md's
 * "Link activation safety" section: a link tap that would download a file
 * or leave the mesh must not activate immediately.
 *
 * Both cases are real now that micron2compose exposes
 * `LinkTarget.isFileDownload` (fixed in that library's v0.1.0, after being
 * reported from this app's initial integration — see
 * `FEEDBACK-from-nomadportal-android.md` in that repo, now resolved).
 */
sealed interface PendingLinkWarning {
    data class ExternalWeb(val url: String) : PendingLinkWarning
    data class FileDownload(val url: String, val fileName: String) : PendingLinkWarning
}

@Composable
fun LinkWarningDialog(
    warning: PendingLinkWarning,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (warning) {
        is PendingLinkWarning.ExternalWeb -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Leave the mesh?") },
            text = { Text("This opens an external browser to:\n\n${warning.url}") },
            confirmButton = { TextButton(onClick = onConfirm) { Text("Open") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )

        is PendingLinkWarning.FileDownload -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Download file?") },
            text = {
                Text(
                    "This site offers a file for download:\n\n${warning.fileName}\n\n" +
                        "No virus scan is available on this device — only download files " +
                        "from sites you trust (porting-notes.md §4)."
                )
            },
            confirmButton = { TextButton(onClick = onConfirm) { Text("Download") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}
