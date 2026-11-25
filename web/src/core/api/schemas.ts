import { z } from 'zod';

export const UserRoleSchema = z.enum(['ADMIN', 'INSTRUCTOR', 'COACH']);
export const TestTypeSchema = z.enum(['HEIGHT', 'VERTICAL_JUMP', 'STANDING_BROAD_JUMP', 'SPRINT']);
export const CalibrationPlaneTypeSchema = z.enum(['INTRINSICS', 'VERTICAL_PLANE', 'GROUND_PLANE']);
export const AttemptValidityStatusSchema = z.enum(['VALID', 'FLAGGED', 'INVALID']);
export const ApprovalStatusSchema = z.enum(['PENDING', 'APPROVED', 'REJECTED']);

export const HealthResponseSchema = z.object({
  status: z.string(),
  database: z.string(),
});

export const InstitutionResponseSchema = z.object({
  id: z.string().uuid(),
  name: z.string(),
  code: z.string(),
  is_active: z.boolean(),
  created_at: z.string(),
});

export const UserResponseSchema = z.object({
  id: z.string().uuid(),
  institution_id: z.string().uuid(),
  email: z.string(),
  full_name: z.string(),
  role: UserRoleSchema,
  is_active: z.boolean(),
  created_at: z.string(),
});

export const BootstrapResponseSchema = z.object({
  institution: InstitutionResponseSchema,
  admin_user: UserResponseSchema,
});

export const WebSessionResponseSchema = z.object({
  user: UserResponseSchema,
  institution: InstitutionResponseSchema,
  csrf_token: z.string().min(1),
  expires_at: z.string(),
});

export const StudentResponseSchema = z.object({
  id: z.string().uuid(),
  institution_id: z.string().uuid(),
  institution_student_code: z.string(),
  full_name: z.string(),
  date_of_birth: z.string().nullable(),
  gender_category: z.string().nullable(),
  department: z.string().nullable(),
  managed_by_user_id: z.string().uuid(),
  is_active: z.boolean(),
  created_at: z.string(),
  updated_at: z.string(),
});

export const CalibrationProfileResponseSchema = z.object({
  id: z.string().uuid(),
  institution_id: z.string().uuid(),
  device_model: z.string(),
  plane_type: CalibrationPlaneTypeSchema,
  method: z.string(),
  matrix_json: z.record(z.unknown()),
  reprojection_error_px: z.number(),
  reference_target_spec_json: z.record(z.unknown()),
  calibrated_at: z.string(),
  is_active: z.boolean(),
});

export const CharucoSolveResponseSchema = z.object({
  plane_type: CalibrationPlaneTypeSchema,
  detected_markers_count: z.number().int(),
  detected_charuco_corners: z.number().int(),
  homography_3x3: z.array(z.number()).length(9).nullable(),
  reprojection_error_px: z.number().nullable(),
  spatial_coverage_ratio: z.number(),
  quadrants_covered: z.number().int(),
  image_width: z.number().int(),
  image_height: z.number().int(),
  reference_target_spec_json: z.record(z.unknown()),
  used_intrinsics: z.boolean(),
  quality_gate_passed: z.boolean(),
  provisional_thresholds: z.record(z.unknown()),
  warnings: z.array(z.string()),
  rejection_reason: z.string().nullable(),
});

export const IntrinsicsSolveResponseSchema = z.object({
  camera_matrix_3x3: z.array(z.number()).length(9).nullable(),
  dist_coeffs: z.array(z.number()).nullable(),
  reprojection_error_px: z.number().nullable(),
  views_submitted: z.number().int(),
  views_accepted: z.number().int(),
  views_rejected: z.number().int(),
  image_width: z.number().int().nullable(),
  image_height: z.number().int().nullable(),
  quality_gate_passed: z.boolean(),
  provisional_thresholds: z.record(z.unknown()),
  warnings: z.array(z.string()),
  rejection_reason: z.string().nullable(),
});

export const ScoringBandSpecSchema = z.object({
  label: z.string(),
  min_value: z.number().nullable().optional(),
  max_value: z.number().nullable().optional(),
  points: z.number().nullable().optional(),
  qualified: z.boolean().default(false),
});

export const ScoringStandardResponseSchema = z.object({
  id: z.string().uuid(),
  institution_id: z.string().uuid(),
  name: z.string(),
  test_type: TestTypeSchema,
  age_min: z.number().int(),
  age_max: z.number().int(),
  gender_category: z.string().nullable(),
  discipline: z.string().nullable(),
  metric_unit: z.string(),
  bands_json: z.record(z.unknown()),
  source_citation: z.string(),
  is_active: z.boolean(),
  created_at: z.string(),
});

export const ExaminationResponseSchema = z.object({
  id: z.string().uuid(),
  institution_id: z.string().uuid(),
  title: z.string(),
  test_types_json: z.array(z.string()),
  status: z.string(),
  created_by_user_id: z.string().uuid(),
  created_at: z.string(),
});

