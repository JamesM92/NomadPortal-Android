package com.jamesm92.nomadportal.ui.network

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.connectivity.BluetoothMeshStatus
import com.jamesm92.nomadportal.connectivity.InterfaceController
import com.jamesm92.nomadportal.connectivity.TcpConnection
import com.jamesm92.nomadportal.connectivity.TcpConnectionsRepository
import com.jamesm92.nomadportal.data.browsing.BrowserRepository
import com.jamesm92.nomadportal.data.browsing.NodeInfo
import com.jamesm92.nomadportal.data.calling.CallRepository
import com.jamesm92.nomadportal.data.messaging.AnnounceStatus
import com.jamesm92.nomadportal.data.messaging.ConversationSummary
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.ui.browser.NodeRow
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.SearchField
import com.jamesm92.nomadportal.ui.components.SortDropdown
import com.jamesm92.nomadportal.ui.components.SortOption
import com.jamesm92.nomadportal.ui.components.dismissKeyboardOnTap
import com.jamesm92.nomadportal.ui.components.rememberStableOrder
import com.jamesm92.nomadportal.ui.messages.ConversationRow
import com.jamesm92.nomadportal.ui.theme.NomadAccent2
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import com.jamesm92.nomadportal.ui.theme.NomadWarn
import kotlinx.coroutines.launch

/**
 * Interface/connection status, plus — per explicit direction, a Columba
 * UI/UX parity-audit follow-up ("i want the network section to emulate
 * what columba is doing with their contacts section, minus the my
 * contact section, where you can filter all the announces by network,
 * type, and search, and sort") — a unified, filterable/sortable/
 * searchable browser over every announce heard (LXMF peers and NomadNet
 * nodes together), modeled on Columba's real Contacts screen's own
 * `NETWORK` sub-tab (confirmed against its actual source — a
 * `ContactsTab.MY_CONTACTS`/`ContactsTab.NETWORK` split with expandable
 * filter chips, search, and an announce action on the Network half).
 * This is **additive**, not a replacement — Sites/Messages keep their
 * own simple Favorites+Announces-heard/Chats+Users sections exactly as
 * they already do (an earlier attempt to move those out entirely was
 * tried and explicitly reverted this same session: "keep the announces
 * in the sites and messages tabs").
 *
 * Two filter dimensions were requested — "network" and "type":
 * - **Type** (Peers / Sites / All) is real and implemented — it's
 *   exactly the LXMF-peer-vs-NomadNet-node distinction this app already
 *   tracks natively.
 * - **Network** (i.e. filtering by *which interface* currently has the
 *   best path to a given announce) is now also real and implemented —
 *   [InterfaceController.announceInterfaces] reads
 *   `RNS.Transport.next_hop_interface()`, a real, already-existing
 *   public RNS API against state RNS's own routing already maintains
 *   (`Transport.path_table`). This corrects an earlier, real mistake in
 *   this same file's own doc comment: "not buildable without new
 *   tracking plumbing" was wrong — confirmed directly against RNS's
 *   installed source during a fresh Columba-parity-audit pass, not
 *   guessed. **Honest limitation that remains**: it's a *live* snapshot
 *   each poll, not a history — it reflects whichever interface
 *   currently has the best known path right now, and doesn't remember
 *   "also seen via a different interface once" the way a real
 *   interface-sighting-history table would (confirmed real in Columba's
 *   own schema during that same pass — a genuinely bigger feature, not
 *   attempted here).
 *
 * Interface status itself keeps its original scope/honesty rules — see
 * [BluetoothMeshStatus]'s own doc comment for what "neighbor" does and
 * doesn't prove, and the top caveat text below for what's live vs.
 * toggle-only. Toggles themselves stay in Settings, not here.
 */
