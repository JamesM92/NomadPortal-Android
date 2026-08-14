package com.jamesm92.nomadportal.data.rnsh

/**
 * Mirrors `nomadnet_web.rnsh_client.RnshSession`'s own real state
 * machine 1:1 (see that class's own doc comment) — [IDLE] is the one
 * Kotlin-side addition, meaning no session has ever been started yet
 * (or the app just launched), distinct from [CLOSED] (a session that
 * *did* run and has since ended).
 */
enum class RnshConnectionState { IDLE, CONNECTING, CONNECTED, CLOSED, FAILED }

/**
 * Live status of this device's one active (or just-ended) rnsh remote-
 * shell session — see [RnshRepository]'s own doc comment for what rnsh
 * is and this app's own client-only scope decision.
 */
data class RnshStatus(
    val state: RnshConnectionState,
    /** Non-null only when [state] is [RnshConnectionState.FAILED] — a
     * real, UI-displayable reason (bad destination hash, no path,
     * handshake timeout, a remote error message), never fabricated. */
    val error: String?,
    /** Non-null only once the remote shell has actually exited (state
     * [RnshConnectionState.CLOSED] via a real `CommandExitedMessage`,
     * not just a link drop) — the shell's own real exit code. */
    val exitCode: Int?,
)
