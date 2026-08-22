package com.jamesm92.nomadportal.data.identity

import androidx.compose.ui.graphics.Color
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.jamesm92.nomadportal.data.browsing.PageCacheStore
import com.jamesm92.nomadportal.data.messaging.ContactIcon
import com.jamesm92.nomadportal.data.messaging.parseHexColor
import com.jamesm92.nomadportal.data.messaging.toHexString
import com.jamesm92.nomadportal.data.pollingFlow
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real [IdentityRepository], backed by `nomadportal_core.orchestrator`'s
 * multi-identity bridge functions (`list_identities_json`/`create_identity`/
 * `switch_active_identity`/`rename_identity`/`set_identity_icon`/
 * `delete_identity`/`import_identity_file`/`export_identity_file_bytes`).
 * Same JSON-string-bridge + poll-based-`Flow` conventions as
 * [com.jamesm92.nomadportal.data.messaging.RealMessagingRepository] — see
 * that class's own doc comment for why (no push mechanism exists on the
 * Python side for any of this).
 */
class RealIdentityRepository(
    // Required, not optional — deleteIdentity()'s own cascade-delete
    // contract (see IdentityRepository's own doc comment) now includes
    // the page cache; a missing store here would silently leave a
    // deleted identity's cached pages behind instead of failing loudly.
    private val pageCacheStore: PageCacheStore,
) : IdentityRepository {
    private val orchestrator by lazy {
        Python.getInstance().getModule("nomadportal_core.orchestrator")
    }

    override fun identities(): Flow<List<Identity>> = pollingFlow(POLL_INTERVAL_MS) { fetchIdentities() }

    private fun fetchIdentities(): List<Identity> {
        val obj = JSONObject(orchestrator.callAttr("list_identities_json").toString())
        val array = obj.optJSONArray("identities") ?: JSONArray()
        return (0 until array.length()).map { i ->
            val e = array.getJSONObject(i)
            Identity(
                id = e.getString("id"),
                name = e.optString("name", ""),
                lxmfAddress = if (e.isNull("lxmf_address")) null else e.optString("lxmf_address"),
                publicKeyHex = e.optStringOrNull("public_key_hex"),
                iconAppearance = if (e.isNull("icon_glyph")) {
                    null
                } else {
                    ContactIcon.Appearance(
                        glyphName = e.getString("icon_glyph"),
                        backgroundColor = parseHexColor(e.optStringOrNull("icon_bg"), Color(0xFF888888)),
                        foregroundColor = parseHexColor(e.optStringOrNull("icon_fg"), Color.White),
                    )
                },
                isActive = e.optBoolean("is_active", false),
                createdAtMillis = (e.optDouble("created_at", 0.0) * 1000).toLong(),
            )
        }
    }

    override suspend fun createIdentity(name: String): String = withContext(Dispatchers.IO) {
        try {
            orchestrator.callAttr("create_identity", name).toString()
        } catch (e: PyException) {
            throw IOException(e.message, e)
        }
    }

    override suspend fun switchActiveIdentity(identityId: String) {
        withContext(Dispatchers.IO) {
            try {
                orchestrator.callAttr("switch_active_identity", identityId)
            } catch (e: PyException) {
                throw IOException(e.message, e)
            }
        }
    }

    override suspend fun renameIdentity(identityId: String, name: String): Boolean = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext false
        orchestrator.callAttr("rename_identity", identityId, name).toBoolean()
    }

    override suspend fun setIdentityIcon(
        identityId: String,
        glyphName: String,
        foreground: Color,
        background: Color,
    ): Boolean = withContext(Dispatchers.IO) {
        orchestrator.callAttr(
            "set_identity_icon",
            identityId,
            glyphName,
            foreground.toHexString(),
            background.toHexString(),
        ).toBoolean()
    }

    override suspend fun deleteIdentity(identityId: String) {
        withContext(Dispatchers.IO) {
            try {
                orchestrator.callAttr("delete_identity", identityId)
            } catch (e: PyException) {
                throw IOException(e.message, e)
            }
            // Page cache is purely Kotlin-side (never touches Python —
            // see PageCacheStore's own doc comment), so it's not part of
            // orchestrator.py's own delete_identity() cascade above;
            // torn down here instead so the *whole* cascade this
            // function promises (per IdentityRepository's own doc
            // comment) actually holds. Runs even though delete_identity
            // already succeeded by this point — a stale page cache left
            // behind for an identity that no longer exists is a real,
            // if minor, leak either way.
            pageCacheStore.deleteForIdentity(identityId)
        }
    }

    override suspend fun importIdentity(keyBytes: ByteArray, name: String): String = withContext(Dispatchers.IO) {
        try {
            orchestrator.callAttr("import_identity_file", keyBytes, name).toString()
        } catch (e: PyException) {
            throw IOException(e.message, e)
        }
    }

    override suspend fun exportIdentity(identityId: String): ByteArray? = withContext(Dispatchers.IO) {
        // Same `?.toJava(ByteArray::class.java)` shape CallAudioEngine's
        // own popAudioFrame() already established for a nullable-bytes
        // Python return — Chaquopy maps Python None to Kotlin null here.
        orchestrator.callAttr("export_identity_file_bytes", identityId)
            ?.toJava(ByteArray::class.java)
    }

    // Same private-extension convention as RealMessagingRepository/
    // RealCallRepository's own optStringOrNull — not shared/promoted,
    // per this project's own "promote only after real reuse in a
    // shared location" rule (each repository's own copy is trivial and
    // independent).
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key)

    companion object {
        // Same interval every other list-shaped repository Flow in this
        // app polls at (RealMessagingRepository/RealBrowserRepository) —
        // kept consistent rather than tuned independently.
        const val POLL_INTERVAL_MS = 4000L
    }
}
