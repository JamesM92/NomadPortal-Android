package com.jamesm92.nomadportal.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jamesm92.nomadportal.NomadPortalApp
import java.util.concurrent.TimeUnit

/**
 * Battery-friendly notification mode's periodic check — no persistent
 * notification, but Android may delay or skip runs under Doze (a real,
 * disclosed trade-off — see Settings' own Notifications section copy
 * and the Columba-settings-parity plan). One-shot per invocation:
 * `doWork()` just calls the same [MessageNotifier.checkAndNotify] the
 * always-on service's own loop calls, so "what counts as new" logic is
 * never duplicated between the two modes.
 *
 * [Python.getInstance] is safe to call here — [NomadPortalApp.onCreate]
 * (which starts Python) always runs before any component in this
 * process, `Worker` included, since WorkManager runs in-process by
 * default (no separate `:work` process declared anywhere in this app).
 */
class MessageCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            MessageNotifier.ensureChannels(applicationContext)
            val app = applicationContext as NomadPortalApp
            val stateStore = NotificationStateStore(applicationContext)
            MessageNotifier.checkAndNotify(
                context = applicationContext,
                messagingRepository = app.messagingRepository,
                stateStore = stateStore,
            )
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "nomad_message_check"

        /** Android's own real minimum period for periodic work (15
         * minutes) — this app can't check more often than that in
         * battery-friendly mode, which is exactly the trade-off
         * Settings' own copy discloses. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MessageCheckWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
