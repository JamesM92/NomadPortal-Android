package com.jamesm92.nomadportal.ui.settings

import android.content.Context
import android.os.Process
import com.jamesm92.nomadportal.BuildConfig
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
     * unavailable on some OEM build), never throwing.
     *
     * Silences a fixed list of framework tags that are real, high-
     * volume noise but never diagnostically useful for this app's own
     * bugs (Compose's own per-frame `setRequestedFrameRate` spam,
     * `Choreographer`/`HWUI`/`BLASTBufferQueue*` frame-timing chatter,
     * `WM-*`/`nativeloader`/insets/IME plumbing that fires on every
     * screen transition) — found the hard way when a real captured
     * log from a live 3-phone Bluetooth-mesh test hit a 50,000-
     * character paste limit before it ever reached the actual
     * `MeshTransport`/`MeshGattClient`/`MeshGattServer`/`RnsBleBridge`
     * lines the bug chase actually needed, buried under thousands of
     * lines of this. Uses `logcat`'s own filterspec (`Tag:S` = silent
     * for that tag, trailing `*:D` = everything else still at Debug+)
     * rather than a client-side line-by-line grep — cheaper (the
     * noise is filtered by the logging framework itself, not read
     * into memory first) and it's the same mechanism `adb logcat`
     * callers already reach for. */
    /** Real gap found via a direct user question ("does the debug log include the build
     * # and etc?") -- it didn't. Build/commit tracking now spans two composite-built
     * repos (see the apk-delivery-location memory's build-number scheme), so a pasted
     * log with no header gives no way to tell which of several near-identical test
     * builds it actually came from. `BuildConfig.GIT_SHA`/`RNSBLE_GIT_SHA` are populated
     * at Gradle configuration time from each repo's own real `git rev-parse --short
     * HEAD` (see app/build.gradle.kts). */
    private fun logHeader(): String {
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        return "NomadPortal-Android ${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE}) " +
            "-- nomadportal-android@${BuildConfig.GIT_SHA} rnsble@${BuildConfig.RNSBLE_GIT_SHA} " +
            "-- captured $timestamp\n" +
            "----------------------------------------------------------------\n"
    }

    fun captureLogText(): String? {
        return try {
            val pid = Process.myPid()
            val process = Runtime.getRuntime().exec(
                arrayOf(
                    "logcat", "-d", "-v", "threadtime", "--pid=$pid",
                    "View:S", "ViewRootImpl:S", "Choreographer:S", "HWUI:S",
                    "BLASTBufferQueue:S", "BLASTBufferQueue_Java:S",
                    "NativeCustomFrequencyManager:S", "InputTransport:S",
                    "InputMethodManager:S", "InputMethodManagerUtils:S",
                    "InputMethodManager_LC:S", "InsetsController:S",
                    "InsetsSourceConsumer:S", "ImeTracker:S",
                    "WindowOnBackDispatcher:S", "RemoteInputConnectionImpl:S",
                    "AssistStructure:S", "nativeloader:S", "ApplicationLoaders:S",
                    "ActivityThread:S", "DecorView:S", "DisplayManager:S",
                    "GraphicsEnvironment:S", "DesktopExperienceFlags:S",
                    "DesktopModeFlags:S", "IDS_TAG:S", "ashmem:S",
                    "WM-WrkMgrInitializer:S", "WM-PackageManagerHelper:S",
                    "WM-Schedulers:S", "WM-ForceStopRunnable:S",
                    "AccessibilityNodeInfoDumper:S", "*:D",
                ),
            )
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            logHeader() + output
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
