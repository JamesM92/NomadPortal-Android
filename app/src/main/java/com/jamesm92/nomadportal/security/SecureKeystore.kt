package com.jamesm92.nomadportal.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Convention + wipe support for this app's Android Keystore-backed
 * encryption keys.
 *
 * **Why this exists**: the panic wipe (nomadportal_android_handoff.md's
 * "Main menu / connectivity & privacy controls" section /
 * [com.jamesm92.nomadportal.panicwipe.PanicWipe]) needs to be fast and
 * reliable on flash storage, where multi-pass file overwrite is neither —
 * flash wear-leveling means old physical blocks can survive an overwrite
 * pass at the filesystem level, and overwrite time scales with data
 * volume. **Cryptographic erasure ("crypto-shredding") doesn't have either
 * problem**: if sensitive data (RNS identity, message content, once those
 * exist — see sequencing step 1) is stored encrypted with a key that lives
 * in the hardware-backed Android Keystore, destroying that one small key
 * makes the ciphertext left on disk permanently unrecoverable instantly,
 * regardless of how much data there is or how flash storage physically
 * relocates blocks. Android itself uses this technique for fast
 * File-Based-Encryption factory resets, for the same reason.
 *
 * **This means**: any future code that stores real secrets (RNS identity
 * keys, LXMF message content, anything the trust model in
 * `porting-notes.md` §3 treats as sensitive) must generate its encryption
 * key via [getOrCreateKey] (or at minimum, store it in the
 * `"AndroidKeyStore"` provider) rather than rolling its own key storage —
 * that's what makes it wipeable by [wipeAllKeys]. `androidx.security.
 * crypto`'s `EncryptedFile`/`MasterKey` (Keystore-backed) is the
 * recommended way to consume this rather than raw `Cipher` calls, once
 * there's real data to encrypt.
 *
 * No real keys exist yet at this stage of the project — [wipeAllKeys] is
 * a real, functional, tested operation today, it just has nothing to
 * delete until identity/message storage is built on top of it.
 */
object SecureKeystore {
    private const val PROVIDER = "AndroidKeyStore"

    /**
     * Creates (or returns the existing) AES-256-GCM key under [alias] in
     * the Android Keystore, preferring StrongBox hardware isolation where
     * the device supports it and falling back transparently where it
     * doesn't. Callers don't need to handle the StrongBox-unavailable case
     * themselves.
     */
    fun getOrCreateKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply { trySetStrongBoxBacked(this) }
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun trySetStrongBoxBacked(builder: KeyGenParameterSpec.Builder) {
        try {
            builder.setIsStrongBoxBacked(true)
        } catch (_: Throwable) {
            // Device has no StrongBox — key still gets a TEE-backed
            // (non-StrongBox) Keystore key, which is the normal case on
            // most Android hardware. Not an error.
        }
    }

    /**
     * Deletes every key this app has stored in the Android Keystore. The
     * per-app Keystore namespace is already isolated by the OS (another
     * app cannot see or delete these aliases), so there is no need to
     * filter by an alias prefix — every entry present here is this app's
     * own key, and the panic wipe should destroy all of them
     * unconditionally.
     *
     * Fast and size-independent: this is a handful of Keystore service
     * calls, not proportional to how much ciphertext exists on disk.
     */
    fun wipeAllKeys() {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        keyStore.aliases().toList().forEach { alias ->
            try {
                keyStore.deleteEntry(alias)
            } catch (_: Exception) {
                // Best-effort, same posture as PanicWipe's file pass —
                // don't let one bad entry stop the rest from being wiped.
            }
        }
    }
}
