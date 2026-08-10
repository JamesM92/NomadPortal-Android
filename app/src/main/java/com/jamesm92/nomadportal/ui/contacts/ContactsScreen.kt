package com.jamesm92.nomadportal.ui.contacts

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.calling.CallRepository
import com.jamesm92.nomadportal.data.messaging.ConversationSummary
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.ui.components.AddByAddressDialog
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.SearchField
import com.jamesm92.nomadportal.ui.components.SortDropdown
import com.jamesm92.nomadportal.ui.components.SortOption
import com.jamesm92.nomadportal.ui.components.dismissKeyboardOnTap
import com.jamesm92.nomadportal.ui.components.rememberStableOrder
import com.jamesm92.nomadportal.ui.messages.ConversationRow
import com.jamesm92.nomadportal.ui.theme.NomadAccent2
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import kotlinx.coroutines.launch

/**
 * Every LXMF peer this device knows about — messaged or not — promoted
 * out of [com.jamesm92.nomadportal.ui.messages.ConversationListScreen]'s
 * old "Users" sub-tab into its own top-level bottom-nav destination. See
 * that screen's own top doc comment for the real history/reasoning
 * (a Columba UI/UX parity-audit follow-up: Columba's real top-level tabs
 * are Chats/Contacts/Map/Settings, Contacts sitting at the same level as
 * Chats, not nested inside it — confirmed against its `AppDestination`
 * enum, not assumed).
 *
 * Same "everyone heard, not just the ones you've talked to" role
 * [com.jamesm92.nomadportal.ui.browser.NodeListScreen]'s Announces-heard
 * section plays for nodes — anyone with message history, a saved
 * favorite, or a bare LXMF peer announce ever heard (see
 * `nomadnet_web.lxmf_tracker`) shows up here.
 *
 * Deliberately reuses [ConversationRow] (and, transitively, its own
 * long-press context menu) from the Messages package rather than
 * duplicating that rendering — same
 * favorite/call/mark-unread/block actions apply to a contact regardless
 * of which screen you found them from.
 */
