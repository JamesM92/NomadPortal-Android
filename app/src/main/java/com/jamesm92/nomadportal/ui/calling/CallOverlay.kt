package com.jamesm92.nomadportal.ui.calling

import android.Manifest
import android.media.AudioManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.calling.CallState
import com.jamesm92.nomadportal.data.calling.CallStatusValue
import com.jamesm92.nomadportal.permissions.hasRecordAudioPermission
import com.jamesm92.nomadportal.ui.theme.NomadAccent2
import com.jamesm92.nomadportal.ui.theme.NomadError
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import kotlinx.coroutines.delay

/**
 * A full-screen overlay shown whenever [state] isn't idle, on top of
 * whatever screen the user's currently on — matching a real phone's own
 * "an incoming call interrupts what you were doing" behavior, per this
 * feature's own Phase 0 framing ("the icon will eventually become the
 * way to start a phone call with them"). Deliberately terse for this
 * phase — no ringtone, no per-call avatar, just enough to verify a real
 * call actually rings/connects/hangs up correctly against a real peer
 * (Phase 1a's own purpose) plus real two-way audio once ESTABLISHED
 * (Phase 1b — see call_manager.py's own doc comment for both).
 */
@Composable
fun CallOverlay(
    state: CallState,
    onAnswer: () -> Unit,
    onHangUp: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.status.isActive) return

    // Real live report: "the volume between the 2 phones was
    // different, one was barely audible." Root cause isn't the audio
    // pipeline itself (CallAudioEngine's own AudioTrack already uses
    // the correct AudioAttributes.USAGE_VOICE_COMMUNICATION, and
    // there's no software gain/scaling anywhere in it) — it's that
    // nothing in this app ever pointed the hardware volume buttons at
    // the actual call-audio stream. Without this, Android leaves them
    // controlling whatever stream was last active (commonly media, or
    // nothing call-related at all) — so a user hearing a quiet call
    // has no way to fix it in the moment, and if the two phones'
    // STREAM_VOICE_CALL levels simply started at different points
    // (an ordinary per-device/OEM default), that difference has no way
    // to ever get corrected. Activity.setVolumeControlStream (the real
    // API — accessed via the volumeControlStream property here) is
    // exactly what real phone-dialer apps use for this; reset back to
    // the platform default on dispose (call ends/overlay leaves
    // composition) so this doesn't leak into controlling call volume
    // for the rest of the app once the call's over.
    //
    // LocalActivity, not `LocalContext.current as? Activity` — a real
    // lint catch (ContextCastToActivity, androidx.activity.compose):
    // the composition-local Context isn't guaranteed to *be* an
    // Activity (it can be wrapped/decorated), where LocalActivity
    // resolves the real hosting Activity correctly regardless — same
    // fix RnshTerminalScreen.kt already applies for its own
    // FragmentActivity lookup.
    val activity = LocalActivity.current
    DisposableEffect(Unit) {
        activity?.volumeControlStream = AudioManager.STREAM_VOICE_CALL
        onDispose {
            activity?.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
        }
    }

    // Terminal states (ended/busy/rejected/failed) show a brief message
    // then dismiss themselves — same "toast, not a modal you have to
    // tap through" convention a real phone's own call-ended UI follows.
    LaunchedEffect(state.status) {
        if (state.status.isTerminal) {
            delay(2500)
            onDismiss()
        }
    }

    // RECORD_AUDIO, requested once per call becoming active (this
    // LaunchedEffect(Unit) re-runs every time this composable re-enters
    // composition from the "if (!state.status.isActive) return" above --
    // i.e. once per new call, not once per recomposition). Same "never
    // block, always degrade gracefully on denial" rule this app already
    // applies to Bluetooth permissions (SettingsScreen.kt) -- a denial
    // just means CallAudioEngine's capture thread skips itself for this
    // call (receive-only audio), never a blocked/failed call.
    val context = LocalContext.current
    var recordAudioGranted by remember { mutableStateOf(hasRecordAudioPermission(context)) }
    val recordAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> recordAudioGranted = granted }
    LaunchedEffect(Unit) {
        if (!recordAudioGranted) {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = state.remoteName ?: state.remoteIdentityHash?.take(16) ?: "Unknown",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusLabel(state),
                style = MaterialTheme.typography.bodyLarge,
                color = NomadTextDim,
            )

            Spacer(modifier = Modifier.height(24.dp))

            when (state.status) {
                CallStatusValue.RINGING_INCOMING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                        CallActionButton(
                            icon = Icons.Filled.CallEnd,
                            color = NomadError,
                            contentDescription = "Decline",
                            onClick = onHangUp,
                        )
                        CallActionButton(
                            icon = Icons.Filled.Call,
                            color = NomadAccent2,
                            contentDescription = "Answer",
                            onClick = onAnswer,
                        )
                    }
                }
                CallStatusValue.CALLING, CallStatusValue.RINGING_OUTGOING,
                CallStatusValue.CONNECTING, CallStatusValue.ESTABLISHED -> {
                    CallActionButton(
                        icon = Icons.Filled.CallEnd,
                        color = NomadError,
                        contentDescription = "Hang up",
                        onClick = onHangUp,
                    )
                }
                else -> {
                    // Terminal states: no buttons, just the status label
                    // above and the auto-dismiss timer started already.
                }
            }
        }
    }
}

@Composable
private fun CallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(color),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(28.dp),
        )
    }
}

private fun statusLabel(state: CallState): String = when (state.status) {
    CallStatusValue.CALLING -> "Calling…"
    CallStatusValue.RINGING_OUTGOING -> "Ringing…"
    CallStatusValue.RINGING_INCOMING -> "Incoming call"
    CallStatusValue.CONNECTING -> "Connecting…"
    CallStatusValue.ESTABLISHED -> "Connected"
    CallStatusValue.ENDED -> "Call ended"
    CallStatusValue.BUSY -> "Busy"
    CallStatusValue.REJECTED -> "Call rejected"
    CallStatusValue.FAILED -> state.endedReason ?: "Call failed"
    CallStatusValue.IDLE -> ""
}
