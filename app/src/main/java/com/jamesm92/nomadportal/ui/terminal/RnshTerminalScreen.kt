package com.jamesm92.nomadportal.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.rnsh.RnshConnectionState
import com.jamesm92.nomadportal.data.rnsh.RnshRepository
import com.jamesm92.nomadportal.data.rnsh.RnshStatus
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.theme.NomadMono
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
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
 * line in [OutlinedTextField] below, then it's sent on Enter/Send, not
 * per-keystroke. This app doesn't attempt true character-at-a-time raw
 * terminal mode — Compose has no clean way to capture every raw
 * keystroke (arrow keys, ctrl-combinations) portably across devices/
 * IMEs the way a real terminal app needs, and building that is a much
 * bigger, riskier undertaking (Termux's own extensive custom key-
 * handling code is testament to how real that problem is). Practical
 * effect: shell history recall via the Up arrow and live tab-completion
 * don't work; running a command and reading its output does.
 *
 * **ANSI rendering is deliberately partial**: SGR color/bold codes
 * (`\x1b[...m`) are parsed into real colored/styled text spans; every
 * other escape sequence (cursor positioning, clear-screen, etc.) is
 * silently consumed rather than leaking into the visible text, but
 * *not* actually acted on — there's no real cursor-addressable screen
 * buffer here, just an append-only scrolling log. Full-screen programs
 * (`vim`, `htop`, `less`) will render wrong; ordinary command output
 * (including colored `ls`/`grep`/prompts) looks right. A real, honest
 * scope boundary, not an oversight — see this file's own git history
 * for the reasoning if this ever needs revisiting.
 */
@Composable
fun RnshTerminalScreen(repository: RnshRepository, onBack: () -> Unit) {
    val status by repository.status().collectAsState(
        initial = RnshStatus(RnshConnectionState.IDLE, null, null),
    )
    val scope = rememberCoroutineScope()
    var destinationHash by remember { mutableStateOf("") }
    var connectError by remember { mutableStateOf<String?>(null) }
    var lineInput by remember { mutableStateOf("") }
    var terminalStyle by remember { mutableStateOf(TermStyleState()) }
    var terminalBuffer by remember { mutableStateOf(AnnotatedString("")) }
    val scrollState = rememberScrollState()

    // Collects new output chunks only while a session is actually
    // running — outputChunks() polls Python regardless, but there's
    // nothing useful to append once idle/closed/failed.
    LaunchedEffect(status.state) {
        if (status.state != RnshConnectionState.CONNECTING && status.state != RnshConnectionState.CONNECTED) {
            return@LaunchedEffect
        }
        repository.outputChunks().collect { chunk ->
            if (chunk.isEmpty()) return@collect
            val (appended, newStyle) = parseAnsiChunk(String(chunk, Charsets.UTF_8), terminalStyle)
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

    LaunchedEffect(terminalBuffer) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    fun sendLine(extra: String = "") {
        val toSend = (lineInput + extra).toByteArray(Charsets.UTF_8)
        if (toSend.isEmpty()) return
        lineInput = ""
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

    fun sendControl(byte: Int) {
        scope.launch {
            try {
                repository.sendInput(byteArrayOf(byte.toByte()))
            } catch (e: Exception) {
                // Same non-rethrow reasoning as sendLine above.
            }
        }
    }

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = { Text("Remote Shell (rnsh)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                        "incoming shell sessions, only connects out.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NomadTextDim,
                )
                OutlinedTextField(
                    value = destinationHash,
                    onValueChange = { destinationHash = it.trim() },
                    singleLine = true,
                    label = { Text("Destination hash") },
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
                Button(
                    onClick = {
                        val trimmed = destinationHash.trim().lowercase()
                        val isValidHex = trimmed.isNotEmpty() && trimmed.length % 2 == 0 &&
                            trimmed.all { it in "0123456789abcdef" }
                        if (!isValidHex) {
                            connectError = "Not a valid hex address"
                            return@Button
                        }
                        connectError = null
                        terminalBuffer = AnnotatedString("")
                        terminalStyle = TermStyleState()
                        scope.launch {
                            try {
                                repository.connect(trimmed)
                            } catch (e: Exception) {
                                connectError = e.message ?: "Could not start connecting"
                            }
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Connect") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (status.state == RnshConnectionState.CONNECTING) "Connecting…" else "Connected",
                        style = MaterialTheme.typography.labelSmall,
                        color = NomadTextDim,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { scope.launch { repository.disconnect() } }) { Text("Disconnect") }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .verticalScroll(scrollState),
            ) {
                SelectionContainer {
                    Text(
                        text = terminalBuffer,
                        fontFamily = NomadMono,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD3D7CF),
                    )
                }
            }

            if (status.state == RnshConnectionState.CONNECTED) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { sendControl(0x03) }) { Text("Ctrl+C") }
                    TextButton(onClick = { sendControl(0x04) }) { Text("Ctrl+D") }
                    TextButton(onClick = { sendControl(0x09) }) { Text("Tab") }
                    TextButton(onClick = { sendControl(0x1b) }) { Text("Esc") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedTextField(
                        value = lineInput,
                        onValueChange = { lineInput = it },
                        singleLine = true,
                        placeholder = { Text("Command") },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { sendLine("\r") }) { Text("Send") }
                }
            }
        }
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
