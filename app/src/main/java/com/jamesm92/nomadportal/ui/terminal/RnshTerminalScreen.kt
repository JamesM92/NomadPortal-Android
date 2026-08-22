package com.jamesm92.nomadportal.ui.terminal

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.jamesm92.nomadportal.data.rnsh.RnshConnectionState
import com.jamesm92.nomadportal.data.rnsh.RnshHistoryEntry
import com.jamesm92.nomadportal.data.rnsh.RnshHistoryOutcome
import com.jamesm92.nomadportal.data.rnsh.RnshHistoryRepository
import com.jamesm92.nomadportal.data.rnsh.RnshRepository
import com.jamesm92.nomadportal.data.rnsh.RnshStatus
import com.jamesm92.nomadportal.panicwipe.PanicWipe
import com.jamesm92.nomadportal.security.DeviceCredentialGate
import com.jamesm92.nomadportal.security.DeviceCredentialResult
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.Identicon
import com.jamesm92.nomadportal.ui.components.PanicWipeLogo
import com.jamesm92.nomadportal.ui.components.dismissKeyboardOnTap
import com.jamesm92.nomadportal.ui.components.hexToByteArray
import com.jamesm92.nomadportal.ui.theme.NomadAccent
import com.jamesm92.nomadportal.ui.theme.NomadAccent2
import com.jamesm92.nomadportal.ui.theme.NomadBg2
import com.jamesm92.nomadportal.ui.theme.NomadError
import com.jamesm92.nomadportal.ui.theme.NomadMono
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import com.jamesm92.nomadportal.ui.theme.NomadWarn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * **Up/Down recall from a real local-only history**, not the remote
 * shell's own readline history — a real, deliberate pivot after the
 * original remote-driven design (real arrow-key CSI bytes sent to the
 * remote, its own redraw response parsed and routed into
 * [lineInputValue][TextFieldValue]) went through several genuine,
 * confirmed fix attempts (a stuck-forever flag, buffer-reset races,
 * concurrent-press concatenation, unhandled backspace bytes) without
 * ever becoming reliable — each fix addressed one real bug while the
 * underlying problem (correctly interpreting an adaptive, cursor-
 * position-based redisplay protocol with no real cursor-addressable
 * buffer to apply it to) kept surfacing new ones. [recallLocalHistory]
 * just remembers what *this app* has actually sent this session —
 * instant, no network round trip, no byte-parsing, always editable by
 * construction. Real, honest scope reduction: doesn't include commands
 * run some other way (a different SSH session to the same box, etc.).
 * No Left/Right buttons — cut per explicit direction (their only real
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
 *
 * **Security/usability additions past the initial device-credential
 * gate**: [WindowManager.LayoutParams.FLAG_SECURE] hides this whole
 * screen (destination hashes, nicknames, live session content) from the
 * Recents task-switcher thumbnail and screenshots/screen-recording;
 * `FLAG_KEEP_SCREEN_ON` is armed only while CONNECTED so watching a
 * longer-running remote command doesn't have the screen dim mid-output;
 * a real backgrounding of the *whole app* (via `ProcessLifecycleOwner`,
 * not this screen's own lifecycle — deliberately doesn't fire for a
 * rotation or ordinary in-app navigation) auto-disconnects a live
 * session after a short grace period, since the device-credential gate
 * only guards the moment of connecting, not an already-open session left
 * on an unlocked, unattended phone. Real PTY dimensions are now actually
 * sent (`RnshRepository.resize()` existed end-to-end already but was
 * never called from here) via a `TextMeasurer`-based estimate of rows/
 * cols from the measured output area, recomputed on rotation/resize. A
 * "Retry" action sits next to a failed attempt's error message
 * (reconnects to `currentAttemptHash`, one tap instead of a trip back to
 * the matching history row).
 */
// Real, explicit direction: the visible transcript must survive
// navigating away (Back, which deliberately does NOT end the session —
// see disconnectAndClearSession's own doc comment) and back — a plain
// `remember { mutableStateOf(...) }` inside the composable can't do
// that, since leaving and returning destroys and recreates the whole
// composable instance (fresh `remember` defaults). A process-lifetime
// singleton `MutableState`, read/written directly (not wrapped in a
// second `remember`) so every RnshTerminalScreen instance shares the
// exact same state object, is what actually makes the real, still-live
// session's transcript still be there when you come back to look at
// it — matching what a real terminal app does. Explicitly reset on a
// genuinely new connect attempt and on disconnect (see both call
// sites), so this is "survives navigation," not "leaks into unrelated
// sessions."
private object RnshTranscriptHolder {
    val bufferState = mutableStateOf<AnnotatedString>(AnnotatedString(""))
    val styleState = mutableStateOf(TermStyleState())
}

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
    // Real lint fix (ContextCastToActivity), not a suppression: casting
    // LocalContext.current to an Activity type is flagged because a
    // Context isn't always an Activity (it can be wrapped/themed/etc.) —
    // androidx.activity.compose.LocalActivity resolves the real hosting
    // Activity directly instead, exactly the case this lint check exists
    // to steer toward.
    val hostActivity = LocalActivity.current as? FragmentActivity
    // Plain Context (not the FragmentActivity-typed hostActivity above) —
    // PanicWipe.perform()/restartApp() just need a real Context, matching
    // every other screen's own `val context = LocalContext.current` that
    // hosts the same triple-tap-the-logo kill switch (ConversationList/
    // BrowserScreen/SettingsScreen). This screen was missing it entirely
    // until a direct on-device report.
    val context = LocalContext.current
    var destinationHash by remember { mutableStateOf("") }
    var connectError by remember { mutableStateOf<String?>(null) }
    // Real, explicit direction: Back must NOT end an active session (it
    // stays alive in the background — same as always), but *returning*
    // to view one already in progress needs fresh authentication first,
    // the same real bar as starting one. sessionUnlocked is local to
    // this composable instance (a plain `remember`, not persisted) —
    // navigating away and back always creates a fresh instance with
    // this back at its default `false`, so re-authentication is
    // required on every return, not just once ever. Set true the moment
    // attemptConnect()'s own gate succeeds (a fresh connect from this
    // same instance is already authenticated, no second prompt needed);
    // the LaunchedEffect below is what actually triggers a fresh prompt
    // when this instance mounts onto a session that was already
    // CONNECTED/CONNECTING from *before* it existed.
    var sessionUnlocked by remember { mutableStateOf(false) }
    var unlockError by remember { mutableStateOf<String?>(null) }
    var lineInputValue by remember { mutableStateOf(TextFieldValue("")) }
    // Deliberately NOT `remember` — see RnshTranscriptHolder's own doc
    // comment just above this composable for why these need to be a
    // process-lifetime singleton instead of per-composable-instance
    // state.
    var terminalStyle by RnshTranscriptHolder.styleState
    var terminalBuffer by RnshTranscriptHolder.bufferState
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
    // Up/Down history is local-only — a real, deliberate simplification
    // after real remote-driven recall (arrow bytes sent to the remote,
    // its own redraw response parsed and routed into lineInputValue)
    // went through several genuine fix attempts (buffer-reset races,
    // concurrent-press concatenation, unhandled backspace bytes) without
    // ever becoming reliable — each fix addressed one real, confirmed
    // bug while the underlying problem (correctly interpreting an
    // adaptive, cursor-position-based redisplay protocol with no real
    // cursor-addressable buffer to apply it to) kept surfacing new ones.
    // localHistory just remembers what *this app* has actually sent
    // this session — no network round trip, no byte-parsing, no
    // ambiguity, always instantly editable. Real, honest scope
    // reduction: doesn't include commands run some other way (a
    // different SSH session to the same box, etc.) — this is the app's
    // own memory of what you typed, not the remote shell's own history
    // file, per explicit direction after the remote-driven approach
    // proved unreliable in practice.
    var localHistory by remember { mutableStateOf<List<String>>(emptyList()) }
    // null = not currently browsing history (a fresh/normal line, or one
    // the user has since edited); otherwise an index into localHistory,
    // most-recent-last (so index localHistory.size-1 is the most recent
    // entry, matching real shell history recall order).
    var localHistoryBrowseIndex by remember { mutableStateOf<Int?>(null) }
    // Real PTY dimensions, sent via rnsh's own WindowSize message
    // (RnshRepository.resize() already existed end-to-end — Kotlin →
    // orchestrator.rnsh_resize → RnshSession.resize() — but was never
    // actually called from this screen, so the remote never learned the
    // real terminal size). terminalContainerSize is the measured pixel
    // size of the scrollable output area (set via onSizeChanged below);
    // textMeasurer converts that into rows/cols using the same
    // terminalTextStyle the output itself renders with, so the computed
    // size matches what's actually visible. lastSentTerminalSize dedupes
    // so a resize is only sent when the computed rows/cols actually
    // change, not on every recomposition.
    val textMeasurer = rememberTextMeasurer()
    var terminalContainerSize by remember { mutableStateOf(IntSize.Zero) }
    var lastSentTerminalSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }

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
        // Real, on-device-reported security concern this fixes: cleared
        // synchronously, right here — not inside the coroutine below,
        // and not conditional on auth succeeding — so there is no
        // timing window where the visible field could show the hash
        // again. The previous code instead *set* destinationHash to the
        // real hash after a successful auth (originally meant for the
        // typed-entry case, where it was already redundant — the field
        // already held that value from the user's own typing); for a
        // history-row tap specifically, that line was actively
        // *populating* the field with a hash the user never typed at
        // all, and since status.state doesn't flip away from IDLE until
        // the next status poll tick (up to ~500ms later), the
        // still-visible idle section's OutlinedTextField would render
        // that real hash for a real, visible moment before the screen
        // moved on — "once a hash is entered there should be no way to
        // see it again" is the correct security bar here, not just
        // "eventually gets covered up."
        destinationHash = ""
        scope.launch {
            val authResult = DeviceCredentialGate.authenticate(
                activity = activity,
                title = "Unlock to connect",
                subtitle = "rnsh gives real shell access to a remote machine — confirm it's you.",
            )
            when (authResult) {
                is DeviceCredentialResult.Authenticated -> sessionUnlocked = true
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
            terminalBuffer = AnnotatedString("")
            terminalStyle = TermStyleState()
            currentAttemptHash = trimmed
            recordedOutcomeFor = null
            // A new session always starts at rnsh's own default PTY size
            // until told otherwise — resend the real dimensions once
            // CONNECTED fires even if they're numerically identical to
            // what a *previous* session on this same screen instance was
            // last told (the remote's own state reset with the new
            // session, this screen's lastSentTerminalSize dedupe cache
            // didn't).
            lastSentTerminalSize = null
            try {
                repository.connect(trimmed)
            } catch (e: Exception) {
                connectError = e.message ?: "Could not start connecting"
            }
        }
    }

    // Shared by the top-bar Disconnect button and the real-app-
    // backgrounding auto-disconnect below — both need the exact same
    // "return to a clean selection page" behavior. See the Disconnect
    // button's own former inline comment (now consolidated here) for the
    // on-device-reported bug this also keeps fixed: terminalBuffer/
    // lineInputValue/currentAttemptHash are all `remember`ed at this
    // composable's own scope, so nothing resets them just because
    // status.state moves away from CONNECTED — without this, the setup
    // section reappeared visually stacked on top of the still-fully-
    // present prior session's transcript instead of a clean return to
    // it. disconnect() itself never touches self._error on a clean
    // user-initiated disconnect (see rnsh_client.py's own disconnect()),
    // so status.error/exitCode stay null too — nothing left over
    // anywhere once this runs.
    fun disconnectAndClearSession() {
        scope.launch { repository.disconnect() }
        terminalBuffer = AnnotatedString("")
        terminalStyle = TermStyleState()
        lineInputValue = TextFieldValue("")
        currentAttemptHash = null
        recordedOutcomeFor = null
        destinationHash = ""
        connectError = null
        localHistory = emptyList()
        localHistoryBrowseIndex = null
        sessionUnlocked = false
        unlockError = null
    }

    fun requestSessionUnlock() {
        val activity = hostActivity
        if (activity == null) {
            unlockError = "Could not verify device security — please try again"
            return
        }
        unlockError = null
        scope.launch {
            val result = DeviceCredentialGate.authenticate(
                activity = activity,
                title = "Unlock to view session",
                subtitle = "A real remote shell session is already active on this device — confirm it's you before viewing it.",
            )
            when (result) {
                is DeviceCredentialResult.Authenticated -> sessionUnlocked = true
                // Stays locked — the retry button in the gate UI below
                // lets the user try again rather than getting bounced
                // back out of the screen.
                is DeviceCredentialResult.Cancelled -> Unit
                is DeviceCredentialResult.Unavailable -> unlockError = result.reason
                is DeviceCredentialResult.Failed -> unlockError = "Authentication failed: ${result.reason}"
            }
        }
    }

    // Real, explicit direction: returning to view an already-active
    // session (this composable instance mounting onto a real,
    // CONNECTED/CONNECTING session it didn't itself just start via
    // attemptConnect — e.g. navigating away with Back, which
    // deliberately does NOT end the session, then back into this
    // screen) requires fresh authentication before the terminal content
    // is shown, the same real bar as starting a session in the first
    // place. Keyed on status.state (not Unit/mount-once) because the
    // real first status value only arrives from the polled Flow a
    // moment after this composable mounts — collectAsState's `initial`
    // fallback is IDLE until then, so checking only once at mount would
    // usually see the wrong, stale value.
    LaunchedEffect(status.state) {
        if (!sessionUnlocked &&
            (status.state == RnshConnectionState.CONNECTED || status.state == RnshConnectionState.CONNECTING)
        ) {
            requestSessionUnlock()
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

    // Real screen-capture protection for the whole screen, not just while
    // CONNECTED — destination hashes and nicknames in "Recent
    // connections" are also real information about what remote machines
    // this device can reach. FLAG_SECURE blocks this screen's content
    // from appearing in the Recents task-switcher thumbnail and from
    // being captured by a screenshot/screen-recording — the same flag
    // banking/password-manager apps use, appropriate given this screen
    // offers real remote shell access. Added on composition enter,
    // cleared on exit so it doesn't leak onto the rest of the app.
    DisposableEffect(hostActivity) {
        val window = hostActivity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // Keeps the screen on only while a session is actually live —
    // watching a longer-running remote command shouldn't have the screen
    // dim/lock mid-output. Deliberately scoped to CONNECTED specifically
    // (unlike FLAG_SECURE above, which covers the whole screen) — no
    // similar need while just sitting on the idle/selection view. Keyed
    // on status.state so DisposableEffect's own re-run-on-key-change
    // behavior clears the flag the moment the state moves away from
    // CONNECTED (disconnect, link drop, etc.) without a separate
    // explicit clear call anywhere else.
    DisposableEffect(status.state, hostActivity) {
        val window = hostActivity?.window
        if (status.state == RnshConnectionState.CONNECTED) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Real security gap this closes: DeviceCredentialGate only guards the
    // *moment* of connecting — once authenticated, a live session with
    // real shell access to another machine would otherwise just stay
    // open indefinitely, including while the app sits backgrounded on an
    // unlocked (or later-unlocked) phone. Disconnects (via the same
    // disconnectAndClearSession() the top-bar Close icon uses) a grace
    // period after the app genuinely leaves the foreground.
    //
    // ProcessLifecycleOwner (not this screen's own LocalLifecycleOwner)
    // deliberately — it tracks the whole *process's* foreground state,
    // not this one Activity/NavBackStackEntry's, so it does NOT fire
    // ON_STOP for a mere screen rotation (MainActivity declares no
    // android:configChanges, so a rotation would otherwise fully
    // destroy/recreate the Activity) or for ordinary in-app navigation
    // to a different screen — only for a real "the user left the app"
    // transition (home button, task switch, screen off). The grace
    // period (not an instant disconnect on ON_STOP) tolerates a brief
    // real app-switch without being overly aggressive; ON_START cancels
    // the pending disconnect if the user returns before it fires.
    val latestStatus by rememberUpdatedState(status)
    DisposableEffect(Unit) {
        var backgroundDisconnectJob: Job? = null
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (latestStatus.state == RnshConnectionState.CONNECTING ||
                        latestStatus.state == RnshConnectionState.CONNECTED
                    ) {
                        // Real, explicit direction: leaving the app and
                        // coming back should require re-entering the
                        // PIN, not just silently resume showing a live
                        // session. Locked *immediately* on ON_STOP (not
                        // deferred like the disconnect below) — the same
                        // sessionUnlocked gate already used for
                        // returning to this screen via in-app
                        // navigation, reused here for the "left the
                        // whole app" case too, so both real ways of
                        // "coming back to an active session" share one
                        // real re-auth requirement instead of two
                        // different behaviors. The grace-period
                        // disconnect below still runs on its own timer
                        // underneath this — a quick background+return
                        // just re-locks a still-live session; a longer
                        // absence still actually ends it as a stronger
                        // fail-safe.
                        sessionUnlocked = false
                        backgroundDisconnectJob = scope.launch {
                            delay(BACKGROUND_DISCONNECT_GRACE_MS)
                            disconnectAndClearSession()
                        }
                    }
                }
                Lifecycle.Event.ON_START -> {
                    backgroundDisconnectJob?.cancel()
                    backgroundDisconnectJob = null
                    // Auto-trigger the re-auth prompt right as the app
                    // actually resumes to the foreground — the real
                    // right moment for BiometricPrompt (it needs a
                    // resumed, visible Activity to reliably show;
                    // triggering it reactively off sessionUnlocked
                    // instead, which flips false back on ON_STOP while
                    // still backgrounded, risked trying to show it
                    // before the app was actually back in front). The
                    // "Session locked" gate's own manual Unlock button
                    // is still there underneath as a fallback either way.
                    if (!sessionUnlocked &&
                        (latestStatus.state == RnshConnectionState.CONNECTED || latestStatus.state == RnshConnectionState.CONNECTING)
                    ) {
                        requestSessionUnlock()
                    }
                }
                else -> Unit
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        onDispose {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
            backgroundDisconnectJob?.cancel()
        }
    }

    fun sendLine(extra: String = "") {
        val toSend = (lineInputValue.text + extra).toByteArray(Charsets.UTF_8)
        if (toSend.isEmpty()) return
        // Real local-only history (see localHistory's own doc comment) —
        // append the sent line so Up/Down has it to recall. Blank/
        // whitespace-only sends (e.g. a bare Enter) aren't worth
        // recalling, matching real shells' own history behavior.
        if (lineInputValue.text.isNotBlank()) {
            localHistory = localHistory + lineInputValue.text
        }
        localHistoryBrowseIndex = null
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

    // Up/Down: local-only history recall — see localHistory's own doc
    // comment for why this replaced the earlier remote-driven approach
    // (real arrow bytes + parsing the remote's own redraw response),
    // which went through several genuine fix attempts without ever
    // becoming reliable. This is instant, always-editable-by-
    // construction, and has no bytes to parse — recalled text is just
    // set directly, the same simple, already-proven pattern this screen
    // uses everywhere else a value gets set programmatically. older=true
    // is Up (step to an older entry); older=false is Down (step to a
    // more recent one, or exit back to a blank line once past the
    // newest — matching real shell behavior, not a no-op).
    fun recallLocalHistory(older: Boolean) {
        if (localHistory.isEmpty()) return
        val current = localHistoryBrowseIndex
        if (older) {
            val newIndex = if (current == null) localHistory.size - 1 else (current - 1).coerceAtLeast(0)
            localHistoryBrowseIndex = newIndex
            val recalled = localHistory[newIndex]
            lineInputValue = TextFieldValue(recalled, TextRange(recalled.length))
        } else {
            if (current == null) return
            val newIndex = current + 1
            if (newIndex >= localHistory.size) {
                localHistoryBrowseIndex = null
                lineInputValue = TextFieldValue("")
            } else {
                localHistoryBrowseIndex = newIndex
                val recalled = localHistory[newIndex]
                lineInputValue = TextFieldValue(recalled, TextRange(recalled.length))
            }
        }
        // Real, on-device-reported bug this also fixes ("recovered rows
        // are not editable"): tapping the Up/Down button shifts keyboard
        // focus onto that button, and simply updating lineInputValue's
        // *value* doesn't bring focus back to the text field on its own.
        commandFocusRequester.requestFocus()
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
                        IconButton(onClick = { disconnectAndClearSession() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Disconnect")
                        }
                    }
                    // The triple-tap-the-logo panic wipe needs to be
                    // reachable from anywhere in the app (that's the
                    // whole point of it being a gesture, not a menu item
                    // buried in Settings) — every other top-level screen
                    // already carries it; this one was missed when the
                    // screen was first built, found via direct on-device
                    // report.
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
        },
    ) { innerPadding ->
        // Box, not a bare Column, specifically so the session-lock gate
        // below can overlay on top of the real content without having
        // to restructure this whole large existing block into an
        // if/else — the real content still composes underneath
        // (needed anyway so it's ready to show the instant
        // sessionUnlocked flips true), just visually covered while
        // locked. See sessionUnlocked's own doc comment for why this
        // gate exists at all (returning to an already-active session
        // needs fresh authentication, same as starting one).
        // dismissKeyboardOnTap — same established, real utility already
        // used elsewhere in this app (Compose gives a focused text field
        // no built-in way to lose focus/hide the keyboard from a tap
        // elsewhere on the screen). Real, on-device-asked-for gap here
        // specifically: this screen's own BasicTextField auto-requests
        // focus (on CONNECTED, and after every Up/Down recall), so
        // there was previously no way to dismiss the keyboard at all
        // without leaving the screen or disconnecting.
        // consumeWindowInsets(innerPadding) — same real bug/fix as
        // ConversationScreen.kt's own message compose field (see that
        // file's own doc comment): without it, the key row's own real
        // imePadding() further down this tree double-counts the
        // navigation bar's share of this Box's own innerPadding,
        // floating that whole accessory row a real, visible gap above
        // the actual keyboard.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .dismissKeyboardOnTap(),
        ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
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
                status.error?.let { err ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(
                            "Last attempt failed: $err",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        // Reconnects to the same destination that just
                        // failed, one tap — currentAttemptHash still
                        // holds it (only disconnectAndClearSession()/a
                        // new attemptConnect() clear it), the same real
                        // path as tapping the matching history row below,
                        // just without the extra trip back down to it.
                        currentAttemptHash?.let { retryHash ->
                            TextButton(onClick = { attemptConnect(retryHash) }) {
                                Text("Retry")
                            }
                        }
                    }
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

            // Sends the real terminal size (rnsh's own WindowSize
            // message) once CONNECTED and again whenever the measured
            // output area actually changes (rotation, split-screen/
            // foldable resize) — see terminalContainerSize's own doc
            // comment at this screen's top for why this exists at all
            // (the plumbing already existed, it was just never called).
            // "M" is a representative single character under NomadMono
            // (a fixed-width font), so its measured size is a reasonable
            // per-cell width/height to divide the container by — not
            // pixel-perfect (Compose text metrics vs. what a real
            // monospace grid a native terminal emulator computes can
            // differ slightly), but close enough for line-wrapping/
            // dimension-aware remote programs to behave sensibly, which
            // is all rnsh's WindowSize message is for.
            LaunchedEffect(status.state, terminalContainerSize, terminalTextStyle) {
                if (status.state != RnshConnectionState.CONNECTED) return@LaunchedEffect
                if (terminalContainerSize.width <= 0 || terminalContainerSize.height <= 0) return@LaunchedEffect
                val charSize = textMeasurer.measure(text = "M", style = terminalTextStyle).size
                if (charSize.width <= 0 || charSize.height <= 0) return@LaunchedEffect
                val cols = (terminalContainerSize.width / charSize.width).coerceAtLeast(1)
                val rows = (terminalContainerSize.height / charSize.height).coerceAtLeast(1)
                val newSize = rows to cols
                if (lastSentTerminalSize == newSize) return@LaunchedEffect
                // Real, on-device-confirmed race this debounce fixes:
                // RNS.Channel.send() can genuinely throw "Link is not
                // ready" for a brief window right after status.state
                // first reports CONNECTED (the same class of race this
                // file's own ConnectionStatusDot doc comment already
                // documents for sendInput) — the soft keyboard sliding in
                // right as a session connects also changes
                // terminalContainerSize several times in quick
                // succession, so without this, each of those triggered
                // its own immediate, doomed resize attempt (5 failures
                // logged in ~1.4s on a real device before this fix).
                // LaunchedEffect cancels and restarts on every key
                // change, so this delay naturally coalesces that burst
                // into one later, more-likely-to-land attempt.
                delay(RESIZE_SEND_DEBOUNCE_MS)
                try {
                    repository.resize(rows, cols)
                    lastSentTerminalSize = newSize
                } catch (e: Exception) {
                    // Deliberately NOT marking newSize as sent — a later
                    // container-size change (rotation, IME finishing its
                    // own settle) or a fresh connect attempt will retry
                    // naturally. Best-effort otherwise, same non-rethrow
                    // reasoning as sendLine/sendRaw — the remote just
                    // keeps whatever dimensions it last knew about (or
                    // its own PTY default) until one lands.
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .verticalScroll(scrollState)
                    .onSizeChanged { terminalContainerSize = it },
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
                            // A real, on-device-reported bug this guard
                            // fixes ("up arrow only worked for the most
                            // immediate previous command, never changed
                            // to further-back entries"): requesting focus
                            // right after recallLocalHistory
                            // programmatically sets lineInputValue (to
                            // fix a *different*, earlier-reported focus
                            // bug) causes the platform text field to
                            // echo an onValueChange call back with the
                            // *same* text, as an artifact of its own
                            // internal focus-reconciliation — not a real
                            // user edit. Unconditionally resetting
                            // localHistoryBrowseIndex on every call, as
                            // an earlier version of this did, meant that
                            // echo reset browsing state right after every
                            // single recall, so the *next* Up press
                            // always looked like a fresh "start from the
                            // most recent entry" rather than stepping
                            // further back. Only an *actual* content
                            // change means the user has moved off
                            // whatever history entry was last recalled.
                            if (new.text != lineInputValue.text) {
                                localHistoryBrowseIndex = null
                            }
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
                        // Real, on-device-reported bug this fixes: this
                        // callback fires when the *soft keyboard's own*
                        // Enter/Send key is tapped — a genuinely separate
                        // path from onPreviewKeyEvent below, which only
                        // ever sees real hardware KeyEvents (a Bluetooth/
                        // USB keyboard's physical Enter key). A touch-only
                        // device has no hardware KeyEvent to check
                        // isShiftPressed against, so onSend used to call
                        // sendLine() unconditionally — meaning the Shift
                        // toggle had no effect at all on a soft keyboard,
                        // exactly the reported symptom ("can't get shift
                        // to help enter a multi-line enter"). Checking
                        // shiftActive here and manually inserting a real
                        // newline instead of sending mirrors what the
                        // hardware-keyboard path already does for a true
                        // Shift+Enter keydown.
                        keyboardActions = KeyboardActions(onSend = {
                            if (shiftActive) {
                                val newText = lineInputValue.text.replaceRange(
                                    lineInputValue.selection.min, lineInputValue.selection.max, "\n",
                                )
                                val newCursor = lineInputValue.selection.min + 1
                                lineInputValue = TextFieldValue(newText, TextRange(newCursor))
                            } else {
                                sendLine("\r")
                            }
                        }),
                        decorationBox = { innerTextField ->
                            // Real, on-device-reported bug this fixes:
                            // a Row lays every visual line of
                            // innerTextField() out at the *same* x-offset
                            // as the prompt, because decorationBox wraps
                            // the whole field rather than participating
                            // in its own text-layout line-wrapping — fine
                            // for the common single-line case (the prompt
                            // and the one line of input genuinely belong
                            // side by side), but once real multi-line
                            // content exists (Shift+Enter), every line
                            // after the first stayed indented under where
                            // the prompt ended instead of starting at the
                            // real left margin ("doesn't left justify,
                            // stuck inline with the row above"). Falling
                            // back to a Column once the content actually
                            // contains a newline — prompt on its own
                            // line, above the now-multi-line field — is
                            // what actually left-justifies every line;
                            // the true inline-with-prompt cursor is
                            // deliberately only for the single-line case,
                            // since a decorationBox fundamentally can't
                            // make a decorative prefix participate in the
                            // text field's own internal line-wrapping.
                            if (lineInputValue.text.contains('\n')) {
                                Column {
                                    if (promptAnnotated.isNotEmpty()) {
                                        Text(text = promptAnnotated, style = terminalTextStyle)
                                    }
                                    innerTextField()
                                }
                            } else {
                                Row(verticalAlignment = Alignment.Top) {
                                    if (promptAnnotated.isNotEmpty()) {
                                        Text(text = promptAnnotated, style = terminalTextStyle)
                                    }
                                    innerTextField()
                                }
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
                    // Tighter than the button-row default (was 8dp/4dp,
                    // then 6dp/2dp) — per explicit direction, shrunk
                    // specifically so all six controls (the three
                    // modifiers, Tab, and Up/Down) fit on one real row.
                    // labelSmall for Ctrl/Shift/Alt/Tab (was the
                    // TextButton default). The arrow glyphs get their
                    // own, deliberately *larger* style (per explicit
                    // direction — a lone Unicode arrow at labelSmall
                    // read as too small/hard to tap precisely) and
                    // correspondingly tighter horizontal padding to
                    // compensate for the room that costs.
                    val keyButtonPadding = PaddingValues(horizontal = 3.dp, vertical = 4.dp)
                    val arrowButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    val keyButtonTextStyle = MaterialTheme.typography.labelSmall
                    val arrowButtonTextStyle = MaterialTheme.typography.titleMedium
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        // One row for every key-row control — merged per
                        // explicit direction (Up/Down used to sit in
                        // their own row below this one). The three
                        // modifiers are real persistent toggles (tap to
                        // arm, tap again to release; they do NOT auto-
                        // release after one keystroke, per explicit
                        // direction). No dedicated Esc button — a bare,
                        // standalone Esc byte is essentially a no-op at
                        // an ordinary shell prompt (its real use is
                        // always as a *prefix* before another key, e.g.
                        // readline emacs-mode's "Esc f" = Alt+F, which
                        // the Alt toggle already sends); Esc matters far
                        // more inside full-screen programs like vim,
                        // already out of scope for this screen (no
                        // cursor-addressable rendering — see this file's
                        // own top doc comment). Up/Down recall from a
                        // real local-only history (see localHistory's own
                        // doc comment for why — replaced an earlier
                        // remote-driven design that never became
                        // reliable). No Left/Right — cut per explicit
                        // direction: their only real value (nudging the
                        // cursor one character after an imprecise tap)
                        // was judged too narrow to keep, and a touch
                        // field's own native tap-to-place-cursor / long-
                        // press-to-select already covers positioning.
                        // fillMaxWidth + SpaceEvenly — per explicit
                        // direction, spreads the 6 controls evenly across
                        // the full row instead of clustering together
                        // with a bare 2dp gap. This does give up the
                        // horizontalScroll safety net an earlier revision
                        // had here (the two are fundamentally
                        // incompatible — scroll needs the row's content
                        // to be allowed to exceed the viewport, whereas
                        // SpaceEvenly needs a bounded viewport to have any
                        // leftover space to distribute), but a real
                        // uiautomator dump confirmed all 6 KeyRowButtons
                        // together only need ~190dp on a 320dp-wide
                        // screen — comfortably within a normal phone's
                        // width even accounting for a larger accessibility
                        // text-scale setting, so the risk this reintroduces
                        // is low, unlike the original TextButton-MinWidth
                        // bug (KeyRowButton itself, not this Arrangement
                        // choice, is what actually fixed that).
                        Row(
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            ModifierToggleButton(
                                "Ctrl", active = ctrlActive, contentPadding = keyButtonPadding,
                                textStyle = keyButtonTextStyle,
                                onClick = { ctrlActive = !ctrlActive },
                            )
                            ModifierToggleButton(
                                "Shift", active = shiftActive, contentPadding = keyButtonPadding,
                                textStyle = keyButtonTextStyle,
                                onClick = { shiftActive = !shiftActive },
                            )
                            ModifierToggleButton(
                                "Alt", active = altActive, contentPadding = keyButtonPadding,
                                textStyle = keyButtonTextStyle,
                                onClick = { altActive = !altActive },
                            )
                            KeyRowButton(
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
                            ) { Text("Tab", style = keyButtonTextStyle) }
                            // Local-only recall (see localHistory's own
                            // doc comment) — instant, no round trip, so
                            // no loading state is needed here.
                            KeyRowButton(
                                contentPadding = arrowButtonPadding,
                                onClick = { recallLocalHistory(older = true) },
                            ) { Text("↑", style = arrowButtonTextStyle) }
                            KeyRowButton(
                                contentPadding = arrowButtonPadding,
                                onClick = { recallLocalHistory(older = false) },
                            ) { Text("↓", style = arrowButtonTextStyle) }
                        }
                    }
                }
            }
        }
        val needsUnlock = !sessionUnlocked &&
            (status.state == RnshConnectionState.CONNECTED || status.state == RnshConnectionState.CONNECTING)
        if (needsUnlock) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Session locked", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A real remote shell session is already active on this device. Authenticate to view it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NomadTextDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                unlockError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                TextButton(
                    onClick = { requestSessionUnlock() },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("Unlock") }
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
    textStyle: TextStyle = LocalTextStyle.current,
    onClick: () -> Unit,
) {
    KeyRowButton(onClick = onClick, contentPadding = contentPadding) {
        Text(
            label,
            style = textStyle,
            color = if (active) NomadAccent else NomadTextDim,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * A real, on-device-confirmed bug this exists to fix: `TextButton`/
 * `Button` enforce Material3's own `ButtonDefaults.MinWidth`/`MinHeight`
 * (58dp/40dp) via an internal `defaultMinSize` — a hardcoded floor that
 * `contentPadding` and `LocalMinimumInteractiveComponentSize` (already
 * overridden to 0.dp around this whole key row) do *nothing* to shrink,
 * since neither of those touches Button's own internal min-size
 * constraint. Confirmed directly via a uiautomator dump on a real
 * device: with 6 short-label buttons in one row, `contentPadding` alone
 * measured every `TextButton` at ~58dp regardless of its actual text
 * ("Ctrl"/"Shift"/"Alt"/"Tab" all measured identically), leaving no room
 * for the last one — its real underlying `Button` node collapsed to
 * `[0,0][0,0]`, silently untappable rather than visibly broken. `Surface`
 * with a real `onClick` has no such hardcoded floor and *does* respect
 * `LocalMinimumInteractiveComponentSize`, so this is what actually lets
 * the key row's buttons shrink to their real content size.
 */
@Composable
private fun KeyRowButton(
    onClick: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = Color.Transparent,
    ) {
        Box(modifier = Modifier.padding(contentPadding), contentAlignment = Alignment.Center) {
            content()
        }
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

// How long a CONNECTING/CONNECTED session survives after the whole app
// genuinely leaves the foreground (ProcessLifecycleOwner's ON_STOP —
// see that DisposableEffect's own doc comment) before being auto-
// disconnected. Long enough to tolerate a brief, real app-switch (e.g.
// jumping to Files to grab something) without being disruptive; short
// enough that an unlocked-and-abandoned phone doesn't leave a real
// remote shell session open for long.
private const val BACKGROUND_DISCONNECT_GRACE_MS = 5_000L

// See the resize LaunchedEffect's own doc comment — coalesces the burst
// of container-size changes that happen right as a session connects
// (soft keyboard animating in) into one later, more-likely-to-land
// resize attempt, dodging a real "Link is not ready" race confirmed on
// a live device.
private const val RESIZE_SEND_DEBOUNCE_MS = 600L

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
            } else if (c.code == 0x08 || c.code == 0x7F) {
                // Backspace (0x08) / DEL (0x7F, some terminals send this
                // for backspace instead) — a real, on-device-confirmed
                // bug this fixes: previously fell into the generic
                // control-byte-strip branch below like bell/etc., so a
                // real backspace-based erase (readline commonly uses
                // this for short in-place redraws — erase N characters
                // then retype — not always a fresh `\r`+full-rewrite)
                // never actually removed the character it was erasing.
                // The stale character stayed in the extracted text with
                // whatever got typed next appended right after it —
                // on-device-confirmed as two full recalled commands
                // concatenated with no separator ("whoamiecho test1")
                // instead of a clean replacement. Removes the last
                // character already accumulated in the *current* styled
                // run; if nothing's been accumulated yet here (e.g. the
                // character it would erase was already flushed into an
                // earlier, differently-styled span across a color
                // change), there's genuinely nothing this flat, no-real-
                // cursor buffer can safely undo — a real, narrower
                // remaining limitation, not the common case.
                if (textBuf.isNotEmpty()) textBuf.deleteCharAt(textBuf.length - 1)
                i++
            } else if (c.code < 0x20 && c != '\n' && c != '\t') {
                // Other control bytes (bell, etc.) — silently stripped,
                // not rendered and not acted on.
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
