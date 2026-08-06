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
 * [ExternalWeb] is implemented for real. **File-download links can't be
 * detected yet** — micron2compose's `defaultUrlResolver` currently
 * collapses `/file/` links to a bare `"#"`, indistinguishable from a
 * next-heading-jump link (see this app's
 * `FEEDBACK-from-nomadportal-android.md` filed against that repo). There
 * is no `PendingLinkWarning.FileDownload` case here yet because there's no
 * way to know a file link happened — add it once `LinkTarget` exposes
 * that (tracked in nomadportal_android_handoff.md's micron2compose
 * status note).
 */
sealed interface PendingLinkWarning {
    data class ExternalWeb(val url: String) : PendingLinkWarning
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
    }
}
