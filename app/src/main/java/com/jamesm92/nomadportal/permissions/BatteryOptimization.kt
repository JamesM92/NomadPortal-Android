package com.jamesm92.nomadportal.permissions

import android.content.Context
import android.os.PowerManager

/**
 * Whether this app is already exempt from Android's Doze/App-Standby
 * battery optimization — relevant specifically for the "Always-on"
 * notification mode ([com.jamesm92.nomadportal.notifications.MessageNotificationService]):
 * without this exemption, Android can still throttle a foreground
 * service's own background work under aggressive Doze, even though the
 * service itself keeps running. A denial here is a normal, fully-
 * supported state — Always-on mode just becomes less reliable, same
 * "never block, always degrade gracefully" rule this app applies to
 * every other optional permission (Bluetooth/camera/record-audio).
 *
 * Not a runtime permission (no `RequestPermission()` contract applies)
 * — the actual grant flow is a system Settings screen, opened via
 * `Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
 * Uri.parse("package:$packageName"))` and
 * `rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult())`
 * at the call site (see SettingsScreen.kt's own Notifications section).
 */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}
