package com.jamesm92.nomadportal.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.messaging.AnnounceStatus
import com.jamesm92.nomadportal.data.messaging.ContactIcon
import com.jamesm92.nomadportal.data.messaging.ICON_APPEARANCE_NAMES
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.data.messaging.materialIconFor
import com.jamesm92.nomadportal.panicwipe.PanicWipe
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.AppLogo
import com.jamesm92.nomadportal.ui.components.MessagesIconWithBadge
import com.jamesm92.nomadportal.ui.components.SearchField
import com.jamesm92.nomadportal.ui.theme.NomadAccent
import com.jamesm92.nomadportal.ui.theme.NomadAccent2
import com.jamesm92.nomadportal.ui.theme.NomadBg3
import com.jamesm92.nomadportal.ui.theme.NomadError
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import com.jamesm92.nomadportal.ui.theme.NomadWarn
import kotlinx.coroutines.launch

/**
 * Home shell — the app's identity-management surface, per explicit
 * design direction: manual announcing and renaming for this device's
 * own LXMF identity live *here*, not in Settings (Settings only owns
 * configuration — thresholds, connection lists, on/off — never a "do it
 * now" action; see that screen's own doc comment). Also owns the app's
 * top bar, including the panic-wipe triple-tap target ([AppLogo]) — kept
 * here rather than duplicated per-screen since Home is this app's
 * "always reachable" root.
 *
 * A hosted-node identity section (rename + manual announce, matching
 * the LXMF one below) is NOT here yet, even though it was requested
 * alongside this — node hosting has no real `SiteServer` behind it yet
 * (see [com.jamesm92.nomadportal.connectivity.RealInterfaceController]'s
 * own doc comment: Settings' hosting toggle is still persisted-intent-
 * only). Building rename/announce controls for an identity that doesn't
 * exist yet would be exactly the "toggle that doesn't actually control
 * what it claims to" anti-pattern this app's connectivity design
 * otherwise goes out of its way to avoid — this needs real SiteServer
 * wiring first (sequencing step 5), not a cosmetic stand-in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    messagingRepository: MessagingRepository,
    onOpenSettings: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenNodes: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val announceStatus by messagingRepository.announceStatus().collectAsState(initial = null)
    val conversations by messagingRepository.conversations().collectAsState(initial = emptyList())
    val totalUnread = conversations.sumOf { it.unreadCount }

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = {
                    AppLogo(onTripleTap = {
                        scope.launch {
                            PanicWipe.perform(context)
                            PanicWipe.restartApp(context)
                        }
                    })
                },
                actions = {
                    IconButton(onClick = onOpenNodes) {
                        Icon(Icons.Filled.Explore, contentDescription = "Browse nodes")
                    }
                    MessagesIconWithBadge(unreadCount = totalUnread, onClick = onOpenMessages)
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // enableEdgeToEdge() (MainActivity) means the system no
                // longer auto-resizes/pans the window when the IME opens
                // — the app has to claim that inset itself, same root
                // cause as ConversationScreen's earlier message-field-
                // hidden-behind-keyboard fix. Without this, the icon
                // editor's search field/list end up behind the keyboard
                // once it opens (real on-device report). Applied before
                // verticalScroll so the keyboard shrinks the visible
                // scrollable area rather than the content just being
                // covered by it.
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                // The icon editor's picker grid can push this section
                // taller than short/landscape screens — scrollable rather
                // than clipped, matching the scroll-indicator convention
                // already established on Settings.
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            announceStatus?.let { status ->
                IdentitySection(
                    status = status,
                    onRename = { name -> scope.launch { messagingRepository.setDisplayName(name) } },
                    onAnnounceNow = { scope.launch { messagingRepository.announceNow() } },
                    onSaveIcon = { glyph, fg, bg ->
                        scope.launch { messagingRepository.setIconAppearance(glyph, fg, bg) }
                    },
                )
            }
        }
    }
}

/**
 * This device's own LXMF identity: an editable display name, an editable
 * icon appearance, its address, how long since it last announced, and a
 * manual "Announce now" trigger — see [HomeScreen]'s own doc comment for
 * why this lives here and not in Settings.
 */
@Composable
private fun IdentitySection(
    status: AnnounceStatus,
    onRename: (String) -> Unit,
    onAnnounceNow: () -> Unit,
    onSaveIcon: (glyphName: String, foreground: Color, background: Color) -> Unit,
) {
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember(status.displayName) { mutableStateOf(status.displayName ?: "") }
    var editingIcon by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Identity",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.secondary,
        )

        // Name first, then icon below — per explicit follow-up direction
        // (was icon-then-name); both stay centered, profile-header style.
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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

        Text(
            text = status.lxmfAddress?.let { "LXMF: ${it.take(16)}…" } ?: "LXMF address not ready yet",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.8f,
            ),
            color = NomadTextDim,
        )
        Text(
            text = status.lastAnnounceAtMillis?.let { "Last announced ${formatSince(it)} ago" }
                ?: "Never announced yet",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.8f,
            ),
            color = NomadTextDim,
        )

        Button(onClick = onAnnounceNow, modifier = Modifier.padding(top = 8.dp)) {
            Text("Announce now")
        }

        if (status.sendBlocked) {
            Text(
                text = status.sendBlockedReason ?: "Sending is currently blocked.",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.85f,
                ),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
    }
}

