package com.jamesm92.nomadportal.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.connectivity.InterfaceController
import com.jamesm92.nomadportal.permissions.BLUETOOTH_PERMISSIONS
import com.jamesm92.nomadportal.permissions.hasBluetoothPermissions
import kotlinx.coroutines.launch

/**
 * Connectivity + hosting + permissions section, per
 * nomadportal_android_handoff.md's "Main menu / connectivity & privacy
 * controls". Every toggle here is wired to the real [InterfaceController]
 * interface — flipping one actually calls through to it (currently a
 * no-op stub, see [com.jamesm92.nomadportal.connectivity.NoopInterfaceController]),
 * not just a locally-held UI state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(interfaceController: InterfaceController, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val tcpEnabled by interfaceController.tcpEnabled.collectAsState()
    val bluetoothMeshEnabled by interfaceController.bluetoothMeshEnabled.collectAsState()
    val rNodeEnabled by interfaceController.rNodeEnabled.collectAsState()
    val wifiDiscoveryEnabled by interfaceController.wifiDiscoveryEnabled.collectAsState()
    val nodeHostingEnabled by interfaceController.nodeHostingEnabled.collectAsState()

    var bluetoothGranted by remember { mutableStateOf(hasBluetoothPermissions(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        bluetoothGranted = results.values.all { it }
        // Denial is a fully-supported end state, not an error: leave the
        // toggle off and keep going. Nothing else in this screen (or the
        // app) is allowed to hard-require this permission.
        if (bluetoothGranted) {
            scope.launch { interfaceController.setBluetoothMeshEnabled(true) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding)) {
            item { SectionHeader("Connectivity") }
            item {
                ToggleRow(
                    label = "TCP",
                    description = "Internet-based RNS interfaces (client + server).",
                    checked = tcpEnabled,
                    onCheckedChange = { scope.launch { interfaceController.setTcpEnabled(it) } },
                )
            }
            item {
                ToggleRow(
                    label = "Bluetooth mesh",
                    description = if (bluetoothGranted) {
                        "Local BLE mesh interface."
                    } else {
                        "Local BLE mesh interface. Requires Bluetooth permission — declining just keeps this off, nothing else in the app is affected."
                    },
                    checked = bluetoothMeshEnabled,
                    onCheckedChange = { turningOn ->
                        if (turningOn && !bluetoothGranted) {
                            permissionLauncher.launch(BLUETOOTH_PERMISSIONS)
                        } else {
                            scope.launch { interfaceController.setBluetoothMeshEnabled(turningOn) }
                        }
                    },
                )
            }
            item {
                ToggleRow(
                    label = "RNode",
                    description = "RNode interface, over USB or Bluetooth. Independent of the Bluetooth mesh toggle above — this does not affect it, and vice versa.",
                    checked = rNodeEnabled,
                    onCheckedChange = { scope.launch { interfaceController.setRNodeEnabled(it) } },
                )
            }
            item {
                ToggleRow(
                    label = "Local network discovery",
                    description = "Auto-discover other RNS nodes on the same Wi-Fi/LAN via multicast. Off by default — this announces this device's presence to whichever network you're on.",
                    checked = wifiDiscoveryEnabled,
                    onCheckedChange = { scope.launch { interfaceController.setWifiDiscoveryEnabled(it) } },
                )
            }

            item { HorizontalDivider() }
            item { SectionHeader("Hosting") }
            item {
                ToggleRow(
                    label = "Host a NomadNet node",
                    description = "Serve pages/files to other peers over whichever interfaces above are on. Independent of browsing — off here doesn't stop you from browsing the mesh.",
                    checked = nodeHostingEnabled,
                    onCheckedChange = { scope.launch { interfaceController.setNodeHostingEnabled(it) } },
                )
            }

            item { HorizontalDivider() }
            item { SectionHeader("Permissions") }
            item {
                Text(
                    text = "Every permission this app requests is optional. Denying any of them " +
                        "leaves the related feature off — the rest of the app keeps working. " +
                        "This app never requests location permission, under any circumstances.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