@Composable
fun ContactsScreen(
    repository: MessagingRepository,
    callRepository: CallRepository,
    onOpenConversation: (contactHash: String) -> Unit,
) {
    val conversations by repository.conversations().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var showAddByAddress by remember { mutableStateOf(false) }
    var callCapableOnly by remember { mutableStateOf(false) }
    var sortOption by remember { mutableStateOf(SortOption.RECENT) }

    // Same optimistic-favorite-toggle shape as ConversationListScreen's
    // own copy — see that function's identical comment for why (the 4s
    // poll would otherwise read as "did that even register?").
    var favoriteOverrides by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val effectiveConversations = remember(conversations, favoriteOverrides) {
        if (favoriteOverrides.isEmpty()) {
            conversations
        } else {
            conversations.map { summary ->
                val wanted = favoriteOverrides[summary.contact.lxmfHash]
                if (wanted != null && wanted != summary.contact.isFavorite) {
                    summary.copy(contact = summary.contact.copy(isFavorite = wanted))
                } else {
                    summary
                }
            }
        }
    }

    val filtered = if (searchQuery.isBlank()) {
        effectiveConversations
    } else {
        effectiveConversations.filter {
            it.contact.displayName.contains(searchQuery, ignoreCase = true) ||
                it.contact.lxmfHash.contains(searchQuery, ignoreCase = true)
        }
    }
    // "Recent" means recent *announces* here (a pure discovery list, some
    // entries never messaged at all) — same single meaning
    // NodeListScreen's own "Recent" has throughout, unlike
    // ConversationListScreen's Chats tab where it means recent messages.
    val sorted = when (sortOption) {
        SortOption.RECENT -> filtered.sortedByDescending { it.contact.lastAnnounceMillis }
        SortOption.ALPHABETICAL -> filtered.sortedBy { it.contact.displayName.lowercase() }
        SortOption.HOPS -> filtered.sortedBy { if (it.contact.hopCount < 0) Int.MAX_VALUE else it.contact.hopCount }
        SortOption.ANNOUNCES -> filtered.sortedByDescending { it.contact.announceCount }
    }
    val allContacts = rememberStableOrder(sorted, key = { it.contact.lxmfHash })
    val displayed = if (callCapableOnly) allContacts.filter { it.contact.isCallCapable } else allContacts

    fun toggleFavorite(summary: ConversationSummary) {
        val hash = summary.contact.lxmfHash
        val wanted = !summary.contact.isFavorite
        favoriteOverrides = favoriteOverrides + (hash to wanted)
        scope.launch {
            try {
                repository.setFavorite(hash, wanted)
            } catch (e: Exception) {
                // Same non-rethrow reasoning as ConversationListScreen's
                // own copy — left in favoriteOverrides, the next poll
                // (or the pruning it'd need if this screen kept polling
                // longer) shows the real state.
            }
        }
    }

    fun toggleBlock(summary: ConversationSummary) {
        val hash = summary.contact.lxmfHash
        val wanted = !summary.contact.isBlocked
        scope.launch {
            try {
                repository.setBlocked(hash, wanted)
            } catch (e: Exception) {
                // Same non-rethrow reasoning as above.
            }
        }
    }

    fun markUnread(summary: ConversationSummary) {
        scope.launch {
            try {
                repository.markUnread(summary.contact.lxmfHash)
            } catch (e: Exception) {
                // Same non-rethrow reasoning as above.
            }
        }
    }

    Scaffold(
        topBar = { AdaptiveTopAppBar(title = { Text("Contacts") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .dismissKeyboardOnTap(),
        ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = "Search contacts",
                        modifier = Modifier.weight(1f),
                    )
                    // Same "message an address you already know but
                    // haven't heard from yet" entry point the old Users
                    // sub-tab carried — auto-favorites on confirm, same
                    // convention as ConversationListScreen's own copy.
                    IconButton(onClick = { showAddByAddress = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = "Message address", modifier = Modifier.size(20.dp))
                    }
                    SortDropdown(selected = sortOption, onSelect = { sortOption = it })
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Contacts (${displayed.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Row(
                    modifier = Modifier
                        .clickable { callCapableOnly = !callCapableOnly }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // onCheckedChange = null — same real on-device fix as
                    // ConversationListScreen's identical checkbox: the
                    // enclosing Row already owns the tap via its own
                    // .clickable, both firing on the same tap raced and
                    // could net-cancel back to no visible change.
                    Checkbox(checked = callCapableOnly, onCheckedChange = null)
                    Icon(Icons.Filled.Call, contentDescription = null, tint = NomadAccent2, modifier = Modifier.size(16.dp))
                    Text(
                        text = "Call-capable only",
                        style = MaterialTheme.typography.labelSmall,
                        color = NomadTextDim,
                    )
                }
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(displayed, key = { it.contact.lxmfHash }) { summary ->
                    ConversationRow(
                        summary = summary,
                        onClick = { onOpenConversation(summary.contact.lxmfHash) },
                        onToggleFavorite = { toggleFavorite(summary) },
                        onCall = { scope.launch { callRepository.placeCall(summary.contact.lxmfHash) } },
                        onMarkUnread = { markUnread(summary) },
                        onToggleBlock = { toggleBlock(summary) },
                        // No onDelete — deleting a "chat" only makes
                        // sense from Messages, where there's actual
                        // history/state to clear (same scoping the old
                        // Users sub-tab already had).
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAddByAddress) {
        AddByAddressDialog(
            title = "Message an address",
            onDismiss = { showAddByAddress = false },
            onConfirm = { hash ->
                showAddByAddress = false
                scope.launch { repository.setFavorite(hash, true) }
                onOpenConversation(hash)
            },
        )
    }
}
