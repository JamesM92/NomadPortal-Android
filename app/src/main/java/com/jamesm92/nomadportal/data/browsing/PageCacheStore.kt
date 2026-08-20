package com.jamesm92.nomadportal.data.browsing

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backs BrowserScreen's stale-while-revalidate page loading (per explicit
 * direction: show a cached copy instantly, still fetch the live version in
 * the background, indicate which one's on screen). One flat file per
 * [PageAddress] (its own `toUrl()` form, SHA-256'd into a filename — the
 * real URL isn't reused directly as a filename since it contains `/` and
 * `:`, both invalid/meaningful to a filesystem), holding nothing but the
 * last successfully fetched raw `.mu` source for that address.
 *
 * **Identity-scoped** (per explicit direction) — every [read]/[write] takes
 * an [identityId] and files land under `page_cache/<identityId>/`, the same
 * "each identity's own isolated data" model this app already applies to
 * messages/contacts/favorites (see [com.jamesm92.nomadportal.data.identity.IdentityRepository]'s
 * own doc comment). [deleteForIdentity] is called from
 * [com.jamesm92.nomadportal.data.identity.RealIdentityRepository.deleteIdentity]
 * so an identity's browsed-page cache is torn down as part of that same
 * real cascade delete, not left behind as an orphaned directory for an
 * identity that no longer exists.
 *
 * Deliberately placed under [Context.cacheDir], not `noBackupFilesDir`
 * (where most of this app's other real on-device data lives) — this is
 * genuinely disposable, regeneratable-on-next-fetch data, exactly what
 * `cacheDir` is for (the OS may already clear it under storage pressure,
 * and [com.jamesm92.nomadportal.data.SettingsRepository.pageCacheEnabled]'s
 * own doc comment covers why that's an acceptable place for it privacy-
 * wise). It also means [com.jamesm92.nomadportal.panicwipe.PanicWipe]
 * already wipes this directory for free, irrecoverably (multi-pass secure
 * overwrite, not a plain delete) — `perform()` walks `context.cacheDir`
 * unconditionally today, no change needed there.
 *
 * Callers are expected to check [com.jamesm92.nomadportal.data.SettingsRepository.pageCacheEnabled]
 * themselves before calling [read]/[write] — this class has no opinion on
 * whether caching is turned on, it just is one.
 */
class PageCacheStore(context: Context) {
    private val root = File(context.applicationContext.cacheDir, "page_cache")

    suspend fun read(identityId: String, address: PageAddress): String? = withContext(Dispatchers.IO) {
        val file = fileFor(identityId, address)
        if (file.exists()) {
            try {
                file.readText()
            } catch (_: IOException) {
                null
            }
        } else {
            null
        }
    }

    suspend fun write(identityId: String, address: PageAddress, content: String) = withContext(Dispatchers.IO) {
        try {
            val file = fileFor(identityId, address)
            file.parentFile?.mkdirs()
            file.writeText(content)
        } catch (_: IOException) {
            // Best-effort — a failed cache write shouldn't fail the fetch
            // that triggered it; the next successful fetch just tries again.
        }
    }

    /** Real deletion (not a secure multi-pass overwrite — this is cache
     * content, not the last line of defense PanicWipe's own overwrite
     * passes exist for; see that object's own doc comment for why plain
     * delete is fine for genuinely disposable data), called as part of
     * [com.jamesm92.nomadportal.data.identity.RealIdentityRepository]'s own
     * identity-delete cascade. */
    suspend fun deleteForIdentity(identityId: String) = withContext(Dispatchers.IO) {
        File(root, identityId).deleteRecursively()
    }

    private fun fileFor(identityId: String, address: PageAddress): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(address.toUrl().toByteArray())
        val key = digest.joinToString("") { "%02x".format(it) }
        return File(File(root, identityId), "$key.mu")
    }
}
