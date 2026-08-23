package com.jamesm92.nomadportal.ui.messages

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.jamesm92.nomadportal.data.calling.CallHistoryEntry
import com.jamesm92.nomadportal.data.calling.CallRepository
import com.jamesm92.nomadportal.data.calling.CallStatusValue
import com.jamesm92.nomadportal.data.messaging.Contact
import com.jamesm92.nomadportal.data.messaging.ConversationSummary
import com.jamesm92.nomadportal.data.messaging.Message
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.data.messaging.PropagationSyncStatus
import com.jamesm92.nomadportal.data.messaging.PropagationTransferState
import com.jamesm92.nomadportal.panicwipe.PanicWipe
import com.jamesm92.nomadportal.ui.components.AddByAddressDialog
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.CompactIconButton
import com.jamesm92.nomadportal.ui.components.ContactAvatar
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
 * Every known LXMF contact — not just active conversations. Three
 * sub-tabs:
 * - **Chats** — Favorites (a fixed pane, always visible, not part of
 *   the scrolling list below — see
 *   [com.jamesm92.nomadportal.ui.browser.NodeListScreen]'s doc comment
 *   for why not `LazyColumn.stickyHeader` for this one) plus "General
 *   messages" (real message history). Favoriting adds a copy to
 *   Favorites; it doesn't remove the contact from General messages.
 * - **Calls** — see [CallsTab]'s own doc comment.
 * - **Users** — every LXMF peer ever heard via announce (see
 *   `nomadnet_web.lxmf_tracker`), messaged or not — the same "everyone
 *   heard, not just the ones you've talked to" role
 *   [com.jamesm92.nomadportal.ui.browser.NodeListScreen]'s Announces
 *   heard plays for nodes. Briefly promoted out to its own top-level
 *   "Contacts" bottom-nav tab, then briefly folded into a "Network" tab
 *   instead, both within this same session — reverted back to living
 *   here per explicit direction ("keep the announces in the sites and
 *   messages tabs").
 *
 * Tab declaration order and `selectedTab` tag values are deliberately
 * kept identical (Chats=0, Calls=1, Users=2, declared in exactly that
 * order) — [SecondaryTabRow]'s `selectedTabIndex` is a real positional
 * index among its actual `Tab()` children, not an arbitrary semantic
 * tag; a real on-device crash earlier this session
 * (`IndexOutOfBoundsException` in `TabIndicatorOffsetNode.measure`) came
 * from exactly this mismatch after a tab was removed without
 * renumbering the rest. Don't reintroduce that gap if this ever changes
 * again — position and tag must match, always.
 *
 * A row's subtitle line falls back to LXMF address + hop count + time
 * since last announce when there's no message to preview — the normal
 * case for anyone in the Users tab who's never been messaged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    repository: MessagingRepository,
    callRepository: CallRepository,
    onOpenConversation: (contactHash: String) -> Unit,
) {
    val conversations by remember(repository) { repository.conversations() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var showAddByAddress by remember { mutableStateOf(false) }
    var showCallByAddress by remember { mutableStateOf(false) }
    var callCapableOnly by remember { mutableStateOf(false) }
    // rememberSaveable, not remember, per explicit request ("on both
    // the messages and sites tabs, it should remember if your looking
    // at announces or favorites last... helps save extra clicks") —
    // plain remember loses this the moment the composable leaves
    // composition (switching to another bottom-nav tab and back), since
    // NavHost's own popUpTo(saveState=true)/restoreState=true only
    // preserves the back-stack entry itself, not raw Compose state
    // inside it. rememberSaveable hooks into that entry's own
    // SavedStateRegistry instead, which Navigation-Compose already
    // wires up per-destination — the standard, correct mechanism for
    // exactly this "remember UI state across tab switches" need.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var sortOption by remember { mutableStateOf(SortOption.RECENT) }

    // Optimistic favorite toggling — repository.conversations() is a
    // 4-second poll (see RealMessagingRepository's own doc comment;
    // MessagingService/orchestrator.py have no push mechanism), so
    // without this a favorite tap could take up to 4s to visibly
    // update, which reads as "did that even register?" (a real
    // on-device report). hash -> the value this device is *waiting* to
    // see confirmed; applied on top of the live `conversations` list
    // below rather than replacing it, and self-prunes the moment the
    // next poll actually confirms it — including correcting itself if
    // the underlying setFavorite call silently failed, no separate
    // revert-on-error path needed.
    var favoriteOverrides by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    LaunchedEffect(conversations) {
        if (favoriteOverrides.isEmpty()) return@LaunchedEffect
        val stillPending = favoriteOverrides.filter { (hash, wanted) ->
            conversations.find { it.contact.lxmfHash == hash }?.contact?.isFavorite != wanted
        }
        if (stillPending.size != favoriteOverrides.size) favoriteOverrides = stillPending
    }
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
    // rememberStableOrder freezes each row's screen position against a
    // live-updating sort key reordering between polls — see
    // NodeListScreen's identical usage/that function's own doc comment
    // for the real mis-tap repro this fixes.
    val favorites = rememberStableOrder(
        sortedConversations.filter { it.contact.isFavorite },
        key = { it.contact.lxmfHash },
    )
    val generalMessages = rememberStableOrder(
        // Real, on-device-confirmed bug: this used to filter only on
        // "has a message," with no exclusion for favorited contacts —
        // a favorited contact with messages showed up in *both*
        // Favorites and General messages at once, double-counting its
        // unread badge into General's own header total. Favorites and
        // General are meant to be a strict partition (every conversation
        // lives in exactly one), matching how favoritesUnread/generalUnread
        // above are summed as if they never overlap.
        sortedConversations.filter { it.lastMessage != null && !it.contact.isFavorite },
        key = { it.contact.lxmfHash },
    )
    val allUsers = rememberStableOrder(sortedConversations, key = { it.contact.lxmfHash })

    // Per-row unread badges (below) only surface once a section is
    // expanded — collapse Favorites to read General messages (or vice
    // versa) and there was previously no way to tell an unread message
    // was sitting in the *other*, now-hidden section at all. Summed here
    // so the section header itself can carry the same badge whether
    // expanded or collapsed — a real on-device report ("need to still
    // have the notification... to let us know where the unread message
    // is" while already on this screen).
    val favoritesUnread = favorites.sumOf { it.unreadCount }
    val generalUnread = generalMessages.sumOf { it.unreadCount }

    // Exactly one of Favorites/General messages is ever open — per
    // explicit direction: opening one always closes the other, closing
    // one always opens the other (there's no "both closed"/"both open"
    // state). A single boolean is enough to model that exhaustively:
    // both headers just flip it, since with only two sections "flip"
    // and "swap which one's open" are the same operation regardless of
    // which header triggered it.
    //
    // rememberSaveable — see selectedTab's own doc comment above for
    // why (same real request, same fix).
    var favoritesOpen by rememberSaveable { mutableStateOf(true) }

    fun toggleFavorite(summary: ConversationSummary) {
        val hash = summary.contact.lxmfHash
        val wanted = !summary.contact.isFavorite
        favoriteOverrides = favoriteOverrides + (hash to wanted)
        scope.launch {
            try {
                repository.setFavorite(hash, wanted)
            } catch (e: Exception) {
                // Left in favoriteOverrides — the next poll will show
                // the real (unchanged) state, which the pruning
                // LaunchedEffect above then clears automatically. Not
                // rethrown: this coroutine shares `scope` with every
                // other action on this screen, and an uncaught
                // exception here would cancel all of them.
            }
        }
    }

    // No optimistic override the way toggleFavorite has — block/unblock
    // is a long-press-menu action, not a fast-repeat-tap icon, so the
    // ~4s poll latency isn't the same "did that even register?" risk.
    fun toggleBlock(summary: ConversationSummary) {
        val hash = summary.contact.lxmfHash
        val wanted = !summary.contact.isBlocked
        scope.launch {
            try {
                repository.setBlocked(hash, wanted)
            } catch (e: Exception) {
                // Same non-rethrow reasoning as toggleFavorite above —
                // this coroutine shares `scope` with every other action
                // on this screen.
            }
        }
    }

    fun markUnread(summary: ConversationSummary) {
        scope.launch {
            try {
                repository.markUnread(summary.contact.lxmfHash)
            } catch (e: Exception) {
                // Same non-rethrow reasoning as toggleFavorite above.
            }
        }
    }

    // Confirmed before actually deleting — hard to reverse (message
    // history is gone for good), per this app's own standing convention
    // for destructive actions elsewhere (panic wipe, etc.).
    var pendingDelete by remember { mutableStateOf<Contact?>(null) }

    // Columba's own Chats screen has a "sync from propagation node"
    // action with a status bottom sheet (confirmed during the Columba
    // parity audit) — this is the same idea via an AlertDialog, this
    // app's own established pattern for a tap-opens-detail affordance
    // (NetworkScreen's technical-info dialog, MessageBubble's delivery-
    // details dialog).
    var syncDialogOpen by remember { mutableStateOf(false) }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        topBar = {
            Column {
                AdaptiveTopAppBar(
                    title = { Text("Messages") },
                    // No back arrow, no cross-nav icons — Messages is now
                    // a real bottom-nav tab (NomadNavHost.kt), same as
                    // Home/Nodes/Settings; switching tabs is the way back,
                    // not a navigationIcon on a top-level screen.
                    actions = {
                        CompactIconButton(onClick = { syncDialogOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.Sync,
                                contentDescription = "Propagation sync status",
                                tint = NomadTextDim,
                            )
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
                                style = MaterialTheme.typography.labelMedium.copy(
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
                                "Calls",
                                style = MaterialTheme.typography.labelMedium.copy(
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
                    // Declared last (position 2) with a matching tag of 2 —
                    // see this file's own top doc comment for why position
                    // and tag must always match here.
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = {
                            Text(
                                "Users",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal,
                                ),
                                color = if (selectedTab == 2) {
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
            // Zeroed touch-target reservation on this row's icons — real
            // on-device report: "there is to much dead space around the
            // icons in the messages section." Same fix as every other
            // dense icon row in this app (ConversationRow's own trailing
            // icons, BrowserScreen's nav row, the TCP table) — an
            // IconButton otherwise pads itself to the ~48dp accessibility
            // minimum regardless of the icon's own size.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = when (selectedTab) {
                            0 -> "Search chats"
                            2 -> "Search users"
                            else -> "Search calls"
                        },
                        modifier = Modifier.weight(1f),
                    )
                    // Search only finds already-known contacts (message
                    // history or a live announce heard) — this is the
                    // companion entry point for an address you already
                    // know but have neither messaged nor heard announce
                    // from yet. Chats/Users only — the Calls tab has its
                    // own dedicated "Call an address" entry point instead
                    // (per explicit direction, moved out of this shared
                    // row: "the phone icon should only be in the calls
                    // sub tab of messages").
                    if (selectedTab != 1) {
                        IconButton(onClick = { showAddByAddress = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Add, contentDescription = "Message address", modifier = Modifier.size(20.dp))
                        }
                    }
                    SortDropdown(selected = sortOption, onSelect = { sortOption = it })
                }
            }

            if (selectedTab == 0) {
                // Headers always outside any LazyColumn — always visible,
                // always tappable. Exactly one section is ever expanded
                // (see favoritesOpen's own doc comment above), so the
                // expanded one always gets the full remaining space —
                // no more count-based heuristic for how tall its list
                // should be.
                SectionHeader(
                    title = "Favorites",
                    count = favorites.size,
                    unreadCount = favoritesUnread,
                    expanded = favoritesOpen,
                    onToggle = { favoritesOpen = !favoritesOpen },
                )
                if (favoritesOpen) {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(favorites, key = { it.contact.lxmfHash }) { summary ->
                            ConversationRow(
                                summary = summary,
                                onClick = { onOpenConversation(summary.contact.lxmfHash) },
                                onToggleFavorite = { toggleFavorite(summary) },
                                onCall = { scope.launch { callRepository.placeCall(summary.contact.lxmfHash) } },
                                onMarkUnread = { markUnread(summary) },
                                onToggleBlock = { toggleBlock(summary) },
                                onDelete = { pendingDelete = summary.contact },
                            )
                            HorizontalDivider()
                        }
                    }
                }

                HorizontalDivider()

                SectionHeader(
                    title = "General messages",
                    count = generalMessages.size,
                    unreadCount = generalUnread,
                    expanded = !favoritesOpen,
                    onToggle = { favoritesOpen = !favoritesOpen },
                )
                if (!favoritesOpen) {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(generalMessages, key = { it.contact.lxmfHash }) { summary ->
                            ConversationRow(
                                summary = summary,
                                onClick = { onOpenConversation(summary.contact.lxmfHash) },
                                onToggleFavorite = { toggleFavorite(summary) },
                                onCall = { scope.launch { callRepository.placeCall(summary.contact.lxmfHash) } },
                                onMarkUnread = { markUnread(summary) },
                                onToggleBlock = { toggleBlock(summary) },
                                onDelete = { pendingDelete = summary.contact },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            } else if (selectedTab == 2) {
                // Single-section tab — nothing else competing for space,
                // so the count header stays non-collapsible (unlike
                // Chats' two headers above). callCapableOnly is a plain
                // filter over the same list Users already renders — per
                // explicit direction, no separate call-capable-contacts
                // list duplicating this one; that's what the Calls tab
                // used to do and was removed.
                val displayedUsers = if (callCapableOnly) allUsers.filter { it.contact.isCallCapable } else allUsers
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Users (${displayedUsers.size})",
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
                        // onCheckedChange = null (not a redundant second
                        // { callCapableOnly = it }) — the enclosing Row
                        // already owns the tap via its own .clickable, a
                        // real on-device check showed both firing on the
                        // same tap raced and could net-cancel back to no
                        // visible change. null disables the Checkbox's
                        // own independent click handling while still
                        // rendering the correct checked state, the
                        // standard Compose pattern for a checkbox inside
                        // a larger clickable row.
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
                    items(displayedUsers, key = { it.contact.lxmfHash }) { summary ->
                        ConversationRow(
                            summary = summary,
                            onClick = { onOpenConversation(summary.contact.lxmfHash) },
                            onToggleFavorite = { toggleFavorite(summary) },
                            onCall = { scope.launch { callRepository.placeCall(summary.contact.lxmfHash) } },
                            onMarkUnread = { markUnread(summary) },
                            onToggleBlock = { toggleBlock(summary) },
                        )
                        HorizontalDivider()
                    }
                }
            } else {
                val callHistory by remember(callRepository) { callRepository.callHistory() }
                    .collectAsState(initial = emptyList())
                CallsTab(
                    history = callHistory,
                    onCallByAddress = { showCallByAddress = true },
                    onAnnounce = { scope.launch { callRepository.announceCallAddress() } },
                )
            }
        }
    }

    pendingDelete?.let { contact ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete chat?") },
            text = {
                Text(
                    "This removes all message history with ${contact.displayName} from this " +
                        "device, along with their saved name/icon/favorite state. They can still " +
                        "show up again under Users if they're actively announcing on the mesh.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.deleteConversation(contact.lxmfHash) }
                    pendingDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    if (showAddByAddress) {
        AddByAddressDialog(
            title = "Message an address",
            onDismiss = { showAddByAddress = false },
            messagingRepository = repository,
            onConfirm = { hash ->
                showAddByAddress = false
                // See NodeListScreen's identical comment: an address
                // entered by hand is one you already specifically care
                // about, so auto-favorite it rather than requiring a
                // separate follow-up tap. setFavorite upserts a contact
                // entry if one doesn't exist yet.
                scope.launch { repository.setFavorite(hash, true) }
                onOpenConversation(hash)
            },
        )
    }

    if (showCallByAddress) {
        AddByAddressDialog(
            title = "Call an address",
            onDismiss = { showCallByAddress = false },
            messagingRepository = repository,
            onConfirm = { hash ->
                showCallByAddress = false
                // Same convention as "Message an address" above, per
                // explicit direction ("manually entering a phone address
                // auto adds the contact as a favorite as well"): an
                // address entered by hand is one the user already
                // specifically cares about. setFavorite upserts a
                // contact entry if one doesn't exist yet — this is also
                // what "automatically attaches itself to the associated
                // user if we can" (also explicitly requested) actually
                // means here: CallManager.resolve_identity() and
                // set_contact_favorite() both key off the same address
                // shape (an LXMF-style destination hash), so a hash that
                // resolves to a real identity for the call also becomes
                // (or already was) that same contact's own entry — there
                // isn't a separate "call contact" identity to reconcile.
                scope.launch {
                    // placeCall's own failure reason was previously
                    // discarded here — a real gap found on-device: a
                    // failed manual call gave zero visible feedback,
                    // the dialog just closed as if it had worked.
                    val started = callRepository.placeCall(hash)
                    if (!started) {
                        Toast.makeText(context, "Couldn't place that call", Toast.LENGTH_SHORT).show()
                    }
                    repository.setFavorite(hash, true)
                }
            },
        )
    }

    if (syncDialogOpen) {
        PropagationSyncDialog(repository = repository, onDismiss = { syncDialogOpen = false })
    }
}

/**
 * Status + manual "Sync now" trigger for LXMF propagation-node sync —
 * see [PropagationSyncStatus]'s own doc comment for what this actually
 * does (a real store-and-forward mailbox pull, plus a path-table
 * keepalive that's already running automatically every 5 minutes
 * regardless of whether this dialog is ever opened). Polls only while
 * open — [MessagingRepository.propagationSyncStatus]'s `Flow` starts/
 * stops with this composable's own lifecycle via `collectAsState`.
 */
@Composable
private fun PropagationSyncDialog(repository: MessagingRepository, onDismiss: () -> Unit) {
    val status by remember(repository) { repository.propagationSyncStatus() }.collectAsState(initial = null)
    // Only retryViaRelay is actually used from this — see this
    // property's own doc comment. Pulling the whole AnnounceStatus flow
    // just for one boolean is the same trade-off Settings' own Privacy
    // section already accepts for the same reason.
    val announceStatus by remember(repository) { repository.announceStatus() }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var syncing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Propagation Sync") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Pulls messages queued for you at a mesh propagation node " +
                        "while you were unreachable. Runs automatically in the " +
                        "background every few minutes — this is a manual, " +
                        "immediate trigger on top of that.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NomadTextDim,
                )
                val s = status
                if (s == null) {
                    Text("Loading…", style = MaterialTheme.typography.bodySmall, color = NomadTextDim)
                } else {
                    SyncInfoRow("Known nodes", "${s.knownNodes} (${s.freshNodes} recently active)")
                    SyncInfoRow("Current node", s.pickedNodeHash?.take(16) ?: "None discovered yet")
                    SyncInfoRow("Status", formatTransferState(s.transferState))
                    if (s.transferState in IN_PROGRESS_TRANSFER_STATES) {
                        LinearProgressIndicator(
                            progress = { s.transferProgress },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                    SyncInfoRow("Last synced", formatSyncTime(s.lastSyncedAtMillis))
                    s.transferLastResult?.let {
                        SyncInfoRow("Last pull", "$it message" + if (it == 1) "" else "s")
                    }
                    s.lastError?.let { SyncInfoRow("Last error", it) }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                // The send-side complement to the pull-sync above — per
                // the Columba-parity-audit's own "retry via relay on
                // failure" finding (MessageDeliveryRetrievalCard.kt).
                // Off by default: this is a reliability preference, not
                // something that needs to be pre-enabled to be safe.
                announceStatus?.let { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Retry via relay on failure",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = a.retryViaRelay,
                            onCheckedChange = {
                                scope.launch { repository.setRetryViaRelay(it) }
                            },
                        )
                    }
                    Text(
                        "If a direct send fails, automatically retry once through a " +
                            "propagation node instead of just giving up.",
                        style = MaterialTheme.typography.labelSmall,
                        color = NomadTextDim,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !syncing,
                onClick = {
                    syncing = true
                    scope.launch {
                        try {
                            val message = repository.triggerPropagationSync()
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "Sync failed", Toast.LENGTH_SHORT).show()
                        } finally {
                            syncing = false
                        }
                    }
                },
            ) { Text("Sync now") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private val IN_PROGRESS_TRANSFER_STATES = setOf(
    PropagationTransferState.REQUESTING_PATH,
    PropagationTransferState.CONNECTING,
    PropagationTransferState.CONNECTED,
    PropagationTransferState.REQUEST_SENT,
    PropagationTransferState.RECEIVING,
    PropagationTransferState.RESPONSE_RECEIVED,
)

@Composable
private fun SyncInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = NomadTextDim)
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun formatTransferState(state: PropagationTransferState): String = when (state) {
    PropagationTransferState.IDLE -> "Idle"
    PropagationTransferState.REQUESTING_PATH -> "Requesting path…"
    PropagationTransferState.CONNECTING -> "Connecting…"
    PropagationTransferState.CONNECTED -> "Connected"
    PropagationTransferState.REQUEST_SENT -> "Request sent…"
    PropagationTransferState.RECEIVING -> "Receiving…"
    PropagationTransferState.RESPONSE_RECEIVED -> "Response received"
    PropagationTransferState.COMPLETE -> "Complete"
    PropagationTransferState.NO_PATH -> "No path to node"
    PropagationTransferState.LINK_FAILED -> "Link failed"
    PropagationTransferState.TRANSFER_FAILED -> "Transfer failed"
    PropagationTransferState.NO_IDENTITY_RECEIVED -> "No identity received"
    PropagationTransferState.NO_ACCESS -> "Access denied by node"
    PropagationTransferState.FAILED -> "Failed"
    PropagationTransferState.UNKNOWN -> "Unknown"
}

/** Distinct wording from this file's own [formatRelativeTime] (which
 * says "never heard" — an announce-heard concept) — "Never" here means
 * "no sync has ever completed," a different, sync-specific null case. */
private fun formatSyncTime(millis: Long?): String {
    if (millis == null) return "Never"
    val diffSeconds = ((System.currentTimeMillis() - millis) / 1000).coerceAtLeast(0)
    return when {
        diffSeconds < 60 -> "just now"
        diffSeconds < 3600 -> "${diffSeconds / 60}m ago"
        diffSeconds < 86_400 -> "${diffSeconds / 3600}h ago"
        else -> "${diffSeconds / 86_400}d ago"
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
    // Sum of unreadCount across this section's rows — shown as a badge
    // next to the title so a collapsed section (or the Users tab, which
    // has no per-row badges of its own to speak of) still surfaces
    // "there's an unread message in here" without needing to expand it
    // first. 0 renders nothing, same convention as MessagesIconWithBadge.
    unreadCount: Int = 0,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (collapsible) Modifier.clickable(onClick = onToggle) else Modifier)
            // 4dp (the redesign's own "tight/inline" grid tier) — was
            // 12.dp originally, then a not-quite-grid-aligned 6.dp; this
            // is a section divider, not a screen title, doesn't need
            // that much room.
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "$title ($count)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            if (unreadCount > 0) {
                Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) }
            }
        }
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

// internal (not private) — NetworkScreen's own unified announces browser
// reuses this row/menu pair too (see this file's own top doc comment).
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationRow(
    summary: ConversationSummary,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCall: () -> Unit,
    onMarkUnread: () -> Unit,
    onToggleBlock: () -> Unit,
    // Null on the Users tab — deleting a "chat" only makes sense where
    // there's actual history/state to clear, per explicit request
    // ("delete chats from our chats tab").
    onDelete: (() -> Unit)? = null,
) {
    // Long-press context menu (Columba UI/UX parity audit finding: a
    // long-press row menu, not a 3rd always-visible icon) carries the
    // less-frequent/destructive actions — Mark as Unread, Delete, Block —
    // so this row doesn't need a 4th trailing icon for each one. Favorite
    // and Call stay as always-visible icons (already-established,
    // frequently-used affordances, not touched here).
    var showMenu by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .combinedClickable(onClick = onClick, onLongClick = { showMenu = true })
                // 4dp (the redesign's own "tight/inline" grid tier) — was
                // 12.dp originally, then a not-quite-grid-aligned 6.dp; a
                // HorizontalDivider between rows now provides visual
                // separation.
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ContactAvatar(summary.contact)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary.contact.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitleFor(summary.contact, summary.lastMessage),
                    style = MaterialTheme.typography.bodySmall,
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
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            // Zeroed touch-target reservation — without it, IconButton pads
            // itself out to the ~48dp accessibility minimum regardless of
            // the icon's own size, which read as way too much horizontal
            // space on either side of these two in a dense row (real
            // on-device report). Same fix as every other compact icon
            // control in this app (BrowserScreen's nav row, the TCP table).
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                if (summary.contact.isCallCapable) {
                    // Phase 1a: now a real tap-to-call target (Phase 0 shipped
                    // it as plain/non-interactive first — "the icon will
                    // eventually become the way to start a phone call with
                    // them," now true). Green (NomadAccent2, the same
                    // "confirmed/good" green FetchStatusDot uses for a
                    // successful page load) since a confirmed call announce
                    // is the only case this icon shows at all.
                    IconButton(onClick = onCall, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Call,
                            contentDescription = "Call ${summary.contact.displayName}",
                            tint = NomadAccent2,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (summary.contact.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (summary.contact.isFavorite) "Unfavorite" else "Favorite",
                        tint = if (summary.contact.isFavorite) MaterialTheme.colorScheme.primary else NomadTextDim,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        ConversationContextMenu(
            expanded = showMenu,
            onDismiss = { showMenu = false },
            isBlocked = summary.contact.isBlocked,
            showMarkUnread = summary.unreadCount == 0,
            onMarkUnread = { showMenu = false; onMarkUnread() },
            onToggleBlock = { showMenu = false; onToggleBlock() },
            onDelete = onDelete?.let { delete -> { showMenu = false; delete() } },
        )
    }
}

/**
 * Long-press context menu for [ConversationRow] — a Columba UI/UX
 * parity-audit finding: Columba offers this same set of secondary
 * actions (plus Save/Remove Contact and View Peer Details, which this
 * app already surfaces differently — the always-visible favorite star,
 * and no separate contact-detail screen yet) from a long-press menu
 * rather than a wall of always-visible icons.
 */
@Composable
private fun ConversationContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isBlocked: Boolean,
    showMarkUnread: Boolean,
    onMarkUnread: () -> Unit,
    onToggleBlock: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (showMarkUnread) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.MarkEmailUnread, contentDescription = null) },
                text = { Text("Mark as Unread") },
                onClick = onMarkUnread,
            )
        }
        if (onDelete != null) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                text = { Text("Delete Conversation", color = MaterialTheme.colorScheme.error) },
                onClick = onDelete,
            )
        }
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = if (isBlocked) Icons.Filled.LockOpen else Icons.Filled.Block,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            text = {
                Text(if (isBlocked) "Unblock User" else "Block User", color = MaterialTheme.colorScheme.error)
            },
            onClick = onToggleBlock,
        )
    }
}

