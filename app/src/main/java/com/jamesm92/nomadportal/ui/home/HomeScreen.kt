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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jamesm92.nomadportal.connectivity.HostedNodeStatus
import com.jamesm92.nomadportal.connectivity.InterfaceController
import com.jamesm92.nomadportal.data.messaging.AnnounceStatus
import com.jamesm92.nomadportal.data.messaging.ContactIcon
import com.jamesm92.nomadportal.data.messaging.ICON_APPEARANCE_NAMES
import com.jamesm92.nomadportal.data.messaging.MdiIconRepository
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.data.messaging.materialIconFor
import com.jamesm92.nomadportal.data.messaging.parseHexColor
import com.jamesm92.nomadportal.data.messaging.toHexString
import com.jamesm92.nomadportal.panicwipe.PanicWipe
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.AppLogo
import com.jamesm92.nomadportal.ui.components.CompactTextField
import com.jamesm92.nomadportal.ui.components.MessagesIconWithBadge
import com.jamesm92.nomadportal.ui.components.MinutesField
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
    interfaceController: InterfaceController,
    onOpenSettings: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenNodes: () -> Unit,
    onOpenHostedNode: (nodeHash: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val announceStatus by messagingRepository.announceStatus().collectAsState(initial = null)
    val conversations by messagingRepository.conversations().collectAsState(initial = emptyList())
    val totalUnread = conversations.sumOf { it.unreadCount }
    val hostedNodeStatus by interfaceController.hostedNodeStatus().collectAsState(initial = null)

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

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            hostedNodeStatus?.let { status ->
                HostedNodeSection(
                    status = status,
                    onToggle = { scope.launch { interfaceController.setNodeHostingEnabled(it) } },
                    onRename = { name -> scope.launch { interfaceController.setHostedNodeName(name) } },
                    onAnnounceNow = { scope.launch { interfaceController.announceHostedNodeNow() } },
                    onAnnounceIntervalChange = {
                        scope.launch { interfaceController.setHostedNodeAnnounceInterval(it) }
                    },
                    onOpen = { hash -> onOpenHostedNode(hash) },
                )
            }
        }
    }
}

/**
 * This device's own hosted NomadNet node: name, on/off, announce
 * interval, manual "Announce now", and a way to view it (opens the same
 * browser screen used for any other node) — see this file's own doc
 * comment for why manual announcing lives here and not Settings (that
 * screen only ever owns *configuration*, never a "do it now" action).
 * A duplicate on/off toggle and announce-interval config also live on
 * Settings' Node tab, per explicit design direction — flip/configure
 * from either place, same underlying state, same convention every other
 * interface's Settings tab already follows.
 */
@Composable
private fun HostedNodeSection(
    status: HostedNodeStatus,
    onToggle: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onAnnounceNow: () -> Unit,
    onAnnounceIntervalChange: (seconds: Int) -> Unit,
    onOpen: (nodeHash: String) -> Unit,
) {
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember(status.nodeName) { mutableStateOf(status.nodeName ?: "") }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Hosted node",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
            Switch(checked = status.enabled, onCheckedChange = onToggle)
        }

        if (!status.enabled) {
            Text(
                text = "Off — this device isn't serving any pages right now.",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.85f,
                ),
                color = NomadTextDim,
                modifier = Modifier.padding(top = 4.dp),
            )
            return
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (editingName) {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.9f,
                    ),
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
                    text = status.nodeName ?: "Unnamed node",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f, fill = false),
                )
                IconButton(onClick = { editingName = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename")
                }
            }
        }

        status.nodeHash?.let { hash ->
            Text(
                text = hash,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.55f,
                ),
                color = NomadTextDim,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = if (status.lastAnnounceAtMillis != null) {
                "Last announced ${formatRelativeAnnounceTime(status.lastAnnounceAtMillis)}"
            } else {
                "Never announced yet"
            },
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.7f,
            ),
            color = NomadTextDim,
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Auto-announce (min)",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.75f,
                ),
            )
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
            status.nodeHash?.let { hash ->
                TextButton(onClick = { onOpen(hash) }) { Text("View") }
            }
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

/** The three accordion sections [IconAppearanceEditor] toggles between —
 * only one expanded at a time (see that function's own doc comment). */
private enum class EditorSection { BACKGROUND, FOREGROUND, ICON }

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

/** Preset background/foreground swatches offered by the editor below —
 * a small curated palette (this app's own accent colors plus basics)
 * rather than a full RGB picker, matching the rest of this app's
 * understated aesthetic. Any '#rrggbb' is valid over LXMF regardless —
 * this is just what the editor itself offers to tap. */
