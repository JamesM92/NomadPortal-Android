package com.jamesm92.nomadportal.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Real result of a [DeviceCredentialGate.authenticate] attempt.
 */
sealed class DeviceCredentialResult {
    /** The user proved they hold the device's own lock (biometric or PIN/pattern/password). */
    data object Authenticated : DeviceCredentialResult()

    /** The device has no screen lock configured at all — there's nothing to gate with. */
    data class Unavailable(val reason: String) : DeviceCredentialResult()

    /** The user backed out (system back button, or dismissed the prompt) — not an error to surface loudly. */
    data object Cancelled : DeviceCredentialResult()

    /** A real failure other than user cancellation (lockout, hardware error, etc.). */
    data class Failed(val reason: String) : DeviceCredentialResult()
}

/**
 * rnsh's real per-connect security gate — see
 * [com.jamesm92.nomadportal.ui.terminal.RnshTerminalScreen]'s own doc
 * comment for the client-only scope decision (this device never *accepts*
 * shell sessions), but once connected, whoever is holding this phone has a
 * real interactive shell on whatever machine is on the other end. An
 * unlocked (or lock-screen-bypassed, e.g. left on a table mid-session)
 * phone would otherwise hand that over to anyone who picks it up, with zero
 * extra friction beyond what it takes to open the app itself.
 *
 * **Reuses the phone's own already-configured lock, via [BiometricPrompt]
 * with [BiometricManager.Authenticators.DEVICE_CREDENTIAL] as an allowed
 * fallback** — deliberately not a custom in-app password. A second,
 * app-specific secret is one more thing to remember/leak/reset, and adds no
 * real security a device that's already unlocked by the OS's own credential
 * doesn't already provide; reusing the OS mechanism means whatever the user
 * already trusts (fingerprint, face, PIN, pattern, password) gates this too,
 * with no separate enrollment step this app has to build or store.
 *
 * **Every real connect attempt re-authenticates** — [DeviceCredentialGate]
 * has no "remember this session" concept by design. rnsh sessions are
 * typically short (a command or two against a Pi/server), and the whole
 * point is that picking up an unattended-but-unlocked phone shouldn't be
 * enough; a one-time unlock that then stays armed indefinitely would
 * reintroduce exactly that gap.
 *
 * Requires a real [FragmentActivity] host — see [com.jamesm92.nomadportal
 * .MainActivity]'s own doc comment for why this app's Activity base class
 * was widened for this.
 */
object DeviceCredentialGate {
    private const val ALLOWED_AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
    ): DeviceCredentialResult {
        val biometricManager = BiometricManager.from(activity)
        val canAuthenticate = biometricManager.canAuthenticate(ALLOWED_AUTHENTICATORS)
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            // Covers the real "no lock screen configured at all" case
            // (BIOMETRIC_ERROR_NONE_ENROLLED with DEVICE_CREDENTIAL in the
            // allowed set means literally no PIN/pattern/password/biometric
            // exists on this device) along with the other BiometricManager
            // failure codes (no hardware, hardware unavailable, security
            // update required) — all of which mean the same thing here:
            // there is nothing this gate can check against, so refuse
            // rather than silently letting the connect through ungated.
            return DeviceCredentialResult.Unavailable(
                "This device has no screen lock set up (PIN, pattern, password, or biometric). " +
                    "Set one up in your device's Settings to use rnsh — it's what keeps remote " +
                    "shell access from being available to anyone who picks up this phone.",
            )
        }

        return suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(DeviceCredentialResult.Authenticated))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!continuation.isActive) return
                    val result = when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED,
                        -> DeviceCredentialResult.Cancelled
                        else -> DeviceCredentialResult.Failed(errString.toString())
                    }
                    continuation.resumeWith(Result.success(result))
                }

                override fun onAuthenticationFailed() {
                    // A single wrong attempt (bad fingerprint read, wrong
                    // PIN digit before submit, etc.) — BiometricPrompt's
                    // own UI stays up and lets the user retry, so this
                    // callback firing is not terminal; don't resolve the
                    // coroutine here.
                }
            }

            val prompt = BiometricPrompt(activity, executor, callback)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { if (subtitle != null) setSubtitle(subtitle) }
                // No setNegativeButtonText(): BiometricPrompt rejects
                // combining a negative button with DEVICE_CREDENTIAL in
                // the allowed authenticators (the credential fallback IS
                // the way out — confirmed via a real
                // IllegalArgumentException before this comment existed).
                .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
                .build()

            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            prompt.authenticate(promptInfo)
        }
    }
}
