package com.athletiq.core.network

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.athletiq.core.database.AthletiqDatabase

/**
 * WorkManager outbox synchronization worker.
 *
 * Drains queued offline attempt/metadata payloads idempotently using client_uuid,
 * preserves explicit sync failure states, and never uploads raw video clips automatically.
 */
class OutboxSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val tokenStore = EncryptedTokenStore(applicationContext)
        val credentials = tokenStore.loadSession() ?: return Result.failure()

        val db = AthletiqDatabase.getInstance(applicationContext)
        val outboxDao = db.syncOutboxDao()
        val apiClient = AthletiqApiClient()

        val pending = outboxDao.getPendingBatch(limit = 25)
        if (pending.isEmpty()) return Result.success()

        var anyRetryableFailure = false
        for (item in pending) {
            if (item.institutionId != credentials.institutionId) continue

            when (val res = apiClient.postOutboxJson(credentials, item.endpointPath, item.payloadJson)) {
                is ApiResult.Success -> {
                    outboxDao.markResult(item.id, "SYNCED", null)
                }
                is ApiResult.AuthRejected -> {
                    outboxDao.markResult(item.id, "FAILED_RETRYABLE", "HTTP ${res.statusCode}")
                    return Result.failure()
                }
                is ApiResult.ValidationError -> {
                    outboxDao.markResult(item.id, "FAILED_TERMINAL", "HTTP ${res.statusCode}: ${res.detail}")
                }
                is ApiResult.RateLimited -> {
                    outboxDao.markResult(item.id, "FAILED_RETRYABLE", "Rate limited (${res.retryAfterSeconds}s)")
                    anyRetryableFailure = true
                }
                is ApiResult.ServerError -> {
                    outboxDao.markResult(item.id, "FAILED_RETRYABLE", res.detail)
                    anyRetryableFailure = true
                }
                is ApiResult.NetworkUnavailable -> {
                    outboxDao.markResult(item.id, "FAILED_RETRYABLE", res.message)
                    anyRetryableFailure = true
                }
            }
        }

        return if (anyRetryableFailure) Result.retry() else Result.success()
    }
}
