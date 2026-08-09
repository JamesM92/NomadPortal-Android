package com.jamesm92.nomadportal.ui.messages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.messaging.AttachmentKind
import com.jamesm92.nomadportal.data.messaging.Contact
import com.jamesm92.nomadportal.data.messaging.ImageSizeTier
import com.jamesm92.nomadportal.data.messaging.MAX_ATTACHMENT_BYTES
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.data.messaging.compressImageForSend
import com.jamesm92.nomadportal.data.messaging.readAttachmentForSend
import com.jamesm92.nomadportal.panicwipe.PanicWipe
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.ContactAvatar
import com.jamesm92.nomadportal.ui.components.PanicWipeLogo
import com.jamesm92.nomadportal.ui.components.dismissKeyboardOnTap
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 *   many times the keyboard-open transition resizes the layout. That
 *   alone doesn't cover a *newly arrived* message while the list was
 *   scrolled up reading history, though (Compose anchors scroll state to
 *   the item that was visible, not to "index 0" — a real on-device
 *   report: the chat didn't auto-scroll on send) — the `LaunchedEffect
 *   (messages.size)` below handles that explicitly, for both directions
 *   (sent and received), not just sends.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    repository: MessagingRepository,
    contact: Contact,
    onBack: () -> Unit,
) {
    val messages by repository.messages(contact.lxmfHash).collectAsState(initial = emptyList())
    val announceStatus by repository.announceStatus().collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var draft by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    // True while a picked attachment is being read/compressed and sent
    // — disables the attach button so a second tap can't fire a second
    // pick mid-send (compressImageForSend/readAttachmentForSend +
    // sendMessage together can take a moment for a large image).
    var sendingAttachment by remember { mutableStateOf(false) }
    var attachMenuExpanded by remember { mutableStateOf(false) }
    // Set the moment an image is picked, cleared once a size tier is
    // chosen (or the picker dialog is dismissed) — per explicit
    // direction: unlike a raw file (always sent as-is), an image gets a
    // prompt for how much to shrink it before it's actually sent.
    var pendingImageUri by remember { mutableStateOf<android.net.Uri?>(null) }

    fun sendFileAttachment(uri: android.net.Uri?) {
        if (uri == null) return
        sendingAttachment = true
        scope.launch {
            val picked = withContext(Dispatchers.IO) { readAttachmentForSend(context, uri) }
            sendingAttachment = false
            if (picked == null) {
                sendError = "Couldn't read that file"
                return@launch
            }
            if (picked.bytes.size > MAX_ATTACHMENT_BYTES) {
                sendError = "That file is too large to send (max ${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MB)"
                return@launch
            }
            try {
                repository.sendMessage(
                    contact.lxmfHash, draft.trim(),
                    attachmentFilename = picked.filename,
                    attachmentData = picked.bytes,
                    attachmentKind = AttachmentKind.FILE,
                )
                draft = ""
                sendError = null
            } catch (e: Exception) {
                sendError = e.message ?: "Failed to send attachment"
            }
        }
    }

    fun sendImageAttachment(uri: android.net.Uri, tier: ImageSizeTier) {
        sendingAttachment = true
        scope.launch {
            val picked = withContext(Dispatchers.IO) { compressImageForSend(context, uri, tier) }
            sendingAttachment = false
            if (picked == null) {
                sendError = "Couldn't read that image"
                return@launch
            }
            // MAX_ATTACHMENT_BYTES check deliberately omitted here — every
            // tier's real-world output is well under the cap (even HIGH's
            // 1280px/quality-75 WEBP is normally a few hundred KB at
            // most), unlike an arbitrary raw file pick.
            try {
                repository.sendMessage(
                    contact.lxmfHash, draft.trim(),
                    attachmentFilename = picked.filename,
                    attachmentData = picked.bytes,
                    attachmentKind = AttachmentKind.IMAGE,
                    imageFormat = "webp",
                )
                draft = ""
                sendError = null
            } catch (e: Exception) {
                sendError = e.message ?: "Failed to send image"
            }
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> pendingImageUri = uri }
    val pickAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> sendFileAttachment(uri) }
    val pickFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> sendFileAttachment(uri) }

    // The Contact passed in is a one-shot snapshot from nav time — kept
    // fresh by re-fetching whenever `messages` changes, rather than an
    // independent poll timer: per explicit direction, an icon only ever
    // arrives attached to a new message (there's nothing else to poll
    // for there), and `messages` is already its own live Flow, so this
    // piggybacks on an update signal that already exists instead of
    // adding a second, redundant one. (A peer's own display-name update
    // only comes from a fresh announce, which this doesn't directly
    // watch — but repository.contact() reflects whatever the latest
    // live announce name is on every call regardless of what triggered
    // the call, so it still comes through here for free.)
    var liveContact by remember(contact.lxmfHash) { mutableStateOf(contact) }
    LaunchedEffect(contact.lxmfHash, messages) {
        repository.contact(contact.lxmfHash)?.let { liveContact = it }
    }
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember(liveContact.displayName) { mutableStateOf(liveContact.displayName) }

    LaunchedEffect(contact.lxmfHash) {
        repository.markRead(contact.lxmfHash)
    }

    val listState = rememberLazyListState()
    // Index 0 is the visual bottom (reverseLayout = true below) — scroll
    // there whenever the message count changes so a freshly sent or
    // received message is always brought into view, not left off-screen
    // if the list happened to be scrolled up. Keyed on size rather than
    // the newest message's own id: a sent message's id gets rewritten
    // once delivery confirms (client UUID -> real LXMF hash — see
    // Message's own doc comment), which would otherwise double-fire this.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            // Wrapped in a Column — same convention ConversationListScreen
            // uses for its own SecondaryTabRow — so the LXMF address row
            // below can span the screen's full width independently of
            // the title slot's own layout, per explicit direction ("the
            // lxmf address should be seperatre from the neame header, and
            // should be able to go across the full top of the screen").
            Column {
                AdaptiveTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Falls back to the initial-letter circle when
                            // there's no icon data — per explicit request,
                            // this should always show *something* here, not
                            // just when a real icon happens to be set. Fixed
                            // 40dp, same as everywhere else ContactAvatar is
                            // used (it always applies its own .size(40.dp)
                            // last in the modifier chain regardless of what's
                            // passed in, so there's no smaller variant to ask
                            // for here).
                            ContactAvatar(liveContact)
                            if (editingName) {
                                OutlinedTextField(
                                    value = nameDraft,
                                    onValueChange = { nameDraft = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                IconButton(onClick = {
                                    val trimmed = nameDraft.trim()
                                    if (trimmed.isNotEmpty()) {
                                        scope.launch {
                                            if (repository.setContactName(contact.lxmfHash, trimmed)) {
                                                liveContact = liveContact.copy(displayName = trimmed)
                                            }
                                        }
                                    }
                                    editingName = false
                                }) {
                                    Icon(Icons.Filled.Check, contentDescription = "Save name")
                                }
                                IconButton(onClick = {
                                    nameDraft = liveContact.displayName
                                    editingName = false
                                }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Cancel")
                                }
                            } else {
                                Text(
                                    text = liveContact.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                IconButton(onClick = { editingName = true }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Rename")
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        PanicWipeLogo(
                            modifier = Modifier.padding(end = 8.dp),
                            onTripleTap = {
                                scope.launch {
                                    PanicWipe.perform(context)
                                    PanicWipe.restartApp(context)
                                }
                            },
                        )
                    },
                )
                // Full LXMF address, separate from the name row above —
                // per explicit direction — spanning the full screen
                // width rather than squeezed into the title slot next to
                // the avatar/edit controls. Small enough plus no-wrap/
                // ellipsis to always stay a single line.
                Text(
                    text = liveContact.lxmfHash,
                    // bodyMedium, not a smaller label role — this was
                    // deliberately sized up from an original 0.55x
                    // multiplier per explicit on-device feedback; bodyMedium
                    // (14sp) preserves that "needs to read clearly" intent.
                    style = MaterialTheme.typography.bodyMedium,
                    color = NomadTextDim,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        },
    ) { innerPadding ->
        // imePadding() — without it, edge-to-edge mode (enableEdgeToEdge()
        // in MainActivity) leaves the compose box sitting wherever it was
        // laid out, right underneath the soft keyboard when it opens,
        // instead of being pushed up above it (confirmed: a real on-device
        // send test showed the input field invisible behind the keyboard,
        // only the suggestion bar visible above it).
        Column(modifier = Modifier.padding(innerPadding).imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .dismissKeyboardOnTap(),
                reverseLayout = true,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages.asReversed(), key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }

            // Proactive warning, per explicit design direction ("messages
            // need to have a note that you wont be allowed to send if it
            // is disabled") — shown before the user even tries to send,
            // not just reacted to after a failed attempt. sendError below
            // (a real send failure) is the reactive fallback for whatever
            // this proactive check couldn't predict.
            val blockedNote = announceStatus?.takeIf { it.sendBlocked }?.sendBlockedReason
            (blockedNote ?: sendError)?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Attach: a small menu rather than a single button, per
                // explicit direction ("add files, images, or audio files
                // for transfer") — three distinct pickers, each filtered
                // to the right content so the system picker itself does
                // the narrowing rather than a generic "any file" dialog
                // for all three. Disabled mid-send so a second tap can't
                // fire a second pick while one's already being read/sent.
                Box {
                    IconButton(
                        onClick = { attachMenuExpanded = true },
                        enabled = !sendingAttachment,
                    ) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach")
                    }
                    DropdownMenu(
                        expanded = attachMenuExpanded,
                        onDismissRequest = { attachMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Photo") },
                            leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                            onClick = {
                                attachMenuExpanded = false
                                pickImageLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Audio file") },
                            leadingIcon = { Icon(Icons.Filled.AudioFile, contentDescription = null) },
                            onClick = {
                                attachMenuExpanded = false
                                pickAudioLauncher.launch(arrayOf("audio/*"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("File") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null) },
                            onClick = {
                                attachMenuExpanded = false
                                pickFileLauncher.launch(arrayOf("*/*"))
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        sendDraft(draft, contact.lxmfHash, repository, scope, onError = { sendError = it }) {
                            draft = ""
                            sendError = null
                        }
                    }),
                )
                IconButton(onClick = {
                    sendDraft(draft, contact.lxmfHash, repository, scope, onError = { sendError = it }) {
                        draft = ""
                        sendError = null
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }

    // Per explicit direction: an image gets a size-tier prompt before
    // it's sent (a raw file attachment never does — it's always sent
    // as-is). Cancelling just clears pendingImageUri without sending
    // anything.
    pendingImageUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImageUri = null },
            title = { Text("Image size") },
            text = {
                Column {
                    ImageSizeTier.entries.forEach { tier ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pendingImageUri = null
                                    sendImageAttachment(uri, tier)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = tier == ImageSizeTier.MEDIUM, onClick = {
                                pendingImageUri = null
                                sendImageAttachment(uri, tier)
                            })
                            Column {
                                Text(tier.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = tier.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NomadTextDim,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingImageUri = null }) { Text("Cancel") }
            },
        )
    }
}

private fun sendDraft(
    draft: String,
    contactHash: String,
    repository: MessagingRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    onError: (String) -> Unit,
    onSent: () -> Unit,
) {
    val trimmed = draft.trim()
    if (trimmed.isEmpty()) return
    scope.launch {
        try {
            repository.sendMessage(contactHash, trimmed)
            onSent()
        } catch (e: Exception) {
            // Reactive fallback for whatever the proactive
            // AnnounceStatus.sendBlocked check above didn't predict
            // (e.g. a send-blocked message from send_message() itself,
            // or any other failure) — the draft is deliberately NOT
            // cleared here, so the user doesn't lose what they typed.
            onError(e.message ?: "Failed to send message")
        }
    }
}
