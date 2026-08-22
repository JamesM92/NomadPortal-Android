package com.jamesm92.nomadportal.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * The one real poll-based-repository shape this whole app's data layer uses (see the
 * `nomadportal-android-conventions` skill's "Chaquopy bridge" section — every
 * `Real*Repository` polls its Python-side orchestrator on a Flow since no push
 * mechanism exists there). Collapsed here after a real bug found via a device report:
 * every one of these flows (7 files, 15 call sites) used to inline its own
 * `flow { while (true) { emit(fetch()); delay(ms) } }`, completely unguarded.
 *
 * Chaquopy's orchestrator can genuinely throw on a single poll tick — most plausibly
 * during real app cold-start on a slower/more-loaded device, while Python-side RNS/LXMF
 * bring-up is still finishing — and an uncaught exception inside a `flow {}` builder
 * terminates that flow *permanently*: no retry, no further emissions, ever, for the
 * lifetime of that particular collection. Compose's `collectAsState` just keeps showing
 * whatever the `initial` value was (typically an empty list) with no visible error and
 * no recovery. On-device this read as "Manage Identities shows nothing until I add a new
 * one" and "Messages shows nothing for a full minute" — not actually missing data, just
 * a dead flow that happened to get resurrected by some unrelated action starting a fresh
 * collection (e.g. navigating away and back, or another screen's recomposition).
 *
 * [fetch] failing on one tick now just logs it and skips that emission, retrying after
 * the normal [intervalMs] — the resilience real polling code always needs, which none of
 * these call sites had before.
 */
fun <T> pollingFlow(intervalMs: Long, fetch: suspend () -> T): Flow<T> = flow {
    while (true) {
        try {
            emit(fetch())
        } catch (e: Exception) {
            Log.w("PollingFlow", "Poll tick failed, retrying in ${intervalMs}ms", e)
        }
        delay(intervalMs)
    }
}.flowOn(Dispatchers.IO)
