package com.jamesm92.nomadportal.ui.messages

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jamesm92.nomadportal.data.messaging.Contact
import com.jamesm92.nomadportal.data.messaging.ConversationSummary
import com.jamesm92.nomadportal.data.messaging.Message
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.ui.components.ContactAvatar
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import kotlinx.coroutines.launch

/**
 * Every known LXMF contact — not just active conversations. Split into
 * three sections, mirroring
 * [com.jamesm92.nomadportal.ui.browser.NodeListScreen]'s Favorites/
 * Announces-heard pattern:
 * - **Favorites** — a fixed pane above the rest, always visible (its own
 *   bounded/independently-scrolling list, not part of the LazyColumn
 *   below — see that screen's doc comment for why not
 *   `LazyColumn.stickyHeader`), even for a contact never actually
 *   messaged. Favoriting adds a copy here; it doesn't remove the contact
 *   from Messaged/Announces heard below.
 * - **Messaged** — real message history.
 * - **Announces heard** — LXMF peers heard via announce (see
 *   `nomadnet_web.lxmf_tracker`) but never messaged — surfaces the LXMF
 *   address/hop-count/last-heard data
 *   [Contact.lastAnnounceMillis]/[Contact.hopCount] carry for exactly
 *   this case, same fields [com.jamesm92.nomadportal.data.browsing.NodeInfo]
 *   uses for RNS node announces.
 *
 * Every row's subtitle line falls back to LXMF address + hop count + time
 * since last announce when there's no message to preview — the whole
 * point of the Announces-heard section existing.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationListScreen(
    repository: MessagingRepository,
    onOpenConversation: (contactHash: String) -> Unit,
    onBack: () -> Unit,
) {
    val conversations by repository.conversations().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    // Favoriting adds a copy to Favorites, it doesn't move the contact
    // out of Messaged/Announces heard — per explicit request, these
    // sections aren't a mutually-exclusive partition.
    val favorites = conversations.filter { it.contact.isFavorite }
    val messaged = conversations.filter { it.lastMessage != null }
    val announcesHeard = conversations.filter { it.lastMessage == null }

    var favoritesExpanded by remember { mutableStateOf(true) }
    var messagedExpanded by remember { mutableStateOf(true) }
    var announcesExpanded by remember { mutableStateOf(true) }

    fun toggleFavorite(summary: ConversationSummary) {
        scope.launch { repository.setFavorite(summary.contact.lxmfHash, !summary.contact.isFavorite) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())) {
            // Fixed pane — always visible regardless of how far
            // Messaged/Announces heard is scrolled.
            SectionHeader(
                title = "Favorites",
                count = favorites.size,
                expanded = favoritesExpanded,
                onToggle = { favoritesExpanded = !favoritesExpanded },
            )
            if (favoritesExpanded && favorites.isNotEmpty()) {
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    items(favorites, key = { it.contact.lxmfHash }) { summary ->
                        ConversationRow(
                            summary = summary,
                            onClick = { onOpenConversation(summary.contact.lxmfHash) },
                            onToggleFavorite = { toggleFavorite(summary) },
                        )
                    }
                }
            }

            HorizontalDivider()

            LazyColumn(modifier = Modifier.weight(1f)) {
                // Both are stickyHeader (not one item{} + one
                // stickyHeader) so pinning correctly hands off from
                // "Messaged" to "Announces heard" once you scroll past
                // the Messaged section, rather than only the first one
                // ever pinning.
                stickyHeader {
                    SectionHeader(
                        title = "Messaged",
                        count = messaged.size,
                        expanded = messagedExpanded,
                        onToggle = { messagedExpanded = !messagedExpanded },
                        modifier = Modifier.background(MaterialTheme.colorScheme.background),
                    )
                }
                if (messagedExpanded) {
                    items(messaged, key = { it.contact.lxmfHash }) { summary ->
                        ConversationRow(
                            summary = summary,
                            onClick = { onOpenConversation(summary.contact.lxmfHash) },
                            onToggleFavorite = { toggleFavorite(summary) },
                        )
                    }
                }

                stickyHeader {
                    SectionHeader(
                        title = "Announces heard",
                        count = announcesHeard.size,
                        expanded = announcesExpanded,
                        onToggle = { announcesExpanded = !announcesExpanded },
                        modifier = Modifier.background(MaterialTheme.colorScheme.background),
                    )
                }
                if (announcesExpanded) {
                    items(announcesHeard, key = { it.contact.lxmfHash }) { summary ->
                        ConversationRow(
                            summary = summary,
                            onClick = { onOpenConversation(summary.contact.lxmfHash) },
                            onToggleFavorite = { toggleFavorite(summary) },
                        )
                    }
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
private fun ConversationRow(
    summary: ConversationSummary,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ContactAvatar(summary.contact)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.contact.displayName,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.85f,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitleFor(summary.contact, summary.lastMessage),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.7f,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (summary.unreadCount > 0) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = summary.unreadCount.toString(),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (summary.contact.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (summary.contact.isFavorite) "Unfavorite" else "Favorite",
                tint = if (summary.contact.isFavorite) MaterialTheme.colorScheme.primary else NomadTextDim,
            )
        }
    }
}

/** Real message preview when one exists; otherwise falls back to the LXMF
 * address + hop count + time since last announce — the Announces-heard
 * section's whole reason for existing, matching NodeRow's identical
 * fallback shape for RNS nodes. */
private fun subtitleFor(contact: Contact, lastMessage: Message?): String {
    if (lastMessage != null) return lastMessage.content
    val hops = if (contact.hopCount < 0) "?" else contact.hopCount.toString()
    return "$hops hop${if (contact.hopCount == 1) "" else "s"}" +
        " · ${formatRelativeTime(contact.lastAnnounceMillis)} · ${contact.lxmfHash.take(8)}…"
}

/** Same convention as NodeListScreen's formatRelativeTime — `<= 0` covers
 * "never actually heard an announce" (a message-history-only or manually
 * added contact with no LXMF peer-tracker entry at all). */
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
