package com.jamesm92.nomadportal.ui.browser

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.browsing.BrowserRepository
import com.jamesm92.nomadportal.data.browsing.NodeInfo
import com.jamesm92.nomadportal.ui.theme.NomadAccent2
import com.jamesm92.nomadportal.ui.theme.NomadError
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import kotlinx.coroutines.launch

/**
 * Discovered-node list (porting-notes.md §4): hop count, last-fetch-ok/fail
 * indicator, favorites. No real RNS announce-listening yet — see
 * [com.jamesm92.nomadportal.data.browsing.StubBrowserRepository].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeListScreen(
    repository: BrowserRepository,
    onOpenNode: (nodeHash: String) -> Unit,
    onBack: () -> Unit,
) {
    val nodes by repository.discoveredNodes().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

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
            items(nodes, key = { it.hash }) { node ->
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

@Composable
private fun NodeRow(node: NodeInfo, onClick: () -> Unit, onToggleFavorite: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FetchStatusDot(node.lastFetchOk)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = node.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "${node.hopCount} hop${if (node.hopCount == 1) "" else "s"} · ${node.hash.take(8)}…",
                style = MaterialTheme.typography.bodyLarge,
                color = NomadTextDim,
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
