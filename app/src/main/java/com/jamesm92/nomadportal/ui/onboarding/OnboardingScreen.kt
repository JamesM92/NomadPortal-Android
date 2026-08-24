package com.jamesm92.nomadportal.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.connectivity.InterfaceController
import com.jamesm92.nomadportal.data.messaging.ContactIcon
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.data.messaging.materialIconFor
import com.jamesm92.nomadportal.permissions.BLUETOOTH_PERMISSIONS
import com.jamesm92.nomadportal.permissions.hasBluetoothPermissions
import com.jamesm92.nomadportal.ui.components.TentPortalMark
import com.jamesm92.nomadportal.ui.theme.NomadBg3
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val STEP_COUNT = 4

/**
 * First-run guided flow — shown once (see [Routes.ONBOARDING][com.jamesm92.nomadportal.nav]'s
 * own doc comment for the gating mechanism), always skippable, never a
 * hard gate — same "denial/skip is a fully-supported state" philosophy
 * this app already applies to permissions.
 *
 * The real gap this closes isn't setup burden (identity creation and
 * TCP connectivity are already fully automatic — see
 * `nomadportal-android-onboarding` memory) but *context*: nothing
 * previously explained the silently-auto-generated identity, surfaced
 * the app's own flagship bitchat-comparison feature (Bluetooth mesh,
 * off by default, previously discoverable only by finding it in
 * Settings), or primed the Bluetooth permission dialog before it just
 * appeared.
 *
 * One composable with an internal [HorizontalPager], not 4 separate nav
 * routes — this is one linear flow, matching how this app already
 * handles other multi-step UI (Settings' tab row, the icon-appearance
 * accordion) as internal state rather than nav-graph proliferation.
 */
@Composable
fun OnboardingScreen(
    messagingRepository: MessagingRepository,
    interfaceController: InterfaceController,
    onComplete: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { STEP_COUNT })
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                // Always visible, every step — skipping is a fully
                // supported outcome, not a "give up" path buried at the
                // end.
                TextButton(onClick = onComplete) { Text("Skip") }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                when (page) {
                    0 -> WelcomeStep()
                    1 -> IdentityStep(messagingRepository)
                    2 -> ConnectivityStep(interfaceController)
                    else -> SafetyStep()
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(STEP_COUNT) { i ->
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (i == pagerState.currentPage) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                ),
                        )
                    }
                }

                if (pagerState.currentPage < STEP_COUNT - 1) {
                    Button(onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }) { Text("Next") }
                } else {
                    Button(onClick = onComplete) { Text("Get Started") }
                }
            }
        }
    }
}

