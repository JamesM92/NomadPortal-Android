package com.jamesm92.nomadportal.ui.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.messaging.Contact
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import kotlinx.coroutines.launch

/**
 * Message thread for one contact. Two mobile-UX lessons from
 * porting-notes.md §6 are structural here, not bolted on:
 *
 * - **Enter-to-send never intercepts raw key events.** The compose box
 *   uses `KeyboardOptions(imeAction = Send)` + `KeyboardActions(onSend)`,
 *   which only fires on the IME's actual committed-send signal — the same
 *   fix shape as the web version's `beforeinput`/`insertLineBreak` switch,
 *   just via the native-Android API for it. Never add a raw
 *   `onKeyEvent`/`ACTION_DOWN` handler here to "catch" Enter.
 * - **`reverseLayout = true` on the message [LazyColumn]** structurally
 *   avoids the "scroll-to-bottom needs to re-apply after the keyboard
 *   settles, not just once" bug class entirely, rather than patching it
 *   with a `ViewTreeObserver`-equivalent listener: the newest message
 *   stays pinned at the visual bottom by construction, regardless of how
 *   many times the keyboard-open transition resizes the layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    repository: MessagingRepository,
    contact: Contact,
    onBack: () -> Unit,
) {
    val messages by repository.messages(contact.lxmfHash).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(contact.lxmfHash) {
        repository.markRead(contact.lxmfHash)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(contact.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                reverseLayout = true,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages.asReversed(), key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        sendDraft(draft, contact.lxmfHash, repository, scope) { draft = "" }
                    }),
                )
                IconButton(onClick = {
                    sendDraft(draft, contact.lxmfHash, repository, scope) { draft = "" }
                }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

private fun sendDraft(
    draft: String,
    contactHash: String,
    repository: MessagingRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    onSent: () -> Unit,
) {
    val trimmed = draft.trim()
    if (trimmed.isEmpty()) return
    scope.launch { repository.sendMessage(contactHash, trimmed) }
    onSent()
}
