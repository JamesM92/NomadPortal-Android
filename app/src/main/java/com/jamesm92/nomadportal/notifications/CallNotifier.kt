package com.jamesm92.nomadportal.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jamesm92.nomadportal.MainActivity
import com.jamesm92.nomadportal.R
import com.jamesm92.nomadportal.data.calling.CallRepository
import com.jamesm92.nomadportal.data.calling.CallState
import com.jamesm92.nomadportal.data.calling.CallStatusValue
import kotlinx.coroutines.flow.first

/**
 * Real, previously-missing incoming-call alert — found via an actual
 * live on-device test call (a real external NomadNet peer called this
 * device; it showed up correctly in call history, but nothing ever
 * alerted the person holding the phone while the app was backgrounded).
 * [CallOverlay] only renders once the app's Compose tree is already
 * alive/foregrounded — closing that gap needed a real Android
 * notification, the same way [MessageNotifier] already closed it for
 * messages. Two real, separate gaps, not one: this app had *no* incoming
 * call notification at all before this, on any build.
 *
 * Deliberately its own channel, own higher-urgency treatment (sound +
 * vibration, `IMPORTANCE_HIGH` for a real heads-up alert) — a missed
 * call is a much more time-sensitive miss than a delayed message
 * notification, and real phone call notifications on Android always
 * get this treatment.
 *
 * Real product constraint, not an oversight: incoming-call alerting
 * genuinely cannot work under Battery-friendly mode. A call only rings
 * for [CallRepository]'s own real ~60s timeout (see call_manager.py's
 * ring_timeout_s); WorkManager's real minimum periodic interval is 15
 * minutes, so a Battery-friendly check would almost never land inside
 * that window. This only ever runs from [MessageNotificationService]'s
 * own Always-on loop, at [com.jamesm92.nomadportal.data.calling.RealCallRepository.POLL_INTERVAL_MS]'s
 * much tighter 500ms cadence (not the 4s message-check interval) — see
 * that service's own doc comment for where the two loops actually live
 * side by side.
 */
object CallNotifier {
    const val CHANNEL_CALLS = "nomad_calls"
    private const val NOTIFICATION_ID = 1950

    // In-memory only, not a persisted NotificationStateStore entry like
    // MessageNotifier's own dedup — a call is inherently ephemeral (it
    // either gets answered/declined/times out within ~60s, or it's
    // gone), so there's no real "was this call already notified before
    // the last process restart" question worth answering the way an
    // unread message's dedup key needs to.
    @Volatile private var notifiedCallKey: String? = null

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_CALLS,
            "Incoming calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Real-time alert for an incoming voice call"
            enableVibration(true)
            setSound(
                android.provider.Settings.System.DEFAULT_RINGTONE_URI ?: Uri.EMPTY,
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        manager.createNotificationChannel(channel)
    }

    /** Reads [callRepository]'s current state once and either posts,
     * leaves alone, or cancels the incoming-call notification — safe to
     * call on every poll tick, same "cheap enough to call constantly,
     * dedup is this function's own job" contract [MessageNotifier
     * .checkAndNotify] already established. */
    suspend fun checkAndNotify(context: Context, callRepository: CallRepository) {
        val state = callRepository.callState().first()
        if (state.status != CallStatusValue.RINGING_INCOMING) {
            // Not ringing (answered, timed out, hung up, or never was) —
            // clear any notification a previous tick posted and reset
            // the dedup key so a genuinely new call rings again.
            if (notifiedCallKey != null) {
                NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
                notifiedCallKey = null
            }
            return
        }
        val key = callKey(state)
        if (key == notifiedCallKey) return // Already alerted for this exact call.
        if (ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val callerLabel = state.remoteName ?: state.remoteIdentityHash?.take(16) ?: "Unknown caller"
        // Real CodeQL finding (java/android/implicit-pendingintents),
        // verified against its actual source rather than suppressed —
        // see MessageNotifier.checkAndNotify's own doc comment for the
        // full story, confirmed by actually pulling this finding's real
        // SARIF codeFlow data: splitting mutations into separate
        // statements (below) fixed the dataflow *tracing*, but the
        // sanitizer still never fired, because CodeQL's ExplicitIntent
        // check doesn't reliably recognize Kotlin's `X::class.java`
        // argument on the Intent(context, Class) *constructor* the way
        // it does a real Java `X.class` literal. An explicit .setClass()
        // *method call* matches by name alone (no argument-type check),
        // so that's the form all three Intents use below.
        val openIntent = Intent()
        openIntent.setClass(context, MainActivity::class.java)
        openIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        // Plain "open the app" intent, no extras — CallOverlay is wired
        // at NomadNavHost's own root and renders off callRepository's
        // live state regardless of which screen is showing, so there's
        // no conversation-hash-style deep link to carry here the way
        // MessageNotifier's own content intent needs.
        val contentIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val answerAction = Intent()
        answerAction.setClass(context, CallActionReceiver::class.java)
        answerAction.action = CallActionReceiver.ACTION_ANSWER
        val answerIntent = PendingIntent.getBroadcast(
            context, 1, answerAction,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val declineAction = Intent()
        declineAction.setClass(context, CallActionReceiver::class.java)
        declineAction.action = CallActionReceiver.ACTION_DECLINE
        val declineIntent = PendingIntent.getBroadcast(
            context, 2, declineAction,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Incoming call")
            .setContentText(callerLabel)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true) // Not swipe-dismissible -- answer or decline, like a real call.
            .setAutoCancel(false)
            .setContentIntent(contentIntent)
            .addAction(0, "Answer", answerIntent)
            .addAction(0, "Decline", declineIntent)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            notifiedCallKey = key
        } catch (e: SecurityException) {
            // Permission revoked between the check above and now.
        }
    }

    /** Identifies one real call attempt — who's calling, and when this
     * specific ring started, so a second unrelated call from the same
     * remote (a real, if unlikely, possibility: hang up and immediately
     * call back) still gets its own real alert rather than being
     * silently deduped against the first one. */
    private fun callKey(state: CallState): String =
        "${state.remoteIdentityHash}:${state.startedAtMillis}"
}
