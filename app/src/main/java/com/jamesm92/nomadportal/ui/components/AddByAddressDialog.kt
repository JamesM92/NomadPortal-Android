package com.jamesm92.nomadportal.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import com.jamesm92.nomadportal.ui.theme.NomadMono

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
 */
@Composable
fun AddByAddressDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (hash: String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    val trimmed = value.trim().lowercase()
    val isValidHex = trimmed.isNotEmpty() && trimmed.length % 2 == 0 &&
        trimmed.all { it in "0123456789abcdef" }

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
