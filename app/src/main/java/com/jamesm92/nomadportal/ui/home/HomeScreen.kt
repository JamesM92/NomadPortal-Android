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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
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
                    IconButton(onClick = onOpenMessages) {
                        Icon(Icons.AutoMirrored.Filled.Message, contentDescription = "Messages")
                    }
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

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IdentityIconPreview(
                appearance = status.iconAppearance,
                onClick = { editingIcon = !editingIcon },
            )

            if (editingName) {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
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
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { editingName = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename")
                }
            }
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

/** Small circular preview of this device's own icon appearance, tappable
 * to open [IconAppearanceEditor]. Renders the same way
 * [com.jamesm92.nomadportal.ui.components.ContactAvatar] renders a
 * contact's [ContactIcon.Appearance] — duplicated rather than shared
 * since that composable takes a full [com.jamesm92.nomadportal.data.messaging.Contact],
 * which this device's own identity isn't one of. */
@Composable
private fun IdentityIconPreview(appearance: ContactIcon.Appearance?, onClick: () -> Unit) {
    val vector = remember(appearance?.glyphName) { appearance?.glyphName?.let(::materialIconFor) }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(appearance?.backgroundColor ?: NomadBg3)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (vector != null && appearance != null) {
            Icon(
                imageVector = vector,
                contentDescription = "Edit icon",
                tint = appearance.foregroundColor,
                modifier = Modifier.size(28.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Set icon",
                tint = NomadTextDim,
                modifier = Modifier.size(20.dp),
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
 * Inline editor for this device's own [ContactIcon.Appearance]: a
 * horizontally-scrollable row of every icon name this app can actually
 * resolve to a real glyph ([ICON_APPEARANCE_NAMES] — picking only from
 * these means the preview the user sees while editing is exactly what
 * every peer capable of resolving the same name will also see, never a
 * name that silently falls back to a letter for everyone), plus
 * background/foreground swatch pickers.
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .border(1.dp, NomadBg3, shape = RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "Choose an icon", style = MaterialTheme.typography.bodyLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ICON_APPEARANCE_NAMES) { name ->
                val vector = materialIconFor(name)
                if (vector != null) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (name == selectedGlyph) selectedBg else NomadBg3)
                            .clickable { selectedGlyph = name },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = vector,
                            contentDescription = name,
                            tint = if (name == selectedGlyph) selectedFg else NomadTextDim,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

        Text(text = "Background color", style = MaterialTheme.typography.bodyLarge)
        ColorSwatchRow(selected = selectedBg, onSelect = { selectedBg = it })

        Text(text = "Icon color", style = MaterialTheme.typography.bodyLarge)
        ColorSwatchRow(selected = selectedFg, onSelect = { selectedFg = it })

        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { onSave(selectedGlyph, selectedFg, selectedBg) }) { Text("Save") }
        }
    }
}

@Composable
private fun ColorSwatchRow(selected: Color, onSelect: (Color) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
