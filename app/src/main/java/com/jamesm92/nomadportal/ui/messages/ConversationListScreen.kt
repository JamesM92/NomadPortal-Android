package com.jamesm92.nomadportal.ui.messages

import android.content.res.Configuration
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jamesm92.nomadportal.data.messaging.Contact
import com.jamesm92.nomadportal.data.messaging.ConversationSummary
import com.jamesm92.nomadportal.data.messaging.Message
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.panicwipe.PanicWipe
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.ContactAvatar
import com.jamesm92.nomadportal.ui.components.PanicWipeLogo
import com.jamesm92.nomadportal.ui.components.SearchField
import com.jamesm92.nomadportal.ui.components.SortDropdown
import com.jamesm92.nomadportal.ui.components.SortOption
import com.jamesm92.nomadportal.ui.components.dismissKeyboardOnTap
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import kotlinx.coroutines.launch

/**
 * Every known LXMF contact — not just active conversations. Two tabs,
 * per explicit request:
 * - **Chats** — Favorites (a fixed pane, always visible, not part of
 *   the scrolling list below — see
 *   [com.jamesm92.nomadportal.ui.browser.NodeListScreen]'s doc comment
 *   for why not `LazyColumn.stickyHeader` for this one) plus "General
 *   messages" (real message history). Favoriting adds a copy to
 *   Favorites; it doesn't remove the contact from General messages.
 * - **Users** — every LXMF peer ever heard via announce (see
 *   `nomadnet_web.lxmf_tracker`), messaged or not — the same "everyone
 *   heard, not just the ones you've talked to" role
 *   [com.jamesm92.nomadportal.ui.browser.NodeListScreen]'s Announces
 *   heard plays for nodes.
 *
 * A row's subtitle line falls back to LXMF address + hop count + time
 * since last announce when there's no message to preview — the normal
 * case for anyone in the Users tab who's never been messaged.
 */
