import { z } from 'zod';
import {
  AttemptResponse,
  AttemptResponseSchema,
  AttemptSyncResponse,
  AttemptSyncResponseSchema,
  AuditLogResponse,
  AuditLogResponseSchema,
  BootstrapResponse,
  BootstrapResponseSchema,
  CalibrationProfileResponse,
  CalibrationProfileResponseSchema,
  CharucoSolveResponse,
  CharucoSolveResponseSchema,
  ExaminationResponse,
  ExaminationResponseSchema,
  ExaminationSummaryResponse,
  ExaminationSummaryResponseSchema,
  HealthResponse,
  HealthResponseSchema,
  InstitutionResponse,
  InstitutionResponseSchema,
  IntrinsicsSolveResponse,
  IntrinsicsSolveResponseSchema,
  ScoringStandardResponse,
  ScoringStandardResponseSchema,
  StudentHistoryResponse,
  StudentHistoryResponseSchema,
  StudentResponse,
  StudentResponseSchema,
  UserResponse,
  UserResponseSchema,
  VideoFramesFallbackResponse,
  VideoFramesFallbackResponseSchema,
  WebSessionResponse,
  WebSessionResponseSchema,
} from './schemas';
import type { ApprovalStatus, CalibrationPlaneType, TestType, UserRole } from '../model/types';

export type ApiErrorKind =
  | 'NETWORK_UNAVAILABLE'
  | 'AUTH_REJECTED'
  | 'CSRF_ERROR'
  | 'NOT_FOUND'
  | 'CONFLICT'
  | 'VALIDATION_ERROR'
  | 'RATE_LIMITED'
  | 'SERVICE_UNAVAILABLE'
  | 'SCHEMA_DRIFT'
  | 'SERVER_ERROR';

export class ApiClientError extends Error {
  readonly kind: ApiErrorKind;
  readonly statusCode: number | null;
  readonly retryAfterSeconds?: number;