@Composable
private fun StepContainer(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
private fun WelcomeStep() {
    StepContainer {
        Text(
            "Welcome to NomadPortal",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "A mesh messenger that works without relying on the internet " +
                "or any company's servers — built for when it actually " +
                "matters, not just to experiment with.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = NomadTextDim,
        )
    }
}

@Composable
private fun IdentityStep(messagingRepository: MessagingRepository) {
    // Same source HomeScreen's own Identity section reads — this
    // identity already exists by the time onboarding shows (created
    // automatically, before any UI, see identity_store.py's own doc
    // comment); this step's whole job is explaining what already
    // silently happened, not creating anything new.
    val announceStatusFlow = remember(messagingRepository) { messagingRepository.announceStatus() }
    val announceStatus by announceStatusFlow.collectAsState(initial = null)
    StepContainer {
        Text("Your identity", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        ReadOnlyIdentityIcon(announceStatus?.iconAppearance)
        Spacer(Modifier.height(16.dp))
        Text(announceStatus?.displayName ?: "…", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "This name and icon are derived from your unique identity hash — " +
                "deterministic, not random each time, and yours alone. Change " +
                "either anytime from Settings.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = NomadTextDim,
        )
    }
}

/** Read-only rendering of this device's own identity icon — deliberately
 * not [HomeScreen][com.jamesm92.nomadportal.ui.home]'s own
 * `IdentityIconPreview` (private to that file, and carries an edit-pencil
 * badge + click target neither of which belongs in this read-only step —
 * see [OnboardingScreen]'s own doc comment on why renaming stays
 * deferred to Home, not duplicated here). */
@Composable
private fun ReadOnlyIdentityIcon(appearance: ContactIcon.Appearance?) {
    val vector = remember(appearance?.glyphName) { appearance?.glyphName?.let(::materialIconFor) }
    Box(
        modifier = Modifier.size(72.dp).clip(CircleShape).background(appearance?.backgroundColor ?: NomadBg3),
        contentAlignment = Alignment.Center,
    ) {
        if (vector != null && appearance != null) {
            Icon(
                imageVector = vector,
                contentDescription = null,
                tint = appearance.foregroundColor,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun ConnectivityStep(interfaceController: InterfaceController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bluetoothMeshEnabled by interfaceController.bluetoothMeshEnabled.collectAsState()
    var bluetoothGranted by remember { mutableStateOf(hasBluetoothPermissions(context)) }
    // Same permission-request shape SettingsScreen's own Bluetooth-mesh
    // toggle already uses — the point of surfacing this here is the
    // explanation *before* the OS dialog appears, not a different
    // mechanism.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        bluetoothGranted = results.values.all { it }
        if (bluetoothGranted) {
            scope.launch { interfaceController.setBluetoothMeshEnabled(true) }
        }
    }

    StepContainer {
        Text("Staying connected", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        Text(
            "TCP is already on — real-time mesh messaging over the internet, no setup needed.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Bluetooth mesh", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Message nearby devices directly — no internet or infrastructure required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NomadTextDim,
                )
            }
            Switch(
                checked = bluetoothMeshEnabled,
                onCheckedChange = { turningOn ->
                    if (turningOn && !bluetoothGranted) {
                        permissionLauncher.launch(BLUETOOTH_PERMISSIONS)
                    } else {
                        scope.launch { interfaceController.setBluetoothMeshEnabled(turningOn) }
                    }
                },
            )
        }
    }
}

@Composable
private fun SafetyStep() {
    StepContainer {
        Text("One more thing", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(28.dp))
        // A real, at-scale rendering of the exact icon shown in every
        // top bar (see PanicWipeLogo's own doc comment for the mark
        // itself) with a real arrow pointing at it — per explicit
        // direction, showing *which* on-screen element rather than
        // just describing "the logo" in prose, so there's no ambiguity
        // about what to actually tap. Deliberately not PanicWipeLogo
        // itself (its real onTripleTap would trigger a genuine wipe) —
        // same TentPortalMark, same circular tint, just non-interactive
        // and larger for visibility.
        Box(
            modifier = Modifier.size(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                TentPortalMark(markSize = 44.dp)
            }
            PointingArrow(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(56.dp)
                    .padding(bottom = 6.dp, start = 6.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "Triple-tap this icon — it's in the top bar on every real screen " +
                "— to instantly and irreversibly wipe your identity and data. " +
                "A real safety measure, not a hidden gesture to stumble into.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = NomadTextDim,
        )
    }
}

/** A real drawn arrow (not a rotated Material glyph — a straight
 * diagonal line with its own arrowhead gives more direct control over
 * exactly where the point lands) swooping in from the upper-right and
 * terminating right at whatever it's overlaid on, per [SafetyStep]'s own
 * doc comment on why this exists at all. */
@Composable
private fun PointingArrow(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = 5.dp.toPx()
        val start = Offset(size.width * 0.94f, size.height * 0.08f)
        val end = Offset(size.width * 0.12f, size.height * 0.92f)
        drawLine(color = color, start = start, end = end, strokeWidth = strokeWidth, cap = StrokeCap.Round)

        val angle = atan2(end.y - start.y, end.x - start.x)
        val headLen = 15.dp.toPx()
        val headAngle = Math.toRadians(28.0)
        val p1 = Offset(
            end.x - headLen * cos(angle - headAngle).toFloat(),
            end.y - headLen * sin(angle - headAngle).toFloat(),
        )
        val p2 = Offset(
            end.x - headLen * cos(angle + headAngle).toFloat(),
            end.y - headLen * sin(angle + headAngle).toFloat(),
        )
        drawLine(color = color, start = end, end = p1, strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color = color, start = end, end = p2, strokeWidth = strokeWidth, cap = StrokeCap.Round)
    }
}
