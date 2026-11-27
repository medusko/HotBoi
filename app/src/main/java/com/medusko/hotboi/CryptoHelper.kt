package com.medusko.hotboi

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed AES-256-GCM encryption helper for sensitive credentials at rest.
 * Uses Android Keystore so private keys, passwords, and API tokens are never persisted in plaintext.
 * Fails closed on encryption errors (never falls back to returning plaintext).
 */
object CryptoHelper {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "hotboi_cred_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc:v1:"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts [plainText] using Android Keystore AES-256-GCM.
     * Returns null if encryption fails (fail-closed: callers must never persist plaintext on failure).
     */
    fun encryptOrNull(plainText: String?): String? {
        if (plainText.isNullOrEmpty()) return ""
        var plainBytes: ByteArray? = null
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            plainBytes = plainText.toByteArray(Charsets.UTF_8)
            val cipherBytes = cipher.doFinal(plainBytes)
            val combined = ByteArray(iv.size + cipherBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
            PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            ErrorLogManager.logError("Crypto Encrypt Error (Fail-Closed)", e)
            null
        } finally {
            plainBytes?.fill(0)
        }
    }

    /**
     * Fail-closed convenience wrapper: returns empty string instead of leaking plaintext if Keystore fails.
     */
    fun encrypt(plainText: String?): String {
        if (plainText.isNullOrEmpty()) return ""
        return encryptOrNull(plainText) ?: ""
    }

    fun decrypt(cipherText: String?): String {
        if (cipherText.isNullOrEmpty()) return ""
        if (!cipherText.startsWith(PREFIX)) {
            // Backwards-compatible read of pre-Keystore values; re-saved encrypted on next save
            return cipherText
        }
        var plainBytes: ByteArray? = null
        return try {
            val rawB64 = cipherText.removePrefix(PREFIX)
            val combined = Base64.decode(rawB64, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return ""

            val iv = ByteArray(GCM_IV_LENGTH)
            val cipherBytes = ByteArray(combined.size - GCM_IV_LENGTH)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
            System.arraycopy(combined, GCM_IV_LENGTH, cipherBytes, 0, cipherBytes.size)

            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ErrorLogManager.logError("Crypto Decrypt Error", e)
            ""
        } finally {
            plainBytes?.fill(0)
        }
    }
}
