package com.jamesm92.nomadportal.data.rnsh

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.rnshHistoryDataStore by preferencesDataStore(name = "nomadportal_rnsh_history")

/**
 * Real [RnshHistoryRepository] — a JSON array of entries under one
 * DataStore key, same "own small DataStore file" shape as
 * [com.jamesm92.nomadportal.data.SettingsRepository], not folded into
 * that one since this is list-shaped rather than scalar preferences.
 * Purely local Kotlin-side bookkeeping — never touches the Chaquopy
 * boundary, so this doesn't follow that boundary's own "JSON string
 * across the bridge" convention for the same reason, just because JSON
 * is a reasonable plain encoding for a small persisted list either way.
 */
class RealRnshHistoryRepository(context: Context) : RnshHistoryRepository {
    private val dataStore = context.applicationContext.rnshHistoryDataStore

    override fun history(): Flow<List<RnshHistoryEntry>> = dataStore.data.map { prefs ->
        parseHistory(prefs[KEY_HISTORY]).sortedByDescending { it.lastAttemptAtMillis }
    }

    override suspend fun recordAttempt(destinationHash: String, outcome: RnshHistoryOutcome, error: String?) {
        dataStore.edit { prefs ->
            val entries = parseHistory(prefs[KEY_HISTORY]).toMutableList()
            val existingNickname = entries.find { it.destinationHash == destinationHash }?.nickname
            entries.removeAll { it.destinationHash == destinationHash }
            entries.add(
                RnshHistoryEntry(
                    destinationHash = destinationHash,
                    nickname = existingNickname,
                    lastAttemptAtMillis = System.currentTimeMillis(),
                    lastOutcome = outcome,
                    lastError = error,
                ),
            )
            prefs[KEY_HISTORY] = serializeHistory(entries)
        }
    }

    override suspend fun setNickname(destinationHash: String, nickname: String?) {
        dataStore.edit { prefs ->
            val entries = parseHistory(prefs[KEY_HISTORY]).toMutableList()
            val index = entries.indexOfFirst { it.destinationHash == destinationHash }
            if (index != -1) {
                val trimmed = nickname?.trim()?.takeIf { it.isNotEmpty() }
                entries[index] = entries[index].copy(nickname = trimmed)
                prefs[KEY_HISTORY] = serializeHistory(entries)
            }
        }
    }

    override suspend fun remove(destinationHash: String) {
        dataStore.edit { prefs ->
            val entries = parseHistory(prefs[KEY_HISTORY]).toMutableList()
            if (entries.removeAll { it.destinationHash == destinationHash }) {
                prefs[KEY_HISTORY] = serializeHistory(entries)
            }
        }
    }

    private fun parseHistory(json: String?): List<RnshHistoryEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val hash = obj.optString("destination_hash").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                RnshHistoryEntry(
                    destinationHash = hash,
                    nickname = obj.optString("nickname").takeIf { it.isNotEmpty() },
                    lastAttemptAtMillis = obj.optLong("last_attempt_at_millis"),
                    lastOutcome = if (obj.optString("last_outcome") == "SUCCESS") {
                        RnshHistoryOutcome.SUCCESS
                    } else {
                        RnshHistoryOutcome.FAILED
                    },
                    lastError = obj.optString("last_error").takeIf { it.isNotEmpty() },
                )
            }
        } catch (e: Exception) {
            // A corrupt/foreign value here is a display-only history
            // list, not RNS/identity state — degrade to "no history yet"
            // rather than crashing the whole terminal screen over it.
            emptyList()
        }
    }

    private fun serializeHistory(entries: List<RnshHistoryEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("destination_hash", entry.destinationHash)
                    put("nickname", entry.nickname ?: "")
                    put("last_attempt_at_millis", entry.lastAttemptAtMillis)
                    put("last_outcome", entry.lastOutcome.name)
                    put("last_error", entry.lastError ?: "")
                },
            )
        }
        return array.toString()
    }

    private companion object {
        val KEY_HISTORY = stringPreferencesKey("history_json")
    }
}
