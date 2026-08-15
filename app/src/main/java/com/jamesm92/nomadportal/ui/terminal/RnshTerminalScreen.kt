package com.jamesm92.nomadportal.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.jamesm92.nomadportal.data.rnsh.RnshConnectionState
import com.jamesm92.nomadportal.data.rnsh.RnshHistoryEntry
import com.jamesm92.nomadportal.data.rnsh.RnshHistoryOutcome
import com.jamesm92.nomadportal.data.rnsh.RnshHistoryRepository
import com.jamesm92.nomadportal.data.rnsh.RnshRepository
import com.jamesm92.nomadportal.data.rnsh.RnshStatus
import com.jamesm92.nomadportal.security.DeviceCredentialGate
import com.jamesm92.nomadportal.security.DeviceCredentialResult
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.Identicon
import com.jamesm92.nomadportal.ui.components.hexToByteArray
import com.jamesm92.nomadportal.ui.theme.NomadAccent
import com.jamesm92.nomadportal.ui.theme.NomadAccent2
import com.jamesm92.nomadportal.ui.theme.NomadBg2
import com.jamesm92.nomadportal.ui.theme.NomadError
import com.jamesm92.nomadportal.ui.theme.NomadMono
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import com.jamesm92.nomadportal.ui.theme.NomadWarn
import kotlinx.coroutines.launch

/**
 * A real remote-shell terminal speaking rnsh's actual wire protocol —
 * see [RnshRepository]'s own doc comment for the interop details and
 * this app's client-only scope decision (never a listener, only ever
 * connects OUT to a remote `rnsh listener` someone else runs).
 *
 * **"Line mode" only, deliberately** — the same real, first-class mode
 * rnsh's own reference client supports via its `~L` escape (confirmed
 * against its actual source, not guessed): the user composes a full
 * line, then it's sent on Enter, not per-keystroke. This app doesn't
 * attempt true character-at-a-time raw terminal mode — Compose has no
 * clean way to capture every raw keystroke (arrow keys, ctrl-
 * combinations) portably across devices/IMEs the way a real terminal
 * app needs, and building that is a much bigger, riskier undertaking
 * (Termux's own extensive custom key-handling code is testament to how
 * real that problem is). Practical effect: shell history recall via
 * the Up arrow and live tab-completion don't work; running a command
 * and reading its output does.
 *
 * **No separate "Connect"/"Send" buttons — the keyboard's own Enter/Go
 * action submits**, both for the destination-hash field (idle state)
 * and for composing a command once connected. Once
 * [RnshConnectionState.CONNECTED], the command input is a borderless
 * [BasicTextField] (no outlined box, matching this screen's terminal-
 * emulator look) sitting directly below the received output, not a
 * boxed "Command" field with a separate Send button. **Not truly
 * inline on the same visual line as the last output** — a real, on-
 * device-confirmed attempt at that (combining scrollback and input into
 * one `BasicTextField` and manually reconstructing its `TextFieldValue`
 * every recomposition) broke typing after exactly one keystroke, a
 * known Compose pitfall (manually rebuilt `TextFieldValue`s that don't
 * preserve the IME's own active composition span desync the keyboard's
 * internal buffer tracking); reverted deliberately, prioritizing input
 * that actually works over that last bit of visual polish. What the
 * remote shell actually echoes back (via its own real pty local-echo,
 * arriving through the normal STDOUT stream) is what shows once a line
 * is sent — this field only ever holds the *not-yet-sent* line.
 *
 * **Ctrl/Shift/Alt are real persistent toggles, plus Tab/arrow-key
 * buttons pinned above the keyboard** (a Termux/Blink-style extra-keys
 * accessory row, via `Modifier.imePadding()`, on its own [NomadBg2]
 * background distinguishing it from the terminal itself) — per explicit
 * direction, tapping one arms it until tapped again (no auto-release
 * after one keystroke). No dedicated Esc button (its one real
 * standalone use, a Meta-prefix, is already what Alt sends) or Ctrl+C/
 * Ctrl+D shortcuts (arm Ctrl, then type "c"/"d"). While Ctrl or Alt is
 * armed, every character typed into
 * the inline field is dispatched immediately as its own real control
 * byte/sequence (Ctrl+letter → the standard 0x01-0x1A; Alt+anything →
 * a real `ESC`-prefix, stackable with Ctrl) rather than being composed
 * into the line — genuinely terminal-accurate, not a compromise: even a
 * real line-buffered tty processes Ctrl+C via out-of-band signal
 * handling, never as buffered line content. Shift only combines with
 * Tab (a real, distinct `CSI Z` "reverse tab" sequence) since a soft
 * keyboard's own Shift key already produces the right *text* for
 * anything typed normally — see [modifiedCharBytes]/[ctrlByteFor]'s own
 * doc comments for the exact byte-level details.
 *
 * **Up/Down are reinterpreted, not forwarded as raw bytes** — a naive
 * first attempt just sent real arrow-key CSI sequences straight
 * through, which broke on-device: the remote's own history-recall
 * redraw landed in this screen's append-only, read-only output buffer
 * indistinguishable from ordinary output, with no way to remove it
 * (not even backspace, since that text was never actually in the
 * editable field). Up/Down still send the *real* arrow CSI sequence
 * (`ESC [ A`/`ESC [ B`) — driving the remote's own genuine readline
 * history, not a fake local-only list — but the outputChunks collector
 * now intercepts the resulting redraw and routes it into
 * [TextFieldValue] `lineInputValue` as real, editable, backspaceable
 * text instead of the scrollback (see that collector's and
 * [requestHistoryRecall]'s own doc comments for the real, honestly-
 * scoped `\r`-based redraw-detection heuristic and its limits). No
 * Left/Right buttons — cut per explicit direction (their only real
 * value, nudging the cursor after an imprecise tap, was judged too
 * narrow given a touch field's own native tap-to-place-cursor already
 * covers positioning).
 *
 * **Recent connections**: [historyRepository] remembers every distinct
 * destination this device has attempted (success or failure, with a
 * real error message on failure), each shown with a real deterministic
 * [Identicon] and an optional user-set nickname — purely local
 * bookkeeping, never announced anywhere. Rendered as a scrollable list
 * below the destination-hash field; tapping an entry connects to it
 * directly (no separate fill-then-submit step, consistent with there
 * being no Connect button to press afterward). See
 * [RnshHistoryRepository]'s own doc comment.
 *
 * **ANSI rendering is deliberately partial**: SGR color/bold codes
 * (`\x1b[...m`) are parsed into real colored/styled text spans; every
 * other CSI escape sequence (cursor positioning, clear-screen, etc.)
 * *and* OSC sequences (`\x1b]...BEL`/`\x1b]...\x1b\\` — e.g. bash's own
 * default PS1 setting the window title) are silently consumed rather
 * than leaking into the visible text, but *not* actually acted on —
 * there's no real cursor-addressable screen buffer here, just an
 * append-only scrolling log. Full-screen programs (`vim`, `htop`,
 * `less`) will render wrong; ordinary command output (including
 * colored `ls`/`grep`/prompts, and a real bash prompt's own OSC-wrapped
 * title-setting) looks right. A real, honest scope boundary, not an
 * oversight — see this file's own git history for the reasoning if this
 * ever needs revisiting.
 */
