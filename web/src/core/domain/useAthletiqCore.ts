import { useCallback, useEffect, useMemo, useState } from 'react';
import { AthletiqApiClient } from '../api/client';
import type {
  AttemptResponse,
  AuditLogResponse,
  CalibrationProfileResponse,
  ExaminationResponse,
  ExaminationSummaryResponse,
  ScoringStandardResponse,
  StudentHistoryResponse,
  StudentResponse,
  UserResponse,
} from '../api/schemas';
import type { ApprovalStatus, AttemptValidityStatus, CalibrationPlaneType, UserRole } from '../model/types';
import {
  DurableIndexedDbStore,
  type LocalAuditRecord,
  type OutboxAttemptRecord,
} from '../storage/indexedDbStore';
import {
  deleteAllOpfsVideos,
  inspectStorageQuota,
  purgeExpiredOpfsVideos,
  requestDurablePersistence,
  type StorageQuotaTelemetry,
} from '../storage/opfsVideoStore';
import { OutboxSyncManager, type SyncBatchSummary } from '../storage/outboxSyncManager';
import { useAuthSession } from './useAuthSession';
import { type EvaluatedCaptureResult, useCapturePipeline } from './useCapturePipeline';

export interface WorkspaceSnapshot {
  students: StudentResponse[];
  calibrations: CalibrationProfileResponse[];
  examinations: ExaminationResponse[];
  standards: ScoringStandardResponse[];
  users: UserResponse[];
  auditLogs: AuditLogResponse[];
  outbox: OutboxAttemptRecord[];
  localAudit: LocalAuditRecord[];
  storageQuota: StorageQuotaTelemetry | null;
  usingCachedData: boolean;
  cacheTimestampEpochMs: number | null;
  lastSyncSummary: SyncBatchSummary | null;
  busy: boolean;
  error: string | null;
}

export const GUEST_INSTITUTION_SCOPE_ID = '00000000-0000-0000-0000-000000000000';