/** Circular preview of this device's own icon appearance, tappable to
 * open [IconAppearanceEditor] — the whole circle is a tap target (as
 * before), plus a small pencil badge overlaid at its bottom-right corner
 * so the edit affordance is actually visible, matching the name row's
 * own explicit pencil [IconButton] rather than relying on an undiscoverable
 * "the whole circle is secretly clickable" convention. Otherwise renders
 * the same way [com.jamesm92.nomadportal.ui.components.ContactAvatar]
 * renders a contact's [ContactIcon.Appearance] — duplicated rather than
 * shared since that composable takes a full
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

private enum class ColorSection { BACKGROUND, FOREGROUND }

/** Collapsed-by-default color picker: a tappable one-line summary (swatch
 * dot + label + chevron) that expands to the full [ColorSwatchRow] grid
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
            ColorSwatchRow(
                selected = selected,
                onSelect = onSelect,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Preset background/foreground swatches offered by the editor below —
 * a small curated palette (this app's own accent colors plus basics)
 * rather than a full RGB picker, matching the rest of this app's
 * understated aesthetic. Any '#rrggbb' is valid over LXMF regardless —
 * this is just what the editor itself offers to tap. */
private val ICON_COLOR_SWATCHES = listOf(
    Color.White, Color.Black, NomadAccent, NomadAccent2, NomadWarn, NomadError,
    Color(0xFF9575CD), Color(0xFF4DB6AC),
)

/**
 * Inline editor for this device's own [ContactIcon.Appearance]. Order
 * is deliberate, per explicit direction: background color, then
 * foreground color, *then* a searchable vertical list of every icon
 * name this app can resolve ([ICON_APPEARANCE_NAMES] — now the full
 * [com.jamesm92.nomadportal.data.messaging.materialIconFor] catalog,
 * search is what makes browsing that practical) — colors first so each
 * icon row can preview itself live in the colors already chosen, rather
 * than picking an icon before knowing what it'll actually look like.
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
    // Which color section (if any) is showing its full swatch grid —
    // per explicit direction, a color is "assumed already picked" (both
    // start with a real default, never blank) so the swatch grid stays
    // collapsed to a compact one-line summary until tapped open, rather
    // than permanently occupying space above the icon list/keyboard.
    // Picking a swatch collapses it back down immediately.
    var expandedColorSection by remember { mutableStateOf<ColorSection?>(null) }

    val filteredNames = remember(searchQuery) {
        val q = searchQuery.trim().lowercase().replace(' ', '_')
        if (q.isBlank()) ICON_APPEARANCE_NAMES else ICON_APPEARANCE_NAMES.filter { it.contains(q) }
    }

    // Scrolls to whatever's already selected every time this editor is
    // (re)opened — per explicit request — rather than always starting
    // back at the front of the list, which previously meant re-finding
    // your own icon by scrolling every single time. Only meaningful
    // against the unfiltered list (search starts blank each open).
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        val index = ICON_APPEARANCE_NAMES.indexOf(selectedGlyph)
        if (index >= 0) {
            listState.scrollToItem(index)
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
        CompactColorRow(
            label = "Background color",
            selected = selectedBg,
            expanded = expandedColorSection == ColorSection.BACKGROUND,
            onToggle = {
                expandedColorSection = if (expandedColorSection == ColorSection.BACKGROUND) {
                    null
                } else {
                    ColorSection.BACKGROUND
                }
            },
            onSelect = {
                selectedBg = it
                expandedColorSection = null
            },
        )

        CompactColorRow(
            label = "Icon color",
            selected = selectedFg,
            expanded = expandedColorSection == ColorSection.FOREGROUND,
            onToggle = {
                expandedColorSection = if (expandedColorSection == ColorSection.FOREGROUND) {
                    null
                } else {
                    ColorSection.FOREGROUND
                }
            },
            onSelect = {
                selectedFg = it
                expandedColorSection = null
            },
        )

        Text(text = "Choose an icon", style = MaterialTheme.typography.bodyLarge)
        SearchField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Search icons",
            modifier = Modifier.fillMaxWidth(),
        )
        // Bounded height — this list nests inside HomeScreen's own
        // verticalScroll Column, so an unbounded LazyColumn here would
        // conflict with that outer scroll's own height constraints
        // (same reasoning as NodeListScreen's bounded Favorites pane).
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
            items(filteredNames, key = { it }) { name ->
                val vector = materialIconFor(name)
                if (vector != null) {
                    val isSelected = name == selectedGlyph
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedGlyph = name }
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                } else {
                                    Color.Transparent
                                },
                            )
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Live-updates with the colors selected above —
                        // per explicit request ("icons in list shoukd
                        // update based on the colors selected") — free
                        // from Compose's own recomposition since this
                        // reads the same selectedBg/selectedFg state.
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
                            text = name.replace('_', ' '),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.85f,
                            ),
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

        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { onSave(selectedGlyph, selectedFg, selectedBg) }) { Text("Save") }
        }
    }
}

@Composable
private fun ColorSwatchRow(selected: Color, onSelect: (Color) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ICON_COLOR_SWATCHES.forEach { swatch ->
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(swatch)
                    .border(
                        width = if (swatch == selected) 2.dp else 1.dp,
                        color = if (swatch == selected) MaterialTheme.colorScheme.secondary else NomadBg3,
                        shape = CircleShape,
                    )
                    .clickable { onSelect(swatch) },
            )
        }
    }
}

/** Same convention as every other relative-time helper in this app —
 * bare duration, caller appends " ago" itself. */
private fun formatSince(millis: Long): String {
    val diffSeconds = ((System.currentTimeMillis() - millis) / 1000).coerceAtLeast(0)
    return when {
        diffSeconds < 3600 -> "${diffSeconds / 60}m"
        diffSeconds < 86_400 -> "${diffSeconds / 3600}h"
        else -> "${diffSeconds / 86_400}d"
    }
}
