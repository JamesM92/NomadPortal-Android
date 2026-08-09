package com.jamesm92.nomadportal.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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

    // Gates the first-run OnboardingScreen (NomadNavHost.kt) -- false
    // until the user actually reaches/skips the end of that flow once.
    val hasCompletedOnboarding: Flow<Boolean> = boolFlow(KEY_ONBOARDING_COMPLETE, default = false)

    // User-adjustable app-wide text scale, applied by NomadPortalTheme to
    // NomadTypography (see Theme.kt) — a multiplier, not an absolute size,
    // so it scales consistently with whatever base sizes the theme
    // defines rather than needing every screen to know an absolute sp
    // value. 1.0 = the theme's own defaults; clamped to [MIN_TEXT_SCALE,
    // MAX_TEXT_SCALE] on write so a bad persisted value (or a future
    // migration bug) can't leave text unreadably tiny or absurdly large.
    val textScale: Flow<Float> = dataStore.data.map {
        (it[KEY_TEXT_SCALE] ?: DEFAULT_TEXT_SCALE).coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
    }

    suspend fun setTcpEnabled(enabled: Boolean) = setBool(KEY_TCP, enabled)
    suspend fun setBluetoothMeshEnabled(enabled: Boolean) = setBool(KEY_BLUETOOTH_MESH, enabled)
    suspend fun setRNodeEnabled(enabled: Boolean) = setBool(KEY_RNODE, enabled)
    suspend fun setWifiDiscoveryEnabled(enabled: Boolean) = setBool(KEY_WIFI_DISCOVERY, enabled)
    suspend fun setNodeHostingEnabled(enabled: Boolean) = setBool(KEY_NODE_HOSTING, enabled)
    suspend fun setOnboardingComplete(completed: Boolean) = setBool(KEY_ONBOARDING_COMPLETE, completed)

    suspend fun setTextScale(scale: Float) {
        dataStore.edit { it[KEY_TEXT_SCALE] = scale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE) }
    }

    private fun boolFlow(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, default: Boolean): Flow<Boolean> =
        dataStore.data.map { it[key] ?: default }

    private suspend fun setBool(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { it[key] = value }
    }

    companion object {
        const val DEFAULT_TEXT_SCALE = 1.0f
        const val MIN_TEXT_SCALE = 0.75f
        const val MAX_TEXT_SCALE = 1.75f

        private val KEY_TCP = booleanPreferencesKey("tcp_enabled")
        private val KEY_BLUETOOTH_MESH = booleanPreferencesKey("bluetooth_mesh_enabled")
        private val KEY_RNODE = booleanPreferencesKey("rnode_enabled")
        private val KEY_WIFI_DISCOVERY = booleanPreferencesKey("wifi_discovery_enabled")
        private val KEY_NODE_HOSTING = booleanPreferencesKey("node_hosting_enabled")
        private val KEY_TEXT_SCALE = floatPreferencesKey("text_scale")
        private val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }
}
