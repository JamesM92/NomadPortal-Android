package com.jamesm92.nomadportal.ui.browser

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.browsing.BrowserRepository
import com.jamesm92.nomadportal.data.browsing.NodeInfo
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.panicwipe.PanicWipe
import com.jamesm92.nomadportal.ui.components.AddByAddressDialog
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.MessagesIconWithBadge
import com.jamesm92.nomadportal.ui.components.PanicWipeLogo
import com.jamesm92.nomadportal.ui.components.SearchField
import com.jamesm92.nomadportal.ui.components.SortDropdown
import com.jamesm92.nomadportal.ui.components.SortOption
import com.jamesm92.nomadportal.ui.components.dismissKeyboardOnTap
import com.jamesm92.nomadportal.ui.components.rememberStableOrder
import com.jamesm92.nomadportal.ui.theme.NomadAccent2
import com.jamesm92.nomadportal.ui.theme.NomadError
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import kotlinx.coroutines.launch

/**
 * Discovered-node list (porting-notes.md §4): hop count, last-fetch-ok/fail
 * indicator, favorites, time since last announce. Real RNS
 * announce-listening as of Aug 2026 — see
 * [com.jamesm92.nomadportal.data.browsing.RealBrowserRepository]. Split
 * into two sections — favorited nodes first, then everything heard on
 * the mesh (favoriting adds a copy here, it doesn't remove the node from
 * "Announces heard") — so a busy hub's announce volume doesn't bury the
 * handful of nodes someone actually cares about. Both section headers
 * live directly in the outer [Column], not inside either section's own
 * [LazyColumn] — so they're always visible above their (independently
 * scrolling) content without needing `LazyColumn.stickyHeader` at all
 * (tried first; real device testing showed it not actually staying
 * pinned once scrolled past — this sidesteps the whole mechanism rather
 * than chasing it further). Exactly one section is ever expanded at a
 * time — see [com.jamesm92.nomadportal.ui.messages.ConversationListScreen]'s
 * identical `favoritesOpen` pattern/doc comment, mirrored here per
 * explicit follow-up request once that fix landed on the Messages
 * screen first.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeListScreen(
    repository: BrowserRepository,
    messagingRepository: MessagingRepository,
    onOpenNode: (nodeHash: String) -> Unit,
    onBack: () -> Unit,
    onOpenMessages: () -> Unit,
) {
    val nodes by repository.discoveredNodes().collectAsState(initial = emptyList())
    // Powers the Messages cross-nav icon's unread badge — same source
    // ConversationListScreen itself reads, just summed here.
    val conversations by messagingRepository.conversations().collectAsState(initial = emptyList())
    val totalUnread = conversations.sumOf { it.unreadCount }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var showAddByAddress by remember { mutableStateOf(false) }
    var sortOption by remember { mutableStateOf(SortOption.RECENT) }

    // Optimistic favorite toggling — same fix/rationale as
    // ConversationListScreen's identical `favoriteOverrides`: discoveredNodes()
    // is a multi-second poll, so without this a favorite tap could take
    // several seconds to visibly update (a real on-device report —
    // "clicking the favorites hearts needs to be more instant").
    var favoriteOverrides by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    LaunchedEffect(nodes) {
        if (favoriteOverrides.isEmpty()) return@LaunchedEffect
        val stillPending = favoriteOverrides.filter { (hash, wanted) ->
            nodes.find { it.hash == hash }?.isFavorite != wanted
        }
        if (stillPending.size != favoriteOverrides.size) favoriteOverrides = stillPending
    }
    val effectiveNodes = remember(nodes, favoriteOverrides) {
        if (favoriteOverrides.isEmpty()) {
            nodes
        } else {
            nodes.map { node ->
                val wanted = favoriteOverrides[node.hash]
                if (wanted != null && wanted != node.isFavorite) node.copy(isFavorite = wanted) else node
            }
        }
    }
    fun toggleFavorite(node: NodeInfo) {
        val wanted = !node.isFavorite
        favoriteOverrides = favoriteOverrides + (node.hash to wanted)
        scope.launch {
            try {
                repository.setFavorite(node.hash, wanted)
            } catch (e: Exception) {
                // Self-corrects via the pruning LaunchedEffect above on
                // the next poll — see ConversationListScreen's identical
                // comment for why this isn't rethrown.
            }
        }
    }

    // Filters name and hash (a user may search by either) — applied
    // before the Favorites/Announces-heard split so a search still
    // respects that split, not a flat re-merged result.
    val filteredNodes = if (searchQuery.isBlank()) {
        effectiveNodes
    } else {
        effectiveNodes.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                it.hash.contains(searchQuery, ignoreCase = true)
        }
    }
    // browser.py's get_nodes() sorts favorited nodes to the front (a
    // deliberate tie-break ahead of recency, for the original app's one
    // combined sidebar) — re-sorted here per the user's own chosen
    // SortOption instead, so favoriting a node doesn't also reorder it
    // within Announces heard (favorite status is already shown via its
    // own section + the heart icon, not by bubbling it to the top of
    // this one too), and both sections share one consistent order.
    val sortedNodes = when (sortOption) {
        SortOption.RECENT -> filteredNodes.sortedByDescending { it.lastAnnounceMillis }
        SortOption.ALPHABETICAL -> filteredNodes.sortedBy { it.displayName.lowercase() }
        SortOption.HOPS -> filteredNodes.sortedBy { if (it.hopCount < 0) Int.MAX_VALUE else it.hopCount }
        SortOption.ANNOUNCES -> filteredNodes.sortedByDescending { it.announceCount }
    }

    // Favoriting adds a copy to Favorites, it doesn't move the node out
    // of Announces heard — a favorited node is still a node you've
    // heard announce, so it stays listed there too (per explicit
    // request; the two sections are not a mutually-exclusive partition).
    // rememberStableOrder freezes each row's screen position against
    // reorders from a live-updating sort key (RECENT/ANNOUNCES) between
    // polls — see that function's own doc comment for the real mis-tap
    // repro this fixes.
    val favorites = rememberStableOrder(sortedNodes.filter { it.isFavorite }, key = { it.hash })
    val announcesHeard = rememberStableOrder(sortedNodes, key = { it.hash })

    // Exactly one of Favorites/Announces heard is ever open — see this
    // file's own doc comment / ConversationListScreen's identical
    // `favoritesOpen`: opening one always closes the other, closing one
    // always opens the other. Both headers just flip this single flag.
    var favoritesOpen by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = { Text("Nodes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Cross-nav to the other list screen — per explicit
                    // request, present here (a list/hub screen) but
                    // deliberately NOT on BrowserScreen (viewing one
                    // specific node's page): that screen already has its
                    // own back arrow to return here, and its address bar
                    // row is busy enough without more icons crowding it.
                    // Settings is deliberately NOT here either — per
                    // explicit request, only reachable from the main
                    // menu (Home).
                    MessagesIconWithBadge(unreadCount = totalUnread, onClick = onOpenMessages)
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
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .dismissKeyboardOnTap(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = "Search nodes",
                    modifier = Modifier.weight(1f),
                )
                // Search only finds already-discovered nodes (a live
                // announce heard) — this is the companion entry point for
                // a hash you already know but haven't seen announce yet.
                IconButton(onClick = { showAddByAddress = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Go to address")
                }
                SortDropdown(selected = sortOption, onSelect = { sortOption = it })
            }

            // Header always outside any LazyColumn — always visible,
            // always tappable. Exactly one section is ever expanded (see
            // favoritesOpen's own doc comment above), so the expanded
            // one always gets the full remaining space.
            SectionHeader(
                title = "Favorites",
                count = favorites.size,
                expanded = favoritesOpen,
                onToggle = { favoritesOpen = !favoritesOpen },
            )
            if (favoritesOpen) {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(favorites, key = { it.hash }) { node ->
                        NodeRow(
                            node = node,
                            onClick = { onOpenNode(node.hash) },
                            onToggleFavorite = { toggleFavorite(node) },
                        )
                        HorizontalDivider()
                    }
                }
            }

            HorizontalDivider()

            SectionHeader(
                title = "Announces heard",
                count = announcesHeard.size,
                expanded = !favoritesOpen,
                onToggle = { favoritesOpen = !favoritesOpen },
            )
            if (!favoritesOpen) {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(announcesHeard, key = { it.hash }) { node ->
                        NodeRow(
                            node = node,
                            onClick = { onOpenNode(node.hash) },
                            onToggleFavorite = { toggleFavorite(node) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showAddByAddress) {
        AddByAddressDialog(
            title = "Go to node address",
            onDismiss = { showAddByAddress = false },
            onConfirm = { hash ->
                showAddByAddress = false
                // Per explicit request: an address entered by hand is
                // one you already specifically care about, unlike one
                // merely discovered via announce — auto-favorite it
                // rather than requiring a separate follow-up tap.
                // setFavorite upserts a node entry if one doesn't exist
                // yet (see browser.py's own doc comment), so this is
                // safe even for a hash never seen announce from before.
                scope.launch { repository.setFavorite(hash, true) }
                onOpenNode(hash)
            },
        )
    }
}

/** [collapsible] = false renders a count-only label with no chevron and
 * no click target. Not currently used by [NodeListScreen] (both its
 * sections are always tappable — see `favoritesOpen`'s doc comment) —
 * kept for parity with [com.jamesm92.nomadportal.ui.messages.ConversationListScreen]'s
 * identical `SectionHeader`, which still needs it for the Users tab's
 * single non-collapsible section. */