@Composable
fun NetworkScreen(
    interfaceController: InterfaceController,
    tcpConnectionsRepository: TcpConnectionsRepository,
    messagingRepository: MessagingRepository,
    callRepository: CallRepository,
    browserRepository: BrowserRepository,
    onOpenConversation: (contactHash: String) -> Unit,
    onOpenNode: (nodeHash: String) -> Unit,
) {
    val tcpEnabled by interfaceController.tcpEnabled.collectAsState()
    val bluetoothMeshEnabled by interfaceController.bluetoothMeshEnabled.collectAsState()
    val rNodeEnabled by interfaceController.rNodeEnabled.collectAsState()
    val wifiDiscoveryEnabled by interfaceController.wifiDiscoveryEnabled.collectAsState()
    val tcpConnections by tcpConnectionsRepository.connections().collectAsState(initial = emptyList())
    val bluetoothMeshStatus by interfaceController.bluetoothMeshStatus()
        .collectAsState(initial = BluetoothMeshStatus(neighborCount = 0, lastActivityAtMillis = null))

    // Both default collapsed except Announces — per a real on-device
    // report ("a bunch of the hard written network stats at the top
    // making it impossible to scroll and see the network search
    // tools"): the interface-status block below used to always render
    // fully expanded, with no way to scroll past it (the outer Column
    // itself doesn't scroll — only Announces' own inner LazyColumn
    // does), so on a device where that fixed content is tall enough it
    // could crowd out — or entirely hide — Announces' search/sort/filter
    // tools with no way to reach them. Wrapping Interfaces in the same
    // collapsible pattern Announces already uses, and defaulting it
    // closed, means Announces (what a user opening this tab most likely
    // wants) always gets the screen by default; Interfaces is still one
    // tap away, not removed.
    var interfacesOpen by remember { mutableStateOf(false) }
    var announcesOpen by remember { mutableStateOf(true) }

    Scaffold(
        topBar = { AdaptiveTopAppBar(title = { Text("Network") }) },
    ) { innerPadding ->
        // Plain Column, not a LazyColumn — interface status below is a
        // short, fixed set of rows that never needs to scroll on its
        // own. Announces is the one section with real scale (thousands
        // of heard peers/sites is normal), so it alone gets a real
        // LazyColumn with weight(1f) inside AnnouncesSectionBody, same
        // "only the genuinely large list is lazy" shape
        // ConversationListScreen/NodeListScreen already use for their
        // own Favorites/Announces-heard sections. A real on-device
        // OutOfMemoryError crash (found via this session's own testing,
        // not assumed) came from an earlier version of this screen
        // rendering all ~2800 combined announces as plain, non-lazy
        // Compose nodes at once — don't reintroduce that.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .dismissKeyboardOnTap(),
        ) {
            ExpandableSectionHeader(
                title = "Interfaces",
                count = null,
                expanded = interfacesOpen,
                onToggle = { interfacesOpen = !interfacesOpen },
            )
            if (interfacesOpen) {
                Text(
                    text = "Live per-connection status is available for TCP and Bluetooth mesh. " +
                        "RNode and local network discovery show on/off state only. Toggles live in Settings.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NomadTextDim,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()

                InterfaceStatusRow(
                    label = "TCP",
                    enabled = tcpEnabled,
                    detail = if (!tcpEnabled) {
                        null
                    } else if (tcpConnections.isEmpty()) {
                        "No connections configured"
                    } else {
                        val online = tcpConnections.count { it.enabled && it.online }
                        val configured = tcpConnections.count { it.enabled }
                        "$online of $configured connections online"
                    },
                )
                if (tcpEnabled) {
                    // Plain forEach, not lazy — a user-configured connection
                    // list realistically stays small (single digits), unlike
                    // the Announces section below.
                    tcpConnections.forEach { connection -> TcpConnectionStatusRow(connection) }
                }
                HorizontalDivider()

                InterfaceStatusRow(
                    label = "Bluetooth mesh",
                    enabled = bluetoothMeshEnabled,
                    detail = if (!bluetoothMeshEnabled) {
                        null
                    } else if (bluetoothMeshStatus.neighborCount == 0) {
                        "On · no neighbors seen yet"
                    } else {
                        "On · ${bluetoothMeshStatus.neighborCount} neighbor" +
                            (if (bluetoothMeshStatus.neighborCount == 1) "" else "s") +
                            " seen · last ${formatRelativeTime(bluetoothMeshStatus.lastActivityAtMillis)}"
                    },
                )
                HorizontalDivider()

                InterfaceStatusRow(label = "RNode", enabled = rNodeEnabled)
                HorizontalDivider()

                InterfaceStatusRow(label = "Local network discovery", enabled = wifiDiscoveryEnabled)
                HorizontalDivider()
            }

            AnnouncesSection(
                interfaceController = interfaceController,
                messagingRepository = messagingRepository,
                callRepository = callRepository,
                browserRepository = browserRepository,
                onOpenConversation = onOpenConversation,
                onOpenNode = onOpenNode,
                expanded = announcesOpen,
                onToggleExpanded = { announcesOpen = !announcesOpen },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun InterfaceStatusRow(label: String, enabled: Boolean, detail: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(color = if (enabled) NomadAccent2 else NomadTextDim)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail ?: if (enabled) "On" else "Off",
                style = MaterialTheme.typography.bodySmall,
                color = NomadTextDim,
            )
        }
    }
}

/** Sub-row under TCP for each configured connection — same read-only
 * status framing as [InterfaceStatusRow], but a 3rd color: amber for
 * "enabled but not currently online," matching [NomadWarn]'s established
 * "problem worth flagging, not necessarily an error" role elsewhere
 * (e.g. NodeListScreen's own half-filled fetch-status dot). */
@Composable
private fun TcpConnectionStatusRow(connection: TcpConnection) {
    val color = when {
        !connection.enabled -> NomadTextDim
        connection.online -> NomadAccent2
        else -> NomadWarn
    }
    val status = when {
        !connection.enabled -> "Disabled"
        connection.online -> "Online"
        else -> "Not connected"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 44.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(color = color, size = 8.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = connection.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${connection.host}:${connection.port} · $status",
                style = MaterialTheme.typography.labelSmall,
                color = NomadTextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusDot(color: Color, size: androidx.compose.ui.unit.Dp = 10.dp) {
    Canvas(modifier = Modifier.size(size).clip(CircleShape)) {
        drawCircle(color = color)
    }
}

/** Same bucketing convention as every other screen's own local copy of
 * this (NodeListScreen/ConversationListScreen/Settings' hosted-site row)
 * — kept local rather than shared since each has its own "never/null"
 * fallback tailored to what it's describing. [millis] null here means
 * "no neighbor sighting since Bluetooth mesh last started," distinct
 * from those other screens' "never heard an announce at all." */
private fun formatRelativeTime(millis: Long?): String {
    if (millis == null) return "n/a"
    val diffSeconds = ((System.currentTimeMillis() - millis) / 1000).coerceAtLeast(0)
    return when {
        diffSeconds < 60 -> "just now"
        diffSeconds < 3600 -> "${diffSeconds / 60}m ago"
        diffSeconds < 86_400 -> "${diffSeconds / 3600}h ago"
        else -> "${diffSeconds / 86_400}d ago"
    }
}

/** Same convention as NodeListScreen's/ConversationListScreen's own
 * formatRelativeTime — `<= 0` means "never heard an announce at all"
 * (NodeInfo/Contact's own sentinel), distinct from [formatRelativeTime]
 * above (Bluetooth mesh neighbor activity, a `Long?` where null is the
 * "nothing yet" case instead). */
private fun formatAnnounceTime(millis: Long): String {
    if (millis <= 0L) return "never heard"
    val diffSeconds = ((System.currentTimeMillis() - millis) / 1000).coerceAtLeast(0)
    return when {
        diffSeconds < 60 -> "just now"
        diffSeconds < 3600 -> "${diffSeconds / 60}m ago"
        diffSeconds < 86_400 -> "${diffSeconds / 3600}h ago"
        diffSeconds < 2_592_000 -> "${diffSeconds / 86_400}d ago"
        else -> "${diffSeconds / 2_592_000}mo ago"
    }
}

/** [key] is one of [AnnounceStatus]'s own interface-key constants (or
 * null, meaning RNS currently has no known path to this destination at
 * all) — see [InterfaceController.announceInterfaces]'s own doc comment
 * for why "no path known" is a real, honest, expected state, not an
 * error. */
private fun formatNetworkLabel(key: String?): String = when (key) {
    AnnounceStatus.INTERFACE_TCP -> "TCP"
    AnnounceStatus.INTERFACE_BLUETOOTH -> "Bluetooth mesh"
    AnnounceStatus.INTERFACE_RNODE -> "RNode"
    AnnounceStatus.INTERFACE_WIFI_DISCOVERY -> "Local network discovery"
    else -> "No path known"
}

private enum class AnnounceTypeFilter(val label: String) {
    ALL("All"), PEERS("Peers"), SITES("Sites")
}

/** The network/interface filter dimension — see this file's own top doc
 * comment for the real mechanism ([InterfaceController.announceInterfaces])
 * and its one honest limitation (a live snapshot, not history). [key]
 * null means [ALL] (no filtering); every other option's [key] matches
 * one of [com.jamesm92.nomadportal.data.messaging.AnnounceStatus]'s own
 * interface-key constants. */
private enum class NetworkFilter(val key: String?, val label: String) {
    ALL(null, "All"),
    TCP(AnnounceStatus.INTERFACE_TCP, "TCP"),
    BLUETOOTH(AnnounceStatus.INTERFACE_BLUETOOTH, "Bluetooth"),
    RNODE(AnnounceStatus.INTERFACE_RNODE, "RNode"),
    WIFI(AnnounceStatus.INTERFACE_WIFI_DISCOVERY, "Local"),
}

/** One row in [NetworkScreen]'s unified announces browser — either an
 * LXMF peer or a NomadNet node, wrapped so both can share one filtered/
 * sorted/searched list and one row-tap contract (open technical info,
 * not navigate away — see this file's own top doc comment for why that
 * differs from Sites'/Messages' own row taps). */
private sealed class AnnounceItem {
    abstract val displayName: String
    abstract val hash: String
    abstract val hopCount: Int
    abstract val lastAnnounceMillis: Long
    abstract val announceCount: Int

    data class Peer(val summary: ConversationSummary) : AnnounceItem() {
        override val displayName get() = summary.contact.displayName
        override val hash get() = summary.contact.lxmfHash
        override val hopCount get() = summary.contact.hopCount
        override val lastAnnounceMillis get() = summary.contact.lastAnnounceMillis
        override val announceCount get() = summary.contact.announceCount
    }

    data class Site(val node: NodeInfo) : AnnounceItem() {
        override val displayName get() = node.displayName
        override val hash get() = node.hash
        override val hopCount get() = node.hopCount
        override val lastAnnounceMillis get() = node.lastAnnounceMillis
        override val announceCount get() = node.announceCount
    }
}

/**
 * A plain `@Composable`, not a `LazyListScope` extension — [NetworkScreen]
 * itself is now a plain `Column`, and this section is the one place that
 * needs real laziness (thousands of combined announces is normal scale),
 * so [AnnouncesSectionBody] owns its own inner `LazyColumn` rather than
 * this contributing items into an outer one. [modifier] is expected to
 * carry `Modifier.weight(1f)` from the caller's `ColumnScope` so the body
 * — when expanded — can actually fill and scroll the remaining space;
 * when collapsed only the fixed-size header renders, so the weight is
 * effectively inert. That inner `LazyColumn` carries the search/sort row
 * and both filter-chip rows as its own leading `item{}`s, not fixed
 * content above it — see [AnnouncesSectionBody]'s own doc comment for
 * why (a real on-device report: those controls used to be permanently
 * pinned, eating vertical space results could otherwise use).
 */
@Composable
private fun AnnouncesSection(
    interfaceController: InterfaceController,
    messagingRepository: MessagingRepository,
    callRepository: CallRepository,
    browserRepository: BrowserRepository,
    onOpenConversation: (contactHash: String) -> Unit,
    onOpenNode: (nodeHash: String) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        val conversations by messagingRepository.conversations().collectAsState(initial = emptyList())
        val nodes by browserRepository.discoveredNodes().collectAsState(initial = emptyList())
        ExpandableSectionHeader(
            title = "Announces",
            count = conversations.size + nodes.size,
            expanded = expanded,
            onToggle = onToggleExpanded,
        )
        if (expanded) {
            AnnouncesSectionBody(
                interfaceController = interfaceController,
                messagingRepository = messagingRepository,
                callRepository = callRepository,
                browserRepository = browserRepository,
                onOpenConversation = onOpenConversation,
                onOpenNode = onOpenNode,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ExpandableSectionHeader(title: String, count: Int?, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (count != null) "$title ($count)" else title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = NomadTextDim,
        )
    }
}

/** Search/sort row + both filter-chip rows (Type, Network) live inside
 * this composable's own `LazyColumn` as leading `item{}`s, scrolling
 * away with the results below them — not fixed content pinned above a
 * separately-scrolling list, per a real on-device report ("the filter
 * section needs to be part of the scrollable section so there is more
 * room on the screen for the results"). Each filter-chip row is itself
 * a `LazyRow`, not a plain `Row` — a fixed `Row` let chip labels wrap
 * ("Local" rendering as "Loc"/"al" on a narrower screen) instead of
 * scrolling horizontally, per the same report. */
@Composable
private fun AnnouncesSectionBody(
    interfaceController: InterfaceController,
    messagingRepository: MessagingRepository,
    callRepository: CallRepository,
    browserRepository: BrowserRepository,
    onOpenConversation: (contactHash: String) -> Unit,
    onOpenNode: (nodeHash: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val conversations by messagingRepository.conversations().collectAsState(initial = emptyList())
    val nodes by browserRepository.discoveredNodes().collectAsState(initial = emptyList())
    // Live "which interface currently has the best path" lookup — see
    // InterfaceController.announceInterfaces' own doc comment for what
    // this does/doesn't prove. Keyed by hash; a hash with no entry has
    // no currently-known path at all.
    val announceInterfaces by interfaceController.announceInterfaces().collectAsState(initial = emptyMap())
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(SortOption.RECENT) }
    var typeFilter by remember { mutableStateOf(AnnounceTypeFilter.ALL) }
    var networkFilter by remember { mutableStateOf(NetworkFilter.ALL) }
    var infoTarget by remember { mutableStateOf<AnnounceItem?>(null) }

    val combined: List<AnnounceItem> = remember(conversations, nodes, typeFilter) {
        buildList {
            if (typeFilter != AnnounceTypeFilter.SITES) addAll(conversations.map { AnnounceItem.Peer(it) })
            if (typeFilter != AnnounceTypeFilter.PEERS) addAll(nodes.map { AnnounceItem.Site(it) })
        }
    }
    val searched = if (searchQuery.isBlank()) {
        combined
    } else {
        combined.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) || it.hash.contains(searchQuery, ignoreCase = true)
        }
    }
    val filtered = if (networkFilter == NetworkFilter.ALL) {
        searched
    } else {
        searched.filter { announceInterfaces[it.hash] == networkFilter.key }
    }
    val sorted = when (sortOption) {
        SortOption.RECENT -> filtered.sortedByDescending { it.lastAnnounceMillis }
        SortOption.ALPHABETICAL -> filtered.sortedBy { it.displayName.lowercase() }
        SortOption.HOPS -> filtered.sortedBy { if (it.hopCount < 0) Int.MAX_VALUE else it.hopCount }
        SortOption.ANNOUNCES -> filtered.sortedByDescending { it.announceCount }
    }
    val displayed = rememberStableOrder(sorted, key = { it.hash + (if (it is AnnounceItem.Peer) ":peer" else ":site") })

    fun toggleFavorite(item: AnnounceItem) {
        scope.launch {
            try {
                when (item) {
                    is AnnounceItem.Peer -> messagingRepository.setFavorite(item.hash, !item.summary.contact.isFavorite)
                    is AnnounceItem.Site -> browserRepository.setFavorite(item.hash, !item.node.isFavorite)
                }
            } catch (e: Exception) {
                // Not rethrown — matches every other favorite-toggle in this app.
            }
        }
    }

    // One LazyColumn for the whole section, not a fixed Column of
    // controls above a separately-scrolling list — per a real on-device
    // report ("the filter section needs to be part of the scrollable
    // section so there is more room on the screen for the results"):
    // search/sort + both filter-chip rows used to be permanently pinned
    // above the list, eating vertical space that could go to actual
    // results. Putting them in their own `item{}`s at the top of the
    // same LazyColumn lets them scroll away with the rest of the
    // content instead. `displayed` itself still can run into the
    // thousands (a real OutOfMemoryError crash was found on-device with
    // ~2850 combined items when this used a plain forEach) — mixing a
    // handful of fixed-size items ahead of `items(displayed, ...)` in
    // one LazyColumn doesn't reintroduce that; only a genuinely
    // per-row-eager pattern would.
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = "Search announces",
                    modifier = Modifier.weight(1f),
                )
                SortDropdown(selected = sortOption, onSelect = { sortOption = it })
            }
        }
        item {
            // LazyRow, not a plain Row — per the same on-device report:
            // a fixed Row let chip labels wrap/squash ("Local" rendering
            // as "Loc/al" on a narrower screen) instead of scrolling.
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(AnnounceTypeFilter.entries.toList()) { option ->
                    FilterChip(
                        selected = typeFilter == option,
                        onClick = { typeFilter = option },
                        label = { Text(option.label, maxLines = 1) },
                    )
                }
            }
        }
        item {
            // Second filter-chip row, same "one row per dimension" shape
            // as Columba's own real AnnounceFilterChips (confirmed
            // against its source) — Type above, Network here.
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(NetworkFilter.entries.toList()) { option ->
                    FilterChip(
                        selected = networkFilter == option,
                        onClick = { networkFilter = option },
                        label = { Text(option.label, maxLines = 1) },
                    )
                }
            }
        }
        items(
            displayed,
            key = { it.hash + (if (it is AnnounceItem.Peer) ":peer" else ":site") },
        ) { item ->
            when (item) {
                is AnnounceItem.Peer -> ConversationRow(
                    summary = item.summary,
                    onClick = { infoTarget = item },
                    onToggleFavorite = { toggleFavorite(item) },
                    onCall = { scope.launch { callRepository.placeCall(item.hash) } },
                    onMarkUnread = {
                        scope.launch {
                            try {
                                messagingRepository.markUnread(item.hash)
                            } catch (e: Exception) {
                                // Not rethrown — matches every other action in this section.
                            }
                        }
                    },
                    onToggleBlock = {
                        scope.launch {
                            try {
                                messagingRepository.setBlocked(item.hash, !item.summary.contact.isBlocked)
                            } catch (e: Exception) {
                                // Not rethrown — matches every other action in this section.
                            }
                        }
                    },
                )
                is AnnounceItem.Site -> NodeRow(
                    node = item.node,
                    onClick = { infoTarget = item },
                    onToggleFavorite = { toggleFavorite(item) },
                )
            }
            HorizontalDivider()
        }
    }

    infoTarget?.let { item ->
        AnnounceTechnicalInfoDialog(
            item = item,
            networkKey = announceInterfaces[item.hash],
            onDismiss = { infoTarget = null },
            onOpen = {
                infoTarget = null
                when (item) {
                    is AnnounceItem.Peer -> onOpenConversation(item.hash)
                    is AnnounceItem.Site -> onOpenNode(item.hash)
                }
            },
        )
    }
}

