package com.jamesm92.nomadportal.data

import android.util.Log
import kotlinx.coroutines.CancellationException
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
 *
 * Real regression found via a device report (a low-end phone showing the exact same
 * "Identities/Network/Messages barely populate" symptom this function was written to
 * fix, moments after this function first shipped): the original `catch (e: Exception)`
 * also caught [CancellationException] — including Compose's own
 * `LeftCompositionCancellationException`, fired routinely whenever a screen collecting
 * this flow leaves composition. Swallowing it here instead of letting it propagate
 * fights Kotlin's structured concurrency (a cancelled coroutine is supposed to stop, not
 * catch its own cancellation and loop around for another `delay()`), and on a slower/
 * more memory-constrained device — where Compose recomposes and tears down screens far
 * more often under real GC pressure — this fired constantly, exactly the same class of
 * bug already found once this session in BrowserScreen.kt's refreshLive(). Cancellation
 * must always rethrow; only a genuine fetch failure gets logged and retried.
 *
 * Widened `catch (e: Exception)` to `catch (t: Throwable)` (still rethrowing
 * CancellationException first) after a live device investigation: a `conversations()`
 * poll processing ~1900 real LXMF peers on a memory-constrained phone showed zero
 * emissions, zero "Poll tick failed" warnings anywhere in the log, and no crash --
 * consistent with an uncaught `OutOfMemoryError` silently killing that one poll
 * coroutine. `OutOfMemoryError` is a `Throwable`/`Error`, not an `Exception` -- a real
 * JVM class-hierarchy gap the original catch couldn't close no matter how correct its
 * own logic was. The same resilience reasoning applies here as to an ordinary fetch
 * failure: skip this tick, log it, retry after the normal interval -- by the next tick,
 * memory pressure may have eased and GC may have already reclaimed the failed attempt's
 * garbage, exactly the kind of transient condition a poll loop should ride out rather
 * than die to permanently.
 */
fun <T> pollingFlow(intervalMs: Long, fetch: suspend () -> T): Flow<T> = flow {
    while (true) {
        try {
            emit(fetch())
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("PollingFlow", "Poll tick failed, retrying in ${intervalMs}ms", t)
        }
        delay(intervalMs)
    }
}.flowOn(Dispatchers.IO)
