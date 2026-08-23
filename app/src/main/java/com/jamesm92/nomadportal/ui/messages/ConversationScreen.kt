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
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.messaging.AttachmentKind
import com.jamesm92.nomadportal.data.messaging.Contact
import com.jamesm92.nomadportal.data.messaging.ImageSizeTier
import com.jamesm92.nomadportal.data.messaging.MAX_ATTACHMENT_BYTES
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.data.SettingsRepository
import com.jamesm92.nomadportal.data.messaging.compressImageForSend
import com.jamesm92.nomadportal.data.messaging.readAttachmentForSend
import com.jamesm92.nomadportal.panicwipe.PanicWipe
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.CompactIconButton
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
    settingsRepository: SettingsRepository,
    contact: Contact,
    onBack: () -> Unit,
) {
    // remember()-pinned — this screen's own draft text field recomposes on every
    // keystroke, which without this would restart the messages() poll from scratch on
    // every character typed (the same class of bug found+fixed live on NetworkScreen.kt
    // this session — see its own doc comment). Keyed on contact.lxmfHash too, so
    // navigating to a different conversation still gets a fresh Flow for the new contact.
    val messages by remember(repository, contact.lxmfHash) { repository.messages(contact.lxmfHash) }
        .collectAsState(initial = emptyList())
    val announceStatus by remember(repository) { repository.announceStatus() }.collectAsState(initial = null)
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

    // Disappearing messages — see MessagingRepository.setDisappearingTimer's
    // own doc comment for the local-only caveat this whole feature carries.
    var timerMenuExpanded by remember { mutableStateOf(false) }
    val hasSeenDisappearingNotice by settingsRepository.hasSeenDisappearingMessagesNotice
        .collectAsState(initial = true) // true (not false) as the loading-state default -- avoids
        // ever flashing the explanatory dialog for a returning user whose real "already seen"
        // value just hasn't resolved from DataStore yet (same defensive-default reasoning as
        // NomadNavHost's own hasCompletedOnboarding gate, just inline here since a wrongly-shown
        // dialog is a much smaller mistake than a wrongly-shown onboarding flow would be).
    // Holds the just-picked duration while the one-time explanatory dialog
    // is up, so its confirm button knows what to actually apply.
    var pendingDisappearingSeconds by remember { mutableStateOf<Int?>(null) }

    fun applyDisappearingTimer(seconds: Int) {
        scope.launch {
            if (repository.setDisappearingTimer(contact.lxmfHash, seconds)) {
                liveContact = liveContact.copy(disappearingSeconds = seconds)
            }
        }
    }

    fun pickDisappearingTimer(seconds: Int) {
        timerMenuExpanded = false
        if (seconds > 0 && !hasSeenDisappearingNotice) {
            pendingDisappearingSeconds = seconds
        } else {
            applyDisappearingTimer(seconds)
        }
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
                                CompactIconButton(onClick = {
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
                                CompactIconButton(onClick = {
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
                                CompactIconButton(onClick = { editingName = true }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Rename")
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        CompactIconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        Box {
                            CompactIconButton(onClick = { timerMenuExpanded = true }) {
                                Icon(
                                    imageVector = if (liveContact.disappearingSeconds > 0) Icons.Filled.Timer else Icons.Filled.TimerOff,
                                    contentDescription = if (liveContact.disappearingSeconds > 0) {
                                        "Disappearing messages: ${disappearingTimerLabel(liveContact.disappearingSeconds)}"
                                    } else {
                                        "Disappearing messages off"
                                    },
                                    tint = if (liveContact.disappearingSeconds > 0) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                )
                            }
                            DropdownMenu(
                                expanded = timerMenuExpanded,
                                onDismissRequest = { timerMenuExpanded = false },
                            ) {
                                DISAPPEARING_TIMER_OPTIONS.forEach { seconds ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                disappearingTimerLabel(seconds),
                                                fontWeight = if (seconds == liveContact.disappearingSeconds) FontWeight.Bold else null,
                                            )
                                        },
                                        onClick = { pickDisappearingTimer(seconds) },
                                    )
                                }
                            }
                        }
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
        //
        // consumeWindowInsets(innerPadding) — a second, real on-device bug
        // found later: without this, imePadding() has no way to know
        // innerPadding already consumed the navigation-bar's own share of
        // the bottom inset, so it stacks the keyboard's height *on top of*
        // that instead of replacing it — the input field floated a real,
        // visible gap (the navigation bar's own height) above the actual
        // keyboard. This tells imePadding "that part of the inset is
        // already accounted for", so it only adds what's actually left.
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
        ) {
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

            // Real bug found via a direct user report across 3 physical
            // phones ("the error for most attempts to message users is
            // 'no delivery identitiy registered for this user'"). Root
            // cause: `AnnounceStatus.lxmfAddress` is null until the LXMF
            // delivery router actually exists — orchestrator.py only
            // registers it inside `_run_deferred_setup()`'s "LXMF
            // delivery setup" step, which itself waits on
            // `_browser.wait_ready()` first (RNS.Reticulum() init is
            // documented, in this same file, to take 60-300s on real
            // deployments). There was previously no UI-level signal for
            // this window at all — the app looked fully interactive
            // (conversations list, contact names, everything else
            // already worked off cached/local state) while sendMessage()
            // synchronously threw that exact raw string underneath.
            // Checked ahead of sendBlocked/sendError below: this is the
            // more fundamental "not ready at all" state, not just "ready
            // but shouldn't send right now."
            val notReadyYet = announceStatus?.lxmfAddress == null
            val startingUpNote = if (notReadyYet) {
                "Still starting up — please wait a moment before sending."
            } else null

            // Proactive warning, per explicit design direction ("messages
            // need to have a note that you wont be allowed to send if it
            // is disabled") — shown before the user even tries to send,
            // not just reacted to after a failed attempt. sendError below
            // (a real send failure) is the reactive fallback for whatever
            // this proactive check couldn't predict.
            val blockedNote = announceStatus?.takeIf { it.sendBlocked }?.sendBlockedReason
            (startingUpNote ?: blockedNote ?: sendError)?.let { note ->
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
                // Both send entry points (keyboard "Send" action and the
                // icon button) route through this one guard rather than
                // calling sendDraft() directly, so notReadyYet only has
                // to be checked in one place. Deliberately still lets the
                // tap through to set sendError (not just disable the
                // button silently) — see startingUpNote's own doc comment
                // above for the real bug this closes; a tap during this
                // window now reinforces the already-visible proactive
                // note instead of surfacing the raw Python exception text
                // sendDraft's catch block would otherwise show.
                val onSendTapped = {
                    if (notReadyYet) {
                        sendError = "Still starting up — please wait a moment before sending."
                    } else {
                        sendDraft(draft, contact.lxmfHash, repository, scope, onError = { sendError = it }) {
                            draft = ""
                            sendError = null
                        }
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSendTapped() }),
                )
                IconButton(onClick = onSendTapped) {
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

    // Shown exactly once, ever, app-wide, the first time a non-Off
    // duration is picked in any conversation — never again after (see
    // hasSeenDisappearingMessagesNotice's own doc comment). This is the
    // one thing about this whole feature that isn't optional: LXMF has
    // no way to communicate or enforce a timer to the other party's
    // device, so saying so plainly here (not just implying
    // Signal-equivalent behavior) matters more than the feature itself.
    pendingDisappearingSeconds?.let { seconds ->
        AlertDialog(
            onDismissRequest = { pendingDisappearingSeconds = null },
            title = { Text("Disappearing messages") },
            text = {
                Text(
                    "This only affects your own device. Messages you send or " +
                        "receive here will delete themselves — including any " +
                        "attachment — after ${disappearingTimerLabel(seconds).lowercase()}. " +
                        "There's no way for this app to make that happen on " +
                        "${liveContact.displayName}'s device too — LXMF has no " +
                        "way to send them that instruction. Their own copy of " +
                        "this conversation is entirely up to them.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { settingsRepository.setSeenDisappearingMessagesNotice(true) }
                    applyDisappearingTimer(seconds)
                    pendingDisappearingSeconds = null
                }) { Text("Turn on") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDisappearingSeconds = null }) { Text("Cancel") }
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

/** 0 = off, then a reduced Signal-style preset set — see
 * MessagingRepository.setDisappearingTimer's own doc comment for why
 * there's no custom/arbitrary duration input in v1. */
private val DISAPPEARING_TIMER_OPTIONS = listOf(0, 5 * 60, 60 * 60, 24 * 60 * 60, 7 * 24 * 60 * 60)

private fun disappearingTimerLabel(seconds: Int): String = when (seconds) {
    0 -> "Off"
    5 * 60 -> "5 minutes"
    60 * 60 -> "1 hour"
    24 * 60 * 60 -> "1 day"
    7 * 24 * 60 * 60 -> "1 week"
    else -> "${seconds}s"
}