/**
 * Technical detail for one announce, per explicit direction ("the
 * network tab is for in depth analyses, stats and info for each
 * announce... clicking a site brings up its technical info with the
 * option of going to the site... clicking a user in network brings
 * their technical info") — Network's row tap opens this instead of
 * navigating straight to the chat/site the way Sites'/Messages' own row
 * taps still do.
 */
@Composable
private fun AnnounceTechnicalInfoDialog(
    item: AnnounceItem,
    networkKey: String?,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoRow("Type", if (item is AnnounceItem.Peer) "LXMF peer" else "NomadNet site")
                InfoRow("Address", item.hash)
                InfoRow("Network", formatNetworkLabel(networkKey))
                InfoRow("Hops", if (item.hopCount < 0) "Unknown" else item.hopCount.toString())
                InfoRow("Last announce", formatAnnounceTime(item.lastAnnounceMillis))
                InfoRow("Announces heard", item.announceCount.toString())
                when (item) {
                    is AnnounceItem.Peer -> {
                        InfoRow("Call-capable", if (item.summary.contact.isCallCapable) "Yes" else "No")
                        InfoRow("Favorite", if (item.summary.contact.isFavorite) "Yes" else "No")
                        InfoRow("Blocked", if (item.summary.contact.isBlocked) "Yes" else "No")
                    }
                    is AnnounceItem.Site -> {
                        InfoRow(
                            "Last fetch",
                            when (item.node.lastFetchOk) {
                                true -> "OK"
                                false -> if (item.node.everFetchOk) "Failed (worked before)" else "Failed (never worked)"
                                null -> "Never fetched"
                            },
                        )
                        InfoRow("Favorite", if (item.node.isFavorite) "Yes" else "No")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpen) {
                Text(if (item is AnnounceItem.Peer) "Open Chat" else "Go to Site")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    // value gets Modifier.weight(1f) — real, on-device-reported bug this
    // fixes: without it, a long value (the real 32-hex-char Address
    // field is the one that actually hits this) had no defined width to
    // wrap against, so it wrapped unevenly/cramped rather than using the
    // row's true remaining space (row width minus the label's own
    // intrinsic width) the way weight correctly computes. textAlign.End
    // keeps the same "label ... value" reading order once it wraps to
    // more than one line. Short values (the common case — "Yes"/"No"/
    // "TCP"/etc.) are unaffected: weight(1f) just gives them more room
    // than they need, right-aligned same as before.
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = NomadTextDim)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}