private val ICON_COLOR_SWATCHES = listOf(
    Color.White, Color.Black,
    Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835), Color(0xFF43A047),
    Color(0xFF00897B), Color(0xFF00ACC1), Color(0xFF1E88E5), Color(0xFF3949AB),
    Color(0xFF8E24AA), Color(0xFFD81B60), Color(0xFF6D4C41), Color(0xFF757575),
    NomadAccent, NomadAccent2,
)

/**
 * Inline editor for this device's own [ContactIcon.Appearance] — three
 * accordion sections (background color, foreground color, icon), only
 * one expanded at a time, per explicit direction: the icon picker
 * itself "still needs to be a collapsed view" (a compact preview row
 * like the color rows already were, not always-expanded), and opening
 * it should mean "only icons are in view" — expanding one section via
 * [expandedSection] structurally collapses whichever other section was
 * open, so the icon list is never sharing screen space with a color
 * grid above it.
 *
 * The icon section keeps its own bottom Save/Cancel row *inside* the
 * expanded section, not floating at the whole editor's very bottom —
 * that's specifically what avoided the earlier real on-device report
 * (Save landing off-screen below a tall list): the list is the only
 * other thing visible while it's open, so Save is always right below
 * it, never buried under two more open sections above.
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
    val filteredNames = remember(searchQuery, allNames) {
        val q = searchQuery.trim().lowercase().replace(' ', '_').replace('-', '_')
        if (q.isBlank()) {
            allNames
        } else {
            allNames.filter { it.replace('-', '_').contains(q) }
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
                selectedGlyph = selectedGlyph,
                selectedBg = selectedBg,
                selectedFg = selectedFg,
                listState = listState,
                onSelect = { selectedGlyph = it },
                onSave = { onSave(selectedGlyph, selectedFg, selectedBg) },
                onDismiss = { expandedSection = null },
            )
        }
    }
}

/**
 * Takes over the entire screen — per explicit direction — while picking
 * an icon, rather than expanding inline within [IconAppearanceEditor]'s
 * bordered box (which was itself already nested inside Home's own
 * scrollable column, capping the list to a fixed 320dp regardless of
 * actual screen size). A real full-screen [Dialog] renders in its own
 * window above everything else, so the list can use `weight(1f)` to
 * claim all remaining vertical space instead of a bounded height guess,
 * and Save sits in a real fixed footer row at the true bottom of the
 * screen, not just below a capped-height list nested several sections
 * deep.
 */
@Composable
private fun FullScreenIconPicker(
    query: String,
    onQueryChange: (String) -> Unit,
    names: List<String>,
    selectedGlyph: String,
    selectedBg: Color,
    selectedFg: Color,
    listState: LazyListState,
    onSelect: (String) -> Unit,
    onSave: () -> Unit,
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
                    // Same reasoning as every other imePadding() fix in
                    // this app — a Dialog is its own window, so it needs
                    // this independently of whatever HomeScreen itself does.
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
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                // Live-updates with the colors selected
                                // in the collapsed editor behind this —
                                // free from Compose's own recomposition
                                // since this reads the same selectedBg/
                                // selectedFg state passed in.
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

                // A real fixed footer row — per explicit direction — not
                // just "below the list" within a longer scrolling page.
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Button(onClick = onSave) { Text("Save") }
                }
            }
        }
    }
}

/**
 * "Micron compatible" per explicit direction: real NomadNet Micron
 * markup's own color model (confirmed directly against
 * `nomadnet/ui/textui/MicronParser.py`) is plain hex — a full 6-digit
 * `` `Fxxxxxx`` or a 3-digit shorthand `` `Fxyz`` (each digit doubled,
 * same convention CSS's own hex shorthand uses — already how this
 * app's deterministic-identity color generation works, see
 * identity_store.py's `_hex_shorthand_to_rgb`) — not a fixed named
 * palette (there is no such thing in real Micron). So this offers 16
 * common colors as fast quick-picks, plus a free-entry hex field for
 * anything else — any 6-digit hex is valid Micron regardless of
 * whether it's one of the 16.
 */
@Composable
private fun ColorSwatchRow(selected: Color, onSelect: (Color) -> Unit, modifier: Modifier = Modifier) {
    var hexDraft by remember(selected) { mutableStateOf(selected.toHexString()) }

    Column(modifier = modifier) {
        ICON_COLOR_SWATCHES.chunked(8).forEach { row ->
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { swatch ->
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
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactTextField(
                value = hexDraft,
                onValueChange = { hexDraft = it },
                placeholder = "#rrggbb",
                modifier = Modifier.width(110.dp),
            )
            TextButton(onClick = { onSelect(parseHexColor(hexDraft, selected)) }) {
                Text("Use")
            }
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
