package com.jamesm92.nomadportal.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.jamesm92.nomadportal.NomadPortalApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the real "Answer"/"Decline" action buttons on
 * [CallNotifier]'s own incoming-call notification — a one-tap answer
 * without opening the app first, matching how a real phone call
 * notification works. [BroadcastReceiver.onReceive] can't suspend
 * directly, so this uses the standard [goAsync] + coroutine pattern
 * (the receiver's process is kept alive long enough for the suspend
 * call to actually finish, not torn down the instant onReceive
 * returns).
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? NomadPortalApp ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_ANSWER -> app.callRepository.answerCall()
                    ACTION_DECLINE -> app.callRepository.hangUp()
                }
            } catch (e: Exception) {
                // Best-effort, same as every other notification-action
                // receiver in this app's own conventions — a failed
                // answer/decline here still leaves CallOverlay itself as
                // a working fallback once the app is opened.
            } finally {
                NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_INCOMING_CALL)
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.jamesm92.nomadportal.action.CALL_ANSWER"
        const val ACTION_DECLINE = "com.jamesm92.nomadportal.action.CALL_DECLINE"

        // Mirrors CallNotifier's own private NOTIFICATION_ID -- kept as
        // a second constant rather than making that one internal/public,
        // since this is the only other real call site that needs it and
        // this project's own convention (see MdiIconRepository's own
        // "promote only after real reuse" note) is to avoid widening
        // visibility until there's an actual second need beyond just
        // this pairing.
        private const val NOTIFICATION_ID_INCOMING_CALL = 1950
    }
}