/** See [com.jamesm92.nomadportal.ui.browser.NodeListScreen]'s identical constant. */
private const val FAVORITES_AUTO_EXPAND_THRESHOLD = 7

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    repository: MessagingRepository,
    onOpenConversation: (contactHash: String) -> Unit,
    onBack: () -> Unit,
    onOpenNodes: () -> Unit,
) {
    val conversations by repository.conversations().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }
    var sortOption by remember { mutableStateOf(SortOption.RECENT) }

    val filtered = if (searchQuery.isBlank()) {
        conversations
    } else {
        conversations.filter {
            it.contact.displayName.contains(searchQuery, ignoreCase = true) ||
                it.contact.lxmfHash.contains(searchQuery, ignoreCase = true)
        }
    }
    // orchestrator.py's _conversation_entries() doesn't sort at all
    // (arbitrary set-iteration order) — sorted here per the user's own
    // chosen SortOption, same as NodeListScreen. "Recent" is the one
    // option whose meaning depends on which tab is showing (per explicit
    // request): Chats is a message list, so "recent" means recent
    // *messages*; Users is a pure discovery list (some entries never
    // messaged at all), so "recent" means recent *announces* there,
    // same as NodeListScreen's single meaning of "Recent" throughout.
    // The other three options (A>Z/Hops/Announces) aren't message-vs-
    // announce concepts at all, so they stay identical across both tabs.
    val sortedConversations = when (sortOption) {
        SortOption.RECENT -> if (selectedTab == 0) {
            filtered.sortedByDescending { it.lastMessage?.timestampMillis ?: it.contact.lastAnnounceMillis }
        } else {
            filtered.sortedByDescending { it.contact.lastAnnounceMillis }
        }
        SortOption.ALPHABETICAL -> filtered.sortedBy { it.contact.displayName.lowercase() }
        SortOption.HOPS -> filtered.sortedBy { if (it.contact.hopCount < 0) Int.MAX_VALUE else it.contact.hopCount }
        SortOption.ANNOUNCES -> filtered.sortedByDescending { it.contact.announceCount }
    }

    // Favoriting adds a copy to Favorites, it doesn't move the contact
    // out of General messages/Users — these aren't mutually-exclusive
    // partitions (matches NodeListScreen's identical convention).
    val favorites = sortedConversations.filter { it.contact.isFavorite }
    val generalMessages = sortedConversations.filter { it.lastMessage != null }
    val allUsers = sortedConversations

    var favoritesExpanded by remember { mutableStateOf(true) }
    var generalExpanded by remember { mutableStateOf(true) }
    // See NodeListScreen's identical `favoritesDominant` comment for the
    // full rationale — same item-count-based approximation of "when a
    // section is big enough to need the whole screen it auto collapses
    // the other sections".
    val favoritesDominant = favorites.size > FAVORITES_AUTO_EXPAND_THRESHOLD
    // A default, not a lock — see NodeListScreen's identical comment.
    LaunchedEffect(favoritesDominant) {
        if (favoritesDominant) generalExpanded = false
    }

    fun toggleFavorite(summary: ConversationSummary) {
        scope.launch { repository.setFavorite(summary.contact.lxmfHash, !summary.contact.isFavorite) }
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        topBar = {
            Column {
                AdaptiveTopAppBar(
                    title = { Text("Messages") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        // See NodeListScreen's identical comment: cross-nav
                        // to the other list screen, present here but not
                        // on an individual ConversationScreen. Settings is
                        // deliberately NOT here — per explicit request,
                        // only reachable from the main menu (Home).
                        IconButton(onClick = onOpenNodes) {
                            Icon(Icons.Filled.Explore, contentDescription = "Nodes")
                        }
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
                    // Material3's default tab height carries more
                    // padding than a two-word text tab needs here —
                    // shrunk further still in landscape, per "header rows
                    // need to be as small as possible" when rotated.
                    modifier = Modifier.height(if (isLandscape) 28.dp else 36.dp),
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                "Chats",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.8f,
                                    fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                                ),
                                color = if (selectedTab == 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    NomadTextDim
                                },
                            )
                        },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                "Users",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.8f,
                                    fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                ),
                                color = if (selectedTab == 1) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    NomadTextDim
                                },
                            )
                        },
                    )
                }
            }
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
                    placeholder = if (selectedTab == 0) "Search chats" else "Search users",
                    modifier = Modifier.weight(1f),
                )
                SortDropdown(selected = sortOption, onSelect = { sortOption = it })
            }

            if (selectedTab == 0) {
                // Headers always outside any LazyColumn — always visible,
                // always tappable, regardless of dominance state (see
                // NodeListScreen's identical structure/comment).
                // See NodeListScreen's identical comment: while Favorites
                // is dominant, expanding either section collapses the
                // other, not just at the initial default.
                SectionHeader(
                    title = "Favorites",
                    count = favorites.size,
                    expanded = favoritesExpanded,
                    onToggle = {
                        favoritesExpanded = !favoritesExpanded
                        if (favoritesExpanded && favoritesDominant) generalExpanded = false
                    },
                )
                if (favoritesExpanded && favorites.isNotEmpty()) {
                    LazyColumn(
                        modifier = if (favoritesDominant) {
                            Modifier.weight(1f)
                        } else {
                            Modifier.heightIn(max = 280.dp)
                        },
                    ) {
                        items(favorites, key = { it.contact.lxmfHash }) { summary ->
                            ConversationRow(
                                summary = summary,
                                onClick = { onOpenConversation(summary.contact.lxmfHash) },
                                onToggleFavorite = { toggleFavorite(summary) },
                            )
                            HorizontalDivider()
                        }
                    }
                }

                HorizontalDivider()

                SectionHeader(
                    title = "General messages",
                    count = generalMessages.size,
                    expanded = generalExpanded,
                    onToggle = {
                        generalExpanded = !generalExpanded
                        if (generalExpanded && favoritesDominant) favoritesExpanded = false
                    },
                )
                if (generalExpanded) {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(generalMessages, key = { it.contact.lxmfHash }) { summary ->
                            ConversationRow(
                                summary = summary,
                                onClick = { onOpenConversation(summary.contact.lxmfHash) },
                                onToggleFavorite = { toggleFavorite(summary) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                // Single-section tab — nothing else competing for space,
                // so the count header stays non-collapsible (unlike
                // Chats' two headers above).
                SectionHeader(
                    title = "Users",
                    count = allUsers.size,
                    expanded = true,
                    onToggle = {},
                    collapsible = false,
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(allUsers, key = { it.contact.lxmfHash }) { summary ->
                        ConversationRow(
                            summary = summary,
                            onClick = { onOpenConversation(summary.contact.lxmfHash) },
                            onToggleFavorite = { toggleFavorite(summary) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/**
 * [collapsible] = false renders a count-only label with no chevron and
 * no click target — for the Users tab, which is the tab's entire
 * content (nothing else competing for space, so nothing to collapse
 * into), but still needs the same "how many" visibility every other
 * section header carries.
 */
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
            // Tighter than before (was 12.dp) — a HorizontalDivider
            // between rows now provides visual separation.
            .padding(horizontal = 16.dp, vertical = 6.dp),
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
 * address + hop count + time since last announce — the normal case for
 * anyone in the Users tab who's never been messaged. */
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
