package com.jamesm92.nomadportal.data.rnsh

import kotlinx.coroutines.flow.Flow

/** How a past connection *attempt* to [destinationHash] most recently
 * ended — deliberately just the last outcome, not a full attempt log
 * (this is a "know what you're reconnecting to" aid, not an audit
 * trail). */
enum class RnshHistoryOutcome { SUCCESS, FAILED }

/**
 * One remembered rnsh destination this device has tried to connect to
 * — real client-side history, purely local (never synced/announced;
 * see [RnshRepository]'s own doc comment for this feature's client-only
 * scope). [nickname] is user-editable and optional; when absent the UI
 * falls back to a truncated [destinationHash], same convention as every
 * other address display in this app.
 */
data class RnshHistoryEntry(
    val destinationHash: String,
    val nickname: String?,
    val lastAttemptAtMillis: Long,
    val lastOutcome: RnshHistoryOutcome,
    /** Non-null only when [lastOutcome] is [RnshHistoryOutcome.FAILED]
     * — the same real, UI-displayable reason [RnshStatus.error] carries. */
    val lastError: String?,
)

/**
 * Persists the "recent rnsh destinations" list shown on
 * [com.jamesm92.nomadportal.ui.terminal.RnshTerminalScreen]'s idle
 * state — one entry per distinct [RnshHistoryEntry.destinationHash]
 * ever attempted, upserted (not appended) on every connect attempt so
 * the list stays a "destinations you know about," not a growing log.
 * Purely local bookkeeping — nothing here is announced or shared with
 * anyone else on the mesh.
 */
interface RnshHistoryRepository {
    /** Most-recently-attempted first. */
    fun history(): Flow<List<RnshHistoryEntry>>

    /** Upserts [destinationHash]'s entry with a fresh attempt outcome —
     * called once per real connect attempt reaching a terminal state
     * ([RnshHistoryOutcome.SUCCESS] on first reaching CONNECTED,
     * [RnshHistoryOutcome.FAILED] on reaching FAILED). Preserves any
     * existing [RnshHistoryEntry.nickname] already set for this hash. */
    suspend fun recordAttempt(destinationHash: String, outcome: RnshHistoryOutcome, error: String?)

    /** Sets or clears (via `null`/blank) [destinationHash]'s nickname.
     * A no-op if [destinationHash] has no history entry yet. */
    suspend fun setNickname(destinationHash: String, nickname: String?)

    /** Removes [destinationHash] from the remembered list entirely. */
    suspend fun remove(destinationHash: String)
}