export const AttemptResponseSchema = z.object({
  id: z.string().uuid(),
  client_uuid: z.string().uuid(),
  institution_id: z.string().uuid(),
  examination_id: z.string().uuid(),
  student_id: z.string().uuid(),
  calibration_profile_id: z.string().uuid(),
  test_type: TestTypeSchema,
  value: z.number(),
  unit: z.string(),
  uncertainty: z.number(),
  confidence_score: z.number(),
  fps_actual: z.number(),
  frame_dt_ms: z.number(),
  dropped_frames: z.number().int(),
  landmark_provenance: z.string(),
  algorithm_version: z.string(),
  validity_status: AttemptValidityStatusSchema,
  approval_status: ApprovalStatusSchema,
  is_best_attempt: z.boolean(),
  raw_metrics_json: z.record(z.unknown()),
  recorded_at: z.string(),
  approved_by_user_id: z.string().uuid().nullable(),
  approved_at: z.string().nullable(),
});

export const AttemptSyncResponseSchema = z.object({
  attempt: AttemptResponseSchema,
  idempotent_replay: z.boolean(),
});

export const ScoringEvaluationResponseSchema = z.object({
  status: z.string(),
  standard_id: z.string().nullable(),
  standard_name: z.string().nullable(),
  source_citation: z.string().nullable(),
  category_label: z.string().nullable(),
  score_points: z.number().nullable(),
  qualified: z.boolean().nullable(),
});

export const StudentTestTrendSchema = z.object({
  test_type: TestTypeSchema,
  latest_value: z.number(),
  previous_value: z.number().nullable(),
  delta_from_previous: z.number().nullable(),
  best_value: z.number(),
  unit: z.string(),
  attempt_count: z.number().int(),
  scoring: ScoringEvaluationResponseSchema,
});

export const StudentHistoryResponseSchema = z.object({
  student: StudentResponseSchema,
  trends: z.array(StudentTestTrendSchema),
  attempts: z.array(AttemptResponseSchema),
});

export const ExamTestSummarySchema = z.object({
  test_type: TestTypeSchema,
  count: z.number().int(),
  average: z.number(),
  best: z.number(),
  unit: z.string(),
});

export const ExamRankingItemSchema = z.object({
  test_type: TestTypeSchema,
  rank: z.number().int(),
  student_id: z.string().uuid(),
  institution_student_code: z.string(),
  full_name: z.string(),
  department: z.string().nullable(),
  value: z.number(),
  unit: z.string(),
  category_label: z.string().nullable(),
});

export const ExaminationSummaryResponseSchema = z.object({
  examination: ExaminationResponseSchema,
  total_students_tested: z.number().int(),
  approved_attempts_count: z.number().int(),
  pending_attempts_count: z.number().int(),
  test_summaries: z.array(ExamTestSummarySchema),
  rankings: z.array(ExamRankingItemSchema),
});

export const AuditLogResponseSchema = z.object({
  id: z.string().uuid(),
  institution_id: z.string().uuid(),
  actor_user_id: z.string().uuid().nullable(),
  action: z.string(),
  resource_type: z.string(),
  resource_id: z.string(),
  details_json: z.record(z.unknown()),
  created_at: z.string(),
});

export const VideoFramesFallbackResponseSchema = z.object({
  frame_count: z.number().int(),
  fps_estimated: z.number().nullable(),
  duration_seconds: z.number().nullable(),
  width: z.number().int().nullable(),
  height: z.number().int().nullable(),
  frames_base64: z.array(z.string()),
  timestamps_us: z.array(z.number().int()),
});

export type HealthResponse = z.infer<typeof HealthResponseSchema>;
export type InstitutionResponse = z.infer<typeof InstitutionResponseSchema>;
export type UserResponse = z.infer<typeof UserResponseSchema>;
export type BootstrapResponse = z.infer<typeof BootstrapResponseSchema>;
export type WebSessionResponse = z.infer<typeof WebSessionResponseSchema>;
export type StudentResponse = z.infer<typeof StudentResponseSchema>;
export type CalibrationProfileResponse = z.infer<typeof CalibrationProfileResponseSchema>;
export type CharucoSolveResponse = z.infer<typeof CharucoSolveResponseSchema>;
export type IntrinsicsSolveResponse = z.infer<typeof IntrinsicsSolveResponseSchema>;
export type ScoringStandardResponse = z.infer<typeof ScoringStandardResponseSchema>;
export type ExaminationResponse = z.infer<typeof ExaminationResponseSchema>;
export type AttemptResponse = z.infer<typeof AttemptResponseSchema>;
export type AttemptSyncResponse = z.infer<typeof AttemptSyncResponseSchema>;
export type StudentHistoryResponse = z.infer<typeof StudentHistoryResponseSchema>;
export type ExaminationSummaryResponse = z.infer<typeof ExaminationSummaryResponseSchema>;
export type AuditLogResponse = z.infer<typeof AuditLogResponseSchema>;
export type VideoFramesFallbackResponse = z.infer<typeof VideoFramesFallbackResponseSchema>;
