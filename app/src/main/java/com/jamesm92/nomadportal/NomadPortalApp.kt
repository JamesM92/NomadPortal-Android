package com.jamesm92.nomadportal

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Starts the embedded CPython interpreter once, at process start, so any
 * screen can call [Python.getInstance] without checking [Python.isStarted]
 * itself.
 *
 * This is deliberately the only thing this class does. Suggested-sequencing
 * step 2 in nomadportal_android_handoff.md is "get RNS/LXMF actually running
 * under Chaquopy" — this file is the plumbing that step depends on, not the
 * RNS bootstrap itself, which belongs in the extracted core module once it
 * exists (see handoff doc's "Suggested sequencing", step 1).
 */
class NomadPortalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }
}
