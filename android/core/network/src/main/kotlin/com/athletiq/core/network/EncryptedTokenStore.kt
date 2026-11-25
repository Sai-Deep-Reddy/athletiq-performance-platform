package com.athletiq.core.network

import android.content.Context
import com.athletiq.core.database.SecureKeyManager

data class AuthSessionCredentials(
    val baseUrl: String,
    val accessToken: String,
    val institutionId: String,
)

/**
 * Encrypts and retrieves JWT session credentials using AndroidKeyStore AES-256-GCM.
 */
class EncryptedTokenStore(private val context: Context) {
    private val prefsName = "athletiq_auth_session_enc"
    private val keyCipherHex = "token_cipher_hex"
    private val keyIvHex = "token_iv_hex"
    private val keyBaseUrl = "api_base_url"
    private val keyInstitutionId = "institution_id"

    fun saveSession(baseUrl: String, accessToken: String, institutionId: String) {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        val isAllowedLoopbackHttp = listOf("localhost", "127.0.0.1", "10.0.2.2").any { host ->
            cleanUrl == "http://$host" || cleanUrl.startsWith("http://$host:") || cleanUrl.startsWith("http://$host/")
        }
        require(cleanUrl.startsWith("https://") || isAllowedLoopbackHttp) {
            "Non-local API base URL must use HTTPS"
        }
        val (cipherBytes, iv) = SecureKeyManager.encryptWithKeystore(
            SecureKeyManager.tokenKeyAlias(),
            accessToken.toByteArray(Charsets.UTF_8),
        )
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(keyBaseUrl, cleanUrl)
            .putString(keyInstitutionId, institutionId)
            .putString(keyCipherHex, SecureKeyManager.bytesToHex(cipherBytes))
            .putString(keyIvHex, SecureKeyManager.bytesToHex(iv))
            .apply()
    }

    fun loadSession(): AuthSessionCredentials? {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val baseUrl = prefs.getString(keyBaseUrl, null) ?: return null
        val institutionId = prefs.getString(keyInstitutionId, null) ?: return null
        val cipherHex = prefs.getString(keyCipherHex, null) ?: return null
        val ivHex = prefs.getString(keyIvHex, null) ?: return null

        return runCatching {
            val plain = SecureKeyManager.decryptWithKeystore(
                SecureKeyManager.tokenKeyAlias(),
                SecureKeyManager.hexToBytes(cipherHex),
                SecureKeyManager.hexToBytes(ivHex),
            )
            val token = plain.toString(Charsets.UTF_8)
            plain.fill(0)
            AuthSessionCredentials(baseUrl = baseUrl, accessToken = token, institutionId = institutionId)
        }.getOrNull()
    }

    fun clearSession() {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
