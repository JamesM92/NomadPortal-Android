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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
    onBack: () -> Unit,
) {
    val context = LocalContext.current
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
    // replaces the old single-hardcoded-hub design). Node is the hosted-
    // node's own duplicate toggle, same pattern, no deeper config yet
    // (SiteServer wiring doesn't exist — see InterfaceController's own
    // doc comment).
    var selectedTab by remember { mutableIntStateOf(0) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val tabLabels = listOf("Main", "TCP", "Bluetooth", "RNode", "LAN", "Node")

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
                                // 6 tabs sharing one row leaves little
                                // width each — smaller fontSize plus an
                                // explicit maxLines=1/no-wrap keeps every
                                // label ("Bluetooth" is the longest) on
                                // one line instead of wrapping to two and
                                // blowing out the tab row's height.
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.6f,
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
                            label = "Host a NomadNet node",
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
                    items(tcpConnections, key = { it.id }) { connection ->
                        TcpConnectionRow(
                            connection = connection,
                            onToggle = {
                                scope.launch { tcpConnectionsRepository.setConnectionEnabled(connection.id, it) }
                            },
                            onRemove = {
                                scope.launch { tcpConnectionsRepository.removeConnection(connection.id) }
                            },
                        )
                    }
                    item {
                        AddTcpConnectionForm(
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
                else -> {
                    // Node (hosted NomadNet node) — duplicate toggle
                    // only for now. Renaming/manual-announce for the
                    // hosted node live on Home instead (per explicit
                    // direction), and deeper hosting config needs real
                    // SiteServer wiring that doesn't exist yet.
                    item {
                        ToggleRow(
                            label = "Host a NomadNet node",
                            checked = nodeHostingEnabled,
                            onCheckedChange = { scope.launch { interfaceController.setNodeHostingEnabled(it) } },
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

/** One configured TCP connection: name/host:port, its own enable
 * switch, and a delete button. No edit affordance yet — remove and
 * re-add is the only way to change host/port for now. */
@Composable
private fun TcpConnectionRow(
    connection: TcpConnection,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = connection.name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.85f,
                ),
            )
            Text(
                text = "${connection.host}:${connection.port}",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.7f,
                ),
                color = NomadTextDim,
            )
        }
        Switch(checked = connection.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Remove ${connection.name}",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Inline add-connection form — plain [OutlinedTextField]s (not the
 * compact BasicTextField+DecorationBox construction used elsewhere in
 * this file) since nothing here is height-constrained the way a table
 * cell or a tab-row label is; the convenience composable's default
 * sizing is fine. Name is optional (falls back to "host:port" server-
 * side — see orchestrator.py's add_tcp_connection); host and a valid
 * port [1, 65535] are required before Add does anything. */
@Composable
private fun AddTcpConnectionForm(onAdd: (name: String, host: String, port: Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("4965") }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "Add connection",
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.85f),
            color = NomadTextDim,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text("Name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            placeholder = { Text("Host") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        OutlinedTextField(
            value = portText,
            onValueChange = { new -> if (new.length <= 5 && new.all { it.isDigit() }) portText = new },
            placeholder = { Text("Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        TextButton(
            onClick = {
                val port = portText.toIntOrNull()
                if (host.isNotBlank() && port != null && port in 1..65535) {
                    onAdd(name.trim(), host.trim(), port)
                    name = ""
                    host = ""
                    portText = "4965"
                }
            },
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text("Add")
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