@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    collapsible: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (collapsible) Modifier.clickable(onClick = onToggle) else Modifier)
            // Tighter than before (was 12.dp) — this is a section
            // divider, not a screen title, doesn't need that much room.
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "$title ($count)",
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = MaterialTheme.typography.titleLarge.fontSize * 0.85f,
            ),
            color = MaterialTheme.colorScheme.secondary,
        )
        if (collapsible) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = NomadTextDim,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun NodeRow(node: NodeInfo, onClick: () -> Unit, onToggleFavorite: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onClick)
            // Tighter than before (was 12.dp) — a HorizontalDivider
            // between rows now provides visual separation, so padding
            // alone doesn't need to carry that job too.
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FetchStatusDot(node.lastFetchOk)
        Column(modifier = Modifier.weight(1f)) {
            // Sizes are fractions of the theme's own bodyLarge size, not
            // fixed sp values — so this row's relative "smaller than
            // normal body text" density holds regardless of the user's
            // Settings → text size multiplier (NomadPortalTheme's
            // textScale), rather than needing its own separate setting.
            Text(
                text = node.displayName,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.85f,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // hopCount == -1 is RealBrowserRepository's "unknown hop
                // count" sentinel (browser.py's `hops` is nullable —
                // NodeInfo.hopCount isn't) — shown as "? hops" rather
                // than the misleading "-1 hops".
                text = "${if (node.hopCount < 0) "?" else node.hopCount.toString()} hop${if (node.hopCount == 1) "" else "s"}" +
                    " · ${formatRelativeTime(node.lastAnnounceMillis)} · ${node.hash.take(8)}…",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.7f,
                ),
                color = NomadTextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (node.isHosted || node.isDefault) {
            // browser.py forces favorited=true server-side for these two
            // and set_favorite() no-ops on the hosted node's own index —
            // there's nothing to toggle. A normal tappable IconButton here
            // would apply an optimistic override the server can never
            // confirm (it always reports back `true`), leaving the star
            // visibly stuck on whatever the last tap requested. Render it
            // as a plain, non-interactive "always favorited" indicator
            // instead of a control that silently does nothing.
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = if (node.isHosted) "Your hosted node — always favorited" else "Default node — always favorited",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(12.dp).size(24.dp),
            )
        } else {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (node.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (node.isFavorite) "Unfavorite" else "Favorite",
                    tint = if (node.isFavorite) MaterialTheme.colorScheme.primary else NomadTextDim,
                )
            }
        }
    }
}

