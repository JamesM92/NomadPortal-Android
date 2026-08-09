package com.jamesm92.nomadportal.data.calling

import kotlinx.coroutines.flow.Flow

/**
 * Phase 1a voice-call bridge — signalling only, no audio yet (see
 * python-core's call_manager.py). Poll-based like every other repository
 * in this app (no push mechanism exists on the Python side for this any
 * more than for messaging/browsing), but at a much shorter interval than
 * those — see [RealCallRepository.POLL_INTERVAL_MS]'s own comment for why
 * ringing/hangup need to feel near-instant in a way a 4s messaging poll
 * doesn't.
 */
interface CallRepository {
    fun callState(): Flow<CallState>

    /** [addressHex] may be a destination hash (a contact's already-
     * familiar LXMF address, for manual entry — real on-device request:
     * "we need the ability to manually enter a call address, if
     * somebody hasnt annoucned it") or an identity hash (what a known
     * call-capable contact's phone-icon tap already has on hand) — see
     * CallManager.resolve_identity()'s own doc comment for why both
     * shapes just work. Suspends for real network I/O (path lookup),
     * same as every other network-touching repository call in this app. */
    suspend fun placeCall(addressHex: String): Boolean

    suspend fun answerCall(): Boolean

    suspend fun hangUp(): Boolean

    /** Fires a fresh announce of this device's own telephony destination
     * — a real periodic loop already re-announces automatically (see
     * orchestrator.py's start_call_announce_loop), but a manual trigger
     * matters on its own for testing/troubleshooting right now, per
     * explicit request ("eventually the call address auto announce will
     * need its own auto announce toggle and manual announce toggle"). */
    suspend fun announceCallAddress(): Boolean

    /** Clears a terminal call state (ended/busy/rejected/failed) back to
     * idle — called once the UI's shown that state for a moment, not
     * automatically the instant the call ends (see CallOverlay). */
    suspend fun dismiss()
}
