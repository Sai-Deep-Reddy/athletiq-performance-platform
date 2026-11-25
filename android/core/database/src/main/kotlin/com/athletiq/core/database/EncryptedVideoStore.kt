package com.athletiq.core.database

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedVideoRecord(
    val attemptClientUuid: String,
    val encryptedFilePath: String,
    val createdAtEpochMs: Long,
    val uploadConsentGranted: Boolean,
)

/**
 * App-private AES-256-GCM encrypted video clip store with configurable retention
 * and explicit opt-in upload consent.
 *
 * Raw examination videos are stored inside app-private filesDir, encrypted at rest
 * with an AndroidKeyStore-wrapped per-install key + per-file random IV, and are
 * NEVER uploaded automatically unless explicitly authorized.
 */
class EncryptedVideoStore(
    private val context: Context,
) {
    private val videoDir: File
        get() = File(context.filesDir, "exam_videos").apply { mkdirs() }

    private val keyPrefsName = "athletiq_video_store_key"
    private val prefWrappedKey = "wrapped_video_aes_key"
    private val prefWrappedIv = "wrapped_video_aes_iv"

    fun encryptAndSaveVideo(
        attemptClientUuid: String,
        sourceStream: InputStream,
        uploadConsentGranted: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): EncryptedVideoRecord {
        val targetFile = File(videoDir, "$attemptClientUuid.enc")
        val videoKey = getOrCreateVideoAesKey()
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(videoKey, "AES"), GCMParameterSpec(128, iv))

        targetFile.outputStream().use { rawOut ->
            rawOut.write(iv.size)
            rawOut.write(iv)
            CipherOutputStream(rawOut, cipher).use { cipherOut ->
                sourceStream.copyTo(cipherOut)
            }
        }
        videoKey.fill(0)

        return EncryptedVideoRecord(
            attemptClientUuid = attemptClientUuid,
            encryptedFilePath = targetFile.absolutePath,
            createdAtEpochMs = nowEpochMs,
            uploadConsentGranted = uploadConsentGranted,
        )
    }

    fun openDecryptedStream(encryptedFilePath: String): InputStream {
        val file = File(encryptedFilePath)
        require(file.exists() && file.canonicalPath.startsWith(videoDir.canonicalPath)) {
            "Encrypted video file not found inside app-private store"
        }
        val rawIn = file.inputStream()
        val ivLen = rawIn.read()
        require(ivLen == 12) { "Corrupted encrypted video header" }
        val iv = ByteArray(ivLen)
        val readCount = rawIn.read(iv)
        require(readCount == ivLen) { "Incomplete IV header" }

        val videoKey = getOrCreateVideoAesKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(videoKey, "AES"), GCMParameterSpec(128, iv))
        videoKey.fill(0)

        return CipherInputStream(rawIn, cipher)
    }

    fun deleteVideo(attemptClientUuid: String): Boolean {
        val target = File(videoDir, "$attemptClientUuid.enc")
        return !target.exists() || target.delete()
    }

    fun purgeExpiredVideos(maxRetentionMs: Long, nowEpochMs: Long = System.currentTimeMillis()): Int {
        require(maxRetentionMs > 0) { "maxRetentionMs must be > 0" }
        var deletedCount = 0
        videoDir.listFiles()?.forEach { file ->
            if (file.isFile && (nowEpochMs - file.lastModified()) > maxRetentionMs) {
                if (file.delete()) deletedCount++
            }
        }
        return deletedCount
    }

    private fun getOrCreateVideoAesKey(): ByteArray {
        val prefs = context.getSharedPreferences(keyPrefsName, Context.MODE_PRIVATE)
        val wrappedHex = prefs.getString(prefWrappedKey, null)
        val ivHex = prefs.getString(prefWrappedIv, null)
        if (wrappedHex != null && ivHex != null) {
            return SecureKeyManager.decryptWithKeystore(
                SecureKeyManager.videoKeyAlias(),
                SecureKeyManager.hexToBytes(wrappedHex),
                SecureKeyManager.hexToBytes(ivHex),
            )
        }
        val raw = ByteArray(32)
        SecureRandom().nextBytes(raw)
        val (cipherBytes, iv) = SecureKeyManager.encryptWithKeystore(SecureKeyManager.videoKeyAlias(), raw)
        prefs.edit()
            .putString(prefWrappedKey, SecureKeyManager.bytesToHex(cipherBytes))
            .putString(prefWrappedIv, SecureKeyManager.bytesToHex(iv))
            .apply()
        return raw
    }
}
