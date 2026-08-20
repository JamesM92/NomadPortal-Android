package com.jamesm92.nomadportal.notifications

import android.content.Context

/**
 * The one place that reconciles Settings' Notifications toggle +
 * reliability choice into "which of [MessageNotificationService]/
 * [MessageCheckWorker] should actually be running right now" — called
 * both from the Settings UI (immediately, on toggle change) and from
 * [com.jamesm92.nomadportal.NomadPortalApp]'s own boot-time replay
 * (same pattern every other interface toggle already uses there),
 * so there's exactly one place this reconciliation logic lives rather
 * than duplicated at both call sites.
 */
object MessageNotificationController {
    fun apply(context: Context, enabled: Boolean, alwaysOn: Boolean) {
        if (!enabled) {
            MessageNotificationService.stop(context)
            MessageCheckWorker.cancel(context)
            return
        }
        if (alwaysOn) {
            MessageCheckWorker.cancel(context)
            MessageNotificationService.start(context)
        } else {
            MessageNotificationService.stop(context)
            MessageCheckWorker.schedule(context)
        }
    }
}
