package com.jamesm92.nomadportal.data.rnsh

import kotlinx.coroutines.flow.Flow

/**
 * A real remote-shell client speaking rnsh's actual wire protocol
 * (github.com/acehoss/rnsh, MIT License) — confirmed by reading its
 * real `initiator.py`/`protocol.py` source, not guessed; see
 * `nomadnet_web.rnsh_client`'s own top doc comment for the full
 * interop details.
 *
 * **Client (initiator) only, deliberately** — see the
 * nomadportal-android-rnsh-decision memory for the full reasoning.
 * This app only ever connects OUT to a remote `rnsh listener` someone
 * else already runs and controls (e.g. a home server reachable over
 * the mesh); there is no listener here, and none should ever be added
 * — a listener would mean anyone with the right destination hash gets
 * a real shell *on this device*, a fundamentally different risk
 * category than anything else this app does, and one that directly
 * conflicts with its own safety-first positioning.
 *
 * **"Line mode" only** — see `ui/terminal/RnshTerminalScreen.kt`'s own
 * doc comment for why: this app doesn't attempt true character-at-a-
 * time raw terminal mode (arrow-key shell history, live tab
 * completion), only composing a line locally and sending it on Enter/
 * an explicit control action — a real, first-class mode rnsh's own
 * reference client also supports (its `~L` escape), not a hacky
 * workaround.
 */
interface RnshRepository {
    /** Polled, same "no push mechanism across the Chaquopy boundary"
     * convention every other real-time-ish status in this app already
     * follows (`nomadportal-android-conventions` skill) — ticks fast
     * (not the app's usual 4s interval) since a remote-shell session
     * needs to feel reasonably responsive. */
    fun status(): Flow<RnshStatus>

    /** New output bytes (stdout+stderr interleaved, in receive order —
     * a real interactive terminal already presents both on one visual
     * stream) since the last poll — an empty array most ticks, never
     * an error. Same polled-Flow shape as [status], just delivering a
     * delta each emission instead of a snapshot. */
    fun outputChunks(): Flow<ByteArray>

    /**
     * Starts connecting to [destinationHash] (32 hex chars — a raw RNS
     * destination hash, the same shape every other manual-address entry
     * in this app already accepts), tearing down any prior session
     * first (single-session-at-a-time). Returns once the attempt has
     * been *started*, not once it's actually connected — poll [status]
     * for real progress. Throws with a UI-displayable reason if the
     * attempt couldn't even be started (e.g. this device's own identity
     * isn't ready yet) — same "throws on failure" contract as
     * [com.jamesm92.nomadportal.data.messaging.MessagingRepository.sendMessage].
     * A bad/unreachable destination hash doesn't throw here — it starts
     * a session that fails asynchronously, surfaced through [status]'s
     * own [RnshConnectionState.FAILED] state instead.
     */
    suspend fun connect(destinationHash: String)

    /** Sends [data] as the remote shell's stdin — a full composed line
     * (already including its own trailing newline/control bytes, if
     * any), not per-keystroke, per this interface's own "line mode
     * only" doc comment. */
    suspend fun sendInput(data: ByteArray)

    /** Informs the remote shell of this device's own terminal size —
     * best-effort, most programs don't strictly need it for basic line-
     * mode usage. */
    suspend fun resize(rows: Int, cols: Int)

    /** Ends the current session, if any. Safe to call with no session
     * active (a no-op). */
    suspend fun disconnect()
}
