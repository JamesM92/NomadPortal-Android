package com.jamesm92.nomadportal.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "nomadportal_settings")

/** This device's own display theme — see [SettingsRepository.themeMode]'s
 * own doc comment and [com.jamesm92.nomadportal.ui.theme.NomadPortalTheme]
 * for where this is actually applied. [SYSTEM] follows the OS's own
 * light/dark setting; [LIGHT]/[DARK] pin it regardless of the OS. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

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

    // Gates ConversationScreen's one-time "this only affects your own
    // device" explanation dialog -- shown the first time ever a user
    // picks a non-Off disappearing-messages duration (any conversation),
    // never again after. Same "explain once, don't re-nag" shape as
    // onboarding's own SafetyStep already established for panic-wipe.
    val hasSeenDisappearingMessagesNotice: Flow<Boolean> =
        boolFlow(KEY_DISAPPEARING_MESSAGES_NOTICE_SEEN, default = false)

    // "Messages from contacts only" allowlist mode (per the Columba-
    // parity-audit's own PrivacyCard.kt finding) — silently discards any
    // inbound message from a sender who isn't already a known contact;
    // real enforcement lives in messaging.py's _on_delivery. Defaults
    // off (matches every other privacy-narrowing toggle in this app —
    // nothing new gets *more* restrictive than before without the user
    // explicitly opting in). Persisted here (unlike Settings' own Auto
    // Announce master toggle, which is deliberately left Python-side
    // ephemeral) specifically because a privacy-protective toggle
    // silently resetting to permissive on every app restart would be a
    // real footgun — see RealMessagingRepository's own doc comment for
    // the boot-replay this enables.
    val messagesContactsOnly: Flow<Boolean> = boolFlow(KEY_MESSAGES_CONTACTS_ONLY, default = false)

    // "Calls from contacts only" — the calls-specific counterpart to
    // messagesContactsOnly above, deliberately a separate persisted
    // setting rather than shared (a user may want messages from
    // strangers but never want to be rung by one, or vice versa). Same
    // persistence rationale: a privacy-protective toggle resetting to
    // permissive on restart would be a real footgun. See
    // AnnounceStatus.callsContactsOnly's own doc comment.
    val callsContactsOnly: Flow<Boolean> = boolFlow(KEY_CALLS_CONTACTS_ONLY, default = false)

    // Master "Allow incoming voice calls" toggle — a real Columba-parity
    // gap (its own real allowVoiceCalls setting, found via a direct
    // source audit) closed here per explicit direction. Independent of,
    // and enforced ahead of, callsContactsOnly above — this is "no calls
    // at all," not "no calls from strangers." Default true (calls
    // accepted), matching Columba's own real default. See
    // AnnounceStatus.callsEnabled's own doc comment.
    val callsEnabled: Flow<Boolean> = boolFlow(KEY_CALLS_ENABLED, default = true)

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

    /** [ThemeMode.SYSTEM] by default — matches the OS until the user
     * explicitly picks otherwise, same "don't surprise the user with a
     * new default" reasoning as this class's own doc comment. Corrupt/
     * unrecognized stored values (a future enum-rename migration gap)
     * fall back to [ThemeMode.SYSTEM] rather than crashing. */
    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE]?.let {
            try {
                ThemeMode.valueOf(it)
            } catch (e: IllegalArgumentException) {
                ThemeMode.SYSTEM
            }
        } ?: ThemeMode.SYSTEM
    }

    // Real resource/bandwidth commitment (relaying strangers' mesh
    // traffic, not just this device's own) — opt-in, same convention as
    // every other toggle here that broadens what this device exposes to
    // the network. Takes effect on next app restart only — see
    // orchestrator.py's start()'s own doc comment for why RNS's
    // enable_transport can't be a live toggle.
    val transportNodeEnabled: Flow<Boolean> = boolFlow(KEY_TRANSPORT_NODE, default = false)

    // Opt-in — a persistent notification is a real, visible change to
    // how this app behaves, not something to turn on by default on an
    // existing install. See MessageNotificationService/MessageCheckWorker
    // for what each mode actually does.
    val notificationsEnabled: Flow<Boolean> = boolFlow(KEY_NOTIFICATIONS_ENABLED, default = false)
    /** True = always-on foreground service (reliable, persistent
     * notification); false = battery-friendly WorkManager (no
     * persistent notification, but Android may delay/skip checks under
     * Doze). Only meaningful while [notificationsEnabled] is true.
     * Defaults true (Always-on) — the user's own explicit "Recommended"
     * pick when this choice was designed. */
    val notificationsAlwaysOn: Flow<Boolean> = boolFlow(KEY_NOTIFICATIONS_ALWAYS_ON, default = true)

    suspend fun setTcpEnabled(enabled: Boolean) = setBool(KEY_TCP, enabled)
    suspend fun setBluetoothMeshEnabled(enabled: Boolean) = setBool(KEY_BLUETOOTH_MESH, enabled)
    suspend fun setRNodeEnabled(enabled: Boolean) = setBool(KEY_RNODE, enabled)
    suspend fun setWifiDiscoveryEnabled(enabled: Boolean) = setBool(KEY_WIFI_DISCOVERY, enabled)
    suspend fun setNodeHostingEnabled(enabled: Boolean) = setBool(KEY_NODE_HOSTING, enabled)
    suspend fun setOnboardingComplete(completed: Boolean) = setBool(KEY_ONBOARDING_COMPLETE, completed)
    suspend fun setSeenDisappearingMessagesNotice(seen: Boolean) =
        setBool(KEY_DISAPPEARING_MESSAGES_NOTICE_SEEN, seen)
    suspend fun setMessagesContactsOnly(enabled: Boolean) = setBool(KEY_MESSAGES_CONTACTS_ONLY, enabled)
    suspend fun setCallsContactsOnly(enabled: Boolean) = setBool(KEY_CALLS_CONTACTS_ONLY, enabled)
    suspend fun setCallsEnabled(enabled: Boolean) = setBool(KEY_CALLS_ENABLED, enabled)

    suspend fun setTextScale(scale: Float) {
        dataStore.edit { it[KEY_TEXT_SCALE] = scale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE) }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setTransportNodeEnabled(enabled: Boolean) = setBool(KEY_TRANSPORT_NODE, enabled)
    suspend fun setNotificationsEnabled(enabled: Boolean) = setBool(KEY_NOTIFICATIONS_ENABLED, enabled)
    suspend fun setNotificationsAlwaysOn(alwaysOn: Boolean) = setBool(KEY_NOTIFICATIONS_ALWAYS_ON, alwaysOn)

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
        private val KEY_DISAPPEARING_MESSAGES_NOTICE_SEEN = booleanPreferencesKey("disappearing_messages_notice_seen")
        private val KEY_MESSAGES_CONTACTS_ONLY = booleanPreferencesKey("messages_contacts_only")
        private val KEY_CALLS_CONTACTS_ONLY = booleanPreferencesKey("calls_contacts_only")
        private val KEY_CALLS_ENABLED = booleanPreferencesKey("calls_enabled")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_TRANSPORT_NODE = booleanPreferencesKey("transport_node_enabled")
        private val KEY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        private val KEY_NOTIFICATIONS_ALWAYS_ON = booleanPreferencesKey("notifications_always_on")
    }
}
