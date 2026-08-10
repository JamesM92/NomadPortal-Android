package com.jamesm92.nomadportal.ui.settings

import android.content.ClipData
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jamesm92.nomadportal.connectivity.InterfaceController
import com.jamesm92.nomadportal.connectivity.TcpConnection
import com.jamesm92.nomadportal.connectivity.TcpConnectionsRepository
import com.jamesm92.nomadportal.data.SettingsRepository
import com.jamesm92.nomadportal.data.messaging.AnnounceStatus
import com.jamesm92.nomadportal.data.messaging.InterfaceAnnounceConfig
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.panicwipe.PanicWipe
import com.jamesm92.nomadportal.permissions.BLUETOOTH_PERMISSIONS
import com.jamesm92.nomadportal.permissions.hasBluetoothPermissions
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.CompactTextField
import com.jamesm92.nomadportal.ui.components.MinutesField
import com.jamesm92.nomadportal.ui.components.PanicWipeLogo
import com.jamesm92.nomadportal.ui.components.VerticalScrollIndicator
import com.jamesm92.nomadportal.ui.theme.NomadMono
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import com.jamesm92.nomadportal.ui.theme.NomadWarn
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
 *
 * Manual identity/hosted-node announcing deliberately does NOT live here
 * — per explicit direction, that's Home's job now (the "identity
 * management" surface). This screen only owns *configuration*
 * (thresholds, connection lists, on/off), never a "do it now" action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    interfaceController: InterfaceController,
    settingsRepository: SettingsRepository,
    messagingRepository: MessagingRepository,
    tcpConnectionsRepository: TcpConnectionsRepository,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    val textScale by settingsRepository.textScale.collectAsState(initial = SettingsRepository.DEFAULT_TEXT_SCALE)
    val announceStatus by messagingRepository.announceStatus().collectAsState(initial = null)
    val tcpConnections by tcpConnectionsRepository.connections().collectAsState(initial = emptyList())

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

    // Main = connectivity/hosting toggles + kill switch, the master
    // auto-announce toggle, Appearance, and Permissions. Each announce-
    // tracked interface (TCP/Bluetooth/RNode/LAN) gets its own dedicated
    // tab carrying a *duplicate* of its Main-tab toggle (per explicit
    // request — flip it from either place, same underlying state) plus
    // that interface's own Message/Auto policy; TCP's tab additionally
    // carries the full connection list (add/remove/enable/disable —
    // replaces the old single-hardcoded-hub design). There's no "Node"
    // tab here (removed per explicit direction: "everything related to
    // it is already on the home page... the different interfaces need
    // room to manage their different settings, the node doesnt really
    // need that") — Home's own HostedNodeSection already carries the
    // on/off toggle, rename, manual "Announce now", and the auto-announce
    // interval field; unlike the four interfaces above, the hosted node
    // had nothing here that wasn't already a plain duplicate of Home.
    var selectedTab by remember { mutableIntStateOf(0) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val tabLabels = listOf("Main", "TCP", "Bluetooth", "RNode", "LAN")

    Scaffold(
        topBar = {
            Column {
                AdaptiveTopAppBar(
                    title = { Text("Settings") },
                    // No back arrow — Settings is now a real bottom-nav
                    // tab (NomadNavHost.kt), same as Home/Messages/Nodes;
                    // switching tabs is the way back.
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
                                // 6 tabs sharing one row leaves little
                                // width each — a deliberate, scoped
                                // exception below the type scale, not a
                                // reintroduction of the bodyLarge-fraction
                                // anti-pattern the rest of this app's
                                // typography was migrated off of. Real
                                // on-device check: labelSmall (11sp, M3's
                                // own smallest defined role) still
                                // truncates "Bluetooth" — no real semantic
                                // role fits here, so this stays a named,
                                // commented exception (matching the
                                // android-compose-app-design skill's own
                                // allowance for information-dense UI, e.g.
                                // a compact table) rather than force a
                                // role that visibly breaks. 9.6sp is this
                                // row's original tuned value (confirmed via
                                // git — unchanged by this migration).
                                // maxLines=1/softWrap=false's actual job
                                // was always "truncate rather than wrap to
                                // 2 lines and blow out the row's height,"
                                // not "guarantee zero truncation" — even
                                // pre-migration, "Bluetooth" ellipsizing
                                // was the accepted worst case, confirmed
                                // directly against the pre-Phase-T code,
                                // not assumed.
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.6.sp,
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Same fix as HomeScreen's own tap-outside-to-commit --
                // real on-device report: the TCP table / MinutesField
                // cells here have no other way to lose focus besides the
                // keyboard's own Done action, since nothing was otherwise
                // claiming focus away from them on an outside tap.
                .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
                .padding(innerPadding),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
            when (selectedTab) {
                0 -> {
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
                            label = "Host a NomadNet site",
                            checked = nodeHostingEnabled,
                            onCheckedChange = { scope.launch { interfaceController.setNodeHostingEnabled(it) } },
                        )
                    }

                    item { HorizontalDivider() }
                    item { SectionHeader("Announce") }
                    announceStatus?.let { status ->
                        item {
                            ToggleRow(
                                label = "Auto-announce",
                                checked = status.autoAnnounceMasterEnabled,
                                onCheckedChange = {
                                    scope.launch { messagingRepository.setAutoAnnounceMaster(it) }
                                },
                            )
                        }
                    }

                    item { HorizontalDivider() }
                    item { SectionHeader("Addresses") }
                    announceStatus?.let { status ->
                        item {
                            AddressRow(label = "LXMF address", value = status.lxmfAddress)
                        }
                        item {
                            AddressRow(label = "Identity hash", value = status.identityHash)
                        }
                        item {
                            AddressRow(
                                label = "Site address",
                                value = status.hostedNodeHash,
                                placeholder = "Not currently hosting a site",
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
                    // No "Permissions" section here anymore — per explicit
                    // direction, that static blurb didn't need to persist
                    // in a settings menu at all. Its content now lives in
                    // the seeded index.mu (_DEFAULT_INDEX in
                    // site_server.py's ">>Permissions" section) instead;
                    // a future "welcome new user" screen (deferred until
                    // closer to release) is the other place this was
                    // considered for.
                }
                1 -> {
                    // TCP: duplicate master toggle, the full connection
                    // list (add/remove/enable per connection), then this
                    // interface's own Message/Auto announce policy.
                    item {
                        ToggleRow(
                            label = "TCP",
                            checked = tcpEnabled,
                            onCheckedChange = { scope.launch { interfaceController.setTcpEnabled(it) } },
                        )
                    }
                    item { HorizontalDivider() }
                    item { SectionHeader("Connections") }
                    item { TcpConnectionsTableHeader() }
                    items(tcpConnections, key = { it.id }) { connection ->
                        TcpConnectionEditRow(
                            connection = connection,
                            // Only actually "down" if TCP itself is on and
                            // this connection is individually enabled too --
                            // an intentionally-off connection isn't a
                            // problem to flag, same reasoning as
                            // InterfaceController.hasDownTcpConnection.
                            isDown = tcpEnabled && connection.enabled && !connection.online,
                            onUpdate = { name, host, port ->
                                scope.launch {
                                    tcpConnectionsRepository.updateConnection(connection.id, name, host, port)
                                }
                            },
                            onToggle = {
                                scope.launch { tcpConnectionsRepository.setConnectionEnabled(connection.id, it) }
                            },
                            onRemove = {
                                scope.launch { tcpConnectionsRepository.removeConnection(connection.id) }
                            },
                        )
                    }
                    item {
                        TcpConnectionAddRow(
                            onAdd = { name, host, port ->
                                scope.launch { tcpConnectionsRepository.addConnection(name, host, port) }
                            },
                        )
                    }
                    announceStatus?.interfaces?.get(AnnounceStatus.INTERFACE_TCP)?.let { config ->
                        item { HorizontalDivider() }
                        item {
                            InterfaceAnnounceTab(
                                config = config,
                                onAnnounceMaxChange = {
                                    scope.launch {
                                        messagingRepository.setAnnounceMax(AnnounceStatus.INTERFACE_TCP, it)
                                    }
                                },
                                onAutoAnnounceIntervalChange = {
                                    scope.launch {
                                        messagingRepository.setAutoAnnounceInterval(AnnounceStatus.INTERFACE_TCP, it)
                                    }
                                },
                            )
                        }
                    }
                }
                2 -> {
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
                    announceStatus?.interfaces?.get(AnnounceStatus.INTERFACE_BLUETOOTH)?.let { config ->
                        item { HorizontalDivider() }
                        item {
                            InterfaceAnnounceTab(
                                config = config,
                                onAnnounceMaxChange = {
                                    scope.launch {
                                        messagingRepository.setAnnounceMax(AnnounceStatus.INTERFACE_BLUETOOTH, it)
                                    }
                                },
                                onAutoAnnounceIntervalChange = {
                                    scope.launch {
                                        messagingRepository.setAutoAnnounceInterval(
                                            AnnounceStatus.INTERFACE_BLUETOOTH, it,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
                3 -> {
                    item {
                        ToggleRow(
                            label = "RNode",
                            checked = rNodeEnabled,
                            onCheckedChange = { scope.launch { interfaceController.setRNodeEnabled(it) } },
                        )
                    }
                    announceStatus?.interfaces?.get(AnnounceStatus.INTERFACE_RNODE)?.let { config ->
                        item { HorizontalDivider() }
                        item {
                            InterfaceAnnounceTab(
                                config = config,
                                onAnnounceMaxChange = {
                                    scope.launch {
                                        messagingRepository.setAnnounceMax(AnnounceStatus.INTERFACE_RNODE, it)
                                    }
                                },
                                onAutoAnnounceIntervalChange = {
                                    scope.launch {
                                        messagingRepository.setAutoAnnounceInterval(AnnounceStatus.INTERFACE_RNODE, it)
                                    }
                                },
                            )
                        }
                    }
                }
                4 -> {
                    item {
                        ToggleRow(
                            label = "Local network discovery",
                            checked = wifiDiscoveryEnabled,
                            onCheckedChange = { scope.launch { interfaceController.setWifiDiscoveryEnabled(it) } },
                        )
                    }
                    announceStatus?.interfaces?.get(AnnounceStatus.INTERFACE_WIFI_DISCOVERY)?.let { config ->
                        item { HorizontalDivider() }
                        item {
                            InterfaceAnnounceTab(
                                config = config,
                                onAnnounceMaxChange = {
                                    scope.launch {
                                        messagingRepository.setAnnounceMax(AnnounceStatus.INTERFACE_WIFI_DISCOVERY, it)
                                    }
                                },
                                onAutoAnnounceIntervalChange = {
                                    scope.launch {
                                        messagingRepository.setAutoAnnounceInterval(
                                            AnnounceStatus.INTERFACE_WIFI_DISCOVERY, it,
                                        )
                                    }
                                },
                            )
                        }
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
                style = MaterialTheme.typography.bodyMedium,
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

/** One of this device's own addresses/hashes — full, untruncated
 * (unlike Home's identity summary, which deliberately shows only a
 * truncated LXMF address), with a one-tap copy. [value] null renders
 * [placeholder] instead in a dimmed/italic-equivalent style, rather
 * than an empty row — most relevantly "Node address" before any real
 * SiteServer exists to host one (see [AnnounceStatus.hostedNodeHash]'s
 * own doc comment). */
@Composable
private fun AddressRow(label: String, value: String?, placeholder: String = "Not available yet") {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = NomadTextDim,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value ?: placeholder,
                fontFamily = if (value != null) NomadMono else null,
                style = MaterialTheme.typography.bodyMedium,
                color = if (value != null) MaterialTheme.colorScheme.onSurface else NomadTextDim,
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                // Plain default IconButton sizing here (no compact-field
                // treatment) — per explicit direction, Main tab's own
                // sizing stays as it already was; only the sub-tabs got
                // shrunk.
                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value)))
                        }
                    },
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy $label")
                }
            }
        }
    }
}

/** One configured TCP connection: name/host:port, its own enable
 * switch, and a delete button — a compact editable table, per explicit
 * request ("tcp connections should be more like a editable table" /
 * "the tcp setup needs to be in 1 or 2 lines of fields"), replacing the
 * original remove-and-re-add-only row + separate add form. Each cell
 * commits on focus loss (same convention as [MinutesField]); [Switch]/
 * [IconButton] are shrunk via the shared zeroed
 * [LocalMinimumInteractiveComponentSize] wrapper around the whole table
 * (see [TcpConnectionsTable]... actually applied at the call site in
 * [SettingsScreen] around every `item` in this section) so a 3-field +
 * switch + delete row actually fits on one line at normal device widths.
 */
@Composable
private fun TcpConnectionsTableHeader() {
    val labelStyle = MaterialTheme.typography.labelSmall
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Name", style = labelStyle, color = NomadTextDim, modifier = Modifier.weight(0.8f))
        Text("Host", style = labelStyle, color = NomadTextDim, modifier = Modifier.weight(1.1f))
        Text("Port", style = labelStyle, color = NomadTextDim, modifier = Modifier.width(52.dp), textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.width(76.dp))
    }
}

@Composable
private fun TcpConnectionEditRow(
    connection: TcpConnection,
    /** True when this connection is enabled, TCP itself is on, and the
     * real RNS interface isn't actually connected right now — a
     * transient-but-real state (RNS itself keeps retrying underneath;
     * see orchestrator.py's own TCPClientInterface reconnect-loop
     * notes), surfaced here rather than silently retried with a
     * different server — no automatic re-shuffling after initial setup,
     * per explicit direction. */
    isDown: Boolean,
    onUpdate: (name: String, host: String, port: Int) -> Unit,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    // Keyed on connection.id only (not the whole connection) so this
    // local draft survives a poll tick without fighting the user's own
    // in-progress edit or losing focus — the id is what stays stable
    // across edits, per TcpConnection's own doc comment.
    var name by remember(connection.id) { mutableStateOf(connection.name) }
    var host by remember(connection.id) { mutableStateOf(connection.host) }
    var portText by remember(connection.id) { mutableStateOf(connection.port.toString()) }

    fun commit() {
        val port = portText.toIntOrNull()
        if (host.isNotBlank() && port != null && port in 1..65535) {
            onUpdate(name.trim(), host.trim(), port)
        } else {
            // Invalid edit on blur — revert to last-known-good rather
            // than silently committing garbage or leaving the field
            // stuck showing something that was never saved.
            name = connection.name
            host = connection.host
            portText = connection.port.toString()
        }
    }

    // Zeroed so Switch/IconButton don't each reserve a 48dp accessibility
    // touch target — same fix as SearchField's own icon slots (see the
    // android-compose-compact-fields skill); without it these two alone
    // would blow a 3-field row well past one line on most device widths.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Zero width cost, unlike adding a new column would be —
                // this table is already at "1 or 2 lines of fields" per
                // explicit request, no room to spare for a dedicated
                // status column. The delete button's own badge (below)
                // is the more explicit signal; this is just reinforcement.
                .background(if (isDown) NomadWarn.copy(alpha = 0.12f) else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CompactTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.weight(0.8f).onFocusChanged { if (!it.isFocused) commit() },
            )
            CompactTextField(
                value = host,
                onValueChange = { host = it },
                modifier = Modifier.weight(1.1f).onFocusChanged { if (!it.isFocused) commit() },
            )
            CompactTextField(
                value = portText,
                onValueChange = { new -> if (new.length <= 5 && new.all { it.isDigit() }) portText = new },
                modifier = Modifier.width(52.dp).onFocusChanged { if (!it.isFocused) commit() },
                textAlign = TextAlign.Center,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            // Checkbox instead of Switch, per explicit request — also
            // meaningfully more compact in a dense table row (no fixed
            // ~52dp track the way Switch has).
            Checkbox(
                checked = connection.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.size(24.dp),
            )
            BadgedBox(
                badge = {
                    if (isDown) {
                        Badge(containerColor = NomadWarn) {
                            // Tiny "!" rather than an empty dot -- still
                            // fits a 24dp row, and reads as "problem" at
                            // a glance without needing a legend.
                            Text("!")
                        }
                    }
                },
            ) {
                IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = if (isDown) {
                            "Remove ${connection.name} (not connected)"
                        } else {
                            "Remove ${connection.name}"
                        },
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** Trailing row of the same table — same 3 compact fields, an Add
 * button in place of the switch+delete pair. Name is optional (falls
 * back to "host:port" server-side — see orchestrator.py's
 * add_tcp_connection); host and a valid port [1, 65535] are required
 * before Add does anything. */
@Composable
private fun TcpConnectionAddRow(onAdd: (name: String, host: String, port: Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("4965") }

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CompactTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "Name",
                modifier = Modifier.weight(0.8f),
            )
            CompactTextField(
                value = host,
                onValueChange = { host = it },
                placeholder = "Host",
                modifier = Modifier.weight(1.1f),
            )
            CompactTextField(
                value = portText,
                onValueChange = { new -> if (new.length <= 5 && new.all { it.isDigit() }) portText = new },
                modifier = Modifier.width(52.dp),
                textAlign = TextAlign.Center,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            IconButton(
                onClick = {
                    val port = portText.toIntOrNull()
                    if (host.isNotBlank() && port != null && port in 1..65535) {
                        onAdd(name.trim(), host.trim(), port)
                        name = ""
                        host = ""
                        portText = "4965"
                    }
                },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add connection", modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(24.dp))
        }
    }
}

/**
 * Per-interface announce policy: two number-of-minutes fields (message
 * max / auto-announce interval) — see [InterfaceAnnounceConfig]'s own
 * doc comment for what each controls. **0 in the auto field disables
 * auto-announce for that interface — there's no separate enabled
 * switch**, matching [InterfaceAnnounceConfig.autoAnnounceEnabled]'s own
 * derivation.
 */
@Composable
private fun InterfaceAnnounceTab(
    config: InterfaceAnnounceConfig,
    onAnnounceMaxChange: (seconds: Int) -> Unit,
    onAutoAnnounceIntervalChange: (seconds: Int) -> Unit,
) {
    // Smaller than Main's own text — per explicit feedback that the
    // settings *sub-tabs* specifically (not Main) run oversized; this
    // composable is sub-tab-only (Main never renders per-interface
    // fields), so it's safe to shrink further without touching Main's
    // own text sizes. labelMedium/labelSmall (not a hand-derived
    // fraction of bodyLarge) — two distinct label-tier roles preserves
    // the original "label bigger than hint" relationship exactly.
    val labelStyle = MaterialTheme.typography.labelMedium
    val hintStyle = MaterialTheme.typography.labelSmall

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
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
            modifier = Modifier.width(64.dp).padding(top = 4.dp),
        )

        Spacer(modifier = Modifier.height(14.dp))

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
            modifier = Modifier.width(64.dp).padding(top = 4.dp),
        )
    }
}


