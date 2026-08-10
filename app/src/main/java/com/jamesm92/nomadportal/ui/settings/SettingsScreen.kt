package com.jamesm92.nomadportal.ui.settings

import android.content.ClipData
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jamesm92.nomadportal.connectivity.HostedNodeStatus
import com.jamesm92.nomadportal.connectivity.InterfaceController
import com.jamesm92.nomadportal.connectivity.TcpConnection
import com.jamesm92.nomadportal.connectivity.TcpConnectionsRepository
import com.jamesm92.nomadportal.data.SettingsRepository
import com.jamesm92.nomadportal.data.messaging.AnnounceStatus
import com.jamesm92.nomadportal.data.messaging.ContactIcon
import com.jamesm92.nomadportal.data.messaging.ICON_APPEARANCE_NAMES
import com.jamesm92.nomadportal.data.messaging.InterfaceAnnounceConfig
import com.jamesm92.nomadportal.data.messaging.MdiIconRepository
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.data.messaging.materialIconFor
import com.jamesm92.nomadportal.panicwipe.PanicWipe
import com.jamesm92.nomadportal.permissions.BLUETOOTH_PERMISSIONS
import com.jamesm92.nomadportal.permissions.hasBluetoothPermissions
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.CompactTextField
import com.jamesm92.nomadportal.ui.components.MicronColorPicker
import com.jamesm92.nomadportal.ui.components.MinutesField
import com.jamesm92.nomadportal.ui.components.PanicWipeLogo
import com.jamesm92.nomadportal.ui.components.SearchField
import com.jamesm92.nomadportal.ui.components.VerticalScrollIndicator
import com.jamesm92.nomadportal.ui.theme.NomadAccent
import com.jamesm92.nomadportal.ui.theme.NomadBg3
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
 * Manual identity/hosted-site announcing — rename, icon editing,
 * "Announce now", hosted-site management — lives here too, in the
 * Appearance/Announce/Hosting sections below. That used to be Home's
 * job exclusively (this screen only owning *configuration*, never a
 * "do it now" action) back when Home existed as a separate always-
 * reachable identity-management surface; once Home was removed
 * (dropped from the bottom nav entirely, per explicit direction —
 * Messages/Sites/Settings is the whole nav now), those actions had
 * nowhere else to live, so they moved here rather than disappearing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    interfaceController: InterfaceController,
    settingsRepository: SettingsRepository,
    messagingRepository: MessagingRepository,
    tcpConnectionsRepository: TcpConnectionsRepository,
    onManageHostedPages: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    val textScale by settingsRepository.textScale.collectAsState(initial = SettingsRepository.DEFAULT_TEXT_SCALE)
    val announceStatus by messagingRepository.announceStatus().collectAsState(initial = null)
    val hostedNodeStatus by interfaceController.hostedNodeStatus().collectAsState(initial = null)
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
    // auto-announce toggle + manual "Announce now", identity/hosted-site
    // rename+icon+management (Appearance/Hosting sections — moved here
    // from the now-removed Home screen), and Permissions. Each announce-
    // tracked interface (TCP/Bluetooth/RNode/LAN) gets its own dedicated
    // tab carrying a *duplicate* of its Main-tab toggle (per explicit
    // request — flip it from either place, same underlying state) plus
    // that interface's own Message/Auto policy; TCP's tab additionally
    // carries the full connection list (add/remove/enable/disable —
    // replaces the old single-hardcoded-hub design). There's still no
    // separate "Site" tab here (per the original explicit direction: the
    // different interfaces need room to manage their different settings,
    // the hosted site doesn't really need that) — its on/off toggle,
    // rename, manual "Announce now", and auto-announce interval field all
    // live in the "Hosting" section of this Main tab instead, same as
    // every other hosted-site control.
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
                    // Rename/announce-interval/manual-announce/manage-pages
                    // — moved here from the now-removed Home screen's own
                    // HostedNodeSection. The site's address/hash already
                    // has its own row below (Addresses), so this only
                    // covers what Addresses doesn't: the site's *name* and
                    // its actions.
                    hostedNodeStatus?.let { status ->
                        item {
                            HostedSiteActionsRow(
                                status = status,
                                onRename = { name -> scope.launch { interfaceController.setHostedNodeName(name) } },
                                onAnnounceNow = { scope.launch { interfaceController.announceHostedNodeNow() } },
                                onAnnounceIntervalChange = {
                                    scope.launch { interfaceController.setHostedNodeAnnounceInterval(it) }
                                },
                                onManagePages = onManageHostedPages,
                            )
                        }
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
                        // Manual trigger, independent of the auto-announce
                        // toggle above — moved here from Home's own
                        // IdentitySection (same "Announce now" action, just
                        // relocated). Announce interval configuration for
                        // each interface still lives on that interface's
                        // own tab (TCP/Bluetooth/RNode/LAN), unaffected.
                        item {
                            TextButton(
                                onClick = { scope.launch { messagingRepository.announceNow() } },
                                modifier = Modifier.padding(start = 8.dp),
                            ) { Text("Announce now") }
                        }
                        if (status.sendBlocked) {
                            item {
                                Text(
                                    text = status.sendBlockedReason ?: "Sending is currently blocked.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
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
                    // Name + icon editing — moved here from the now-removed
                    // Home screen's own IdentitySection. The identity's
                    // address/hash already has its own row above
                    // (Addresses) and "Announce now" already has its own
                    // row above (Announce), so this only covers what
                    // neither of those does: the identity's *display name*
                    // and *icon appearance*.
                    announceStatus?.let { status ->
                        item {
                            IdentityAppearanceRow(
                                status = status,
                                onRename = { name -> scope.launch { messagingRepository.setDisplayName(name) } },
                                onSaveIcon = { glyph, fg, bg ->
                                    scope.launch { messagingRepository.setIconAppearance(glyph, fg, bg) }
                                },
                            )
                        }
                    }
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

// ---------------------------------------------------------------------------
// Identity + hosted-site actions — moved here from the now-removed Home
// screen (see this file's own top doc comment for why). Everything below
// this point is either IdentityAppearanceRow/HostedSiteActionsRow
// themselves or composables only they call.
// ---------------------------------------------------------------------------

/**
 * This device's own LXMF identity's *name and icon* — an editable
 * display name and an editable icon appearance. Address/last-announced/
 * manual-announce all have their own rows elsewhere on this tab
 * (Addresses/Announce sections) — this is deliberately narrower than
 * the old Home screen's full IdentitySection, not a verbatim copy of it.
 */
@Composable
private fun IdentityAppearanceRow(
    status: AnnounceStatus,
    onRename: (String) -> Unit,
    onSaveIcon: (glyphName: String, foreground: Color, background: Color) -> Unit,
) {
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember(status.displayName) { mutableStateOf(status.displayName ?: "") }
    var editingIcon by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (editingName) {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    IconButton(onClick = {
                        val trimmed = nameDraft.trim()
                        if (trimmed.isNotEmpty()) onRename(trimmed)
                        editingName = false
                    }) {
                        Icon(Icons.Filled.Check, contentDescription = "Save name")
                    }
                    IconButton(onClick = {
                        nameDraft = status.displayName ?: ""
                        editingName = false
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel")
                    }
                } else {
                    Text(
                        text = status.displayName ?: "Unnamed",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    IconButton(onClick = { editingName = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Rename")
                    }
                }
            }

            IdentityIconPreview(
                appearance = status.iconAppearance,
                onClick = { editingIcon = !editingIcon },
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (editingIcon) {
            IconAppearanceEditor(
                current = status.iconAppearance,
                onSave = { glyph, fg, bg ->
                    onSaveIcon(glyph, fg, bg)
                    editingIcon = false
                },
                onCancel = { editingIcon = false },
            )
        }
    }
}

/**
 * This device's own hosted NomadNet site's *name and actions* — rename,
 * auto-announce interval, manual "Announce now", and "Manage pages"
 * (the file nav for this site's content — see
 * [com.jamesm92.nomadportal.ui.hosting.SiteFilesScreen]). The site's
 * address/hash already has its own row elsewhere on this tab
 * (Addresses section), and its on/off toggle is the row directly above
 * this one (Hosting section) — this is deliberately narrower than the
 * old Home screen's full HostedNodeSection, not a verbatim copy of it.
 */
@Composable
private fun HostedSiteActionsRow(
    status: HostedNodeStatus,
    onRename: (String) -> Unit,
    onAnnounceNow: () -> Unit,
    onAnnounceIntervalChange: (seconds: Int) -> Unit,
    onManagePages: () -> Unit,
) {
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember(status.nodeName) { mutableStateOf(status.nodeName ?: "") }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (!status.enabled) {
            // Still editable while off, per explicit direction — page
            // content lives under the pages directory regardless of
            // whether SiteServer is actually running (see
            // SiteFileRepository's own doc comment), so there's no
            // reason to block preparing/editing a site before turning
            // hosting on.
            Text(
                text = "Off — this device isn't serving any pages right now. You can still edit pages below.",
                style = MaterialTheme.typography.bodyMedium,
                color = NomadTextDim,
            )
            TextButton(onClick = onManagePages, modifier = Modifier.padding(top = 4.dp)) {
                Text("Manage pages")
            }
            return
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (editingName) {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                IconButton(onClick = {
                    val trimmed = nameDraft.trim()
                    if (trimmed.isNotEmpty()) onRename(trimmed)
                    editingName = false
                }) {
                    Icon(Icons.Filled.Check, contentDescription = "Save name")
                }
                IconButton(onClick = {
                    nameDraft = status.nodeName ?: ""
                    editingName = false
                }) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel")
                }
            } else {
                Text(
                    text = status.nodeName ?: "Unnamed site",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f, fill = false),
                )
                IconButton(onClick = { editingName = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename")
                }
            }
        }

        Text(
            text = if (status.lastAnnounceAtMillis != null) {
                "Last announced ${formatRelativeAnnounceTime(status.lastAnnounceAtMillis)}"
            } else {
                "Never announced yet"
            },
            style = MaterialTheme.typography.labelSmall,
            color = NomadTextDim,
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Auto-announce (min)", style = MaterialTheme.typography.labelMedium)
            MinutesField(
                seconds = status.announceIntervalSeconds,
                allowZero = true,
                onCommit = onAnnounceIntervalChange,
                modifier = Modifier.width(64.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onAnnounceNow) { Text("Announce now") }
            TextButton(onClick = onManagePages) { Text("Manage pages") }
        }
    }
}

/** Same relative-time bucketing as ConversationListScreen's/NodeListScreen's
 * own formatRelativeTime — kept local rather than shared since each has
 * its own "never" copy tailored to what it's describing. */
private fun formatRelativeAnnounceTime(millis: Long): String {
    val diffSeconds = ((System.currentTimeMillis() - millis) / 1000).coerceAtLeast(0)
    return when {
        diffSeconds < 60 -> "just now"
        diffSeconds < 3600 -> "${diffSeconds / 60}m ago"
        diffSeconds < 86_400 -> "${diffSeconds / 3600}h ago"
        diffSeconds < 2_592_000 -> "${diffSeconds / 86_400}d ago"
        else -> "${diffSeconds / 2_592_000}mo ago"
    }
}

/** Circular preview of this device's own icon appearance, tappable to
 * open [IconAppearanceEditor] — the whole circle is a tap target, plus a
 * small pencil badge overlaid at its bottom-right corner so the edit
 * affordance is actually visible. Otherwise renders the same way
 * [com.jamesm92.nomadportal.ui.components.ContactAvatar] renders a
 * contact's [ContactIcon.Appearance] — duplicated rather than shared
 * since that composable takes a full
 * [com.jamesm92.nomadportal.data.messaging.Contact], which this device's
 * own identity isn't one of. */
@Composable
private fun IdentityIconPreview(
    appearance: ContactIcon.Appearance?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vector = remember(appearance?.glyphName) { appearance?.glyphName?.let(::materialIconFor) }
    Box(modifier = modifier.size(52.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(appearance?.backgroundColor ?: NomadBg3)
                .align(Alignment.TopStart),
            contentAlignment = Alignment.Center,
        ) {
            if (vector != null && appearance != null) {
                Icon(
                    imageVector = vector,
                    contentDescription = null,
                    tint = appearance.foregroundColor,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondary)
                .align(Alignment.BottomEnd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Edit icon",
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** The three accordion sections [IconAppearanceEditor] toggles between —
 * only one expanded at a time (see that function's own doc comment). */
private enum class EditorSection { BACKGROUND, FOREGROUND, ICON }

/** Collapsed-by-default color picker: a tappable one-line summary (swatch
 * dot + label + chevron) that expands to the full [MicronColorPicker] grid
 * only while [expanded] — see [IconAppearanceEditor]'s own doc comment
 * for why nothing here starts pre-expanded. */
@Composable
private fun CompactColorRow(
    label: String,
    selected: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (Color) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(selected)
                    .border(1.dp, NomadBg3, CircleShape),
            )
            Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Change $label",
                tint = NomadTextDim,
            )
        }
        if (expanded) {
            MicronColorPicker(
                selected = selected,
                onSelect = onSelect,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Same collapsed-by-default convention as [CompactColorRow], for the
 * icon section: a preview circle (rendered in the currently-selected
 * colors, same as each row inside the expanded list) + the current
 * icon's name + chevron. The actual search/list/Save UI it expands to
 * is rendered by [IconAppearanceEditor] itself, not here — this is only
 * the one-line summary row. */
@Composable
private fun CompactIconRow(
    glyphName: String,
    background: Color,
    foreground: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val vector = remember(glyphName) { materialIconFor(glyphName) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(background)
                .border(1.dp, NomadBg3, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (vector != null) {
                Icon(imageVector = vector, contentDescription = null, tint = foreground, modifier = Modifier.size(14.dp))
            }
        }
        Text(
            text = "Icon: ${glyphName.replace('_', ' ')}",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Change icon",
            tint = NomadTextDim,
        )
    }
}

/**
 * Inline editor for this device's own [ContactIcon.Appearance] — three
 * accordion sections (background color, foreground color, icon), only
 * one expanded at a time, per explicit direction: opening it should mean
 * "only icons are in view" — expanding one section via [expandedSection]
 * structurally collapses whichever other section was open, so the icon
 * list is never sharing screen space with a color grid above it.
 *
 * The icon section keeps its own bottom Save/Cancel row *inside* the
 * expanded section, not floating at the whole editor's very bottom —
 * that's specifically what avoided a real on-device report (Save landing
 * off-screen below a tall list): the list is the only other thing
 * visible while it's open, so Save is always right below it, never
 * buried under two more open sections above.
 */
@Composable
private fun IconAppearanceEditor(
    current: ContactIcon.Appearance?,
    onSave: (glyphName: String, foreground: Color, background: Color) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedGlyph by remember(current) {
        mutableStateOf(current?.glyphName ?: ICON_APPEARANCE_NAMES.first())
    }
    var selectedBg by remember(current) { mutableStateOf(current?.backgroundColor ?: NomadAccent) }
    var selectedFg by remember(current) { mutableStateOf(current?.foregroundColor ?: Color.White) }
    var searchQuery by remember { mutableStateOf("") }
    // Accordion: only one of the three sections is ever expanded at
    // once — see this function's own doc comment.
    var expandedSection by remember { mutableStateOf<EditorSection?>(null) }

    // Sourced from the real full MDI catalog (~7400 names, matching
    // exactly what a real MeshChat/Sideband contact can pick from), not
    // just ICON_APPEARANCE_MAP's curated ~180-entry subset — per
    // explicit on-device report ("still not seeing the icon match what
    // I have on MeshChat"): the curated list alone couldn't offer every
    // icon a contact might already be using in another client. Falls
    // back to the curated names only in the brief startup window before
    // MdiIconRepository's background load finishes (isLoaded() false),
    // so the picker is never empty.
    val allNames = remember {
        MdiIconRepository.names().ifEmpty { ICON_APPEARANCE_NAMES }
    }
    val filteredNames = remember(searchQuery, allNames) {
        val q = searchQuery.trim().lowercase().replace(' ', '_').replace('-', '_')
        if (q.isBlank()) {
            allNames
        } else {
            allNames.filter { it.replace('-', '_').contains(q) }
        }
    }

    // Scrolls to whatever's already selected every time the icon
    // section is (re)opened — per explicit request — rather than always
    // starting back at the front of the list, which previously meant
    // re-finding your own icon by scrolling every single time. Only
    // meaningful against the unfiltered list (search starts blank each
    // open).
    val listState = rememberLazyListState()
    LaunchedEffect(expandedSection) {
        if (expandedSection == EditorSection.ICON) {
            val index = allNames.indexOf(selectedGlyph)
            if (index >= 0) {
                listState.scrollToItem(index)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .border(1.dp, NomadBg3, shape = RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Save lives here too, not just inside the icon section's own
        // full-screen picker — per explicit direction, changing only
        // the colors (never opening the icon picker at all) needs its
        // own way to commit and close, not just Cancel.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { onSave(selectedGlyph, selectedFg, selectedBg) }) { Text("Save") }
        }

        CompactColorRow(
            label = "Background color",
            selected = selectedBg,
            expanded = expandedSection == EditorSection.BACKGROUND,
            onToggle = {
                expandedSection = if (expandedSection == EditorSection.BACKGROUND) {
                    null
                } else {
                    EditorSection.BACKGROUND
                }
            },
            onSelect = {
                selectedBg = it
                expandedSection = null
            },
        )

        CompactColorRow(
            label = "Icon color",
            selected = selectedFg,
            expanded = expandedSection == EditorSection.FOREGROUND,
            onToggle = {
                expandedSection = if (expandedSection == EditorSection.FOREGROUND) {
                    null
                } else {
                    EditorSection.FOREGROUND
                }
            },
            onSelect = {
                selectedFg = it
                expandedSection = null
            },
        )

        CompactIconRow(
            glyphName = selectedGlyph,
            background = selectedBg,
            foreground = selectedFg,
            expanded = expandedSection == EditorSection.ICON,
            onToggle = {
                expandedSection = if (expandedSection == EditorSection.ICON) null else EditorSection.ICON
            },
        )

        if (expandedSection == EditorSection.ICON) {
            FullScreenIconPicker(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                names = filteredNames,
                selectedGlyph = selectedGlyph,
                selectedBg = selectedBg,
                selectedFg = selectedFg,
                listState = listState,
                onSelect = { selectedGlyph = it },
                onDismiss = { expandedSection = null },
            )
        }
    }
}

/**
 * Takes over the entire screen — per explicit direction — while picking
 * an icon, rather than expanding inline within [IconAppearanceEditor]'s
 * bordered box (which would otherwise be nested inside a scrollable
 * column, capping the list to a bounded height regardless of actual
 * screen size). A real full-screen [Dialog] renders in its own window
 * above everything else, so the list can use `weight(1f)` to claim all
 * remaining vertical space instead of a bounded height guess.
 *
 * No Save button of its own — [onSelect] already writes straight into
 * [IconAppearanceEditor]'s own `selectedGlyph` state on tap (not a
 * local draft needing a separate confirm step), so the close button
 * above is enough to return to that editor with the pick already
 * applied; that editor's own top-of-panel Save/Cancel row is what
 * actually commits it.
 */
@Composable
private fun FullScreenIconPicker(
    query: String,
    onQueryChange: (String) -> Unit,
    names: List<String>,
    selectedGlyph: String,
    selectedBg: Color,
    selectedFg: Color,
    listState: LazyListState,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = "Choose an icon", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                SearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    placeholder = "Search icons",
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
                ) {
                    items(names, key = { it }) { name ->
                        val vector = materialIconFor(name)
                        if (vector != null) {
                            val isSelected = name == selectedGlyph
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(name) }
                                    .background(
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                        } else {
                                            Color.Transparent
                                        },
                                    )
                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(selectedBg),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = vector,
                                        contentDescription = null,
                                        tint = selectedFg,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                Text(
                                    text = name.replace('_', ' ').replace('-', ' '),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


