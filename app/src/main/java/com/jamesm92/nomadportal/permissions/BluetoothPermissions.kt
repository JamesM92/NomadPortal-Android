package com.jamesm92.nomadportal.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * The only runtime permissions this app ever requests are these two
 * Bluetooth ones — deliberately never `ACCESS_FINE_LOCATION` /
 * `ACCESS_COARSE_LOCATION`, per nomadportal_android_handoff.md's "never
 * request location, under any circumstances" requirement. That requirement
 * is what `minSdk = 31` (app/build.gradle.kts) exists to make possible:
 * `BLUETOOTH_SCAN`'s `neverForLocation` flag (declared in
 * AndroidManifest.xml) only exists on API 31+, and below that Android ties
 * BLE scan results to location permission with no bypass.
 *
 * Every caller of these permissions must treat a denial as a normal,
 * fully-supported state — nothing in this app is allowed to hard-require
 * them. See [hasBluetoothPermissions] for the check to gate
 * Bluetooth-mesh/RNode-over-Bluetooth functionality on, and degrade
 * gracefully (not block) when it's false.
 */
val BLUETOOTH_PERMISSIONS: Array<String> = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    // Needed once the BLE-mesh interface acts as a peripheral/GATT server
    // so other devices can discover this one, not just discover others.
    Manifest.permission.BLUETOOTH_ADVERTISE,
)

fun hasBluetoothPermissions(context: Context): Boolean =
    BLUETOOTH_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
