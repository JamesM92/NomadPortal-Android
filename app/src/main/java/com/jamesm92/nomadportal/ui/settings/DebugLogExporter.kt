package com.jamesm92.nomadportal.ui.settings

import android.content.Context
import android.os.Process
import java.io.File

/**
 * Real, on-device debug-log capture — per explicit request ("is there
 * a way to add a debugging logs download button to the app? so we can
 * grab it on the phone itself?"), surfaced directly while chasing a
 * real Bluetooth-mesh bug across three physical test phones that ADB
 * access wasn't practically available for. `adb logcat` was this
 * project's own established diagnostic tool all session — this makes
 * the same real data reachable without a cable/computer at all.
 *
 * `logcat -d --pid=<this process's own pid>` — no special permission
 * needed for this specific filtered form (`READ_LOGS`, which a normal
 * third-party app can never hold, is only required to read *other*
 * apps'/the system's own log lines; every app is always allowed to
 * read back its *own* process's output this way, the same real
 * mechanism libraries like ACRA build in-app bug reporting on). This
 * captures both this app's own Kotlin-side log lines and Python's
 * stdout/stderr (the `python.stdout`/`python.stderr`-tagged lines
 * this whole project's own development already leans on constantly) —
 * Chaquopy runs Python embedded in this same process, not a separate
 * one, so both land in the same PID-filtered stream automatically.
 */
object DebugLogExporter {
    /** The one real capture — everything else in this object is a
     * different way of handing the same text to the user. Blocking
     * (real process I/O), null on genuine failure (e.g. `logcat`
     * unavailable on some OEM build), never throwing. */
    fun captureLogText(): String? {
        return try {
            val pid = Process.myPid()
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "threadtime", "--pid=$pid"),
            )
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output
        } catch (e: Exception) {
            null
        }
    }

    /** Writes a fresh capture to a real file in this app's private
     * cache dir and returns it — for the system share sheet (Bluetooth,
     * a file manager, email, etc.). Null if either the capture or the
     * write itself failed. */
    fun exportLogs(context: Context): File? {
        val text = captureLogText() ?: return null
        return try {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val file = File(context.cacheDir, "NomadPortal-debug-log-$timestamp.txt")
            file.writeText(text)
            file
        } catch (e: Exception) {
            null
        }
    }
}