  constructor(
    kind: ApiErrorKind,
    message: string,
    statusCode: number | null = null,
    retryAfterSeconds?: number,
  ) {
    super(message);
    this.name = 'ApiClientError';
    this.kind = kind;
    this.statusCode = statusCode;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
const DEFAULT_CSRF_COOKIE = 'athletiq_csrf';

export function readCookie(name: string): string | null {
  if (typeof document === 'undefined' || !document.cookie) {
    return null;
  }
  const prefix = `${encodeURIComponent(name)}=`;
  for (const part of document.cookie.split(';')) {
    const trimmed = part.trim();
    if (trimmed.startsWith(prefix)) {
      return decodeURIComponent(trimmed.slice(prefix.length));
    }
  }
  return null;
}

export interface ApiClientOptions {
  baseUrl?: string;
  timeoutMs?: number;
  fetchImpl?: typeof fetch;
}

export class AthletiqApiClient {
  private readonly baseUrl: string;
  private readonly timeoutMs: number;
  private readonly fetchImpl: typeof fetch;
  private csrfTokenMemory: string | null = null;

  constructor(options: ApiClientOptions = {}) {
    const envBase =
      typeof import.meta !== 'undefined' && import.meta.env?.VITE_API_BASE_URL
        ? String(import.meta.env.VITE_API_BASE_URL)
        : '';
    this.baseUrl = (options.baseUrl ?? envBase).replace(/\/+$/, '');
    this.timeoutMs = options.timeoutMs ?? 15_000;
    this.fetchImpl = options.fetchImpl ?? ((input, init) => globalThis.fetch(input, init));
  }

  setCsrfToken(token: string | null): void {
    this.csrfTokenMemory = token;
  }

  getCsrfToken(): string | null {
    return readCookie(DEFAULT_CSRF_COOKIE) ?? this.csrfTokenMemory;
  }

  private buildUrl(path: string): string {
    return `${this.baseUrl}${path}`;
  }

  private async requestRaw(
    path: string,
    init: RequestInit = {},
  ): Promise<Response> {
    const method = (init.method ?? 'GET').toUpperCase();
    const headers = new Headers(init.headers);
    if (MUTATING_METHODS.has(method)) {
      const csrf = this.getCsrfToken();
      if (csrf && !headers.has('X-CSRF-Token')) {
        headers.set('X-CSRF-Token', csrf);
      }
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);

    try {
      const response = await this.fetchImpl(this.buildUrl(path), {
        ...init,
        method,
        headers,
        credentials: 'include',
        signal: controller.signal,
      });
      if (!response.ok) {
        await this.throwForHttpError(response);
      }
      return response;
    } catch (err) {
      if (err instanceof ApiClientError) {
        throw err;
      }
      const message =
        err instanceof DOMException && err.name === 'AbortError'
          ? `Request timed out after ${this.timeoutMs} ms`
          : err instanceof Error
            ? err.message
            : 'Network connection failed';
      throw new ApiClientError('NETWORK_UNAVAILABLE', message, null);
    } finally {
      clearTimeout(timer);
    }
  }

  private async throwForHttpError(response: Response): Promise<never> {
    let detail = `HTTP ${response.status}`;
    try {
      const text = await response.text();
      if (text) {
        const parsed = JSON.parse(text) as { detail?: unknown };
        if (typeof parsed.detail === 'string') {
          detail = parsed.detail;
        } else if (Array.isArray(parsed.detail)) {
          detail = parsed.detail
            .map((item) =>
              typeof item === 'object' && item && 'msg' in item
                ? String((item as { msg: unknown }).msg)
                : JSON.stringify(item),
            )
            .join('; ');
        }
      }
    } catch {
      // Retain HTTP status message when error body is non-JSON.
    }

    if (response.status === 401) {
      throw new ApiClientError('AUTH_REJECTED', detail, 401);
    }
    if (response.status === 403) {
      const kind: ApiErrorKind = detail.toLowerCase().includes('csrf')
        ? 'CSRF_ERROR'
        : 'AUTH_REJECTED';
      throw new ApiClientError(kind, detail, 403);
    }
    if (response.status === 404) {
      throw new ApiClientError('NOT_FOUND', detail, 404);
    }
    if (response.status === 409) {
      throw new ApiClientError('CONFLICT', detail, 409);
    }
    if (response.status === 422 || response.status === 400) {
      throw new ApiClientError('VALIDATION_ERROR', detail, response.status);
    }
    if (response.status === 429) {
      const retryHeader = Number(response.headers.get('Retry-After') ?? '5');
      const retryAfter = Number.isFinite(retryHeader) && retryHeader > 0 ? retryHeader : 5;
      throw new ApiClientError('RATE_LIMITED', detail, 429, retryAfter);
    }
    if (response.status === 503) {
      throw new ApiClientError('SERVICE_UNAVAILABLE', detail, 503);
    }
    throw new ApiClientError('SERVER_ERROR', detail, response.status);
  }

  private async requestJson<T>(
    path: string,
    schema: z.ZodType<T>,
    init: RequestInit = {},
  ): Promise<T> {
    const headers = new Headers(init.headers);
    if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    const response = await this.requestRaw(path, { ...init, headers });
    const raw: unknown = await response.json();
    const parsed = schema.safeParse(raw);
    if (!parsed.success) {
      throw new ApiClientError(
        'SCHEMA_DRIFT',
        `Unexpected response contract from ${path}: ${parsed.error.issues[0]?.message ?? 'invalid payload'}`,
        response.status,
      );
    }
    return parsed.data;
  }

  async checkHealth(): Promise<HealthResponse> {
    return this.requestJson('/health', HealthResponseSchema);
  }

  async bootstrapInstitution(payload: {
    institution_name: string;
    institution_code: string;
    admin_email: string;
    admin_full_name: string;
    admin_password: string;
  }): Promise<BootstrapResponse> {
    return this.requestJson('/api/v1/auth/bootstrap-institution', BootstrapResponseSchema, {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  }

  async webLogin(email: string, password: string): Promise<WebSessionResponse> {
    const session = await this.requestJson('/api/v1/auth/web/login', WebSessionResponseSchema, {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    });
    this.setCsrfToken(session.csrf_token);
    return session;
  }

  async webLogout(): Promise<void> {
    try {
      await this.requestRaw('/api/v1/auth/web/logout', { method: 'POST' });
    } finally {
      this.setCsrfToken(null);
    }
  }

  async getMe(): Promise<UserResponse> {
    return this.requestJson('/api/v1/auth/me', UserResponseSchema);
  }

  async getInstitution(): Promise<InstitutionResponse> {
    return this.requestJson('/api/v1/auth/institution', InstitutionResponseSchema);
  }

  async listUsers(): Promise<UserResponse[]> {
    return this.requestJson('/api/v1/users', z.array(UserResponseSchema));
  }

  async createUser(payload: {
    email: string;
    full_name: string;
    role: UserRole;
    password: string;
  }): Promise<UserResponse> {
    return this.requestJson('/api/v1/users', UserResponseSchema, {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  }

  async updateUser(
    userId: string,
    payload: { full_name?: string; role?: UserRole; is_active?: boolean },
  ): Promise<UserResponse> {
    return this.requestJson(`/api/v1/users/${userId}`, UserResponseSchema, {
      method: 'PATCH',
      body: JSON.stringify(payload),
    });
  }

  async listStudents(limit = 200, offset = 0): Promise<StudentResponse[]> {
    return this.requestJson(
      `/api/v1/students?limit=${limit}&offset=${offset}`,
      z.array(StudentResponseSchema),
    );
  }

  async createStudent(payload: {
    institution_student_code: string;
    full_name: string;
    date_of_birth?: string | null;
    gender_category?: string | null;
    department?: string | null;
  }): Promise<StudentResponse> {
    return this.requestJson('/api/v1/students', StudentResponseSchema, {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  }

  async updateStudent(
    studentId: string,
    payload: {
      full_name?: string;
      date_of_birth?: string | null;
      gender_category?: string | null;
      department?: string | null;
      is_active?: boolean;
    },
  ): Promise<StudentResponse> {
    return this.requestJson(`/api/v1/students/${studentId}`, StudentResponseSchema, {
      method: 'PATCH',
      body: JSON.stringify(payload),
    });
  }

  async listCalibrations(
    planeType?: CalibrationPlaneType,
    activeOnly = true,
  ): Promise<CalibrationProfileResponse[]> {
    const params = new URLSearchParams({ active_only: String(activeOnly) });
    if (planeType) {
      params.set('plane_type', planeType);
    }
    return this.requestJson(
      `/api/v1/calibrations?${params.toString()}`,
      z.array(CalibrationProfileResponseSchema),
    );
  }

  async createCalibration(payload: {
    device_model: string;
    plane_type: CalibrationPlaneType;
    method: string;
    matrix_json: Record<string, unknown>;
    reprojection_error_px: number;
    reference_target_spec_json: Record<string, unknown>;
    calibrated_at: string;
  }): Promise<CalibrationProfileResponse> {
    return this.requestJson('/api/v1/calibrations', CalibrationProfileResponseSchema, {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  }

  async deactivateCalibration(calibrationId: string): Promise<CalibrationProfileResponse> {
    return this.requestJson(
      `/api/v1/calibrations/${calibrationId}/deactivate`,
      CalibrationProfileResponseSchema,
      { method: 'PATCH' },
    );
  }

  async fetchCharucoBoardPngBlob(params?: {
    squaresX?: number;
    squaresY?: number;
    squareLengthCm?: number;
    markerLengthCm?: number;
  }): Promise<Blob> {
    const query = new URLSearchParams();
    if (params?.squaresX) query.set('squares_x', String(params.squaresX));
    if (params?.squaresY) query.set('squares_y', String(params.squaresY));
    if (params?.squareLengthCm) query.set('square_length_cm', String(params.squareLengthCm));
    if (params?.markerLengthCm) query.set('marker_length_cm', String(params.markerLengthCm));
    const suffix = query.toString() ? `?${query.toString()}` : '';
    const response = await this.requestRaw(`/api/v1/calibrations/charuco-board.png${suffix}`);
    return response.blob();
  }

  async solveIntrinsics(formData: FormData): Promise<IntrinsicsSolveResponse> {
    return this.requestJson('/api/v1/calibrations/solve-intrinsics', IntrinsicsSolveResponseSchema, {
      method: 'POST',
      body: formData,
    });
  }

  async solveCharuco(formData: FormData): Promise<CharucoSolveResponse> {
    return this.requestJson('/api/v1/calibrations/solve-charuco', CharucoSolveResponseSchema, {
      method: 'POST',
      body: formData,
    });
  }

  async listStandards(testType?: TestType, activeOnly = true): Promise<ScoringStandardResponse[]> {
    const params = new URLSearchParams({ active_only: String(activeOnly) });
    if (testType) params.set('test_type', testType);
    return this.requestJson(
      `/api/v1/standards?${params.toString()}`,
      z.array(ScoringStandardResponseSchema),
    );
  }

  async createStandard(payload: {
    name: string;
    test_type: TestType;
    age_min: number;
    age_max: number;
    gender_category?: string | null;
    discipline?: string | null;
    metric_unit: string;
    direction: 'higher_is_better' | 'lower_is_better';
    bands: Array<{
      label: string;
      min_value?: number | null;
      max_value?: number | null;
      points?: number | null;
      qualified?: boolean;
    }>;
    source_citation: string;
  }): Promise<ScoringStandardResponse> {
    return this.requestJson('/api/v1/standards', ScoringStandardResponseSchema, {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  }

  async deactivateStandard(standardId: string): Promise<ScoringStandardResponse> {
    return this.requestJson(
      `/api/v1/standards/${standardId}/deactivate`,
      ScoringStandardResponseSchema,
      { method: 'PATCH' },
    );
  }

  async listExaminations(): Promise<ExaminationResponse[]> {
    return this.requestJson('/api/v1/examinations', z.array(ExaminationResponseSchema));
  }

  async createExamination(payload: {
    title: string;
    test_types: TestType[];
    status?: 'DRAFT' | 'ACTIVE' | 'COMPLETED' | 'ARCHIVED';
  }): Promise<ExaminationResponse> {
    return this.requestJson('/api/v1/examinations', ExaminationResponseSchema, {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  }

  async updateExamination(
    examId: string,
    payload: {
      title?: string;
      test_types?: TestType[];
      status?: 'DRAFT' | 'ACTIVE' | 'COMPLETED' | 'ARCHIVED';
    },
  ): Promise<ExaminationResponse> {
    return this.requestJson(`/api/v1/examinations/${examId}`, ExaminationResponseSchema, {
      method: 'PATCH',
      body: JSON.stringify(payload),
    });
  }

  async listExamAttempts(
    examId: string,
    filters?: { studentId?: string; testType?: TestType },
  ): Promise<AttemptResponse[]> {
    const params = new URLSearchParams();
    if (filters?.studentId) params.set('student_id', filters.studentId);
    if (filters?.testType) params.set('test_type', filters.testType);
    const suffix = params.toString() ? `?${params.toString()}` : '';
    return this.requestJson(
      `/api/v1/examinations/${examId}/attempts${suffix}`,
      z.array(AttemptResponseSchema),
    );
  }

  async syncAttempt(
    examId: string,
    payload: {
      client_uuid: string;
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
    },
  ): Promise<AttemptSyncResponse> {
    return this.requestJson(
      `/api/v1/examinations/${examId}/attempts/sync`,
      AttemptSyncResponseSchema,
      {
        method: 'POST',
        body: JSON.stringify(payload),
      },
    );
  }

  async reviewAttempt(
    examId: string,
    attemptId: string,
    payload: { approval_status: ApprovalStatus; note?: string | null },
  ): Promise<AttemptResponse> {
    return this.requestJson(
      `/api/v1/examinations/${examId}/attempts/${attemptId}/review`,
      AttemptResponseSchema,
      {
        method: 'POST',
        body: JSON.stringify(payload),
      },
    );
  }

  async getStudentHistory(studentId: string): Promise<StudentHistoryResponse> {
    return this.requestJson(
      `/api/v1/analytics/students/${studentId}/history`,
      StudentHistoryResponseSchema,
    );
  }

  async getExaminationSummary(examId: string): Promise<ExaminationSummaryResponse> {
    return this.requestJson(
      `/api/v1/analytics/examinations/${examId}/summary`,
      ExaminationSummaryResponseSchema,
    );
  }

  async downloadStudentPdf(studentId: string): Promise<Blob> {
    const response = await this.requestRaw(`/api/v1/reports/students/${studentId}/pdf`);
    return response.blob();
  }

  async downloadExaminationPdf(examId: string): Promise<Blob> {
    const response = await this.requestRaw(`/api/v1/reports/examinations/${examId}/pdf`);
    return response.blob();
  }

  async listAuditLogs(limit = 100, offset = 0): Promise<AuditLogResponse[]> {
    return this.requestJson(
      `/api/v1/audit-logs?limit=${limit}&offset=${offset}`,
      z.array(AuditLogResponseSchema),
    );
  }

  async extractVideoFramesFallback(formData: FormData): Promise<VideoFramesFallbackResponse> {
    return this.requestJson('/api/v1/video/extract-frames', VideoFramesFallbackResponseSchema, {
      method: 'POST',
      body: formData,
    });
  }
}
