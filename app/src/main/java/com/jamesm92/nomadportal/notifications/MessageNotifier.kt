package com.jamesm92.nomadportal.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jamesm92.nomadportal.MainActivity
import com.jamesm92.nomadportal.R
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import kotlinx.coroutines.flow.first

/**
 * The one real "check for new messages, post a notification" operation
 * — called identically by both [MessageNotificationService] (always-on,
 * every [POLL_INTERVAL_MS]) and [MessageCheckWorker] (battery-friendly,
 * every ~15 min), so there's exactly one place "what counts as new"
 * lives, per this feature's own design (see the Columba-settings-parity
 * plan). Real Columba-parity gap closed here — this app had no
 * notifications at all before.
 */
object MessageNotifier {
    /** The real alerting channel — new messages land here. */
    const val CHANNEL_MESSAGES = "nomad_messages"

    /** Silent/ongoing — only [MessageNotificationService]'s own
     * persistent "staying connected" notification uses this. */
    const val CHANNEL_BACKGROUND = "nomad_background"

    private const val NOTIFICATION_ID_BASE = 2000

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                "Messages",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "New LXMF messages" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BACKGROUND,
                "Background connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Keeps NomadPortal connected to the mesh in the background" },
        )
    }

    /** Fetches conversations once (not a collected [kotlinx.coroutines.flow.Flow])
     * and posts a real notification for any contact whose most recent
     * *received* message is genuinely new since the last check — see
     * [NotificationStateStore]'s own doc comment for the persistence
     * this relies on. Safe to call repeatedly; already-notified messages
     * are never re-notified. */
    suspend fun checkAndNotify(
        context: Context,
        messagingRepository: MessagingRepository,
        stateStore: NotificationStateStore,
    ) {
        if (ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val conversations = messagingRepository.conversations().first()
        val manager = NotificationManagerCompat.from(context)
        for (summary in conversations) {
            val lastMessage = summary.lastMessage ?: continue
            if (lastMessage.isSent) continue // Only received messages notify.
            val contactHash = summary.contact.lxmfHash
            val lastNotified = stateStore.getLastNotifiedId(contactHash)
            if (lastNotified == lastMessage.id) continue // Already notified this exact message.

            // Real CodeQL finding (java/android/implicit-pendingintents),
            // verified against its actual source rather than suppressed:
            // this Intent genuinely is explicit (the two-arg
            // Intent(context, Class) constructor is exactly what
            // ExplicitIntentSanitizer's own real check looks for), but
            // CodeQL's dataflow loses that once mutations happen inside a
            // chained `.apply { }` block instead of as separate
            // statements against a named val — the flow from the
            // constructor call to the PendingIntent sink below never
            // gets traced through the lambda. Splitting the mutations
            // out (below) is the real fix, not a suppression: same
            // object, same explicit intent, just written so the
            // analyzer's own local-dataflow check can actually follow
            // it.
            val openIntent = Intent(context, MainActivity::class.java)
            openIntent.action = Intent.ACTION_VIEW
            openIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            openIntent.putExtra(MainActivity.EXTRA_CONVERSATION_HASH, contactHash)
            val pendingIntent = PendingIntent.getActivity(
                context,
                contactHash.hashCode(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(summary.contact.displayName)
                .setContentText(lastMessage.content.take(MAX_PREVIEW_CHARS))
                .setStyle(NotificationCompat.BigTextStyle().bigText(lastMessage.content.take(MAX_PREVIEW_CHARS)))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            try {
                manager.notify(NOTIFICATION_ID_BASE + (contactHash.hashCode() and 0x7FFFFFFF) % 10000, notification)
            } catch (e: SecurityException) {
                // Permission revoked between the check above and now — a
                // real, if rare, race; not worth crashing a background
                // check over.
            }
            stateStore.setLastNotifiedId(contactHash, lastMessage.id)
        }
    }

    private const val MAX_PREVIEW_CHARS = 200
}
