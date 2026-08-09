package com.jamesm92.nomadportal.data.calling

/**
 * Phase 1a of a real voice-call feature — signalling only, no audio yet
 * (see python-core's call_manager.py for the real, source-verified LXST
 * wire protocol this implements). [status] mirrors CallManager's own
 * CallStatus string values one-for-one, kept as a Kotlin enum here for
 * exhaustive `when` handling on the UI side.
 */
data class CallState(
    val status: CallStatusValue,
    val isIncoming: Boolean,
    val remoteIdentityHash: String?,
    /** Resolved through the same name-fallback chain the Messages
     * screens use — see get_call_status_json's own doc comment. Null
     * whenever no name is resolvable, same convention as elsewhere;
     * callers fall back to a truncated [remoteIdentityHash] for display. */
    val remoteName: String?,
    val startedAtMillis: Long?,
    val establishedAtMillis: Long?,
    val endedReason: String?,
) {
    companion object {
        val IDLE = CallState(
            status = CallStatusValue.IDLE,
            isIncoming = false,
            remoteIdentityHash = null,
            remoteName = null,
            startedAtMillis = null,
            establishedAtMillis = null,
            endedReason = null,
        )
    }
}

enum class CallStatusValue {
    IDLE, CALLING, RINGING_OUTGOING, RINGING_INCOMING, CONNECTING, ESTABLISHED,
    ENDED, BUSY, REJECTED, FAILED;

    /** True for every state worth showing a full-screen call overlay
     * for — the complement (just IDLE) is "nothing to show." */
    val isActive: Boolean get() = this != IDLE

    /** True for the four terminal states — CallOverlay auto-dismisses
     * out of these after a short delay rather than requiring a tap,
     * matching how a real phone's "call ended" toast behaves. */
    val isTerminal: Boolean get() = this in TERMINAL

    companion object {
        private val TERMINAL = setOf(ENDED, BUSY, REJECTED, FAILED)

        fun fromWireValue(value: String): CallStatusValue = when (value) {
            "idle" -> IDLE
            "calling" -> CALLING
            "ringing_outgoing" -> RINGING_OUTGOING
            "ringing_incoming" -> RINGING_INCOMING
            "connecting" -> CONNECTING
            "established" -> ESTABLISHED
            "ended" -> ENDED
            "busy" -> BUSY
            "rejected" -> REJECTED
            "failed" -> FAILED
            // An unrecognized value from a future orchestrator.py change
            // shouldn't crash the poll loop — IDLE is the safe default,
            // same "degrade rather than error" convention every other
            // poll-based repository in this app follows.
            else -> IDLE
        }
    }
}
