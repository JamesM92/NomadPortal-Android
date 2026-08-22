package com.jamesm92.nomadportal.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jamesm92.nomadportal.connectivity.HostedNodeStatus
import com.jamesm92.nomadportal.connectivity.InterfaceController
import com.jamesm92.nomadportal.connectivity.TcpConnection
import com.jamesm92.nomadportal.connectivity.TcpConnectionsRepository
import com.jamesm92.nomadportal.data.SettingsRepository
import com.jamesm92.nomadportal.data.ThemeMode
import com.jamesm92.nomadportal.data.identity.IdentityRepository
import com.jamesm92.nomadportal.data.messaging.AnnounceStatus
import com.jamesm92.nomadportal.data.rnsh.RnshHistoryRepository
import com.jamesm92.nomadportal.data.messaging.ContactIcon
import com.jamesm92.nomadportal.data.messaging.ICON_APPEARANCE_NAMES
import com.jamesm92.nomadportal.data.messaging.InterfaceAnnounceConfig
import com.jamesm92.nomadportal.data.messaging.MdiIconRepository
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.data.messaging.materialIconFor
import com.jamesm92.nomadportal.notifications.MessageNotificationController
import com.jamesm92.nomadportal.panicwipe.PanicWipe
import com.jamesm92.nomadportal.permissions.hasBluetoothPermissions
import com.jamesm92.nomadportal.permissions.hasPostNotificationsPermission
import com.jamesm92.nomadportal.permissions.hasRecordAudioPermission
import com.jamesm92.nomadportal.permissions.isIgnoringBatteryOptimizations
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.CompactTextField
import com.jamesm92.nomadportal.ui.components.MicronColorPicker
import com.jamesm92.nomadportal.ui.components.MinutesField
import com.jamesm92.nomadportal.ui.components.PanicWipeLogo
import com.jamesm92.nomadportal.ui.components.SearchField
import com.jamesm92.nomadportal.ui.components.VerticalScrollIndicator
import com.jamesm92.nomadportal.ui.components.buildIdentityQrPayload
import com.jamesm92.nomadportal.ui.components.generateQrBitmap
import com.jamesm92.nomadportal.util.AppRestart
import com.jamesm92.nomadportal.ui.theme.NomadAccent
import com.jamesm92.nomadportal.ui.theme.NomadBg3
import com.jamesm92.nomadportal.ui.theme.NomadMono
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import com.jamesm92.nomadportal.ui.theme.NomadWarn
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
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
 *
 * **One scrollable page of independently-collapsible sections**, per
 * explicit request to match Columba's own settings layout, replacing the
 * previous per-interface SecondaryTabRow (Main/TCP/Bluetooth/RNode/LAN
 * tabs). Each interface's section header carries its own enable [Switch]
 * right on the (always-visible-even-collapsed) header row — preserving
 * the old tabbed design's "duplicate toggle, flip it from either place"
 * convenience without needing a whole tab switch just to see it. Unlike
 * [IconAppearanceEditor]'s deliberately-exclusive accordion further down
 * this file, sections here are independent: opening one never closes
 * another, matching how a real settings page's sections behave (there is
 * no "only one thing in view at once" rule for a page like this the way
 * there was for that narrower color/icon editor).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    interfaceController: InterfaceController,
    settingsRepository: SettingsRepository,
    messagingRepository: MessagingRepository,
    tcpConnectionsRepository: TcpConnectionsRepository,
    identityRepository: IdentityRepository,
    rnshHistoryRepository: RnshHistoryRepository,
    onManageHostedPages: () -> Unit,
    onOpenRnshTerminal: () -> Unit,
    onOpenIdentities: () -> Unit,
    onOpenBlockedContacts: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    val textScale by settingsRepository.textScale.collectAsState(initial = SettingsRepository.DEFAULT_TEXT_SCALE)
    // Matches SettingsRepository.themeMode's own real default (DARK, not
    // SYSTEM — see that property's own doc comment).
    val themeMode by settingsRepository.themeMode.collectAsState(initial = ThemeMode.DARK)
    val announceStatus by messagingRepository.announceStatus().collectAsState(initial = null)
    val conversations by messagingRepository.conversations().collectAsState(initial = emptyList())
    val hostedNodeStatus by interfaceController.hostedNodeStatus().collectAsState(initial = null)
    val tcpConnections by tcpConnectionsRepository.connections().collectAsState(initial = emptyList())
    val identities by identityRepository.identities().collectAsState(initial = emptyList())
    val rnshHistory by rnshHistoryRepository.history().collectAsState(initial = emptyList())

    val tcpEnabled by interfaceController.tcpEnabled.collectAsState()
    val bluetoothMeshEnabled by interfaceController.bluetoothMeshEnabled.collectAsState()
    val rNodeEnabled by interfaceController.rNodeEnabled.collectAsState()
    val wifiDiscoveryEnabled by interfaceController.wifiDiscoveryEnabled.collectAsState()
    val nodeHostingEnabled by interfaceController.nodeHostingEnabled.collectAsState()
    // Hoisted here (rather than staying local to the Notifications
    // section's own item block, where they used to live) so the Hosting
    // section can warn about them too — see that section's own new
    // warning row for why: a node that's only reachable while this
    // app's process is alive needs Always-on notifications to actually
    // stay reachable, and that's exactly the moment (turning hosting on)
    // a user needs to hear it, not just buried in a different section
    // they may never open.
    val notificationsEnabled by settingsRepository.notificationsEnabled.collectAsState(initial = false)
    val notificationsAlwaysOn by settingsRepository.notificationsAlwaysOn.collectAsState(initial = true)
    // The actual condition under which this process keeps running in
    // the background at all (MessageNotificationController only starts
    // the real foreground service when both of these are true) — real,
    // on-review-found bug fixed here: the Notifications section's own
    // warning below used to check only `!notificationsAlwaysOn`, which
    // missed the case where notifications are off *entirely*
    // (notificationsEnabled == false) but notificationsAlwaysOn still
    // happened to be true from a stored/default value, silently hiding
    // the exact warning it exists to show.
    val backgroundServiceActive = notificationsEnabled && notificationsAlwaysOn

    // Real, on-device-confirmed crash — the most severe instance of the
    // FragmentActivity/ActivityResultRegistry incompatibility found this
    // session (see the Notifications section's own doc comment for the
    // full story): unlike the other launchers in this file, this one was
    // never wrapped in a try/catch at all, so turning this toggle on
    // without Bluetooth permission already granted would deterministically
    // crash the whole app, every time. Root-caused the same way as the
    // others: no launcher at all, a plain context.startActivity() to this
    // app's own details settings page (there's no single dedicated
    // "grant these 2 permissions" system intent for a permission group
    // the way there is for notifications, so this is the same fallback
    // Columba's own real grant actions already use uniformly) + a
    // polling LaunchedEffect that both keeps bluetoothGranted current and
    // auto-enables the toggle the moment it detects a fresh grant —
    // preserving the original "grant then auto-turn-on" intent without
    // needing an ActivityResult callback to react to.
    var bluetoothGranted by remember { mutableStateOf(hasBluetoothPermissions(context)) }
    LaunchedEffect(Unit) {
        delay(500)
        while (true) {
            val nowGranted = hasBluetoothPermissions(context)
            if (nowGranted && !bluetoothGranted) {
                interfaceController.setBluetoothMeshEnabled(true)
            }
            bluetoothGranted = nowGranted
            delay(3000)
        }
    }
    fun requestBluetoothPermissions() {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        } catch (e: ActivityNotFoundException) {
            // Best-effort — a device with no app-details settings screen
            // shouldn't crash this screen.
        }
    }

    // Everything starts collapsed — no more "Connectivity" overview
    // section to default-open now that each interface's own toggle
    // already lives right on its own section header (per explicit
    // direction: the separate Connectivity section was redundant once
    // TCP/Bluetooth/RNode/LAN each carry their own). Independent
    // booleans, not an accordion — see this function's own doc comment.
    var expandedSections by remember { mutableStateOf(emptySet<SettingsSection>()) }
    fun toggleSection(section: SettingsSection) {
        expandedSections = if (section in expandedSections) {
            expandedSections - section
        } else {
            expandedSections + section
        }
    }

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = { Text("Settings") },
                // No back arrow — Settings is now a real bottom-nav
                // tab (NomadNavHost.kt), same as Home/Messages/Nodes;
                // switching tabs is the way back.
                actions = {
                    // Pinned in the top bar (not a scrollable section
                    // item) — per explicit direction, a one-tap emergency
                    // action should stay reachable regardless of scroll
                    // position, now that it's not riding along with a
                    // Connectivity section header anymore. Only the four
                    // connectivity interfaces, not node hosting — hosting
                    // lives under its own "Hosting" section below and
                    // isn't a communication method itself (see
                    // setNodeHostingEnabled's own doc comment: it's
                    // independent of which interfaces are up). A user
                    // reaching for a kill switch wants this device to stop
                    // talking, not to also silently stop answering
                    // requests it was already committed to serving.
                    TextButton(onClick = {
                        scope.launch {
                            interfaceController.setTcpEnabled(false)
                            interfaceController.setBluetoothMeshEnabled(false)
                            interfaceController.setRNodeEnabled(false)
                            interfaceController.setWifiDiscoveryEnabled(false)
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Filled.PowerSettingsNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Kill", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    PanicWipeLogo(
                        modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                        onTripleTap = {
                            scope.launch {
                                PanicWipe.perform(context)
                                PanicWipe.restartApp(context)
                            }
                        },
                    )
                },
            )
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                // Columba's own real Settings screen (verified directly
                // against its source) puts every section in a rounded
                // Card with 16dp gaps and 16dp outer padding, no dividers
                // — this list now matches that shape exactly, replacing
                // the flat divided-list layout each CollapsibleSection
                // used to render on its own.
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    // TCP: duplicate master toggle (on the header, visible
                    // collapsed), the full connection list (add/remove/
                    // enable per connection), then this interface's own
                    // Message/Auto announce policy.
                    CollapsibleSection(
                        title = "TCP",
                        icon = Icons.Filled.Router,
                        expanded = SettingsSection.TCP in expandedSections,
                        onToggleExpanded = { toggleSection(SettingsSection.TCP) },
                        headerTrailing = {
                            Switch(
                                checked = tcpEnabled,
                                onCheckedChange = { scope.launch { interfaceController.setTcpEnabled(it) } },
                            )
                        },
                    ) {
                        SectionHeader("Connections")
                        TcpConnectionsTableHeader()
                        tcpConnections.forEach { connection ->
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
                        TcpConnectionAddRow(
                            onAdd = { name, host, port ->
                                scope.launch { tcpConnectionsRepository.addConnection(name, host, port) }
                            },
                        )
                        announceStatus?.interfaces?.get(AnnounceStatus.INTERFACE_TCP)?.let { config ->
                            HorizontalDivider()
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

                item {
                    CollapsibleSection(
                        title = "Bluetooth mesh",
                        icon = Icons.Filled.Bluetooth,
                        expanded = SettingsSection.BLUETOOTH in expandedSections,
                        onToggleExpanded = { toggleSection(SettingsSection.BLUETOOTH) },
                        headerTrailing = {
                            Switch(
                                checked = bluetoothMeshEnabled,
                                onCheckedChange = { turningOn ->
                                    if (turningOn && !bluetoothGranted) {
                                        requestBluetoothPermissions()
                                    } else {
                                        scope.launch { interfaceController.setBluetoothMeshEnabled(turningOn) }
                                    }
                                },
                            )
                        },
                    ) {
                        announceStatus?.interfaces?.get(AnnounceStatus.INTERFACE_BLUETOOTH)?.let { config ->
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

                item {
                    CollapsibleSection(
                        title = "RNode",
                        icon = Icons.Filled.SettingsInputAntenna,
                        expanded = SettingsSection.RNODE in expandedSections,
                        onToggleExpanded = { toggleSection(SettingsSection.RNODE) },
                        headerTrailing = {
                            Switch(
                                checked = rNodeEnabled,
                                onCheckedChange = { scope.launch { interfaceController.setRNodeEnabled(it) } },
                            )
                        },
                    ) {
                        announceStatus?.interfaces?.get(AnnounceStatus.INTERFACE_RNODE)?.let { config ->
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

                item {
                    CollapsibleSection(
                        title = "Local network discovery",
                        icon = Icons.Filled.Wifi,
                        expanded = SettingsSection.LAN in expandedSections,
                        onToggleExpanded = { toggleSection(SettingsSection.LAN) },
                        headerTrailing = {
                            Switch(
                                checked = wifiDiscoveryEnabled,
                                onCheckedChange = { scope.launch { interfaceController.setWifiDiscoveryEnabled(it) } },
                            )
                        },
                    ) {
                        announceStatus?.interfaces?.get(AnnounceStatus.INTERFACE_WIFI_DISCOVERY)?.let { config ->
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

                item {
                    // Rename/announce-interval/manual-announce/manage-pages
                    // — moved here from the now-removed Home screen's own
                    // HostedNodeSection. The site's own address/hash now
                    // lives here too (AddressRow below) — it used to have
                    // its own row in a separate "Addresses" section, but
                    // that section was removed once identity addresses
                    // moved into IdentitiesScreen (per explicit
                    // direction), and the site address was never
                    // identity-related to begin with — its own hosting
                    // section is a more sensible home for it than a
                    // resurrected Addresses section would be.
                    CollapsibleSection(
                        title = "Hosting",
                        icon = Icons.Filled.Dns,
                        expanded = SettingsSection.HOSTING in expandedSections,
                        onToggleExpanded = { toggleSection(SettingsSection.HOSTING) },
                        headerTrailing = {
                            Switch(
                                checked = nodeHostingEnabled,
                                onCheckedChange = { scope.launch { interfaceController.setNodeHostingEnabled(it) } },
                            )
                        },
                    ) {
                        // Per explicit direction: warn about this right
                        // where a user actually turns hosting on, not
                        // only in the Notifications section they may
                        // never open — this app has no separate daemon;
                        // a hosted node is only reachable while this
                        // process itself is alive, which (per
                        // backgroundServiceActive's own doc comment)
                        // means notifications on and set to Always-on
                        // specifically, not the battery-friendly mode.
                        if (nodeHostingEnabled && !backgroundServiceActive) {
                            Text(
                                text = "Your node will stop being reachable by others whenever " +
                                    "this app isn't in the foreground, unless you turn " +
                                    "notifications on and set them to Always-on in the " +
                                    "Notifications section below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        hostedNodeStatus?.let { status ->
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
                        AddressRow(
                            label = "Site address",
                            value = announceStatus?.hostedNodeHash,
                            placeholder = "Not currently hosting a site",
                        )
                    }
                }

                item {
                    CollapsibleSection(
                        title = "Auto Announce",
                        icon = Icons.Filled.Campaign,
                        expanded = SettingsSection.ANNOUNCE in expandedSections,
                        onToggleExpanded = { toggleSection(SettingsSection.ANNOUNCE) },
                        headerTrailing = {
                            announceStatus?.let { status ->
                                Switch(
                                    checked = status.autoAnnounceMasterEnabled,
                                    onCheckedChange = {
                                        scope.launch { messagingRepository.setAutoAnnounceMaster(it) }
                                    },
                                )
                            }
                        },
                    ) {
                        announceStatus?.let { status ->
                            // Manual trigger, independent of the auto-announce
                            // toggle above — moved here from Home's own
                            // IdentitySection (same "Announce now" action, just
                            // relocated). Announce interval configuration for
                            // each interface still lives in that interface's
                            // own section (TCP/Bluetooth/RNode/LAN), unaffected.
                            TextButton(
                                onClick = { scope.launch { messagingRepository.announceNow() } },
                                modifier = Modifier.padding(start = 8.dp),
                            ) { Text("Announce now") }
                            if (status.sendBlocked) {
                                Text(
                                    text = status.sendBlockedReason ?: "Sending is currently blocked.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                item {
                    // Matches Columba's own real PrivacyCard.kt exactly
                    // (fetched and verified directly against its source,
                    // not recalled from memory): messages-contacts-only,
                    // then calls-contacts-only, then blocked users — all
                    // three in one Privacy card. An earlier pass had moved
                    // the calls toggle out into a separate section; that
                    // was this session's own invented interpretation, not
                    // what Columba actually does, and got corrected back
                    // per explicit direction.
                    CollapsibleSection(
                        title = "Privacy",
                        icon = Icons.Filled.Security,
                        expanded = SettingsSection.PRIVACY in expandedSections,
                        onToggleExpanded = { toggleSection(SettingsSection.PRIVACY) },
                    ) {
                        announceStatus?.let { status ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(text = "Messages from contacts only", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = status.messagesContactsOnly,
                                    onCheckedChange = {
                                        scope.launch { messagingRepository.setMessagesContactsOnly(it) }
                                    },
                                )
                            }
                            Text(
                                text = if (status.messagesContactsOnly) {
                                    "Only contacts can message you. Messages from unknown " +
                                        "senders are silently discarded."
                                } else {
                                    "Anyone can send you messages, including unknown senders."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = NomadTextDim,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            // Calls-specific counterpart, independent of
                            // the messages toggle above — matches
                            // PrivacyCard.kt's own "Calls-from-contacts-
                            // only toggle row (independent of
                            // block_unknown_senders)" comment exactly.
                            // Description text describes our own actual
                            // enforcement (a real BUSY signal — see
                            // AnnounceStatus.callsContactsOnly's own doc
                            // comment) rather than copying Columba's "link
                            // attempts are silently dropped" wording,
                            // which describes a different real backend
                            // behavior than ours.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(text = "Calls from contacts only", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = status.callsContactsOnly,
                                    onCheckedChange = {
                                        scope.launch { messagingRepository.setCallsContactsOnly(it) }
                                    },
                                )
                            }
                            Text(
                                text = if (status.callsContactsOnly) {
                                    "Only contacts can call you. Calls from unknown " +
                                        "identities get a busy signal, and never ring."
                                } else {
                                    "Anyone can call you, including unknown identities."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = NomadTextDim,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            // Columba's own real PrivacyCard also carries a
                            // "blocked users list" (verified against its
                            // source during this session's own Columba
                            // settings audit) — this is that. Blocking
                            // itself already existed (per-contact, via each
                            // row's own long-press menu); this just adds
                            // somewhere to see/manage everyone blocked.
                            val blockedCount = conversations.count { it.contact.isBlocked }
                            Text(
                                text = "$blockedCount blocked contact${if (blockedCount == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NomadTextDim,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            TextButton(
                                onClick = onOpenBlockedContacts,
                                modifier = Modifier.padding(start = 8.dp),
                            ) { Text("Manage blocked contacts") }
                        }
                    }
                }

                item {
                    // Matches Columba's own real VoiceCallPermissionsCard.kt
                    // (fetched and verified directly against its source):
                    // a card entirely about *device* permission status for
                    // calling — separate from Privacy's contacts-only
                    // allowlisting, which is a different concern (who's
                    // allowed to reach you vs. what the OS lets the app
                    // do). Columba's real card also tracks "display over
                    // other apps" and (on Android 14+) "full-screen
                    // notifications" — both there because Columba launches
                    // its call UI from a background notification. This
                    // app's own CallOverlay only ever shows while already
                    // foregrounded (see its own doc comment), so neither
                    // permission is real here; only Microphone applies.
                    // Columba's own card also carries an "allow voice
                    // calls at all" master toggle (separate from its own
                    // contacts-only setting) — this app has no such
                    // feature built, so no toggle is fabricated here
                    // either; this card is status-only, matching what
                    // functionality actually exists.
                    var hasRecordAudioPermission by remember {
                        mutableStateOf(hasRecordAudioPermission(context))
                    }
                    // Columba's own real grant action is a plain
                    // context.startActivity(intent) to this device's
                    // app-details Settings page — verified directly
                    // against VoiceCallPermissionsCard.kt's actual source,
                    // not a rememberLauncherForActivityResult launcher.
                    // That distinction matters here, not just for fidelity:
                    // MainActivity is a FragmentActivity (for BiometricPrompt
                    // — see its own doc comment), and FragmentActivity's own
                    // startActivityForResult override enforces a "request
                    // code must fit in 16 bits" check that every
                    // ActivityResultRegistry-generated code deliberately
                    // violates by design (confirmed via ActivityResultRegistry's
                    // own real source: it always allocates from the upper
                    // 16 bits, specifically to avoid colliding with
                    // hand-picked legacy codes) — a genuine upstream
                    // AndroidX incompatibility, not a flaky one, confirmed
                    // by reproducing it 6/6 times via a temporary debug
                    // log. A launcher-based approach here would deterministically
                    // throw on every tap, caught only by the same defensive
                    // try/catch already covering other launchers in this
                    // file. Plain startActivity() never allocates a request
                    // code at all, so it sidesteps the incompatibility
                    // entirely instead of merely catching it.
                    LaunchedEffect(Unit) {
                        // Matches Columba's own real polling shape exactly
                        // (an initial 500ms delay, then a 3s loop) — this
                        // is how it detects the user granting the
                        // permission via Settings and coming back, since
                        // plain startActivity() has no result callback to
                        // react to instead.
                        delay(500)
                        hasRecordAudioPermission = hasRecordAudioPermission(context)
                        while (true) {
                            delay(3000)
                            hasRecordAudioPermission = hasRecordAudioPermission(context)
                        }
                    }
                    fun openRecordAudioSettings() {
                        try {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        } catch (e: ActivityNotFoundException) {
                            // Best-effort — a device with no Settings app
                            // to open shouldn't crash this screen.
                        }
                    }

                    CollapsibleSection(
                        title = "Voice Call Permissions",
                        icon = Icons.Filled.Call,
                        expanded = SettingsSection.VOICE_CALL_PERMISSIONS in expandedSections,
                        onToggleExpanded = { toggleSection(SettingsSection.VOICE_CALL_PERMISSIONS) },
                        headerTrailing = {
                            // Master "Allow incoming voice calls" toggle —
                            // matches Columba's own real
                            // VoiceCallPermissionsCard header layout
                            // exactly (Switch + chevron together, visible
                            // even while collapsed). See
                            // AnnounceStatus.callsEnabled's own doc
                            // comment; independent of, and enforced ahead
                            // of, the Calls-from-contacts-only toggle in
                            // Privacy above.
                            announceStatus?.let { status ->
                                Switch(
                                    checked = status.callsEnabled,
                                    onCheckedChange = {
                                        scope.launch { messagingRepository.setCallsEnabled(it) }
                                    },
                                )
                            }
                        },
                    ) {
                        if (announceStatus?.callsEnabled == false) {
                            // Matches Columba's own real copy for this
                            // exact state, shown ahead of the permission-
                            // status content below so the user understands
                            // why it might not matter right now.
                            Text(
                                text = "Incoming voice calls are currently disabled. Outgoing calls still work.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        // Columba's own card color-codes its whole body
                        // (secondaryContainer when all-granted,
                        // errorContainer otherwise) — reproduced here as a
                        // tinted background behind this section's content,
                        // without restyling every other section's shared
                        // CollapsibleSection shell.
                        val containerColor = if (hasRecordAudioPermission) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        }
                        val contentColor = if (hasRecordAudioPermission) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(containerColor)
                                .padding(12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = if (hasRecordAudioPermission) {
                                        Icons.Filled.CheckCircle
                                    } else {
                                        Icons.Filled.Close
                                    },
                                    contentDescription = if (hasRecordAudioPermission) "Granted" else "Not granted",
                                    tint = contentColor,
                                )
                                Text(
                                    text = "Microphone",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = contentColor,
                                )
                            }
                            Text(
                                text = "Required to capture audio during voice calls.",
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor,
                                modifier = Modifier.padding(start = 28.dp, top = 2.dp),
                            )
                            if (!hasRecordAudioPermission) {
                                Button(
                                    onClick = { openRecordAudioSettings() },
                                    modifier = Modifier.padding(start = 28.dp, top = 8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) { Text("Open Settings") }
                            }
                        }
                    }
                }

                item {
                    // Columba's own real NotificationSettingsCard
                    // (verified during this session's own Columba
                    // settings audit) — this app had no notifications at
                    // all before. Dual-mode per explicit direction: the
                    // user gets a real choice between Always-on (a
                    // foreground service, reliable, persistent
                    // notification) and Battery-friendly (WorkManager,
                    // no persistent notification, but Android may delay/
                    // skip checks under Doze) — see
                    // MessageNotificationController's own doc comment
                    // for how the two modes are reconciled.
                    var hasNotificationPermission by remember {
                        mutableStateOf(hasPostNotificationsPermission(context))
                    }
                    var isIgnoringBatteryOpt by remember {
                        mutableStateOf(isIgnoringBatteryOptimizations(context))
                    }
                    // Real root-cause fix, not a launcher + try/catch —
                    // same fix already applied to Voice Call Permissions'
                    // own Microphone grant button (see that section's own
                    // doc comment for the full story): MainActivity is a
                    // FragmentActivity (for BiometricPrompt), and
                    // FragmentActivity's own startActivityForResult
                    // override is fundamentally incompatible with
                    // ActivityResultRegistry's own request-code scheme —
                    // confirmed real and deterministic (reproduced 6/6
                    // times via a temporary debug log), not flaky. Every
                    // rememberLauncherForActivityResult launcher in this
                    // section threw on every tap, silently swallowed by
                    // the try/catch that used to wrap each launch() call
                    // — exactly why these two buttons/the master-toggle
                    // auto-prompt all read as "doing nothing" rather than
                    // genuinely not working. Plain context.startActivity()
                    // never allocates a request code at all, so it
                    // sidesteps the incompatibility entirely, and both
                    // buttons now open the real system settings screen
                    // for that specific permission (matching Columba's
                    // own real pattern, not a runtime dialog attempt).
                    // A polling LaunchedEffect (same 500ms+3s shape
                    // already established for Voice Call Permissions)
                    // re-checks both statuses once the user comes back,
                    // since there's no ActivityResult callback to react
                    // to instead.
                    LaunchedEffect(Unit) {
                        delay(500)
                        hasNotificationPermission = hasPostNotificationsPermission(context)
                        isIgnoringBatteryOpt = isIgnoringBatteryOptimizations(context)
                        while (true) {
                            delay(3000)
                            hasNotificationPermission = hasPostNotificationsPermission(context)
                            isIgnoringBatteryOpt = isIgnoringBatteryOptimizations(context)
                        }
                    }
                    fun requestNotificationPermission() {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        } catch (e: ActivityNotFoundException) {
                            // Best-effort — a device with no notification
                            // settings screen shouldn't crash this screen.
                        }
                    }
                    fun requestBatteryOptExemption() {
                        try {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        } catch (e: ActivityNotFoundException) {
                            // Same best-effort reasoning as above.
                        }
                    }

                    CollapsibleSection(
                        title = "Notifications",
                        icon = Icons.Filled.Notifications,
                        expanded = SettingsSection.NOTIFICATIONS in expandedSections,
                        onToggleExpanded = { toggleSection(SettingsSection.NOTIFICATIONS) },
                        headerTrailing = {
                            Switch(
                                checked = notificationsEnabled,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        settingsRepository.setNotificationsEnabled(enabled)
                                        if (enabled && !hasNotificationPermission &&
                                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                        ) {
                                            requestNotificationPermission()
                                        }
                                        MessageNotificationController.apply(context, enabled, notificationsAlwaysOn)
                                    }
                                },
                            )
                        },
                    ) {
                        Text(
                            text = if (notificationsEnabled) {
                                "Notified about new messages, even while the app is closed."
                            } else {
                                "No notifications for new messages."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = NomadTextDim,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        if (notificationsEnabled) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = notificationsAlwaysOn,
                                    onClick = {
                                        scope.launch {
                                            settingsRepository.setNotificationsAlwaysOn(true)
                                            MessageNotificationController.apply(context, true, true)
                                        }
                                    },
                                    label = { Text("Always-on") },
                                )
                                FilterChip(
                                    selected = !notificationsAlwaysOn,
                                    onClick = {
                                        scope.launch {
                                            settingsRepository.setNotificationsAlwaysOn(false)
                                            MessageNotificationController.apply(context, true, false)
                                        }
                                    },
                                    label = { Text("Battery-friendly") },
                                )
                            }
                            Text(
                                text = if (notificationsAlwaysOn) {
                                    "A persistent notification keeps the app connected so messages " +
                                        "arrive in real time."
                                } else {
                                    "No persistent notification — Android may delay checks by 15+ " +
                                        "minutes, or skip them entirely, while the app is backgrounded. " +
                                        "That's not just message notifications: without a foreground " +
                                        "service keeping this process alive, real mesh activity " +
                                        "(announces, relayed traffic) is likely to be missed too."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = NomadTextDim,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                            )
                            // Per explicit direction: make it clear
                            // *when* Battery-friendly's real trade-off
                            // actually matters, rather than leaving it as
                            // an abstract "may delay/skip" warning —
                            // RNode/Bluetooth mesh/hosting/voice calls all
                            // depend on this process staying alive in the
                            // background to keep working at all, not just
                            // on timely notifications. Voice calls added
                            // after a real gap found via an actual live
                            // test call: this app had no incoming-call
                            // alert at all before CallNotifier, and even
                            // with it, Battery-friendly's real ~15min
                            // minimum interval is far longer than a call
                            // ever rings for — see that file's own doc
                            // comment. Checks backgroundServiceActive (not
                            // just notificationsAlwaysOn) so this also
                            // fires when notifications are off entirely,
                            // not just when they're on-but-Battery-
                            // friendly — see that val's own doc comment.
                            if (!backgroundServiceActive &&
                                (rNodeEnabled || bluetoothMeshEnabled || nodeHostingEnabled || announceStatus?.callsEnabled == true)
                            ) {
                                Text(
                                    text = "Recommended: turn notifications on and set them to " +
                                        "Always-on — you have " +
                                        listOfNotNull(
                                            "RNode".takeIf { rNodeEnabled },
                                            "Bluetooth mesh".takeIf { bluetoothMeshEnabled },
                                            "node hosting".takeIf { nodeHostingEnabled },
                                            "voice calls".takeIf { announceStatus?.callsEnabled == true },
                                        ).joinToString(", ") +
                                        " on, and all of those need this app actually running in " +
                                        "the background to keep working.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                                Text(
                                    text = "Notification permission not granted — nothing will show.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                                Button(
                                    onClick = { requestNotificationPermission() },
                                    modifier = Modifier.padding(start = 8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) { Text("Open Settings") }
                            }
                            if (notificationsAlwaysOn && !isIgnoringBatteryOpt) {
                                Text(
                                    text = "For the most reliable delivery, exempt NomadPortal from " +
                                        "battery optimization too.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NomadTextDim,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                                Button(
                                    onClick = { requestBatteryOptExemption() },
                                    modifier = Modifier.padding(start = 8.dp),
                                ) { Text("Don't optimize") }
                            }
                        }
                    }
                }

                item {
                    // Columba's own real IdentityCard → IdentityManagerScreen
                    // shape (verified against its source) — a compact
                    // summary here, real create/switch/rename/delete/
                    // import/export management on its own screen
                    // (IdentitiesScreen.kt). Distinct from "Appearance"
                    // below: this section is about *which* identity is
                    // active; Appearance edits the active one's own
                    // name/icon, same split Columba draws between its
                    // IdentityManagerScreen and its per-identity editing.
                    CollapsibleSection(
                        title = "Identities",
                        icon = Icons.Filled.Badge,
                        expanded = SettingsSection.IDENTITIES in expandedSections,
                        onToggleExpanded = { toggleSection(SettingsSection.IDENTITIES) },
                    ) {
                        val activeName = identities.firstOrNull { it.isActive }?.name
                        Text(
                            text = if (activeName != null) {
                                "${identities.size} identit${if (identities.size == 1) "y" else "ies"} · active: $activeName"
                            } else {
                                "${identities.size} identit${if (identities.size == 1) "y" else "ies"}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = NomadTextDim,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        Button(
                            onClick = onOpenIdentities,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text("Manage identities")
                        }
                    }
                }

                item {
                    // Name/icon editing for the active identity used to
                    // live here (IdentityAppearanceRow) — moved to the
                    // Identities screen instead (each identity's own row
                    // there already offers rename + icon editing,
                    // including the active one), per explicit direction:
                    // it was redundant to edit the same thing in two
                    // places. This section is now just what's left that
                    // isn't identity-specific.
                    CollapsibleSection(
                        title = "Appearance",
                        icon = Icons.Filled.Palette,
                        expanded = SettingsSection.APPEARANCE in expandedSections,
                        onToggleExpanded = { toggleSection(SettingsSection.APPEARANCE) },
                    ) {
                        // Real Columba-parity gap closed here — this app was
                        // dark-only before (see Theme.kt's own doc comment
                        // for the light palette this now offers).
                        ThemeModeRow(
                            mode = themeMode,
                            onModeChange = { scope.launch { settingsRepository.setThemeMode(it) } },
                        )
                        TextScaleRow(
                            scale = textScale,
                            onScaleChange = { scope.launch { settingsRepository.setTextScale(it) } },
                        )
                        // No "Permissions" section here anymore — per explicit
                        // direction, that static blurb didn't need to persist
                        // in a settings menu at all. Its content now lives in
                        // the seeded index.mu (_DEFAULT_INDEX in
                        // site_server.py's ">>Permissions" section) instead;
                        // a future "welcome new user" screen (deferred until
                        // closer to release) is the other place this was
                        // considered for.
                    }
                }

                item {
                    // Per explicit request — a real client for rnsh
                    // (github.com/acehoss/rnsh, MIT), tucked away here
                    // rather than given its own bottom-nav real estate,
                    // since it's a power-user feature most users will
                    // never touch. Client-only, deliberately: this
                    // device only ever connects OUT to a remote rnsh
                    // listener someone else runs and controls — see
                    // RnshRepository's own doc comment for the full
                    // reasoning (no listener/server exists here, and
                    // none should ever be added).
                    CollapsibleSection(
                        title = "Advanced",
                        icon = Icons.Filled.Tune,
                        expanded = SettingsSection.ADVANCED in expandedSections,
                        onToggleExpanded = { toggleSection(SettingsSection.ADVANCED) },
                    ) {
                        Text(
                            text = "Remote shell (rnsh)",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                        )
                        Text(
                            text = "Connect out to a remote rnsh listener elsewhere on the " +
                                "mesh. This device never accepts incoming shell sessions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NomadTextDim,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
                        )
                        // Same "summary line + Manage button" shape as
                        // the Identities section above, per explicit
                        // direction — RnshTerminalScreen is already the
                        // real management surface for remembered
                        // sessions (rename/delete on its own idle-state
                        // list), same as it's also where a new
                        // connection gets started.
                        val rnshCount = rnshHistory.size
                        Text(
                            text = if (rnshCount == 0) {
                                "No remembered connections yet"
                            } else {
                                "$rnshCount remembered connection${if (rnshCount == 1) "" else "s"}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = NomadTextDim,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        Button(
                            onClick = onOpenRnshTerminal,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        ) { Text("Manage rnsh sessions") }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        // Columba's own real AdvancedCard groups "transport
                        // node" alongside crash reporting/shared-instance
                        // hosting (verified during this session's own
                        // Columba settings audit) — this app has neither of
                        // those (no telemetry; no shared-RNS-daemon
                        // concept), so transport mode is the one real piece
                        // that applies here, grouped the same way.
                        val transportNodeEnabled by settingsRepository.transportNodeEnabled
                            .collectAsState(initial = false)
                        Text(
                            text = "Transport node",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                        )
                        Text(
                            text = "Act as a relay for other people's mesh traffic, not just " +
                                "your own — helps the mesh as a whole, at the cost of some " +
                                "extra battery/bandwidth. Takes effect after the app restarts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NomadTextDim,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (transportNodeEnabled) "On" else "Off",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = transportNodeEnabled,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        settingsRepository.setTransportNodeEnabled(enabled)
                                        AppRestart.restart(context)
                                    }
                                },
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        // Stale-while-revalidate page cache (per explicit
                        // direction) — see PageCacheStore's and
                        // BrowserScreen's own doc comments for the full
                        // flow this toggle gates.
                        val pageCacheEnabled by settingsRepository.pageCacheEnabled
                            .collectAsState(initial = true)
                        Text(
                            text = "Cache browsed pages",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                        )
                        Text(
                            text = "Show an already-seen page instantly while it re-fetches " +
                                "the current version in the background, instead of a blank " +
                                "spinner every time. Turning this off just stops using the " +
                                "cache — it doesn't delete what's already saved; a panic " +
                                "wipe does.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NomadTextDim,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (pageCacheEnabled) "On" else "Off",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = pageCacheEnabled,
                                onCheckedChange = { enabled ->
                                    scope.launch { settingsRepository.setPageCacheEnabled(enabled) }
                                },
                            )
                        }
                    }
                }

                item {
                    // A single non-collapsible nav row, not a
                    // CollapsibleSection — About is one nav target, not a
                    // details panel with its own content to show/hide.
                    // Still wrapped in the same Card treatment as every
                    // other section below though (icon, bold title,
                    // rounded surfaceVariant background) — a bare flat Row
                    // would look out of place sitting among rounded cards
                    // now that the rest of this list matches Columba's
                    // own real card-per-section shape.
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenAbout)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "About",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            // Custom-drawn, same as BrowserScreen's page viewer — this
            // page is long enough to benefit from the same "how much more
            // is there" cue.
            VerticalScrollIndicator(
                listState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

/** The independently-collapsible top-level sections on [SettingsScreen]'s
 * single scrollable page — see that function's own doc comment for why
 * this isn't an accordion (multiple sections can be open at once). */
private enum class SettingsSection {
    TCP, BLUETOOTH, RNODE, LAN, HOSTING, ANNOUNCE, PRIVACY, VOICE_CALL_PERMISSIONS, NOTIFICATIONS, IDENTITIES, APPEARANCE, ADVANCED
}

/**
 * One collapsible section of [SettingsScreen]'s single scrollable page —
 * a real Material3 [Card] with a leading semantic icon per section, per
 * Columba's own real `CollapsibleSettingsCard.kt` (verified directly
 * against its source, not recalled/invented — this app's own Settings
 * screen was a flat divided list before this pass, a real aesthetic gap
 * closed here per explicit direction). [headerTrailing] renders on the
 * header row itself, to the right of the title — visible even while
 * collapsed, since it's the section's own quick action/status (an enable
 * [Switch]), not part of the expandable detail. Deliberately laid out as
 * two separate tap zones (icon+title vs. [headerTrailing]+chevron)
 * rather than one big clickable row, so tapping a header [Switch]
 * toggles *that interface*, never also expands/collapses the section as
 * a side effect — the chevron itself gets its own [IconButton] for the
 * same reason (matches Columba's own real layout, which separately wraps
 * its chevron rather than leaving it a bare decorative [Icon]).
 *
 * The header row insets to exactly 16dp each side, matching every
 * existing body-content row throughout this file (all already self-pad
 * to 16dp horizontally — confirmed via a full pass over this file, not
 * assumed) — deliberately so, rather than also wrapping [content] in its
 * own 16dp [Card] padding: doing that would double the inset and
 * visibly misalign expanded body rows from the header's own icon/title,
 * undermining the exact polish this redesign is for.
 */
@Composable
private fun CollapsibleSection(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
    headerTrailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onToggleExpanded)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Confirmed via real on-device check on a 320dp-wide
                        // reference emulator: "Local network discovery" wraps
                        // to 2 lines and blows out the header row's height
                        // without this — a header alongside a Switch/chevron
                        // needs to stay one line the way a table cell does.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    headerTrailing()
                    IconButton(onClick = onToggleExpanded) {
                        Icon(
                            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (expanded) {
                Column(content = content)
            }
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
/** [ThemeMode] picker — three [FilterChip]s, same shape as the Type/
 * Network filter-chip rows Network tab's own Announces browser already
 * established. Real Columba-parity gap closed here (see Theme.kt's own
 * doc comment for the light palette this now offers). */
@Composable
private fun ThemeModeRow(mode: ThemeMode, onModeChange: (ThemeMode) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = "Theme", style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                ThemeMode.SYSTEM to "System",
                ThemeMode.LIGHT to "Light",
                ThemeMode.DARK to "Dark",
            ).forEach { (value, label) ->
                FilterChip(
                    selected = mode == value,
                    onClick = { onModeChange(value) },
                    label = { Text(label) },
                )
            }
        }
    }
}

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
 * own doc comment). [onShowQr] (only offered on the LXMF address row —
 * per the Columba-parity-audit's "QR-code identity sharing" finding,
 * the address someone would actually want to scan to add you as a
 * contact) adds a second icon button opening [AddressQrDialog]. */
// No longer private — IdentitiesScreen's own per-identity address
// detail is a second real caller, per this project's own "promote only
// after real reuse" convention. Same package (ui.settings), no import
// needed there.
@Composable
fun AddressRow(
    label: String,
    value: String?,
    placeholder: String = "Not available yet",
    onShowQr: (() -> Unit)? = null,
) {
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
                if (onShowQr != null) {
                    IconButton(onClick = onShowQr) {
                        Icon(Icons.Filled.QrCode, contentDescription = "Show QR code for $label")
                    }
                }
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

/** QR-encoded identity, for another device to scan via
 * [com.jamesm92.nomadportal.ui.components.QrScannerOverlay] (reachable
 * from [com.jamesm92.nomadportal.ui.components.AddByAddressDialog]'s own
 * scan icon). Encodes both [address] and [publicKeyHex] via
 * [com.jamesm92.nomadportal.ui.components.buildIdentityQrPayload]'s real
 * `lxma://` format — not just the address — so the scanning device can
 * register this identity immediately rather than waiting for a real mesh
 * announce first; see that function's own doc comment for the full
 * rationale (confirmed real against Columba's own QR format during a
 * fresh Columba-parity-audit pass, not invented). Generated fresh each
 * time this dialog opens (cheap, no need to cache) via [remember] keyed
 * on the payload so it doesn't regenerate every recomposition. */
// No longer private — same second-real-caller reasoning as AddressRow
// above.
@Composable
fun AddressQrDialog(
    address: String,
    publicKeyHex: String,
    identityHash: String?,
    onDismiss: () -> Unit,
) {
    val payload = remember(address, publicKeyHex) { buildIdentityQrPayload(address, publicKeyHex) }
    val bitmap = remember(payload) { generateQrBitmap(payload).asImageBitmap() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your LXMF Address") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "QR code for $address",
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "LXMF address",
                    style = MaterialTheme.typography.labelSmall,
                    color = NomadTextDim,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Text(
                    text = address,
                    fontFamily = NomadMono,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (identityHash != null) {
                    Text(
                        text = "Identity hash",
                        style = MaterialTheme.typography.labelSmall,
                        color = NomadTextDim,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        text = identityHash,
                        fontFamily = NomadMono,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = "Someone can scan this to add you as a contact, immediately " +
                        "reachable — no need to wait for a mesh announce first.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NomadTextDim,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
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
// Hosted-site actions — moved here from the now-removed Home screen
// (see this file's own top doc comment for why). Everything below this
// point is either HostedSiteActionsRow itself or composables only it
// calls. (Identity name/icon editing used to live here too —
// IdentityAppearanceRow — moved to IdentitiesScreen.kt, per explicit
// direction: it was redundant to edit the same thing in two places.)
// ---------------------------------------------------------------------------

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
        // Real per-request count (SiteServer._record_view), persisted
        // across restarts — per explicit direction ("are we able to set
        // up a view counter for our hosted node"). Singular/plural
        // matches this app's own existing convention elsewhere
        // (rnshCount's "N remembered connection(s)" in the Advanced
        // section below).
        Text(
            text = if (status.totalViews == 1) "1 view" else "${status.totalViews} views",
            style = MaterialTheme.typography.labelSmall,
            color = NomadTextDim,
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
 * own identity isn't one of.
 *
 * No longer private — [IdentitiesScreen]'s own per-identity icon
 * editing is a second real caller, per this project's own "promote only
 * after real reuse" convention. Same package (`ui.settings`), so no
 * import needed there. */
@Composable
fun IdentityIconPreview(
    appearance: ContactIcon.Appearance?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vector = remember(appearance?.glyphName) { appearance?.glyphName?.let(::materialIconFor) }
    // 56dp, not 48dp — real, on-device-reported fix: the edit badge used
    // to sit almost flush against the avatar's own edge (barely offset,
    // since its parent box was only 4dp bigger than the avatar circle
    // itself), and its background color (colorScheme.secondary) could
    // land close to whatever arbitrary color a user picked for the
    // avatar itself (MicronColorPicker allows any RGB, not a palette
    // chosen to contrast against this app's own theme colors), making
    // the badge hard to notice as a distinct, tappable thing. The extra
    // room here plus the .offset below push the badge further outside
    // the avatar's own circle, and the background-colored ring gives it
    // real contrast against any avatar color, not just this app's own
    // theme ones.
    Box(modifier = modifier.size(56.dp).clickable(onClick = onClick)) {
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
                .size(22.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 2.dp, y = 2.dp)
                .clip(CircleShape)
                // A ring in the surrounding page's own background color,
                // not just the badge's fill, is what actually separates
                // it from an avatar color that might otherwise be close
                // to colorScheme.secondary — the same "cutout ring"
                // convention status/notification badges commonly use for
                // exactly this reason.
                .background(MaterialTheme.colorScheme.background)
                .padding(2.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondary),
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
 *
 * No longer private — [IdentitiesScreen]'s own per-identity icon
 * editing is a second real caller, same reasoning as
 * [IdentityIconPreview]'s own doc comment.
 */
@Composable
fun IconAppearanceEditor(
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
    // Real MDI categories (~61, e.g. "Animal", "Weather") — see
    // MdiIconRepository's own doc comment on categoryToNames for where
    // this data actually comes from. Empty (so no chips render at all)
    // until MdiIconRepository finishes loading, same "degrade instead
    // of showing something wrong" contract as allNames' own fallback.
    val categories = remember { MdiIconRepository.categories() }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    val filteredNames = remember(searchQuery, allNames, selectedCategory) {
        val base = selectedCategory?.let { MdiIconRepository.namesInCategory(it) } ?: allNames
        val q = searchQuery.trim().lowercase().replace(' ', '_').replace('-', '_')
        if (q.isBlank()) {
            base
        } else {
            base.filter { it.replace('-', '_').contains(q) }
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
                categories = categories,
                selectedCategory = selectedCategory,
                onCategoryChange = { selectedCategory = it },
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
    categories: List<String>,
    selectedCategory: String?,
    onCategoryChange: (String?) -> Unit,
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

                // Real MDI categories, not a hand-picked subset — see
                // MdiIconRepository's own doc comment. Horizontally
                // scrolling row of chips (61 real categories won't all
                // fit on screen at once), "All" first so clearing the
                // filter is always in the same reachable place. Combines
                // with the search box above rather than replacing it —
                // picking a category narrows what search then filters
                // further, same relationship Network tab's own real
                // Announces browser already established between its
                // filter chips and search field.
                if (categories.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = selectedCategory == null,
                                onClick = { onCategoryChange(null) },
                                label = { Text("All") },
                            )
                        }
                        items(categories, key = { it }) { category ->
                            FilterChip(
                                selected = category == selectedCategory,
                                onClick = {
                                    onCategoryChange(if (category == selectedCategory) null else category)
                                },
                                label = { Text(category) },
                            )
                        }
                    }
                }

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


