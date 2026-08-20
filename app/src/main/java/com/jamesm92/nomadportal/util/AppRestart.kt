package com.jamesm92.nomadportal.util

import android.content.Context
import android.content.Intent

/**
 * Relaunches the app as a fresh process — no in-memory state (RNS's own
 * singleton `Reticulum()` instance in particular, which per
 * `orchestrator.py`'s own doc comment can't be reconstructed live once
 * built) survives past this call, only what's persisted to disk does.
 *
 * Extracted from [com.jamesm92.nomadportal.panicwipe.PanicWipe]'s own
 * `restartApp` — a second real caller showed up (the Settings "transport
 * node mode" toggle, which needs a real restart for its RNS
 * `enable_transport` change to take effect, same "RNS init-time-only
 * decision" constraint the panic wipe's own identity regeneration
 * already had to work around), so this is now shared plumbing rather
 * than duplicated in two places, per this project's own "promote only
 * after real reuse" convention.
 */
object AppRestart {
    fun restart(context: Context) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val restartIntent = Intent.makeRestartActivityTask(launchIntent?.component)
        context.startActivity(restartIntent)
        Runtime.getRuntime().exit(0)
    }
}
