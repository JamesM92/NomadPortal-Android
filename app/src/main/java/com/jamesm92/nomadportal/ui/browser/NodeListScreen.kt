package com.jamesm92.nomadportal.ui.browser

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.browsing.BrowserRepository
import com.jamesm92.nomadportal.data.browsing.NodeInfo
import com.jamesm92.nomadportal.ui.theme.NomadAccent2
import com.jamesm92.nomadportal.ui.theme.NomadError
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import kotlinx.coroutines.launch

/**
 * Discovered-node list (porting-notes.md §4): hop count, last-fetch-ok/fail
 * indicator, favorites, time since last announce. Real RNS
 * announce-listening as of Aug 2026 — see
 * [com.jamesm92.nomadportal.data.browsing.RealBrowserRepository]. Split
 * into two collapsible sections — favorited nodes first, then everything
 * else heard on the mesh — so a busy hub's announce volume doesn't bury
 * the handful of nodes someone actually cares about. The Favorites header
 * is pinned (`stickyHeader`) so it's always reachable to re-collapse/
 * re-expand without scrolling back up, even with a long "Announces heard"
 * list beneath it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NodeListScreen(
    repository: BrowserRepository,
    onOpenNode: (nodeHash: String) -> Unit,
    onBack: () -> Unit,
) {
    val nodes by repository.discoveredNodes().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    val favorites = nodes.filter { it.isFavorite }
    val announcesHeard = nodes.filter { !it.isFavorite }

    var favoritesExpanded by remember { mutableStateOf(true) }
    var announcesExpanded by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nodes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(contentPadding = PaddingValues(top = innerPadding.calculateTopPadding())) {
            stickyHeader {
                SectionHeader(
                    title = "Favorites",
                    count = favorites.size,
                    expanded = favoritesExpanded,
                    onToggle = { favoritesExpanded = !favoritesExpanded },
                    // Opaque background — a sticky header sits above
                    // scrolled-past content, not composited with it, so
                    // without this the "Announces heard" rows underneath
                    // would show through as the list scrolls.
                    modifier = Modifier.background(MaterialTheme.colorScheme.background),
                )
            }
            if (favoritesExpanded) {
                items(favorites, key = { it.hash }) { node ->
                    NodeRow(
                        node = node,
                        onClick = { onOpenNode(node.hash) },
                        onToggleFavorite = {
                            scope.launch { repository.setFavorite(node.hash, !node.isFavorite) }
                        },
                    )
                }
            }

            item {
                SectionHeader(
                    title = "Announces heard",
                    count = announcesHeard.size,
                    expanded = announcesExpanded,
                    onToggle = { announcesExpanded = !announcesExpanded },
                )
            }
            if (announcesExpanded) {
                items(announcesHeard, key = { it.hash }) { node ->
                    NodeRow(
                        node = node,
                        onClick = { onOpenNode(node.hash) },
                        onToggleFavorite = {
                            scope.launch { repository.setFavorite(node.hash, !node.isFavorite) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "$title ($count)",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = NomadTextDim,
        )
    }
}

@Composable
private fun NodeRow(node: NodeInfo, onClick: () -> Unit, onToggleFavorite: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (node.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (node.isFavorite) "Unfavorite" else "Favorite",
                tint = if (node.isFavorite) MaterialTheme.colorScheme.primary else NomadTextDim,
            )
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
