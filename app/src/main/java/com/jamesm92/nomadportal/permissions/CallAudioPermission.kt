package com.jamesm92.nomadportal.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * `RECORD_AUDIO` — needed by [com.jamesm92.nomadportal.audio.CallAudioEngine]'s
 * capture thread to actually send this device's own audio during a
 * voice call. Same rule as [BLUETOOTH_PERMISSIONS]: a denial is a
 * normal, fully-supported state, not something any caller is allowed
 * to hard-require — see [hasRecordAudioPermission]'s own callers for
 * how a denial degrades to receive-only audio instead of blocking the
 * call.
 */
fun hasRecordAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
