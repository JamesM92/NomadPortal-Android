package com.jamesm92.nomadportal.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jamesm92.nomadportal.MainActivity
import com.jamesm92.nomadportal.NomadPortalApp
import com.jamesm92.nomadportal.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Always-on notification mode's real foreground service — Settings'
 * own explicit user choice (the "Recommended" pick, per the Columba-
 * settings-parity plan) for reliable, real-time message notifications
 * while backgrounded. A persistent low-priority notification keeps the
 * process alive so [MessageNotifier.checkAndNotify] can actually run on
 * a real interval instead of being subject to Doze's own scheduling.
 *
 * Plain Kotlin `Service`, not Python-bound — a genuinely different
 * concern from `RnsBleForegroundService` (BLE-specific, lives in the
 * sibling RNS_BLE_Wrapper repo): this one just periodically polls
 * [com.jamesm92.nomadportal.data.messaging.MessagingRepository.conversations]
 * (already itself backed by the Python bridge) and posts real Android
 * notifications, no Chaquopy/RNS interface concerns of its own.
 *
 * Start/stop mirrors [com.jamesm92.nomadportal.connectivity.BluetoothMeshManager]'s
 * exact shape (`context.startForegroundService`/`context.stopService`),
 * driven by Settings' Notifications toggle + the same boot-time-replay
 * pattern [com.jamesm92.nomadportal.NomadPortalApp] already uses for
 * every other interface toggle.
 */
class MessageNotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob())
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        MessageNotifier.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildOngoingNotification())
        if (loopJob?.isActive != true) {
            loopJob = scope.launch {
                val app = application as NomadPortalApp
                val stateStore = NotificationStateStore(this@MessageNotificationService)
                while (isActive) {
                    try {
                        MessageNotifier.checkAndNotify(
                            context = this@MessageNotificationService,
                            messagingRepository = app.messagingRepository,
                            stateStore = stateStore,
                        )
                    } catch (e: Exception) {
                        // Best-effort — one failed check shouldn't kill the
                        // whole loop; the next tick tries again.
                    }
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun buildOngoingNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, MessageNotifier.CHANNEL_BACKGROUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("NomadPortal is running")
            .setContentText("Staying connected to notify you about new messages")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1900
        // Same interval RealMessagingRepository's own conversations()
        // poll uses — kept consistent rather than tuned independently.
        private const val POLL_INTERVAL_MS = 4000L

        fun start(context: Context) {
            val intent = Intent(context, MessageNotificationService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MessageNotificationService::class.java))
        }
    }
}
