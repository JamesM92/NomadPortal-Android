package com.jamesm92.nomadportal.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.ui.theme.NomadMono
import kotlinx.coroutines.launch

/**
 * Companion to [SearchField] on Nodes/Messages: search only finds
 * *already-discovered* nodes/contacts (a live announce or message
 * history), so there was previously no way to jump directly to an
 * address you already know but haven't seen announce/message from yet.
 * A raw RNS/LXMF destination hash is plain hex — this only checks that,
 * not length (Reticulum's own hash length isn't hardcoded here); an
 * invalid or unreachable one still opens (Nodes: the page fetch fails
 * the normal way, same as any other node whose fetch fails; Messages:
 * the send queues and fails once path discovery times out, same as any
 * other never-announced destination — see orchestrator.py's
 * `get_contact_json` doc comment for the Messages side of this).
 *
 * The QR-scan icon (per the Columba-parity-audit's "QR-code identity
 * sharing" finding) fills this same text field rather than skipping it
 * — a scanned address goes through the exact same trim/validate/confirm
 * path as one typed by hand, no separate QR-only flow to keep in sync.
 * [QrScannerOverlay] is rendered in place of the `AlertDialog` while
 * scanning (not nested inside it — a camera preview needs real screen
 * space, an `AlertDialog`'s content area doesn't offer that), so this
 * composable's own call sites need no changes to support it.
 *
 * [messagingRepository], when non-null, gets a best-effort
 * [MessagingRepository.importScannedContact] call the instant a scan
 * decodes a real `lxma://` identity payload (hash *and* public key, not
 * just a bare hash) — this is what makes the scanned contact reachable
 * immediately rather than only once a real mesh announce arrives, see
 * that method's own doc comment. Fire-and-forget and failure-tolerant on
 * purpose: [value] still fills in and [onConfirm] still works normally
 * either way, exactly this app's usual "an invalid/unreachable address
 * still just fails the normal way once used" degradation, not a new
 * QR-only failure mode. Left null at [NodeListScreen]'s own call site
 * (Sites' manual/QR address entry) — this benefit is specifically about
 * *identity* sharing (Columba's own real QR feature is identity-only
 * too), not general site-address bookmarking.
 */
@Composable
fun AddByAddressDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (hash: String) -> Unit,
    messagingRepository: MessagingRepository? = null,
) {
    var value by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val trimmed = value.trim().lowercase()
    val isValidHex = trimmed.isNotEmpty() && trimmed.length % 2 == 0 &&
        trimmed.all { it in "0123456789abcdef" }

    if (scanning) {
        QrScannerOverlay(
            onResult = { hash, publicKeyHex ->
                value = hash
                scanning = false
                if (messagingRepository != null && publicKeyHex != null) {
                    scope.launch {
                        try {
                            messagingRepository.importScannedContact(hash, publicKeyHex)
                        } catch (e: Exception) {
                            // Best-effort — value/onConfirm's own normal
                            // "unreachable address" path still covers this.
                        }
                    }
                }
            },
            onCancel = { scanning = false },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                isError = value.isNotBlank() && !isValidHex,
                supportingText = {
                    if (value.isNotBlank() && !isValidHex) {
                        Text("Not a valid hex address")
                    }
                },
                textStyle = TextStyle(fontFamily = NomadMono),
                placeholder = { Text("Destination hash") },
                trailingIcon = {
                    // Real on-device report: this icon had no explicit
                    // tint, so it inherited OutlinedTextField's dim
                    // default trailing-icon color and was hard to see.
                    // primary gives it a clearly visible, branded color,
                    // matching NodeListScreen's own precedent for "this
                    // icon starts an action" tinting.
                    IconButton(onClick = { scanning = true }) {
                        Icon(
                            Icons.Filled.QrCodeScanner,
                            contentDescription = "Scan QR code",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        },
        confirmButton = {
            TextButton(
                enabled = isValidHex,
                onClick = { onConfirm(trimmed) },
            ) { Text("Go") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