export function useAthletiqCore() {
  const api = useMemo(() => new AthletiqApiClient(), []);
  const idb = useMemo(() => new DurableIndexedDbStore(), []);
  const syncManager = useMemo(() => new OutboxSyncManager(api, idb), [api, idb]);

  const auth = useAuthSession(api);
  const capture = useCapturePipeline(api);

  const [workspace, setWorkspace] = useState<WorkspaceSnapshot>({
    students: [],
    calibrations: [],
    examinations: [],
    standards: [],
    users: [],
    auditLogs: [],
    outbox: [],
    localAudit: [],
    storageQuota: null,
    usingCachedData: false,
    cacheTimestampEpochMs: null,
    lastSyncSummary: null,
    busy: false,
    error: null,
  });

  const institutionId = auth.isGuest ? GUEST_INSTITUTION_SCOPE_ID : (auth.user?.institution_id ?? null);

  const refreshOfflineState = useCallback(async () => {
    if (!institutionId) return;
    const [outbox, localAudit, storageQuota] = await Promise.all([
      idb.listOutboxAttempts(institutionId).catch(() => []),
      idb.listLocalAudit(institutionId).catch(() => []),
      inspectStorageQuota().catch(() => null),
    ]);
    setWorkspace((prev) => ({ ...prev, outbox, localAudit, storageQuota }));
  }, [idb, institutionId]);

  const refreshWorkspace = useCallback(async () => {
    if (auth.status === 'GUEST') {
      setWorkspace((prev) => ({ ...prev, busy: true, error: null }));
      const [cCals, cStds, outbox, localAudit, storageQuota] = await Promise.all([
        idb.getCachedEntities<CalibrationProfileResponse[]>(GUEST_INSTITUTION_SCOPE_ID, 'calibrations').catch(() => null),
        idb.getCachedEntities<ScoringStandardResponse[]>(GUEST_INSTITUTION_SCOPE_ID, 'standards').catch(() => null),
        idb.listOutboxAttempts(GUEST_INSTITUTION_SCOPE_ID).catch(() => []),
        idb.listLocalAudit(GUEST_INSTITUTION_SCOPE_ID).catch(() => []),
        inspectStorageQuota().catch(() => null),
      ]);
      setWorkspace((prev) => ({
        ...prev,
        students: [],
        examinations: [],
        users: [],
        auditLogs: [],
        calibrations: cCals?.data ?? prev.calibrations,
        standards: cStds?.data ?? [],
        outbox,
        localAudit,
        storageQuota,
        usingCachedData: false,
        cacheTimestampEpochMs: cCals?.updatedAtEpochMs ?? Date.now(),
        busy: false,
        error: null,
      }));
      return;
    }

    if (!institutionId || auth.status !== 'AUTHENTICATED') {
      return;
    }
    setWorkspace((prev) => ({ ...prev, busy: true, error: null }));
    try {
      const [students, calibrations, examinations, standards, users, auditLogs] = await Promise.all([
        api.listStudents(),
        api.listCalibrations(undefined, false),
        api.listExaminations(),
        api.listStandards(undefined, false),
        auth.isAdmin ? api.listUsers() : Promise.resolve([]),
        auth.isAdmin ? api.listAuditLogs(100, 0) : Promise.resolve([]),
      ]);

      await Promise.all([
        idb.setCachedEntities(institutionId, 'students', students).catch(() => undefined),
        idb.setCachedEntities(institutionId, 'calibrations', calibrations).catch(() => undefined),
        idb.setCachedEntities(institutionId, 'examinations', examinations).catch(() => undefined),
        idb.setCachedEntities(institutionId, 'standards', standards).catch(() => undefined),
      ]);

      const [outbox, localAudit, storageQuota] = await Promise.all([
        idb.listOutboxAttempts(institutionId).catch(() => []),
        idb.listLocalAudit(institutionId).catch(() => []),
        inspectStorageQuota().catch(() => null),
      ]);

      setWorkspace((prev) => ({
        ...prev,
        students,
        calibrations,
        examinations,
        standards,
        users,
        auditLogs,
        outbox,
        localAudit,
        storageQuota,
        usingCachedData: false,
        cacheTimestampEpochMs: Date.now(),
        busy: false,
        error: null,
      }));
    } catch (err) {
      // Load explicitly labelled cached entities from IndexedDB if offline/unreachable.
      const [cStudents, cCals, cExams, cStds, outbox, localAudit, storageQuota] = await Promise.all([
        idb.getCachedEntities<StudentResponse[]>(institutionId, 'students').catch(() => null),
        idb.getCachedEntities<CalibrationProfileResponse[]>(institutionId, 'calibrations').catch(() => null),
        idb.getCachedEntities<ExaminationResponse[]>(institutionId, 'examinations').catch(() => null),
        idb.getCachedEntities<ScoringStandardResponse[]>(institutionId, 'standards').catch(() => null),
        idb.listOutboxAttempts(institutionId).catch(() => []),
        idb.listLocalAudit(institutionId).catch(() => []),
        inspectStorageQuota().catch(() => null),
      ]);

      setWorkspace((prev) => ({
        ...prev,
        students: cStudents?.data ?? prev.students,
        calibrations: cCals?.data ?? prev.calibrations,
        examinations: cExams?.data ?? prev.examinations,
        standards: cStds?.data ?? prev.standards,
        outbox,
        localAudit,
        storageQuota,
        usingCachedData: Boolean(cStudents || cCals || cExams || cStds),
        cacheTimestampEpochMs: cStudents?.updatedAtEpochMs ?? prev.cacheTimestampEpochMs,
        busy: false,
        error: err instanceof Error ? err.message : 'Unable to refresh workspace from server',
      }));
    }
  }, [api, auth.isAdmin, auth.status, idb, institutionId]);

  const syncPendingOutbox = useCallback(async () => {
    if (!institutionId || auth.isGuest) return null;
    const summary = await syncManager.syncPending(institutionId);
    setWorkspace((prev) => ({ ...prev, lastSyncSummary: summary }));
    await refreshOfflineState();
    return summary;
  }, [auth.isGuest, institutionId, refreshOfflineState, syncManager]);

  useEffect(() => {
    if (auth.status === 'AUTHENTICATED' || auth.status === 'GUEST') {
      void refreshWorkspace();
    }
  }, [auth.status, refreshWorkspace]);

  useEffect(() => {
    if (!institutionId || auth.isGuest) return;
    const onOnline = () => {
      void syncPendingOutbox().then(() => refreshWorkspace());
    };
    window.addEventListener('online', onOnline);
    return () => window.removeEventListener('online', onOnline);
  }, [auth.isGuest, institutionId, refreshWorkspace, syncPendingOutbox]);

  const queueOrSyncEvaluation = useCallback(
    async (params: {
      evaluation: EvaluatedCaptureResult;
      examinationId: string;
      studentId: string;
    }): Promise<{ queued: boolean; syncSummary: SyncBatchSummary | null }> => {
      if (!institutionId) {
        throw new Error('No active institution session.');
      }
      const { evaluation } = params;
      if (evaluation.outcome.kind !== 'VALID' && evaluation.outcome.kind !== 'INVALID_ATTEMPT') {
        throw new Error('Only evaluated attempts can be queued for examination sync.');
      }

      let val: number;
      let unit: string;
      let uncertainty: number;
      let confidence: number;
      let provenance: string;
      let algorithmVersion: string;
      let validityStatus: AttemptValidityStatus;

      if (evaluation.outcome.kind === 'VALID') {
        val = evaluation.outcome.value;
        unit = evaluation.outcome.unit;
        uncertainty = evaluation.outcome.uncertainty;
        confidence = evaluation.outcome.confidenceScore;
        provenance = evaluation.outcome.provenance.landmarkProvenance;
        algorithmVersion = evaluation.outcome.provenance.algorithmVersion;
        validityStatus = evaluation.outcome.validityStatus;
      } else {
        // INVALID_ATTEMPT
        val = 0;
        unit = 'CENTIMETERS';
        uncertainty = 0;
        confidence = 0;
        provenance = 'MEDIAPIPE_POSE_HEEL_ONLY';
        algorithmVersion = 'invalid-attempt-v1';
        validityStatus = 'INVALID';
      }

      const record: OutboxAttemptRecord = {
        client_uuid: evaluation.clientUuid,
        institution_id: institutionId,
        examination_id: params.examinationId,
        student_id: params.studentId,
        calibration_profile_id: evaluation.calibrationProfileId,
        test_type: evaluation.testType,
        value: val,
        unit,
        uncertainty,
        confidence_score: confidence,
        fps_actual: evaluation.timing.fpsActual,
        frame_dt_ms: evaluation.timing.frameDtMs,
        dropped_frames: evaluation.timing.droppedFrames,
        landmark_provenance: provenance,
        algorithm_version: algorithmVersion,
        validity_status: validityStatus,
        raw_metrics_json: evaluation.rawMetricsJson,
        recorded_at: new Date().toISOString(),
        status: 'PENDING',
        retry_count: 0,
        last_error: null,
        opfs_video_filename: evaluation.opfsVideoFilename,
        created_at_epoch_ms: Date.now(),
      };

      await syncManager.enqueueAttempt(record);
      if (auth.isGuest) {
        await refreshOfflineState();
        return {
          queued: true,
          syncSummary: {
            syncedCount: 0,
            replayedCount: 0,
            failedCount: 0,
            deferredReason:
              'Saved locally in Guest Mode (OPFS + IndexedDB). Sign in with an institutional account to sync to an examination.',
          },
        };
      }
      const syncSummary = await syncPendingOutbox();
      return { queued: true, syncSummary };
    },
    [auth.isGuest, institutionId, refreshOfflineState, syncManager, syncPendingOutbox],
  );

  const registerStudent = useCallback(
    async (payload: Parameters<AthletiqApiClient['createStudent']>[0]) => {
      const created = await api.createStudent(payload);
      await refreshWorkspace();
      return created;
    },
    [api, refreshWorkspace],
  );

  const createExamination = useCallback(
    async (payload: Parameters<AthletiqApiClient['createExamination']>[0]) => {
      const created = await api.createExamination(payload);
      await refreshWorkspace();
      return created;
    },
    [api, refreshWorkspace],
  );

  const updateExaminationStatus = useCallback(
    async (examId: string, status: 'DRAFT' | 'ACTIVE' | 'COMPLETED' | 'ARCHIVED') => {
      const updated = await api.updateExamination(examId, { status });
      await refreshWorkspace();
      return updated;
    },
    [api, refreshWorkspace],
  );

  const saveCalibration = useCallback(
    async (payload: {
      device_model: string;
      plane_type: CalibrationPlaneType;
      method: string;
      matrix_json: Record<string, unknown>;
      reprojection_error_px: number;
      reference_target_spec_json: Record<string, unknown>;
    }) => {
      if (auth.isGuest) {
        const nowIso = new Date().toISOString();
        const id =
          typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
            ? crypto.randomUUID()
            : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
        const localProfile: CalibrationProfileResponse = {
          id,
          institution_id: GUEST_INSTITUTION_SCOPE_ID,
          device_model: payload.device_model,
          plane_type: payload.plane_type,
          method: payload.method,
          matrix_json: payload.matrix_json,
          reprojection_error_px: payload.reprojection_error_px,
          reference_target_spec_json: payload.reference_target_spec_json,
          is_active: true,
          calibrated_at: nowIso,
        };
        const existing =
          (
            await idb
              .getCachedEntities<CalibrationProfileResponse[]>(GUEST_INSTITUTION_SCOPE_ID, 'calibrations')
              .catch(() => null)
          )?.data ?? workspace.calibrations;
        const nextList = [localProfile, ...existing];
        await idb.setCachedEntities(GUEST_INSTITUTION_SCOPE_ID, 'calibrations', nextList);
        await idb
          .appendLocalAudit(
            GUEST_INSTITUTION_SCOPE_ID,
            'GUEST_CALIBRATION_SAVED',
            `${payload.plane_type} (${payload.method}, RMS ${payload.reprojection_error_px.toFixed(2)} px)`,
          )
          .catch(() => undefined);
        await refreshWorkspace();
        return localProfile;
      }
      const created = await api.createCalibration({
        ...payload,
        calibrated_at: new Date().toISOString(),
      });
      await refreshWorkspace();
      return created;
    },
    [api, auth.isGuest, idb, refreshWorkspace, workspace.calibrations],
  );

  const deactivateCalibration = useCallback(
    async (id: string) => {
      if (auth.isGuest) {
        const nextList = workspace.calibrations.map((c) =>
          c.id === id ? { ...c, is_active: false } : c,
        );
        await idb.setCachedEntities(GUEST_INSTITUTION_SCOPE_ID, 'calibrations', nextList);
        await refreshWorkspace();
        return nextList.find((c) => c.id === id) as CalibrationProfileResponse;
      }
      const res = await api.deactivateCalibration(id);
      await refreshWorkspace();
      return res;
    },
    [api, auth.isGuest, idb, refreshWorkspace, workspace.calibrations],
  );

  const createStandard = useCallback(
    async (payload: Parameters<AthletiqApiClient['createStandard']>[0]) => {
      const res = await api.createStandard(payload);
      await refreshWorkspace();
      return res;
    },
    [api, refreshWorkspace],
  );

  const createStaffUser = useCallback(
    async (payload: { email: string; full_name: string; role: UserRole; password: string }) => {
      const res = await api.createUser(payload);
      await refreshWorkspace();
      return res;
    },
    [api, refreshWorkspace],
  );

  const reviewAttempt = useCallback(
    async (examId: string, attemptId: string, approvalStatus: ApprovalStatus, note?: string): Promise<AttemptResponse> => {
      return api.reviewAttempt(examId, attemptId, { approval_status: approvalStatus, note });
    },
    [api],
  );

  const loadExamAttempts = useCallback(
    async (examId: string): Promise<AttemptResponse[]> => api.listExamAttempts(examId),
    [api],
  );

  const loadStudentHistory = useCallback(
    async (studentId: string): Promise<StudentHistoryResponse> => api.getStudentHistory(studentId),
    [api],
  );

  const loadExamSummary = useCallback(
    async (examId: string): Promise<ExaminationSummaryResponse> => api.getExaminationSummary(examId),
    [api],
  );

  return {
    api,
    auth,
    capture,
    workspace,
    refreshWorkspace,
    syncPendingOutbox,
    queueOrSyncEvaluation,
    registerStudent,
    createExamination,
    updateExaminationStatus,
    saveCalibration,
    deactivateCalibration,
    createStandard,
    createStaffUser,
    reviewAttempt,
    loadExamAttempts,
    loadStudentHistory,
    loadExamSummary,
    requestDurablePersistence: async () => {
      const res = await requestDurablePersistence();
      await refreshOfflineState();
      return res;
    },
    purgeExpiredVideos: async (days: number) => {
      const removed = await purgeExpiredOpfsVideos(days);
      await refreshOfflineState();
      return removed;
    },
    deleteAllVideos: async () => {
      const removed = await deleteAllOpfsVideos();
      await refreshOfflineState();
      return removed;
    },
  };
}
