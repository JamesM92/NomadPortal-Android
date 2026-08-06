package com.jamesm92.nomadportal.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "nomadportal_settings")

/**
 * Persists the user's connectivity/hosting *intent* — "I want TCP on" —
 * separately from whether an RNS interface is actually live. Pure state,
 * no side effects; [com.jamesm92.nomadportal.connectivity.InterfaceController]
 * implementations are what turn this into (or out of) actually running
 * interfaces. Kept as its own class so that wiring stays identical once a
 * real [com.jamesm92.nomadportal.connectivity.InterfaceController] exists —
 * only the controller layer changes.
 *
 * Defaults are deliberately opt-in for anything that either needs a
 * permission grant or changes what this device exposes: TCP defaults on
 * (the common case, no extra permission needed, and doesn't announce this
 * device to anyone who isn't already being connected to). Everything else
 * — including Wi-Fi discovery, which auto-announces this device's presence
 * to every local network it joins via multicast — defaults off until the
 * user explicitly turns it on. This matches the "permissions are optional
 * and nothing is requested/broadcast until the feature that needs it is
 * actually turned on" policy in nomadportal_android_handoff.md.
 */
class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val tcpEnabled: Flow<Boolean> = boolFlow(KEY_TCP, default = true)
    val bluetoothMeshEnabled: Flow<Boolean> = boolFlow(KEY_BLUETOOTH_MESH, default = false)
    val rNodeEnabled: Flow<Boolean> = boolFlow(KEY_RNODE, default = false)
    val wifiDiscoveryEnabled: Flow<Boolean> = boolFlow(KEY_WIFI_DISCOVERY, default = false)
    val nodeHostingEnabled: Flow<Boolean> = boolFlow(KEY_NODE_HOSTING, default = false)

    suspend fun setTcpEnabled(enabled: Boolean) = setBool(KEY_TCP, enabled)
    suspend fun setBluetoothMeshEnabled(enabled: Boolean) = setBool(KEY_BLUETOOTH_MESH, enabled)
    suspend fun setRNodeEnabled(enabled: Boolean) = setBool(KEY_RNODE, enabled)
    suspend fun setWifiDiscoveryEnabled(enabled: Boolean) = setBool(KEY_WIFI_DISCOVERY, enabled)
    suspend fun setNodeHostingEnabled(enabled: Boolean) = setBool(KEY_NODE_HOSTING, enabled)

    private fun boolFlow(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, default: Boolean): Flow<Boolean> =
        dataStore.data.map { it[key] ?: default }

    private suspend fun setBool(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { it[key] = value }
    }

    private companion object {
        val KEY_TCP = booleanPreferencesKey("tcp_enabled")
        val KEY_BLUETOOTH_MESH = booleanPreferencesKey("bluetooth_mesh_enabled")
        val KEY_RNODE = booleanPreferencesKey("rnode_enabled")
        val KEY_WIFI_DISCOVERY = booleanPreferencesKey("wifi_discovery_enabled")
        val KEY_NODE_HOSTING = booleanPreferencesKey("node_hosting_enabled")
    }
}