/** Null = never fetched (dim), true = last fetch ok (green), false = last fetch failed (red) — porting-notes.md §4's "last-fetch-ok/fail indicator". */
@Composable
private fun FetchStatusDot(lastFetchOk: Boolean?) {
    val color = when (lastFetchOk) {
        true -> NomadAccent2
        false -> NomadError
        null -> NomadTextDim
    }
    Canvas(modifier = Modifier.size(10.dp)) {
        drawCircle(color = color)
    }
}

/**
 * `lastAnnounceMillis <= 0` covers browser.py's synthesized placeholder
 * entries for the hosted/default node before they've ever actually
 * announced (`last_seen: 0.0`) — see the orchestration-design memory's
 * `get_nodes()` field notes. Everything else is a plain "time ago"
 * bucketed to the coarsest unit that reads naturally, matching how
 * NodeRow's other fields (hop count, hash prefix) are similarly terse.
 */
private fun formatRelativeTime(lastAnnounceMillis: Long): String {
    if (lastAnnounceMillis <= 0L) return "never heard"
    val diffSeconds = ((System.currentTimeMillis() - lastAnnounceMillis) / 1000).coerceAtLeast(0)
    return when {
        diffSeconds < 60 -> "just now"
        diffSeconds < 3600 -> "${diffSeconds / 60}m ago"
        diffSeconds < 86_400 -> "${diffSeconds / 3600}h ago"
        diffSeconds < 2_592_000 -> "${diffSeconds / 86_400}d ago"
        else -> "${diffSeconds / 2_592_000}mo ago"
    }
}
