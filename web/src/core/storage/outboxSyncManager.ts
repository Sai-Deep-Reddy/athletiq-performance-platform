import { ApiClientError, AthletiqApiClient } from '../api/client';
import { DurableIndexedDbStore, type OutboxAttemptRecord } from './indexedDbStore';

export interface SyncBatchSummary {
  syncedCount: number;
  replayedCount: number;
  failedCount: number;
  deferredReason: string | null;
}

export class OutboxSyncManager {
  private readonly api: AthletiqApiClient;
  private readonly store: DurableIndexedDbStore;
  private isSyncing = false;

  constructor(api: AthletiqApiClient, store: DurableIndexedDbStore) {
    this.api = api;
    this.store = store;
  }

  async enqueueAttempt(record: OutboxAttemptRecord): Promise<void> {
    await this.store.putOutboxAttempt(record);
    await this.store.appendLocalAudit(
      record.institution_id,
      'ATTEMPT_QUEUED_OFFLINE',
      `Queued ${record.test_type} attempt (${record.client_uuid}) in IndexedDB`,
    );
  }

  async syncPending(institutionId: string): Promise<SyncBatchSummary> {
    if (this.isSyncing) {
      return {
        syncedCount: 0,
        replayedCount: 0,
        failedCount: 0,
        deferredReason: 'Sync already in progress',
      };
    }
    if (typeof navigator !== 'undefined' && navigator.onLine === false) {
      return {
        syncedCount: 0,
        replayedCount: 0,
        failedCount: 0,
        deferredReason: 'Browser is currently offline; records remain durably queued in IndexedDB.',
      };
    }

    this.isSyncing = true;
    let syncedCount = 0;
    let replayedCount = 0;
    let failedCount = 0;
    let deferredReason: string | null = null;

    try {
      const queue = await this.store.listOutboxAttempts(institutionId);
      const pending = queue.filter((row) => row.status === 'PENDING' || row.status === 'FAILED');

      for (const item of pending) {
        await this.store.putOutboxAttempt({ ...item, status: 'SYNCING' });
        try {
          const result = await this.api.syncAttempt(item.examination_id, {
            client_uuid: item.client_uuid,
            student_id: item.student_id,
            calibration_profile_id: item.calibration_profile_id,
            test_type: item.test_type,
            value: item.value,
            unit: item.unit,
            uncertainty: item.uncertainty,
            confidence_score: item.confidence_score,
            fps_actual: item.fps_actual,
            frame_dt_ms: item.frame_dt_ms,
            dropped_frames: item.dropped_frames,
            landmark_provenance: item.landmark_provenance,
            algorithm_version: item.algorithm_version,
            validity_status: item.validity_status,
            raw_metrics_json: item.raw_metrics_json,
            recorded_at: item.recorded_at,
          });
          await this.store.deleteOutboxAttempt(item.client_uuid);
          if (result.idempotent_replay) {
            replayedCount += 1;
          } else {
            syncedCount += 1;
          }
          await this.store.appendLocalAudit(
            institutionId,
            'ATTEMPT_SYNCED_TO_SERVER',
            `Synced ${item.test_type} (${item.client_uuid}) replay=${result.idempotent_replay}`,
          );
        } catch (err) {
          if (err instanceof ApiClientError) {
            if (err.kind === 'NETWORK_UNAVAILABLE' || err.kind === 'SERVICE_UNAVAILABLE') {
              await this.store.putOutboxAttempt({
                ...item,
                status: 'PENDING',
                retry_count: item.retry_count + 1,
                last_error: err.message,
              });
              deferredReason = err.message;
              break;
            }
            if (err.kind === 'AUTH_REJECTED' || err.kind === 'CSRF_ERROR') {
              await this.store.putOutboxAttempt({
                ...item,
                status: 'PENDING',
                last_error: err.message,
              });
              deferredReason = 'Session expired or unauthorized; sign in to resume sync.';
              break;
            }
            if (err.kind === 'RATE_LIMITED') {
              await this.store.putOutboxAttempt({
                ...item,
                status: 'PENDING',
                retry_count: item.retry_count + 1,
                last_error: `Rate limited (retry after ${err.retryAfterSeconds ?? 5}s)`,
              });
              deferredReason = `Rate limited; retry after ${err.retryAfterSeconds ?? 5}s.`;
              break;
            }
            // Permanent 422/404 validation failure: mark FAILED so examiner sees exact error without infinite retry.
            await this.store.putOutboxAttempt({
              ...item,
              status: 'FAILED',
              retry_count: item.retry_count + 1,
              last_error: err.message,
            });
            failedCount += 1;
          } else {
            await this.store.putOutboxAttempt({
              ...item,
              status: 'FAILED',
              retry_count: item.retry_count + 1,
              last_error: err instanceof Error ? err.message : 'Unknown sync error',
            });
            failedCount += 1;
          }
        }
      }
    } finally {
      this.isSyncing = false;
    }

    return { syncedCount, replayedCount, failedCount, deferredReason };
  }
}
