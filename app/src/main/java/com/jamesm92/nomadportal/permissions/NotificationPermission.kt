package com.jamesm92.nomadportal.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * `POST_NOTIFICATIONS` — only a real runtime permission on API 33+
 * (Android 13 "Tiramisu"); on older versions notifications never needed
 * explicit consent, so this reports granted unconditionally there. Same
 * "never block, always degrade gracefully" rule as every other optional
 * permission in this app: a denial just means Settings' own Notifications
 * section shows a real "permission not granted" status instead of
 * silently pretending notifications are working.
 */
fun hasPostNotificationsPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}
