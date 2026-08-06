package com.jamesm92.nomadportal.panicwipe

import android.content.Context
import android.content.Intent
import com.jamesm92.nomadportal.security.SecureKeystore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * The "triple-tap the logo" panic wipe (nomadportal_android_handoff.md's
 * "Main menu / connectivity & privacy controls" section). Two layers, in
 * order:
 *
 * 1. **Keystore key wipe first** ([SecureKeystore.wipeAllKeys]) — instant,
 *    size-independent, and the *actually reliable* erasure mechanism: any
 *    sensitive data stored encrypted with a Keystore-backed key (see that
 *    object's doc comment — this is where RNS identity/message storage
 *    should live once it exists) becomes permanently unrecoverable
 *    ciphertext the moment its key is gone, regardless of flash
 *    wear-leveling. This is why it runs first, not as an afterthought.
 * 2. **Multi-pass file overwrite as defense-in-depth** for whatever plain
 *    (non-Keystore-encrypted) files exist — today that's just DataStore
 *    preference files (toggle states, not actually sensitive) and
 *    caches. [securelyDeleteFile] overwrites file content (zeroes, then
 *    ones, then random data) and fsyncs before deleting, walking every
 *    app-private storage root (`filesDir`, `noBackupFilesDir`,
 *    `cacheDir`, `codeCacheDir`). Real overwrite passes, not just
 *    `File.delete()` — but note this layer alone is neither as fast nor
 *    as reliable as layer 1 on flash storage (wear-leveling can leave old
 *    physical blocks intact after an overwrite), which is exactly why
 *    layer 1 exists rather than relying on this alone.
 *
 * [regenerateIdentity] is a TODO stub — there is no RNS identity to
 * regenerate yet (lands with the core extraction, sequencing step 1).
 *
 * No confirmation dialog by design — see the handoff doc's "Panic wipe"
 * section for why (matches Bitchat: the triple-tap gesture itself is the
 * safety margin, not a modal).
 */
object PanicWipe {
    private const val PASS_COUNT = 3
    private const val CHUNK_SIZE = 8192

    suspend fun perform(context: Context) = withContext(Dispatchers.IO) {
        SecureKeystore.wipeAllKeys()

        val roots = listOfNotNull(
            context.filesDir,
            context.noBackupFilesDir,
            context.cacheDir,
            context.codeCacheDir,
        )
        roots.forEach { wipeDirectoryContents(it) }
        regenerateIdentity()
    }

    /** Relaunches the app as a fresh process so no in-memory state (old identity, old ViewModels) survives the wipe. */
    fun restartApp(context: Context) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val restartIntent = Intent.makeRestartActivityTask(launchIntent?.component)
        context.startActivity(restartIntent)
        Runtime.getRuntime().exit(0)
    }

    private fun regenerateIdentity() {
        // TODO(core extraction, nomadportal_android_handoff.md sequencing
        // step 1): generate a fresh RNS.Identity here once identity
        // storage exists. Must not derive the new identity from the wiped
        // one in any way, and nothing recoverable from the old identity
        // should remain on disk after this function returns — the wipe
        // pass above should already guarantee that for the identity file
        // itself, but re-verify once the file's real path is known.
    }

    private fun wipeDirectoryContents(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                wipeDirectoryContents(child)
                child.delete()
            } else {
                securelyDeleteFile(child)
            }
        }
    }

    private fun securelyDeleteFile(file: File) {
        if (!file.isFile) {
            file.delete()
            return
        }
        try {
            RandomAccessFile(file, "rws").use { raf ->
                val length = raf.length()
                if (length > 0) {
                    val zeroes = ByteArray(CHUNK_SIZE)
                    val ones = ByteArray(CHUNK_SIZE) { 0xFF.toByte() }
                    val random = ByteArray(CHUNK_SIZE)
                    val secureRandom = SecureRandom()

                    repeat(PASS_COUNT) { pass ->
                        val pattern = when (pass) {
                            0 -> zeroes
                            1 -> ones
                            else -> random.also(secureRandom::nextBytes)
                        }
                        raf.seek(0)
                        var remaining = length
                        while (remaining > 0) {
                            val chunk = minOf(pattern.size.toLong(), remaining).toInt()
                            raf.write(pattern, 0, chunk)
                            remaining -= chunk
                        }
                        raf.fd.sync()
                    }
                }
            }
        } catch (_: IOException) {
            // Best-effort — even if the overwrite passes fail partway
            // (permissions, I/O error), still fall through and delete the
            // directory entry rather than leaving the file behind.
        }
        file.delete()
    }
}
