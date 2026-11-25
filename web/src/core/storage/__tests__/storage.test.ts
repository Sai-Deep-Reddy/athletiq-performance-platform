import { describe, expect, it, vi } from 'vitest';
import { AthletiqApiClient } from '../../api/client';
import { DurableIndexedDbStore, type OutboxAttemptRecord, StorageUnavailableError } from '../indexedDbStore';
import { OutboxSyncManager } from '../outboxSyncManager';
import { inspectStorageQuota } from '../opfsVideoStore';

function makeSampleRecord(clientUuid: string): OutboxAttemptRecord {
  return {
    client_uuid: clientUuid,
    institution_id: '00000000-0000-0000-0000-000000000001',
    examination_id: '00000000-0000-0000-0000-000000000002',
    student_id: '00000000-0000-0000-0000-000000000003',
    calibration_profile_id: '00000000-0000-0000-0000-000000000004',
    test_type: 'HEIGHT',
    value: 172.5,
    unit: 'CENTIMETERS',
    uncertainty: 0.6,
    confidence_score: 0.92,
    fps_actual: 30,
    frame_dt_ms: 33.33,
    dropped_frames: 0,
    landmark_provenance: 'MEDIAPIPE_POSE_HEAD_CROWN_REFINED',
    algorithm_version: 'height-c1-web-v1.0',
    validity_status: 'VALID',
    raw_metrics_json: { source: 'test' },
    recorded_at: '2026-11-25T10:00:00Z',
    status: 'PENDING',
    retry_count: 0,
    last_error: null,
    opfs_video_filename: null,
    created_at_epoch_ms: Date.now(),
  };
}

describe('DurableIndexedDbStore & OutboxSyncManager', () => {
  it('throws StorageUnavailableError instead of silent in-memory fallback when IndexedDB is missing', async () => {
    const store = new DurableIndexedDbStore(undefined);
    await expect(store.listOutboxAttempts('inst-1')).rejects.toBeInstanceOf(StorageUnavailableError);
  });

  it('syncs pending records idempotently and pauses cleanly on 429 rate limits', async () => {
    const uuid1 = '00000000-0000-4000-8000-000000000101';
    const uuid2 = '00000000-0000-4000-8000-000000000102';
    const records = new Map<string, OutboxAttemptRecord>([
      [uuid1, makeSampleRecord(uuid1)],
      [uuid2, makeSampleRecord(uuid2)],
    ]);

    const mockStore = {
      putOutboxAttempt: vi.fn(async (rec: OutboxAttemptRecord) => {
        records.set(rec.client_uuid, rec);
      }),
      listOutboxAttempts: vi.fn(async () => Array.from(records.values())),
      deleteOutboxAttempt: vi.fn(async (id: string) => {
        records.delete(id);
      }),
      appendLocalAudit: vi.fn(async () => undefined),
    } as unknown as DurableIndexedDbStore;

    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            attempt: {
              ...makeSampleRecord(uuid1),
              id: '00000000-0000-4000-8000-000000000099',
              approval_status: 'PENDING',
              is_best_attempt: true,
              approved_by_user_id: null,
              approved_at: null,
            },
            idempotent_replay: false,
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ detail: 'Slow down' }), {
          status: 429,
          headers: { 'Retry-After': '15' },
        }),
      );

    const api = new AthletiqApiClient({ baseUrl: '', fetchImpl: fetchMock });
    const manager = new OutboxSyncManager(api, mockStore);

    const summary = await manager.syncPending('00000000-0000-0000-0000-000000000001');
    expect(summary.syncedCount).toBe(1);
    expect(summary.failedCount).toBe(0);
    expect(summary.deferredReason).toContain('15s');
    expect(records.has(uuid1)).toBe(false);
    expect(records.get(uuid2)?.status).toBe('PENDING');
  });

  it('reports opfsSupported=false explicitly in jsdom when OPFS is absent', async () => {
    const quota = await inspectStorageQuota();
    expect(quota.opfsSupported).toBe(false);
    expect(quota.videoFileCount).toBe(0);
  });
});