/**
 * The Calls tab: the two call-specific actions that used to sit in the
 * shared search row on every tab (moved out per explicit direction —
 * "the phone icon should only be in the calls sub tab of messages"),
 * plus a list of every known contact who's ever announced call support
 * (Phase 0's own signal — see call_tracker.py), each tappable to call
 * directly. Phase 1a is signalling only (see call_manager.py's own doc
 * comment) — this tab surfaces what's real today (who's reachable, a
 * way to reach someone new) without pretending to be a call log, which
 * needs actual call sessions to exist first.
 */
@Composable
private fun CallsTab(
    history: List<CallHistoryEntry>,
    onCallByAddress: () -> Unit,
    onAnnounce: () -> Unit,
) {
    // The two call-specific actions plus a real call-history list — per
    // explicit direction, no separate call-capable-contacts list here
    // (that duplicated what the Users tab's own new filter checkbox
    // already covers) and no fabricated placeholder rows either; an
    // empty list still renders an honest "No calls yet" until
    // call_manager.py's CallManager.history actually has entries.
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onCallByAddress) {
                Icon(
                    imageVector = Icons.Filled.Call,
                    contentDescription = null,
                    tint = NomadAccent2,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Call an address")
            }
            // A real periodic loop already re-announces this device's
            // own call address automatically, but a manual trigger
            // matters on its own too — real on-device request:
            // "eventually the call address auto announce will need its
            // own auto announce toggle and manual announce toggle." This
            // is the manual half; the toggle UI is still deliberately
            // deferred.
            TextButton(onClick = onAnnounce) {
                Icon(
                    imageVector = Icons.Filled.Campaign,
                    contentDescription = null,
                    tint = NomadTextDim,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Announce")
            }
        }
        HorizontalDivider()
        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = "No calls yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NomadTextDim,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(history) { entry ->
                    CallHistoryRow(entry)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun CallHistoryRow(entry: CallHistoryEntry) {
    // A call that was never answered (no establishedAtMillis) reads as
    // "missed" when incoming, regardless of the exact terminal status
    // (busy/rejected/failed/timed-out ended all mean the same thing to
    // the person who never picked up) — matches how every real phone's
    // own call log collapses those cases into one "missed call" concept.
    val wasAnswered = entry.establishedAtMillis != null
    val (icon, iconTint) = when {
        !entry.isIncoming -> Icons.AutoMirrored.Filled.CallMade to NomadTextDim
        wasAnswered -> Icons.Filled.CallReceived to NomadAccent2
        else -> Icons.Filled.CallMissed to NomadError
    }
    val label = when {
        !entry.isIncoming && wasAnswered -> "Outgoing call"
        !entry.isIncoming -> "Outgoing call — ${statusLabel(entry)}"
        wasAnswered -> "Incoming call"
        else -> "Missed call"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.remoteName ?: entry.remoteIdentityHash?.take(16) ?: "Unknown",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = NomadTextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatRelativeTime(entry.endedAtMillis ?: 0L),
            style = MaterialTheme.typography.labelSmall,
            color = NomadTextDim,
        )
    }
}

private fun statusLabel(entry: CallHistoryEntry): String = when (entry.status) {
    CallStatusValue.BUSY -> "busy"
    CallStatusValue.REJECTED -> "rejected"
    CallStatusValue.FAILED -> "failed"
    else -> "no answer"
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
