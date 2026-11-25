import type { TestType } from '../model/types';

export type OutboxStatus = 'PENDING' | 'SYNCING' | 'FAILED' | 'SYNCED';

export interface OutboxAttemptRecord {
  client_uuid: string;
  institution_id: string;
  examination_id: string;
  student_id: string;
  calibration_profile_id: string;
  test_type: TestType;
  value: number;
  unit: string;
  uncertainty: number;
  confidence_score: number;
  fps_actual: number;
  frame_dt_ms: number;
  dropped_frames: number;
  landmark_provenance: string;
  algorithm_version: string;
  validity_status: 'VALID' | 'FLAGGED' | 'INVALID';
  raw_metrics_json: Record<string, unknown>;
  recorded_at: string;
  status: OutboxStatus;
  retry_count: number;
  last_error: string | null;
  opfs_video_filename: string | null;
  created_at_epoch_ms: number;
}

export interface CachedEntityEnvelope<T> {
  cacheKey: string;
  institutionId: string;
  entityKind: 'students' | 'calibrations' | 'examinations' | 'standards';
  updatedAtEpochMs: number;
  data: T;
}

export interface LocalAuditRecord {
  id: string;
  institutionId: string;
  action: string;
  detail: string;
  createdAtIso: string;
}

export class StorageUnavailableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'StorageUnavailableError';
  }
}

const DB_NAME = 'athletiq_web_core_db';
const DB_VERSION = 1;
const STORE_OUTBOX = 'attempts_outbox';
const STORE_CACHE = 'cached_entities';
const STORE_AUDIT = 'local_audit_events';

function openDatabase(idbFactory?: IDBFactory): Promise<IDBDatabase> {
  const factory = idbFactory ?? (typeof indexedDB !== 'undefined' ? indexedDB : undefined);
  if (!factory) {
    return Promise.reject(
      new StorageUnavailableError(
        'IndexedDB is unavailable in this browser; durable offline examination storage cannot operate.',
      ),
    );
  }

  return new Promise<IDBDatabase>((resolve, reject) => {
    const req = factory.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE_OUTBOX)) {
        const outbox = db.createObjectStore(STORE_OUTBOX, { keyPath: 'client_uuid' });
        outbox.createIndex('institution_id', 'institution_id', { unique: false });
        outbox.createIndex('status', 'status', { unique: false });
      }
      if (!db.objectStoreNames.contains(STORE_CACHE)) {
        db.createObjectStore(STORE_CACHE, { keyPath: 'cacheKey' });
      }
      if (!db.objectStoreNames.contains(STORE_AUDIT)) {
        const audit = db.createObjectStore(STORE_AUDIT, { keyPath: 'id' });
        audit.createIndex('institutionId', 'institutionId', { unique: false });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () =>
      reject(
        new StorageUnavailableError(
          req.error?.message ?? 'Failed to open IndexedDB database.',
        ),
      );
  });
}

function runTx<T>(
  db: IDBDatabase,
  storeName: string,
  mode: IDBTransactionMode,
  fn: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const tx = db.transaction(storeName, mode);
    const store = tx.objectStore(storeName);
    const request = fn(store);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error('IndexedDB operation failed'));
  });
}

export class DurableIndexedDbStore {
  private readonly idbFactory?: IDBFactory;

  constructor(idbFactory?: IDBFactory) {
    this.idbFactory = idbFactory;
  }

  private async withDb<T>(fn: (db: IDBDatabase) => Promise<T>): Promise<T> {
    const db = await openDatabase(this.idbFactory);
    try {
      return await fn(db);
    } finally {
      db.close();
    }
  }

  async putOutboxAttempt(record: OutboxAttemptRecord): Promise<void> {
    await this.withDb((db) => runTx(db, STORE_OUTBOX, 'readwrite', (s) => s.put(record)));
  }

  async listOutboxAttempts(institutionId: string): Promise<OutboxAttemptRecord[]> {
    return this.withDb(async (db) => {
      const all = await runTx<OutboxAttemptRecord[]>(db, STORE_OUTBOX, 'readonly', (s) =>
        s.getAll(),
      );
      return all
        .filter((row) => row.institution_id === institutionId)
        .sort((a, b) => a.created_at_epoch_ms - b.created_at_epoch_ms);
    });
  }

  async deleteOutboxAttempt(clientUuid: string): Promise<void> {
    await this.withDb((db) => runTx(db, STORE_OUTBOX, 'readwrite', (s) => s.delete(clientUuid)));
  }

  async setCachedEntities<T>(
    institutionId: string,
    entityKind: CachedEntityEnvelope<T>['entityKind'],
    data: T,
  ): Promise<void> {
    const envelope: CachedEntityEnvelope<T> = {
      cacheKey: `${institutionId}:${entityKind}`,
      institutionId,
      entityKind,
      updatedAtEpochMs: Date.now(),
      data,
    };
    await this.withDb((db) => runTx(db, STORE_CACHE, 'readwrite', (s) => s.put(envelope)));
  }

  async getCachedEntities<T>(
    institutionId: string,
    entityKind: CachedEntityEnvelope<T>['entityKind'],
  ): Promise<CachedEntityEnvelope<T> | null> {
    return this.withDb(async (db) => {
      const result = await runTx<CachedEntityEnvelope<T> | undefined>(
        db,
        STORE_CACHE,
        'readonly',
        (s) => s.get(`${institutionId}:${entityKind}`),
      );
      return result ?? null;
    });
  }

  async appendLocalAudit(institutionId: string, action: string, detail: string): Promise<void> {
    const entry: LocalAuditRecord = {
      id:
        typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
          ? crypto.randomUUID()
          : `${Date.now()}-${Math.random().toString(16).slice(2)}`,
      institutionId,
      action,
      detail,
      createdAtIso: new Date().toISOString(),
    };
    await this.withDb((db) => runTx(db, STORE_AUDIT, 'readwrite', (s) => s.put(entry)));
  }

  async listLocalAudit(institutionId: string, limit = 100): Promise<LocalAuditRecord[]> {
    return this.withDb(async (db) => {
      const all = await runTx<LocalAuditRecord[]>(db, STORE_AUDIT, 'readonly', (s) => s.getAll());
      return all
        .filter((row) => row.institutionId === institutionId)
        .sort((a, b) => (a.createdAtIso < b.createdAtIso ? 1 : -1))
        .slice(0, limit);
    });
  }
}
