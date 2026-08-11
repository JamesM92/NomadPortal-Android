package com.jamesm92.nomadportal.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * `CAMERA` — needed by [com.jamesm92.nomadportal.ui.components.QrScannerOverlay]
 * to preview/scan a QR code for the "add contact by QR" flow. Same rule
 * as [hasRecordAudioPermission]/[BLUETOOTH_PERMISSIONS]: a denial is a
 * normal, fully-supported state — the scanner shows a "camera permission
 * needed" message instead of a preview, and typing an address by hand
 * (this feature's whole reason for existing alongside
 * [com.jamesm92.nomadportal.ui.components.AddByAddressDialog]'s own text
 * field) is never blocked by it.
 */
fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED
