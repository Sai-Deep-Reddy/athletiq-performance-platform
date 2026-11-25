package com.athletiq.core.database

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps random symmetric keys and sensitive secrets using hardware-backed AndroidKeyStore
 * AES-256-GCM keys. Never uses hardcoded passphrases or plaintext SharedPreferences secrets.
 */
object SecureKeyManager {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val DB_KEY_ALIAS = "athletiq_room_master_key_v1"
    private const val TOKEN_KEY_ALIAS = "athletiq_auth_token_key_v1"
    private const val VIDEO_KEY_ALIAS = "athletiq_video_store_key_v1"
    private const val PREFS_NAME = "athletiq_keystore_wrapped_keys"
    private const val PREF_DB_KEY = "wrapped_db_key"
    private const val PREF_DB_IV = "wrapped_db_iv"

    fun getOrCreateDatabasePassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wrappedHex = prefs.getString(PREF_DB_KEY, null)
        val ivHex = prefs.getString(PREF_DB_IV, null)

        if (wrappedHex != null && ivHex != null) {
            return decryptWithKeystore(DB_KEY_ALIAS, hexToBytes(wrappedHex), hexToBytes(ivHex))
        }

        val rawKey = ByteArray(32)
        SecureRandom().nextBytes(rawKey)
        val (cipherBytes, iv) = encryptWithKeystore(DB_KEY_ALIAS, rawKey)
        prefs.edit()
            .putString(PREF_DB_KEY, bytesToHex(cipherBytes))
            .putString(PREF_DB_IV, bytesToHex(iv))
            .apply()
        return rawKey
    }

    fun encryptWithKeystore(alias: String, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey(alias))
        val encrypted = cipher.doFinal(plaintext)
        return encrypted to cipher.iv
    }

    fun decryptWithKeystore(alias: String, ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(alias), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    fun tokenKeyAlias(): String = TOKEN_KEY_ALIAS

    fun videoKeyAlias(): String = VIDEO_KEY_ALIAS

    @Synchronized
    private fun getOrCreateSecretKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (!keyStore.containsAlias(alias)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            generator.init(spec)
            generator.generateKey()
        }
        val entry = keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Invalid hex string length" }
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
