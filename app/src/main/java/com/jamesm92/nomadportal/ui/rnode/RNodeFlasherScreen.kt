package com.jamesm92.nomadportal.ui.rnode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.connectivity.rnode.FlashState
import com.jamesm92.nomadportal.connectivity.rnode.RnodeDeviceInfo
import com.jamesm92.nomadportal.connectivity.rnode.RnodeFirmwareRelease
import com.jamesm92.nomadportal.connectivity.rnode.RnodeFlasherManager
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import kotlinx.coroutines.launch

/**
 * The official-RNode-firmware flasher — reached from Settings' RNode
 * section. Per explicit direction, there is no firmware-source picker at
 * all here (unlike Columba's own real flasher's `Official`/
 * `MicroReticulum`/`CommunityEdition`/`Custom` choice) — this screen only
 * ever installs releases from `markqvist/RNode_Firmware`'s real GitHub
 * Releases API (see `RnodeFlasherManager`/`rnode_firmware.py`'s own doc
 * comments for the real, source-verified protocol underneath).
 *
 * A simple sequential flow rather than a full multi-page wizard (matches
 * this app's own "compact, single scrollable screen" convention over
 * introducing new navigation-within-navigation): pick a device, pick a
 * release + board, confirm, watch real per-block flash progress.
 */
@Composable
fun RNodeFlasherScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember(context) { RnodeFlasherManager(context.applicationContext) }
    val flashState by manager.flashState.collectAsState()

    var devices by remember { mutableStateOf<List<RnodeDeviceInfo>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<RnodeDeviceInfo?>(null) }

    var releases by remember { mutableStateOf<List<RnodeFirmwareRelease>>(emptyList()) }
    var supportedBoards by remember { mutableStateOf<Set<String>>(emptySet()) }
    var releasesError by remember { mutableStateOf<String?>(null) }
    var selectedRelease by remember { mutableStateOf<RnodeFirmwareRelease?>(null) }
    var selectedBoard by remember { mutableStateOf<String?>(null) }

    fun refreshDevices() {
        devices = manager.availableDevices()
    }

    LaunchedEffect(Unit) {
        refreshDevices()
        try {
            releases = manager.fetchReleases()
            supportedBoards = manager.fetchSupportedBoards()
        } catch (e: Exception) {
            releasesError = e.message ?: "Could not fetch firmware releases"
        }
    }

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = { Text("Flash RNode Firmware") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    "Installs the official RNode firmware from markqvist/RNode_Firmware " +
                        "onto an ESP32-based board over USB. Custom or third-party firmware " +
                        "is not supported by this flasher.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NomadTextDim,
                )
            }

            item { SectionHeader("1. Select USB device") }
            if (devices.isEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "No USB-serial devices detected. Plug in the board (hold its BOOT " +
                                "button if it doesn't enter bootloader mode automatically).",
                            style = MaterialTheme.typography.bodySmall,
                            color = NomadTextDim,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { refreshDevices() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Rescan")
                        }
                    }
                }
            } else {
                items(devices, key = { it.deviceId }) { device ->
                    SelectableRow(
                        label = device.productName ?: device.deviceName,
                        sublabel = "VID ${"%04X".format(device.vendorId)} · PID ${"%04X".format(device.productId)}",
                        selected = selectedDevice?.deviceId == device.deviceId,
                        icon = Icons.Filled.Usb,
                        onClick = { selectedDevice = device },
                    )
                }
            }

            item { SectionHeader("2. Select firmware") }
            if (releasesError != null) {
                item { Text(releasesError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            } else if (releases.isEmpty()) {
                item { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
            } else {
                items(releases, key = { it.tag }) { release ->
                    SelectableRow(
                        label = "v${release.tag}",
                        sublabel = release.publishedAt.take(10),
                        selected = selectedRelease?.tag == release.tag,
                        icon = null,
                        onClick = {
                            selectedRelease = release
                            if (selectedBoard !in release.boards) selectedBoard = null
                        },
                    )
                }
                val release = selectedRelease
                if (release != null) {
                    item {
                        Text("Board", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                    items(release.boards, key = { it }) { board ->
                        val supported = board in supportedBoards
                        SelectableRow(
                            label = board,
                            sublabel = if (supported) null else "Not supported by this flasher yet",
                            selected = selectedBoard == board,
                            icon = null,
                            enabled = supported,
                            onClick = { selectedBoard = board },
                        )
                    }
                }
            }

            item { SectionHeader("3. Flash") }
            item {
                FlashStatusSection(
                    flashState = flashState,
                    canFlash = selectedDevice != null && selectedRelease != null && selectedBoard != null,
                    onFlash = {
                        val device = selectedDevice
                        val release = selectedRelease
                        val board = selectedBoard
                        if (device != null && release != null && board != null) {
                            scope.launch { manager.flash(device, release.tag, board) }
                        }
                    },
                    onReset = { manager.reset() },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun SelectableRow(
    label: String,
    sublabel: String?,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.primary else NomadTextDim)
                Spacer(Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else NomadTextDim)
                if (sublabel != null) {
                    Text(sublabel, style = MaterialTheme.typography.labelSmall, color = NomadTextDim)
                }
            }
            if (selected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun FlashStatusSection(
    flashState: FlashState,
    canFlash: Boolean,
    onFlash: () -> Unit,
    onReset: () -> Unit,
) {
    when (flashState) {
        is FlashState.Idle -> {
            Column {
                Text(
                    "Do not unplug the device while flashing is in progress.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NomadTextDim,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onFlash, enabled = canFlash, modifier = Modifier.fillMaxWidth()) {
                    Text("Flash official firmware")
                }
            }
        }
        is FlashState.Connecting -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Connecting and downloading firmware…")
            }
        }
        is FlashState.Flashing -> {
            val progress = if (flashState.total > 0) flashState.current.toFloat() / flashState.total else 0f
            Column {
                Text("Flashing… ${(progress * 100).toInt()}%")
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        is FlashState.Success -> {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Firmware flashed successfully.")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onReset) { Text("Flash another device") }
            }
        }
        is FlashState.Error -> {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(flashState.message, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onFlash, enabled = canFlash, modifier = Modifier.fillMaxWidth()) {
                    Text("Retry")
                }
            }
        }
    }
}
