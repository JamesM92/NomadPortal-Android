package com.jamesm92.nomadportal.ui.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.connectivity.InterfaceController
import com.jamesm92.nomadportal.connectivity.TcpConnection
import com.jamesm92.nomadportal.connectivity.TcpConnectionsRepository
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.theme.NomadAccent2
import com.jamesm92.nomadportal.ui.theme.NomadError
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import com.jamesm92.nomadportal.ui.theme.NomadWarn

/**
 * Read-only interface/connection status — a Columba UI/UX parity-audit
 * follow-up (the user's own framing: "we could setup a network tab like
 * columba"), though Columba itself has no literally-named Network tab
 * (its interface/discovery screens are reached from Settings, not a
 * bottom-nav tab — confirmed directly against its `AppDestination` enum,
 * not assumed) — this is a NomadPortal-original grouping, not a port.
 * Explicit scope, per direction: status only, no toggles here — every
 * interface's own on/off switch stays exactly where it already is, in
 * Settings' per-interface sections.
 *
 * **Honest about what's actually live**: TCP is the only interface with
 * real per-connection status ([TcpConnection.online], the RNS interface's
 * own connected/disconnected state) — Bluetooth mesh/RNode/local network
 * discovery only ever show their on/off toggle state here, not live
 * neighbor/link data, because nothing in this app's Kotlin layer
 * currently subscribes to that (Bluetooth mesh's own `RnsBleBridge.events`
 * already carries real neighbor-sighting data with RSSI —
 * [com.jamesm92.nomadportal.connectivity.BluetoothMeshManager] doesn't
 * expose it to anything yet; a real, well-scoped follow-up, not attempted
 * in this pass). This matches this app's own "authoritative toggle, never
 * fabricate a status" convention elsewhere (e.g.
 * [com.jamesm92.nomadportal.data.messaging.AnnounceStatus.hostedNodeHash]
 * staying honestly null rather than inventing a value).
 */
@Composable
fun NetworkScreen(
    interfaceController: InterfaceController,
    tcpConnectionsRepository: TcpConnectionsRepository,
) {
    val tcpEnabled by interfaceController.tcpEnabled.collectAsState()
    val bluetoothMeshEnabled by interfaceController.bluetoothMeshEnabled.collectAsState()
    val rNodeEnabled by interfaceController.rNodeEnabled.collectAsState()
    val wifiDiscoveryEnabled by interfaceController.wifiDiscoveryEnabled.collectAsState()
    val tcpConnections by tcpConnectionsRepository.connections().collectAsState(initial = emptyList())

    Scaffold(
        topBar = { AdaptiveTopAppBar(title = { Text("Network") }) },
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            item {
                Text(
                    text = "Live per-connection status is only available for TCP right now. " +
                        "Other interfaces show on/off state only. Toggles live in Settings.",
                    style = MaterialTheme.typography.labelSmall,
                    color = NomadTextDim,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item { HorizontalDivider() }

            item {
                InterfaceStatusRow(
                    label = "TCP",
                    enabled = tcpEnabled,
                    detail = if (!tcpEnabled) {
                        null
                    } else if (tcpConnections.isEmpty()) {
                        "No connections configured"
                    } else {
                        val online = tcpConnections.count { it.enabled && it.online }
                        val configured = tcpConnections.count { it.enabled }
                        "$online of $configured connections online"
                    },
                )
            }
            if (tcpEnabled) {
                items(tcpConnections, key = { it.id }) { connection ->
                    TcpConnectionStatusRow(connection)
                }
            }
            item { HorizontalDivider() }

            item {
                InterfaceStatusRow(
                    label = "Bluetooth mesh",
                    enabled = bluetoothMeshEnabled,
                )
            }
            item { HorizontalDivider() }

            item {
                InterfaceStatusRow(
                    label = "RNode",
                    enabled = rNodeEnabled,
                )
            }
            item { HorizontalDivider() }

            item {
                InterfaceStatusRow(
                    label = "Local network discovery",
                    enabled = wifiDiscoveryEnabled,
                )
            }
            item { HorizontalDivider() }
        }
    }
}

@Composable
private fun InterfaceStatusRow(label: String, enabled: Boolean, detail: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(color = if (enabled) NomadAccent2 else NomadTextDim)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail ?: if (enabled) "On" else "Off",
                style = MaterialTheme.typography.bodySmall,
                color = NomadTextDim,
            )
        }
    }
}

/** Sub-row under TCP for each configured connection — same read-only
 * status framing as [InterfaceStatusRow], but a 3rd color: amber for
 * "enabled but not currently online," matching [NomadWarn]'s established
 * "problem worth flagging, not necessarily an error" role elsewhere
 * (e.g. NodeListScreen's own half-filled fetch-status dot). */
@Composable
private fun TcpConnectionStatusRow(connection: TcpConnection) {
    val color = when {
        !connection.enabled -> NomadTextDim
        connection.online -> NomadAccent2
        else -> NomadWarn
    }
    val status = when {
        !connection.enabled -> "Disabled"
        connection.online -> "Online"
        else -> "Not connected"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 44.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(color = color, size = 8.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = connection.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${connection.host}:${connection.port} · $status",
                style = MaterialTheme.typography.labelSmall,
                color = NomadTextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusDot(color: Color, size: androidx.compose.ui.unit.Dp = 10.dp) {
    Canvas(modifier = Modifier.size(size).clip(CircleShape)) {
        drawCircle(color = color)
    }
}