@Composable
fun RnshTerminalScreen(
    repository: RnshRepository,
    historyRepository: RnshHistoryRepository,
    onBack: () -> Unit,
) {
    val status by repository.status().collectAsState(
        initial = RnshStatus(RnshConnectionState.IDLE, null, null),
    )
    val history by historyRepository.history().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    // BiometricPrompt needs a real FragmentActivity host — MainActivity was
    // widened to one specifically for this (see its own doc comment). This
    // cast is expected to always succeed for how this screen is actually
    // hosted (NomadNavHost, inside MainActivity's setContent), same
    // assumption every other Activity-context cast in this codebase makes.
    val hostActivity = LocalContext.current as? FragmentActivity
    var destinationHash by remember { mutableStateOf("") }
    var connectError by remember { mutableStateOf<String?>(null) }
    var lineInputValue by remember { mutableStateOf(TextFieldValue("")) }
    var terminalStyle by remember { mutableStateOf(TermStyleState()) }
    var terminalBuffer by remember { mutableStateOf(AnnotatedString("")) }
    val scrollState = rememberScrollState()
    // The destination the *current* connect attempt is for, and a guard
    // so a real terminal-state transition (CONNECTED/FAILED) only gets
    // recorded into history once per attempt, not on every subsequent
    // status poll tick while sitting in that same terminal state.
    var currentAttemptHash by remember { mutableStateOf<String?>(null) }
    var recordedOutcomeFor by remember { mutableStateOf<String?>(null) }
    var renamingHash by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    val commandFocusRequester = remember { FocusRequester() }
    // Persistent modifier toggles (Termux/Blink-style extra-keys row
    // convention, not invented here) — per explicit direction, tapping
    // one arms it until tapped again, not just for one keystroke. While
    // armed, Ctrl/Alt convert every character typed into the inline
    // field to its real control byte/sequence instead of composing it
    // into the line (see [modifiedCharBytes]'s own doc comment); Shift
    // only affects the dedicated Tab button (Shift+Tab is a real,
    // distinct terminal sequence, `ESC [ Z`) and the arrow-key buttons
    // — a soft keyboard's own Shift key already produces the right
    // uppercase/shifted-symbol *text* for anything typed normally, so
    // there's no separate byte a Shift-modified regular character needs.
    var ctrlActive by remember { mutableStateOf(false) }
    var shiftActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    // Up/Down recall from the *real* remote shell history (its own
    // readline, via real xterm arrow-key CSI sequences — see
    // requestHistoryRecall's own doc comment), not a fake local-only
    // list — but the redraw the remote sends back in response is
    // intercepted (in the outputChunks collector below) and routed into
    // lineInputValue instead of the read-only terminalBuffer, so the
    // recalled command becomes real, editable, backspaceable text, per
    // explicit direction, rather than getting stuck append-only in the
    // scrollback the way a naive byte-forward implementation left it
    // (the original, on-device-confirmed bug this whole redesign fixes).
    var awaitingHistoryRecall by remember { mutableStateOf(false) }
    var historyRecallRawBuffer by remember { mutableStateOf("") }
    var historyRecallStartedAtMillis by remember { mutableStateOf(0L) }

    // Every real connect attempt is gated behind the device's own screen
    // lock (DeviceCredentialGate — see its own doc comment for why this
    // reuses the OS credential rather than a custom in-app password, and
    // why it re-checks on every attempt instead of "unlocking" the screen
    // for the rest of the session). Runs before anything else in
    // attemptConnect touches connection state, so a cancelled/failed/
    // unavailable auth leaves everything exactly as it was pre-attempt.
    fun attemptConnect(hash: String) {
        val trimmed = hash.trim().lowercase()
        val isValidHex = trimmed.isNotEmpty() && trimmed.length % 2 == 0 &&
            trimmed.all { it in "0123456789abcdef" }
        if (!isValidHex) {
            connectError = "Not a valid hex address"
            return
        }
        val activity = hostActivity
        if (activity == null) {
            connectError = "Could not verify device security — please try again"
            return
        }
        connectError = null
        scope.launch {
            val authResult = DeviceCredentialGate.authenticate(
                activity = activity,
                title = "Unlock to connect",
                subtitle = "rnsh gives real shell access to a remote machine — confirm it's you.",
            )
            when (authResult) {
                is DeviceCredentialResult.Authenticated -> Unit
                is DeviceCredentialResult.Cancelled -> return@launch
                is DeviceCredentialResult.Unavailable -> {
                    connectError = authResult.reason
                    return@launch
                }
                is DeviceCredentialResult.Failed -> {
                    connectError = "Authentication failed: ${authResult.reason}"
                    return@launch
                }
            }
            destinationHash = trimmed
            terminalBuffer = AnnotatedString("")
            terminalStyle = TermStyleState()
            currentAttemptHash = trimmed
            recordedOutcomeFor = null
            try {
                repository.connect(trimmed)
            } catch (e: Exception) {
                connectError = e.message ?: "Could not start connecting"
            }
        }
    }

    LaunchedEffect(status.state, status.error, currentAttemptHash) {
        val hash = currentAttemptHash ?: return@LaunchedEffect
        val key = "$hash:${status.state}"
        if (recordedOutcomeFor == key) return@LaunchedEffect
        when (status.state) {
            RnshConnectionState.CONNECTED -> {
                recordedOutcomeFor = key
                historyRepository.recordAttempt(hash, RnshHistoryOutcome.SUCCESS, null)
            }
            RnshConnectionState.FAILED -> {
                recordedOutcomeFor = key
                historyRepository.recordAttempt(hash, RnshHistoryOutcome.FAILED, status.error)
            }
            else -> Unit
        }
    }

    // Collects new output chunks only while a session is actually
    // running — outputChunks() polls Python regardless, but there's
    // nothing useful to append once idle/closed/failed.
    LaunchedEffect(status.state) {
        if (status.state != RnshConnectionState.CONNECTING && status.state != RnshConnectionState.CONNECTED) {
            return@LaunchedEffect
        }
        repository.outputChunks().collect { chunk ->
            if (awaitingHistoryRecall) {
                // Real history-recall redraw handling — see
                // requestHistoryRecall's own doc comment for the full
                // design and its honest limitations. A bare carriage
                // return (`\r`, not followed by `\n`) is how readline's
                // most common/portable redraw strategy overwrites the
                // current line; everything after the *last* `\r` seen
                // since the arrow was sent is the remote's own redrawn
                // version of that line (prompt + recalled command).
                //
                // Real, on-device-confirmed bug this exact structure
                // fixes: outputChunks() emits an *empty* ByteArray every
                // poll even when there's nothing new (see
                // RealRnshRepository's own poll loop) — an early
                // `if (chunk.isEmpty()) return@collect` guard placed
                // ahead of this block, as an earlier version of this
                // code had, meant the timeout check below never even
                // ran when the remote's response used a redraw strategy
                // this parser doesn't recognize (or the byte never
                // reached the remote at all), leaving
                // awaitingHistoryRecall stuck true forever — which
                // silently blocked *all* future terminal output too,
                // not just the recall, since this same gate sits in
                // front of the normal parse-and-append path below.
                // Checking the timeout on every collection (empty
                // chunks included, since collect() still fires for
                // those) is what actually guarantees recovery.
                if (chunk.isNotEmpty()) {
                    historyRecallRawBuffer += String(chunk, Charsets.UTF_8)
                    val crIndex = historyRecallRawBuffer.lastIndexOf('\r')
                    if (crIndex != -1) {
                        val redrawnRaw = historyRecallRawBuffer.substring(crIndex + 1)
                        val (styled, _) = parseAnsiChunk(redrawnRaw, TermStyleState())
                        var recalled = styled.text
                        // Strip the repeated prompt prefix so only the
                        // real recalled *command* lands in the edit
                        // field, not the prompt text too — this device
                        // never streams partial input to the remote
                        // (line mode), so the remote's own idea of "the
                        // current line" before any recall is always
                        // just the bare prompt, which is exactly the
                        // last line already sitting in terminalBuffer.
                        // Falls back to the unstripped text if that
                        // prefix doesn't match (a different prompt
                        // format than expected) rather than silently
                        // dropping a real recalled command.
                        val knownPromptLine = terminalBuffer.text.substringAfterLast('\n')
                        if (knownPromptLine.isNotEmpty() && recalled.startsWith(knownPromptLine)) {
                            recalled = recalled.removePrefix(knownPromptLine)
                        }
                        // Real, on-device-confirmed bug this trim fixes:
                        // a stray leading/trailing '\n' or '\r' left over
                        // in the redraw (e.g. the real terminal-refresh
                        // sequence readline emits around the redrawn
                        // line) put the cursor on a blank line *after*
                        // the visibly-recalled text instead of right at
                        // the end of it — looked like "the recalled text
                        // is stuck in the prompt row above, the cursor
                        // is stuck in the row below" from the outside,
                        // even though both are the same lineInputValue.
                        // A real recalled command line never legitimately
                        // needs a hard newline at either end.
                        recalled = recalled.trim('\n', '\r')
                        lineInputValue = TextFieldValue(recalled, TextRange(recalled.length))
                        awaitingHistoryRecall = false
                        historyRecallRawBuffer = ""
                        return@collect
                    }
                }
                // No \r seen yet — real redraws can arrive split across
                // several polls over a slow link (confirmed real on this
                // app's own RNode/LoRa testing), so keep accumulating
                // rather than giving up on the first empty-handed chunk.
                if (System.currentTimeMillis() - historyRecallStartedAtMillis > 8000L) {
                    // Gave up — flush whatever arrived as ordinary output
                    // instead of silently swallowing it forever; a
                    // redraw strategy this parser doesn't recognize
                    // (real, honest limitation, not every readline
                    // configuration uses a bare \r) shouldn't just eat
                    // real output.
                    awaitingHistoryRecall = false
                    if (historyRecallRawBuffer.isNotEmpty()) {
                        val (appended, newStyle) = parseAnsiChunk(historyRecallRawBuffer, terminalStyle)
                        terminalStyle = newStyle
                        terminalBuffer = buildAnnotatedString {
                            append(terminalBuffer)
                            append(appended)
                        }
                    }
                    historyRecallRawBuffer = ""
                }
                return@collect
            }

            if (chunk.isEmpty()) return@collect
            val raw = String(chunk, Charsets.UTF_8)
            val (appended, newStyle) = parseAnsiChunk(raw, terminalStyle)
            terminalStyle = newStyle
            terminalBuffer = buildAnnotatedString {
                append(terminalBuffer)
                append(appended)
            }.let { full ->
                // Real, sensible cap — an unbounded single AnnotatedString
                // over a very long session would grow without limit
                // otherwise. Keeps roughly the trailing MAX_BUFFER_CHARS,
                // not an exact line count.
                if (full.length > MAX_BUFFER_CHARS) full.subSequence(full.length - MAX_BUFFER_CHARS, full.length) else full
            }
        }
    }

    LaunchedEffect(terminalBuffer, lineInputValue) {
        // Also re-triggers while composing (lineInputValue) — a long,
        // wrapped not-yet-sent line growing the inline field's own
        // height needs the same "keep the bottom in view" treatment as
        // newly-arrived output.
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    LaunchedEffect(status.state) {
        if (status.state == RnshConnectionState.CONNECTED) {
            commandFocusRequester.requestFocus()
        }
    }

    fun sendLine(extra: String = "") {
        val toSend = (lineInputValue.text + extra).toByteArray(Charsets.UTF_8)
        if (toSend.isEmpty()) return
        lineInputValue = TextFieldValue("")
        scope.launch {
            try {
                repository.sendInput(toSend)
            } catch (e: Exception) {
                // Not rethrown — the connection's own status (polled
                // above) is what surfaces a real failure; a single
                // dropped send shouldn't crash this screen's own
                // coroutine scope.
            }
        }
    }

    // Modifier-armed and dedicated-key input (Ctrl/Alt/arrows/Tab) is
    // always sent immediately as its own raw byte sequence, bypassing
    // lineInputValue's normal compose-then-Send flow entirely — this is
    // real terminal-accurate behavior, not a compromise: even a
    // canonical/line-buffered real tty still processes a Ctrl+C via
    // out-of-band signal handling (ISIG) immediately, never as part of
    // buffered line content, and arrow/Tab keys were never text to
    // begin with.
    fun sendRaw(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        scope.launch {
            try {
                repository.sendInput(bytes)
            } catch (e: Exception) {
                // Same non-rethrow reasoning as sendLine above.
            }
        }
    }

    fun sendControl(byte: Int) = sendRaw(byteArrayOf(byte.toByte()))

    // Up/Down: sends the real xterm arrow CSI sequence (`ESC [ A`/
    // `ESC [ B`) to the remote, exactly what a real terminal sends for
    // Up/Down — this *does* drive the remote's own real readline history
    // (not a fake local-only list), matching the destination's actual
    // shell state. The outputChunks collector above is what intercepts
    // the resulting redraw and routes it into lineInputValue instead of
    // the read-only terminalBuffer — see its own doc comment for that
    // half of the design.
    fun requestHistoryRecall(letter: Char) {
        awaitingHistoryRecall = true
        historyRecallRawBuffer = ""
        historyRecallStartedAtMillis = System.currentTimeMillis()
        sendRaw(byteArrayOf(0x1B, '['.code.toByte(), letter.code.toByte()))
    }

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = {
                    // Once a destination is targeted (CONNECTING or
                    // CONNECTED — currentAttemptHash is set the moment
                    // attemptConnect() starts, not just once CONNECTED),
                    // the title becomes "rnsh [identicon]" on one line
                    // with the nickname on a second line below it, per
                    // explicit request/refinement — replacing the
                    // earlier static "Connected"/"Connecting…" +
                    // Disconnect row this screen used to show inline.
                    // Both lines deliberately smaller than a top bar's
                    // usual titleLarge (also per explicit request) —
                    // titleSmall/labelSmall, not the default. Same real
                    // deterministic Identicon and nickname-or-truncated-
                    // hash fallback as a history row. Plain "Remote
                    // Shell (rnsh)" before any destination is targeted
                    // (idle, nothing to identify yet).
                    val hash = currentAttemptHash
                    if (hash != null) {
                        val label = history.find { it.destinationHash == hash }?.nickname
                            ?: "${hash.take(8)}…${hash.takeLast(4)}"
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ConnectionStatusDot(state = status.state)
                                Text("rnsh", style = MaterialTheme.typography.titleSmall)
                                Identicon(hash = hash.hexToByteArray(), size = 18.dp, ringColor = NomadAccent)
                            }
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = NomadTextDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text("Remote Shell (rnsh)", style = MaterialTheme.typography.titleSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (status.state == RnshConnectionState.CONNECTING || status.state == RnshConnectionState.CONNECTED) {
                        IconButton(onClick = {
                            scope.launch { repository.disconnect() }
                            // Real, on-device-reported bug this fixes:
                            // terminalBuffer/lineInputValue/
                            // currentAttemptHash are all `remember`ed at
                            // this composable's own scope, so they don't
                            // reset just because status.state moves away
                            // from CONNECTED — this same screen instance
                            // stays alive (no navigation happens on
                            // Disconnect). Without clearing them here, the
                            // setup section (hash field + recent
                            // connections) reappeared visually stacked on
                            // top of the still-fully-present last-session
                            // transcript below it, rather than that
                            // transcript actually going away — looked like
                            // the "selection page" was just being added on
                            // top of the old session, not a clean return
                            // to it. disconnect() itself never touches
                            // self._error on a clean user-initiated
                            // disconnect (see rnsh_client.py's own
                            // `disconnect()`), so status.error/exitCode
                            // stay null here too — nothing left over
                            // anywhere once this fires.
                            terminalBuffer = AnnotatedString("")
                            terminalStyle = TermStyleState()
                            lineInputValue = TextFieldValue("")
                            currentAttemptHash = null
                            recordedOutcomeFor = null
                            destinationHash = ""
                            connectError = null
                            awaitingHistoryRecall = false
                            historyRecallRawBuffer = ""
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Disconnect")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(12.dp)) {
            if (status.state == RnshConnectionState.IDLE || status.state == RnshConnectionState.CLOSED ||
                status.state == RnshConnectionState.FAILED
            ) {
                Text(
                    "Connect to a real rnsh listener elsewhere on the mesh " +
                        "(github.com/acehoss/rnsh) — this device never accepts " +
                        "incoming shell sessions, only connects out. Enter a " +
                        "destination hash and hit Enter/Go to connect.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NomadTextDim,
                )
                Text(
                    // Session-setup info, not a live-session warning —
                    // per explicit direction, moved out of the connected
                    // view (it doesn't need to persist for the whole
                    // session, just be known going in). A real, confirmed
                    // upstream rnsh behavior (github.com/acehoss/rnsh's
                    // own process.py `write()`), not this app's own
                    // choice: it maps a bare 0x03 byte to SIGINT, then
                    // force-kills the remote process with SIGHUP+SIGTERM
                    // if it's still running ~50ms later. An idle
                    // interactive shell doesn't exit on SIGINT (correct
                    // shell behavior — it just redraws the prompt), so it
                    // survives past that window and gets killed by rnsh's
                    // own escalation, ending the session.
                    text = "Note: Ctrl+C may end the remote session — a real rnsh listener behavior, not a bug here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NomadTextDim,
                    modifier = Modifier.padding(top = 4.dp),
                )

                OutlinedTextField(
                    value = destinationHash,
                    onValueChange = { destinationHash = it.trim() },
                    singleLine = true,
                    label = { Text("Destination hash") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { attemptConnect(destinationHash) }),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                connectError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                status.error?.let {
                    Text(
                        "Last attempt failed: $it",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                status.exitCode?.let {
                    Text(
                        "Session ended (exit code $it)",
                        color = NomadTextDim,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                if (history.isNotEmpty()) {
                    Text(
                        "Recent connections",
                        style = MaterialTheme.typography.labelMedium,
                        color = NomadTextDim,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                    )
                    // Bounded + independently scrollable — this section
                    // sits above the also-scrollable terminal-output
                    // area (which owns the remaining screen height via
                    // its own weight(1f) below), so an unbounded list
                    // here would just push that area off-screen instead
                    // of scrolling in place.
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(history, key = { it.destinationHash }) { entry ->
                            RnshHistoryRow(
                                entry = entry,
                                isRenaming = renamingHash == entry.destinationHash,
                                renameDraft = renameDraft,
                                onRenameDraftChange = { renameDraft = it },
                                onTap = { attemptConnect(entry.destinationHash) },
                                onStartRename = {
                                    renamingHash = entry.destinationHash
                                    renameDraft = entry.nickname ?: ""
                                },
                                onConfirmRename = {
                                    scope.launch {
                                        historyRepository.setNickname(entry.destinationHash, renameDraft)
                                    }
                                    renamingHash = null
                                },
                                onCancelRename = { renamingHash = null },
                                onDelete = {
                                    scope.launch { historyRepository.remove(entry.destinationHash) }
                                },
                            )
                        }
                    }
                }
            }

            val terminalTextStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = NomadMono,
                color = Color(0xFFD3D7CF),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .verticalScroll(scrollState),
            ) {
                if (status.state == RnshConnectionState.CONNECTED) {
                    // The editable area is "everything from the most
                    // recent prompt onward," per explicit direction —
                    // not the whole scrollback (older output stays
                    // read-only) and not a separate field floating below
                    // the prompt either. Older content
                    // (historyPortion) renders exactly as before; the
                    // current prompt line is split OUT of terminalBuffer
                    // and rendered via decorationBox as a read-only
                    // prefix *inline* with the real editable core —
                    // genuinely different from the earlier, reverted
                    // "reconstruct one combined TextFieldValue every
                    // recomposition" attempt that broke typing after one
                    // keystroke (see this file's own git history): here,
                    // lineInputValue is still the sole, directly-owned
                    // canonical editable state, updated only through its
                    // own onValueChange — decorationBox's prompt Text is
                    // purely a display-only sibling rendered alongside
                    // it, never merged into the same TextFieldValue, so
                    // there's no IME-composition-discarding reconstruction
                    // happening on every keystroke this time. Multi-line
                    // composition (Shift+Enter) still works the same way
                    // — the prompt prefix only ever occupies the first
                    // visual line, real typed/composed lines flow below it.
                    val lastNewline = terminalBuffer.text.lastIndexOf('\n')
                    val historyPortion = if (lastNewline == -1) {
                        AnnotatedString("")
                    } else {
                        terminalBuffer.subSequence(0, lastNewline + 1)
                    }
                    // A real AnnotatedString subSequence, not a plain
                    // String — preserves the prompt's own real SGR
                    // colors (e.g. a colored user@host, a differently-
                    // colored path/$) exactly as parseAnsiChunk already
                    // produced them, instead of flattening the prompt to
                    // one uncolored tone the moment it becomes "the
                    // editable line's own prefix."
                    val promptAnnotated = terminalBuffer.subSequence(lastNewline + 1, terminalBuffer.length)
                    if (historyPortion.isNotEmpty()) {
                        SelectionContainer {
                            Text(text = historyPortion, style = terminalTextStyle)
                        }
                    }
                    BasicTextField(
                        value = lineInputValue,
                        onValueChange = { new ->
                            // A literal '\n' reaching here is legitimate,
                            // intentional multi-line composition (Shift+
                            // Enter — see onPreviewKeyEvent below, the
                            // single source of truth for what plain
                            // Enter vs. Shift+Enter each mean), not
                            // something to special-case: it's sent as
                            // real embedded newline bytes along with the
                            // rest of the line once actually submitted,
                            // same as pasting multi-line text into any
                            // real terminal's stdin would be.
                            if ((ctrlActive || altActive) && new.text.length > lineInputValue.text.length) {
                                // A character was typed while Ctrl/Alt is
                                // armed — dispatched immediately as its
                                // own real control byte/sequence (see
                                // sendRaw's own doc comment), never
                                // composed into lineInputValue. The
                                // length check also correctly excludes
                                // deletions (backspace), which still
                                // need to edit lineInputValue normally.
                                sendRaw(modifiedCharBytes(new.text.last(), ctrl = ctrlActive, alt = altActive))
                            } else {
                                lineInputValue = new
                            }
                        },
                        textStyle = terminalTextStyle.copy(color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendLine("\r") }),
                        decorationBox = { innerTextField ->
                            Row(verticalAlignment = Alignment.Top) {
                                if (promptAnnotated.isNotEmpty()) {
                                    Text(text = promptAnnotated, style = terminalTextStyle)
                                }
                                innerTextField()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(commandFocusRequester)
                            // Real terminal-accurate Enter semantics —
                            // per explicit direction, this app aims to
                            // emulate a true terminal as closely as
                            // practical: plain Enter always sends
                            // (matches real terminals — you can't
                            // normally compose a literal multi-line
                            // command with a bare Enter either), while
                            // Shift+Enter inserts a real newline for
                            // genuine multi-line composition (a heredoc,
                            // a multi-line paste built up locally before
                            // one combined send — same effect as pasting
                            // multi-line text into any real terminal's
                            // stdin). Checks both this screen's own
                            // Shift *toggle* (the only "Shift" a touch-
                            // only device really has) and a real
                            // hardware keyboard's own held Shift key
                            // (`event.nativeKeyEvent.isShiftPressed`, for
                            // anyone with a Bluetooth/USB keyboard
                            // attached) — either one counts.
                            //
                            // Interception is necessary at all because
                            // this field isn't singleLine (Shift+Enter
                            // needs it to actually show more than one
                            // composed line), and a plain multi-line
                            // BasicTextField's default Enter behavior is
                            // "insert a newline," not "fire the IME
                            // action." onPreviewKeyEvent fires before the
                            // field's own internal key handling (not
                            // after), so a plain Enter never reaches that
                            // default newline-insertion logic at all;
                            // returning `false` for the Shift+Enter case
                            // explicitly lets the event fall through to
                            // that same default handling instead of
                            // reimplementing it ourselves.
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                ) {
                                    if (shiftActive || event.nativeKeyEvent.isShiftPressed) {
                                        false
                                    } else {
                                        sendLine("\r")
                                        true
                                    }
                                } else {
                                    false
                                }
                            },
                    )
                } else {
                    SelectionContainer {
                        Text(text = terminalBuffer, style = terminalTextStyle)
                    }
                }
            }

            if (status.state == RnshConnectionState.CONNECTED) {
                // imePadding() pins this whole key row above the soft
                // keyboard (a real accessory-bar placement, same
                // mechanism a chat app's own input toolbar uses) rather
                // than letting it sit wherever it falls in the normal
                // layout flow, which the keyboard would otherwise cover.
                // NomadBg2 (a step up from the plain-black terminal area
                // behind it, same tiering every other elevated surface
                // in this app already uses) plus the zeroed minimum-
                // touch-target + tight per-button contentPadding below
                // — per explicit on-device feedback that the default
                // Material button sizing left too much dead space
                // around a row this dense — reads as a real, distinct
                // accessory toolbar rather than a few buttons floating
                // in the terminal's own background.
                Column(
                    modifier = Modifier
                        .imePadding()
                        .fillMaxWidth()
                        .background(NomadBg2)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    val keyButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        // Row 1: the three modifiers (real persistent
                        // toggles — tap to arm, tap again to release;
                        // they do NOT auto-release after one keystroke,
                        // per explicit direction) plus Tab. No dedicated
                        // Esc button — a bare, standalone Esc byte is
                        // essentially a no-op at an ordinary shell prompt
                        // (its real use is always as a *prefix* before
                        // another key, e.g. readline emacs-mode's
                        // "Esc f" = Alt+F, which the Alt toggle above
                        // already sends); Esc matters far more inside
                        // full-screen programs like vim, already out of
                        // scope for this screen (no cursor-addressable
                        // rendering — see this file's own top doc
                        // comment).
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            ModifierToggleButton(
                                "Ctrl", active = ctrlActive, contentPadding = keyButtonPadding,
                                onClick = { ctrlActive = !ctrlActive },
                            )
                            ModifierToggleButton(
                                "Shift", active = shiftActive, contentPadding = keyButtonPadding,
                                onClick = { shiftActive = !shiftActive },
                            )
                            ModifierToggleButton(
                                "Alt", active = altActive, contentPadding = keyButtonPadding,
                                onClick = { altActive = !altActive },
                            )
                            TextButton(
                                contentPadding = keyButtonPadding,
                                onClick = {
                                    // Shift+Tab is a real, distinct
                                    // terminal sequence (CSI Z, "reverse
                                    // tab") — Ctrl/Alt have no standard
                                    // combined-with-Tab meaning in raw
                                    // terminal semantics, so they're
                                    // ignored here rather than guessed at.
                                    if (shiftActive) sendRaw(byteArrayOf(0x1B, '['.code.toByte(), 'Z'.code.toByte()))
                                    else sendControl(0x09)
                                },
                            ) { Text("Tab") }
                        }
                        // Row 2: Up/Down only — reinterpreted rather than
                        // forwarded as raw bytes, see
                        // requestHistoryRecall's own doc comment. Sends
                        // the *real* arrow CSI sequence, driving the
                        // remote shell's own genuine history, but the
                        // redraw that comes back is intercepted and
                        // routed into lineInputValue as real editable/
                        // backspaceable text instead of the read-only
                        // scrollback — the original append-only-
                        // corruption bug this redesign fixes. No Left/
                        // Right — cut per explicit direction: their only
                        // real value (nudging the cursor one character
                        // after an imprecise tap) was judged too narrow
                        // to keep, and a touch field's own native tap-
                        // to-place-cursor / long-press-to-select already
                        // covers cursor positioning otherwise.
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(
                                contentPadding = keyButtonPadding,
                                onClick = { requestHistoryRecall('A') },
                            ) { Text("↑") }
                            TextButton(
                                contentPadding = keyButtonPadding,
                                onClick = { requestHistoryRecall('B') },
                            ) { Text("↓") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Live connection-status indicator in the top bar — per explicit
 * direction, added after real on-device testing surfaced a genuine
 * "Link is not ready" exception when sending input on a connection this
 * app's own [RnshConnectionState.CONNECTED] status already reported as
 * connected (a real link-readiness race, not a UI bug), showing this
 * app's own polled status can lag the real underlying link state on a
 * flaky link. **Never color alone** — same real accessibility
 * precedent this app already established in
 * [com.jamesm92.nomadportal.ui.browser.NodeListScreen]'s own
 * `FetchStatusDot` (shape carries the meaning, color reinforces it):
 * a solid filled circle for CONNECTED, a hollow ring for CONNECTING
 * (shape reads "not solid/settled yet" independent of hue), and an X
 * mark for anything not connected (FAILED/CLOSED/IDLE) — a shape
 * universally read as "stopped/error" regardless of color perception.
 */
@Composable
private fun ConnectionStatusDot(state: RnshConnectionState, modifier: Modifier = Modifier) {
    val dotSize = 10.dp
    when (state) {
        RnshConnectionState.CONNECTED -> Canvas(modifier = modifier.size(dotSize)) {
            drawCircle(color = NomadAccent2)
        }
        RnshConnectionState.CONNECTING -> Canvas(modifier = modifier.size(dotSize)) {
            drawCircle(color = NomadWarn, style = Stroke(width = 1.5.dp.toPx()))
        }
        else -> Canvas(modifier = modifier.size(dotSize)) {
            val inset = size.minDimension * 0.15f
            val strokeWidth = 1.5.dp.toPx()
            drawLine(
                color = NomadError,
                start = Offset(inset, inset),
                end = Offset(size.width - inset, size.height - inset),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = NomadError,
                start = Offset(size.width - inset, inset),
                end = Offset(inset, size.height - inset),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** A persistent (not one-shot) modifier toggle — see the Ctrl/Shift/Alt
 * state doc comment in [RnshTerminalScreen] for the real terminal
 * semantics each one drives. Highlighted in [NomadAccent] while active
 * so an armed-and-forgotten modifier stays visible rather than silently
 * changing what the next keystroke does. */
@Composable
private fun ModifierToggleButton(
    label: String,
    active: Boolean,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, contentPadding = contentPadding) {
        Text(
            label,
            color = if (active) NomadAccent else NomadTextDim,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** Maps [c] to its real Ctrl-modified control byte — Ctrl+A..Z are the
 * standard 0x01..0x1A (case-insensitive, matching every real terminal:
 * ASCII control bytes only have 26 letter slots to begin with), plus
 * the handful of punctuation forms real keyboards/terminals also define
 * (`[`/`\`/`]`/`^`/`_`/`@`/`?`). Returns `null` for anything without a
 * real Ctrl mapping (e.g. digits) — [modifiedCharBytes] falls back to
 * sending the character as-is in that case, so an armed-but-inapplicable
 * Ctrl press still does *something* sensible rather than silently
 * eating the keystroke. */
private fun ctrlByteFor(c: Char): Int? {
    val upper = c.uppercaseChar()
    return when {
        upper in 'A'..'Z' -> (upper - 'A') + 1
        c == '[' -> 0x1B
        c == '\\' -> 0x1C
        c == ']' -> 0x1D
        c == '^' -> 0x1E
        c == '_' -> 0x1F
        c == '?' -> 0x7F
        c == '@' -> 0x00
        else -> null
    }
}

/** Real bytes for [c] with [ctrl]/[alt] applied — Alt is the standard,
 * near-universal terminal "meta sends escape" convention (a bare
 * `0x1B` prefix in front of whatever byte(s) the key would otherwise
 * send; real readline bindings like Alt+B/Alt+F for word-left/word-
 * right rely on exactly this), stackable with Ctrl (Ctrl+Alt+C sends
 * `0x1B 0x03 0x03` — see the Ctrl+C special case below).
 *
 * **Ctrl+C is sent as two 0x03 bytes, not one** — a real, source-
 * verified workaround for rnsh's own upstream `process.py` (see this
 * file's own Ctrl+C caption text for the behavior this routes around):
 * its `write()` special-cases a payload *exactly equal* to the single
 * byte `b'\x03'`, sending SIGINT then escalating to SIGHUP+SIGTERM
 * ~50ms later if the process is still running — which kills an idle
 * interactive shell that correctly doesn't exit on SIGINT. Crucially,
 * `write()` still does a real, unconditional `os.write()` of whatever
 * bytes it received to the child's actual pty *regardless* of which
 * branch matched — so a payload that *isn't* exactly `b'\x03'` skips
 * rnsh's own extra escalation logic entirely, while the byte still
 * reaches the real pty and gets a real SIGINT from the kernel's own
 * ordinary ISIG line-discipline processing, same as any real terminal.
 * Two 0x03s (rather than one 0x03 plus an arbitrary filler byte) keeps
 * every byte in the payload meaningful — a redundant second SIGINT
 * hitting whatever's now in the foreground (the just-interrupted
 * command's parent shell, or the same idle shell if nothing was
 * running) is a real no-op, the same as double-tapping Ctrl+C on any
 * real physical terminal. */
private fun modifiedCharBytes(c: Char, ctrl: Boolean, alt: Boolean): ByteArray {
    val base = if (ctrl) {
        when (val ctrlByte = ctrlByteFor(c)) {
            0x03 -> byteArrayOf(0x03, 0x03)
            null -> c.toString().toByteArray(Charsets.UTF_8)
            else -> byteArrayOf(ctrlByte.toByte())
        }
    } else {
        c.toString().toByteArray(Charsets.UTF_8)
    }
    return if (alt) byteArrayOf(0x1B) + base else base
}

/**
 * One row in [RnshTerminalScreen]'s "Recent connections" list — a real
 * [Identicon] (deterministic from the raw destination-hash bytes, so
 * the same destination always renders the same dot pattern), the
 * nickname (or a truncated hash if none is set), and a small outcome
 * indicator (a colored dot + relative time, [NomadAccent2] green for a
 * last-successful connect, [NomadError] red with the failure reason for
 * a last-failed one — same colored-dot convention as
 * [com.jamesm92.nomadportal.ui.browser.NodeListScreen]'s own
 * `FetchStatusDot`). Tapping the row (outside the trailing icon
 * buttons) connects to this entry's destination directly — there's no
 * separate Connect button to press afterward (see this file's own top
 * doc comment), so a fill-then-submit step would just be an extra tap
 * for no benefit; the destination-hash field still shows exactly what
 * was connected to, and it's editable before hitting Enter/Go again.
 */
@Composable
private fun RnshHistoryRow(
    entry: RnshHistoryEntry,
    isRenaming: Boolean,
    renameDraft: String,
    onRenameDraftChange: (String) -> Unit,
    onTap: () -> Unit,
    onStartRename: () -> Unit,
    onConfirmRename: () -> Unit,
    onCancelRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Identicon(hash = entry.destinationHash.hexToByteArray(), size = 32.dp, ringColor = NomadAccent)

        if (isRenaming) {
            OutlinedTextField(
                value = renameDraft,
                onValueChange = onRenameDraftChange,
                singleLine = true,
                placeholder = { Text("Nickname") },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onConfirmRename) {
                Icon(Icons.Filled.Check, contentDescription = "Save nickname")
            }
            IconButton(onClick = onCancelRename) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel")
            }
        } else {
            Column(
                modifier = Modifier.weight(1f).clickable(onClick = onTap),
            ) {
                Text(
                    text = entry.nickname
                        ?: "${entry.destinationHash.take(8)}…${entry.destinationHash.takeLast(4)}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val outcomeColor = if (entry.lastOutcome == RnshHistoryOutcome.SUCCESS) NomadAccent2 else NomadError
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(color = outcomeColor, shape = CircleShape),
                    )
                    Text(
                        text = buildString {
                            append(formatRelativeTime(entry.lastAttemptAtMillis))
                            if (entry.lastOutcome == RnshHistoryOutcome.FAILED && entry.lastError != null) {
                                append(" — ")
                                append(entry.lastError)
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = NomadTextDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onStartRename) {
                Icon(Icons.Filled.Edit, contentDescription = "Rename", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove from history", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Same relative-time bucketing this app's other screens each keep
 * their own local copy of (see SettingsScreen's own
 * `formatRelativeAnnounceTime` doc comment for why: each has its own
 * "never" copy tailored to what it's describing, so sharing one isn't
 * worth it) — this one has no "never" case since a history row always
 * has a real [RnshHistoryEntry.lastAttemptAtMillis]. */
private fun formatRelativeTime(millis: Long): String {
    val diffSeconds = ((System.currentTimeMillis() - millis) / 1000).coerceAtLeast(0)
    return when {
        diffSeconds < 60 -> "just now"
        diffSeconds < 3600 -> "${diffSeconds / 60}m ago"
        diffSeconds < 86_400 -> "${diffSeconds / 3600}h ago"
        diffSeconds < 2_592_000 -> "${diffSeconds / 86_400}d ago"
        else -> "${diffSeconds / 2_592_000}mo ago"
    }
}

/** Local color/weight state carried between [parseAnsiChunk] calls —
 * an SGR sequence in one chunk can (and often does) affect text that
 * arrives in the *next* chunk, so this can't be reset per-call. */
internal data class TermStyleState(
    val fg: Color? = null,
    val bg: Color? = null,
    val bold: Boolean = false,
)

// Standard 16-color ANSI palette (0-7 normal, 8-15 bright) — a real,
// conventional terminal palette (these exact RGB values are the ones
// GNOME Terminal/most Linux distros ship as their own default, chosen
// for that familiarity), not arbitrary.
private val ANSI_NORMAL = listOf(
    Color(0xFF2E3436), Color(0xFFCC0000), Color(0xFF4E9A06), Color(0xFFC4A000),
    Color(0xFF3465A4), Color(0xFF75507B), Color(0xFF06989A), Color(0xFFD3D7CF),
)
private val ANSI_BRIGHT = listOf(
    Color(0xFF555753), Color(0xFFEF2929), Color(0xFF8AE234), Color(0xFFFCE94F),
    Color(0xFF729FCF), Color(0xFFAD7FA8), Color(0xFF34E2E2), Color(0xFFEEEEEC),
)

private const val MAX_BUFFER_CHARS = 200_000

/**
 * Parses [raw] (already UTF-8-decoded) into styled text given the
 * carried-in [state], returning the styled result and the (possibly
 * updated) state to carry into the *next* chunk. See this file's own
 * top doc comment for exactly what's real here (SGR colors/bold) vs.
 * silently-consumed-not-acted-on (every other escape sequence — no
 * real cursor-addressable buffer).
 */
internal fun parseAnsiChunk(raw: String, state: TermStyleState): Pair<AnnotatedString, TermStyleState> {
    var style = state
    val textBuf = StringBuilder()
    val result = buildAnnotatedString {
        fun flush() {
            if (textBuf.isNotEmpty()) {
                withStyle(
                    SpanStyle(
                        color = style.fg ?: Color.Unspecified,
                        background = style.bg ?: Color.Unspecified,
                        fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                    ),
                ) { append(textBuf.toString()) }
                textBuf.clear()
            }
        }

        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\u001B' && i + 1 < raw.length && raw[i + 1] == '[') {
                var j = i + 2
                while (j < raw.length && (raw[j] !in '@'..'~')) j++
                if (j >= raw.length) break // incomplete sequence at chunk boundary — drop the tail
                val params = raw.substring(i + 2, j)
                val cmd = raw[j]
                if (cmd == 'm') {
                    flush()
                    style = applySgr(params, style)
                }
                // Any other CSI final byte (cursor movement, clear,
                // etc.) — consumed, not rendered, not acted on.
                i = j + 1
            } else if (c == '\u001B' && i + 1 < raw.length && raw[i + 1] == ']') {
                // OSC (Operating System Command) sequence — real, on-
                // device-confirmed gap: bash's own default PS1 wraps
                // the window-title-setting escape `ESC ] 0 ; <title>
                // BEL` around the visible prompt (`\[\e]0;\u@\h:\w\a\]`
                // in a real bash PS1), terminated by either a bare BEL
                // (0x07) or the two-byte ST (`ESC \`). This parser only
                // recognized CSI (`ESC [`) sequences before, so the
                // leading ESC was silently stripped by the plain-
                // control-byte branch below but the *title text itself*
                // (e.g. `]0;user@host:`) fell through as ordinary
                // visible characters, leaking a stray `]0;user@host:`
                // in front of the real prompt. Consumed whole here, same
                // "not rendered, not acted on" treatment as CSI.
                var j = i + 2
                while (j < raw.length && raw[j].code != 0x07 &&
                    !(raw[j] == '\u001B' && j + 1 < raw.length && raw[j + 1] == '\\')
                ) {
                    j++
                }
                if (j >= raw.length) break // incomplete sequence at chunk boundary — drop the tail
                i = if (raw[j].code == 0x07) j + 1 else j + 2
            } else if (c == '\r' && i + 1 < raw.length && raw[i + 1] == '\n') {
                textBuf.append('\n')
                i += 2
            } else if (c == '\r') {
                // No real cursor-addressable buffer to overwrite in
                // place — treated as a newline instead, same
                // deliberate simplification as every other unsupported
                // cursor-control sequence.
                textBuf.append('\n')
                i++
            } else if (c.code < 0x20 && c != '\n' && c != '\t') {
                // Other control bytes (bell, backspace, etc.) —
                // silently stripped, not rendered and not acted on.
                i++
            } else {
                textBuf.append(c)
                i++
            }
        }
        flush()
    }
    return result to style
}

private fun applySgr(params: String, state: TermStyleState): TermStyleState {
    if (params.isEmpty()) return TermStyleState()
    var fg = state.fg
    var bg = state.bg
    var bold = state.bold
    val codes = params.split(";").mapNotNull { it.toIntOrNull() }
    for (code in codes) {
        when {
            code == 0 -> {
                fg = null; bg = null; bold = false
            }
            code == 1 -> bold = true
            code == 22 -> bold = false
            code == 39 -> fg = null
            code == 49 -> bg = null
            code in 30..37 -> fg = ANSI_NORMAL[code - 30]
            code in 90..97 -> fg = ANSI_BRIGHT[code - 90]
            code in 40..47 -> bg = ANSI_NORMAL[code - 40]
            code in 100..107 -> bg = ANSI_BRIGHT[code - 100]
            // 256-color/truecolor SGR (38/48;5;n or 38/48;2;r;g;b) and
            // anything else — not handled, silently ignored rather than
            // mis-rendered; most real shell prompts/tools stick to the
            // basic 16-color codes above.
        }
    }
    return TermStyleState(fg = fg, bg = bg, bold = bold)
}
