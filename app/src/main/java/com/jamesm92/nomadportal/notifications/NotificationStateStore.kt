package com.jamesm92.nomadportal.notifications

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private val Context.notificationStateDataStore by preferencesDataStore(name = "nomadportal_notification_state")

/**
 * Persists, per contact hash, the id of the last *received* message this
 * device has already notified about — the real "what counts as new"
 * bookkeeping [MessageNotifier.checkAndNotify] needs. Backed by its own
 * small DataStore (a single JSON-string preference, not one key per
 * contact — the whole map is small and always read/written together),
 * deliberately separate from [com.jamesm92.nomadportal.data.SettingsRepository]
 * since this is pure notification-delivery bookkeeping, not a user-
 * facing setting.
 *
 * Must survive process death: the battery-friendly mode's
 * [MessageCheckWorker] runs as a cold `CoroutineWorker` invocation with
 * no shared in-memory state from any previous run, so without real
 * persistence here every periodic check would treat *every* existing
 * message as "new" and re-notify for the same messages forever.
 */
class NotificationStateStore(context: Context) {
    private val dataStore = context.applicationContext.notificationStateDataStore

    suspend fun getLastNotifiedId(contactHash: String): String? {
        val json = dataStore.data.first()[KEY_MAP] ?: return null
        return try {
            JSONObject(json).optString(contactHash, "").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun setLastNotifiedId(contactHash: String, messageId: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_MAP]?.let {
                try {
                    JSONObject(it)
                } catch (e: Exception) {
                    JSONObject()
                }
            } ?: JSONObject()
            current.put(contactHash, messageId)
            prefs[KEY_MAP] = current.toString()
        }
    }

    companion object {
        private val KEY_MAP = stringPreferencesKey("last_notified_message_ids")
    }
}
