package com.jamesm92.nomadportal.ui.settings

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.connectivity.InterfaceController
import com.jamesm92.nomadportal.data.SettingsRepository
import com.jamesm92.nomadportal.data.messaging.AnnounceStatus
import com.jamesm92.nomadportal.data.messaging.InterfaceAnnounceConfig
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.panicwipe.PanicWipe
import com.jamesm92.nomadportal.permissions.BLUETOOTH_PERMISSIONS
import com.jamesm92.nomadportal.permissions.hasBluetoothPermissions
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.PanicWipeLogo
import com.jamesm92.nomadportal.ui.components.VerticalScrollIndicator
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Connectivity + hosting + permissions section, per
 * nomadportal_android_handoff.md's "Main menu / connectivity & privacy
 * controls". Every toggle here is wired to the real [InterfaceController]
 * interface — flipping one actually calls through to it. As of
 * [com.jamesm92.nomadportal.connectivity.RealInterfaceController], TCP
 * and Wi-Fi discovery actually control live RNS interfaces; RNode/
 * Bluetooth-mesh/hosting are still persisted-intent-only pending their
 * own separate prerequisites (see that class's doc comment for why).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    interfaceController: InterfaceController,
    settingsRepository: SettingsRepository,
    messagingRepository: MessagingRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val textScale by settingsRepository.textScale.collectAsState(initial = SettingsRepository.DEFAULT_TEXT_SCALE)
    val announceStatus by messagingRepository.announceStatus().collectAsState(initial = null)

    val tcpEnabled by interfaceController.tcpEnabled.collectAsState()
    val bluetoothMeshEnabled by interfaceController.bluetoothMeshEnabled.collectAsState()
    val rNodeEnabled by interfaceController.rNodeEnabled.collectAsState()
    val wifiDiscoveryEnabled by interfaceController.wifiDiscoveryEnabled.collectAsState()
    val nodeHostingEnabled by interfaceController.nodeHostingEnabled.collectAsState()

    var bluetoothGranted by remember { mutableStateOf(hasBluetoothPermissions(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        bluetoothGranted = results.values.all { it }
        // Denial is a fully-supported end state, not an error: leave the
        // toggle off and keep going. Nothing else in this screen (or the
        // app) is allowed to hard-require this permission.
        if (bluetoothGranted) {
            scope.launch { interfaceController.setBluetoothMeshEnabled(true) }
        }
    }

    // Main = everything that isn't a per-interface announce policy:
    // connectivity/hosting toggles + kill switch, Appearance, and
    // Permissions all live together on Main (moved back per explicit
    // follow-up — Appearance/Permissions had briefly been their own
    // tabs, that was wrong). Each announce-tracked interface gets its
    // own dedicated tab instead of one shared table, so its Message/
    // Auto fields aren't competing for width with three other rows.
    var selectedTab by remember { mutableIntStateOf(0) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val tabLabels = listOf("Main", "TCP", "Bluetooth", "RNode", "LAN")

    Scaffold(
        topBar = {
            Column {
                AdaptiveTopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        PanicWipeLogo(
                            modifier = Modifier.padding(end = 8.dp),
                            onTripleTap = {
                                scope.launch {
                                    PanicWipe.perform(context)
                                    PanicWipe.restartApp(context)
                                }
                            },
                        )
                    },
                )
                SecondaryTabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.height(if (isLandscape) 28.dp else 36.dp),
                ) {
                    tabLabels.forEachIndexed { index, label ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                // 5 tabs sharing one row leaves little
                                // width each — smaller fontSize plus an
                                // explicit maxLines=1/no-wrap keeps every
                                // label ("Bluetooth" is the longest) on
                                // one line instead of wrapping to two and
                                // blowing out the tab row's height.
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.65f,
                                        fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                    ),
                                    color = if (selectedTab == index) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        NomadTextDim
                                    },
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        val listState = rememberLazyListState()
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
            if (selectedTab == 0) {
                item {
                    SectionHeaderWithKillSwitch(
                        title = "Connectivity",
                        onKill = {
                            scope.launch {
                                // Only the four connectivity interfaces, not
                                // node hosting — hosting lives under its own
                                // "Hosting" section below and isn't a
                                // communication method itself (see
                                // setNodeHostingEnabled's own doc comment:
                                // it's independent of which interfaces are
                                // up). A user reaching for a kill switch wants
                                // this device to stop talking, not to also
                                // silently stop answering requests it was
                                // already committed to serving.
                                interfaceController.setTcpEnabled(false)
                                interfaceController.setBluetoothMeshEnabled(false)
                                interfaceController.setRNodeEnabled(false)
                                interfaceController.setWifiDiscoveryEnabled(false)
                            }
                        },
                    )
                }
                item {
                    ToggleRow(
                        label = "TCP",
                        checked = tcpEnabled,
                        onCheckedChange = { scope.launch { interfaceController.setTcpEnabled(it) } },
                    )
                }
                item {
                    ToggleRow(
                        label = "Bluetooth mesh",
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
                item {
                    ToggleRow(
                        label = "RNode",
                        checked = rNodeEnabled,
                        onCheckedChange = { scope.launch { interfaceController.setRNodeEnabled(it) } },
                    )
                }
                item {
                    ToggleRow(
                        label = "Local network discovery",
                        checked = wifiDiscoveryEnabled,
                        onCheckedChange = { scope.launch { interfaceController.setWifiDiscoveryEnabled(it) } },
                    )
                }

                item { HorizontalDivider() }
                item { SectionHeader("Hosting") }
                item {
                    ToggleRow(
                        label = "Host a NomadNet node",
                        checked = nodeHostingEnabled,
                        onCheckedChange = { scope.launch { interfaceController.setNodeHostingEnabled(it) } },
                    )
                }

                // Identity/announce overview stays on Main — it's not
                // per-interface (an LXMF address, last-announce time,
                // and a manual trigger apply to the whole device), only
                // each interface's own Message/Auto policy moved out to
                // its own tab below.
                announceStatus?.let { status ->
                    item { HorizontalDivider() }
                    item { SectionHeader("Announce") }
                    item {
                        AnnounceOverview(
                            status = status,
                            onAnnounceNow = { scope.launch { messagingRepository.announceNow() } },
                        )
                    }
                }

                item { HorizontalDivider() }
                item { SectionHeader("Appearance") }
                item {
                    TextScaleRow(
                        scale = textScale,
                        onScaleChange = { scope.launch { settingsRepository.setTextScale(it) } },
                    )
                }

                item { HorizontalDivider() }
                item { SectionHeader("Permissions") }
                item {
                    Text(
                        text = "Every permission this app requests is optional. Denying any of them " +
                            "leaves the related feature off — the rest of the app keeps working. " +
                            "This app never requests location permission, under any circumstances.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                // Tabs 1-4: TCP/Bluetooth/RNode/LAN, each showing just
                // that one interface's own Message/Auto fields.
                val interfaceKey = when (selectedTab) {
                    1 -> AnnounceStatus.INTERFACE_TCP
                    2 -> AnnounceStatus.INTERFACE_BLUETOOTH
                    3 -> AnnounceStatus.INTERFACE_RNODE
                    else -> AnnounceStatus.INTERFACE_WIFI_DISCOVERY
                }
                val config = announceStatus?.interfaces?.get(interfaceKey)
                if (config != null) {
                    item {
                        InterfaceAnnounceTab(
                            config = config,
                            onAnnounceMaxChange = {
                                scope.launch { messagingRepository.setAnnounceMax(interfaceKey, it) }
                            },
                            onAutoAnnounceIntervalChange = {
                                scope.launch { messagingRepository.setAutoAnnounceInterval(interfaceKey, it) }
                            },
                        )
                    }
                }
            }
            }
            // Custom-drawn, same as BrowserScreen's page viewer — Main
            // in particular is long enough to benefit from the same
            // "how much more is there" cue.
            VerticalScrollIndicator(
                listState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

/**
 * Live-previews while dragging (local [sliderValue] drives the label and
 * the slider's own position immediately) but only persists via
 * [onScaleChange] on release ([Slider.onValueChangeFinished]) — writing
 * to DataStore on every intermediate drag tick would mean dozens of
 * writes per gesture for no benefit, since only the final value matters.
 */
@Composable
private fun TextScaleRow(scale: Float, onScaleChange: (Float) -> Unit) {
    var sliderValue by remember(scale) { mutableStateOf(scale) }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "Text size — ${(sliderValue * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onScaleChange(sliderValue) },
            valueRange = SettingsRepository.MIN_TEXT_SCALE..SettingsRepository.MAX_TEXT_SCALE,
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(16.dp),
    )
}

/**
 * [SectionHeader] plus a kill switch for the Connectivity section
 * specifically — a single tap to force every communication interface
 * (TCP, Bluetooth mesh, RNode, local network discovery) off at once,
 * without hunting down four separate switches individually. Doesn't
 * touch node hosting — see the call site's comment for why that's
 * deliberately excluded.
 */
@Composable
private fun SectionHeaderWithKillSwitch(title: String, onKill: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        TextButton(onClick = onKill) {
            Icon(
                imageVector = Icons.Filled.PowerSettingsNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                "Kill",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.85f,
                ),
            )
        }
    }
}

/**
 * Deliberately just a label + switch, no description line — what each
 * toggle actually does/requires (Bluetooth permission behavior, RNode/
 * Bluetooth-mesh independence, etc.) is still documented in this
 * screen's own call-site comments and the connectivity design docs, just
 * not rendered inline anymore.
 */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Identity summary + manual trigger for the Announce section — the LXMF
 * address, how long since the last announce, and (per explicit design:
 * "messages need to have a note that you wont be allowed to send if it
 * is disabled") a persistent warning banner whenever
 * [AnnounceStatus.sendBlocked] is true, so this is visible before the
 * user even opens a conversation and tries to send, not just as a
 * reactive error afterward.
 */
@Composable
private fun AnnounceOverview(status: AnnounceStatus, onAnnounceNow: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = status.lxmfAddress?.let { "LXMF: ${it.take(16)}…" } ?: "LXMF address not ready yet",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = status.lastAnnounceAtMillis?.let { "Last announced ${formatSince(it)} ago" }
                        ?: "Never announced yet",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.8f,
                    ),
                    color = NomadTextDim,
                )
            }
            TextButton(onClick = onAnnounceNow) { Text("Announce now") }
        }
        if (status.sendBlocked) {
            Text(
                text = status.sendBlockedReason ?: "Sending is currently blocked.",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.85f,
                ),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Per-interface announce policy as a plain table — one row per
 * interface (protocol | message max | auto interval), both durations
 * entered directly as a number of minutes rather than preset chips (an
 * earlier version of this UI) or a slider — per explicit request.
 * **0 in the "Auto" column disables auto-announce for that interface —
 * there's no separate enabled switch**, matching
 * [InterfaceAnnounceConfig.autoAnnounceEnabled]'s own derivation.
 */
@Composable
private fun InterfaceAnnounceTab(
    config: InterfaceAnnounceConfig,
    onAnnounceMaxChange: (seconds: Int) -> Unit,
    onAutoAnnounceIntervalChange: (seconds: Int) -> Unit,
) {
    val labelStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.85f)
    val hintStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.7f)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Message (minutes)", style = labelStyle)
        Text(
            "How stale the last announce can get before a send needs a fresh one first.",
            style = hintStyle,
            color = NomadTextDim,
        )
        MinutesField(
            seconds = config.announceMaxSeconds,
            allowZero = false,
            onCommit = onAnnounceMaxChange,
            modifier = Modifier.width(96.dp).padding(top = 4.dp),
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text("Auto-announce (minutes)", style = labelStyle)
        Text(
            "How often this device proactively re-announces on its own. 0 disables auto-announce for this connection.",
            style = hintStyle,
            color = NomadTextDim,
        )
        MinutesField(
            seconds = config.autoAnnounceIntervalSeconds,
            allowZero = true,
            onCommit = onAutoAnnounceIntervalChange,
            modifier = Modifier.width(96.dp).padding(top = 4.dp),
        )
    }
}

/**
 * A number-of-minutes cell, built on `BasicTextField` +
 * `OutlinedTextFieldDefaults.DecorationBox` rather than the convenience
 * `OutlinedTextField` — that convenience composable exposes no
 * `contentPadding`, so a compact table cell built on it clips text no
 * matter how the outer `Modifier` is tuned (see the
 * `android-compose-compact-fields` skill for the full writeup; same
 * root cause and fix as `BrowserScreen`'s address bar and
 * `SearchField`). Commits on focus loss or the keyboard's Done action,
 * clamped to [1, 1440] minutes — or snapped to 0 when [allowZero] and
 * the typed value is 0/blank/invalid.
 */
@Composable
private fun MinutesField(
    seconds: Int,
    allowZero: Boolean,
    onCommit: (seconds: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(seconds) { mutableStateOf((seconds / 60).toString()) }
    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    fun commit() {
        val minutes = text.toIntOrNull()
        val clampedMinutes = when {
            minutes == null -> seconds / 60
            minutes <= 0 -> if (allowZero) 0 else 1
            else -> minutes.coerceAtMost(24 * 60)
        }
        text = clampedMinutes.toString()
        onCommit(clampedMinutes * 60)
    }

    BasicTextField(
        value = text,
        onValueChange = { new -> if (new.length <= 5 && new.all { it.isDigit() }) text = new },
        modifier = modifier.onFocusChanged { if (!it.isFocused) commit() },
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.85f,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            commit()
            focusManager.clearFocus()
        }),
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = text,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interactionSource,
                    )
                },
            )
        },
    )
}

/** Caller (AnnounceOverview) appends " ago" itself — bare duration only. */
private fun formatSince(millis: Long): String {
    val diffSeconds = ((System.currentTimeMillis() - millis) / 1000).coerceAtLeast(0)
    return when {
        diffSeconds < 3600 -> "${diffSeconds / 60}m"
        diffSeconds < 86_400 -> "${diffSeconds / 3600}h"
        else -> "${diffSeconds / 86_400}d"
    }
}
