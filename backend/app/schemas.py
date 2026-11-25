"""Pydantic v2 request and response schemas for Stage A endpoints."""

import math
from datetime import date, datetime
from typing import Any
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.db.models import (
    ApprovalStatus,
    AttemptValidityStatus,
    CalibrationPlaneType,
    TestType,
    UserRole,
)


class HealthResponse(BaseModel):
    status: str
    database: str


class InstitutionBootstrapRequest(BaseModel):
    institution_name: str = Field(..., min_length=2, max_length=200)
    institution_code: str = Field(..., min_length=2, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")
    admin_email: str = Field(..., min_length=5, max_length=255)
    admin_full_name: str = Field(..., min_length=2, max_length=200)
    admin_password: str = Field(..., min_length=12, max_length=128)

    @field_validator("admin_email")
    @classmethod
    def normalize_email(cls, value: str) -> str:
        cleaned = value.strip().lower()
        if "@" not in cleaned or "." not in cleaned.split("@", 1)[1]:
            raise ValueError("Invalid email format")
        return cleaned


class LoginRequest(BaseModel):
    email: str = Field(..., min_length=5, max_length=255)
    password: str = Field(..., min_length=1, max_length=128)

    @field_validator("email")
    @classmethod
    def normalize_email(cls, value: str) -> str:
        return value.strip().lower()


class UserResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    institution_id: UUID
    email: str
    full_name: str
    role: UserRole
    is_active: bool
    created_at: datetime


class InstitutionResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    name: str
    code: str
    is_active: bool
    created_at: datetime


class AuthTokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_at: datetime
    user: UserResponse


class BootstrapResponse(BaseModel):
    institution: InstitutionResponse
    admin_user: UserResponse


class UserCreateRequest(BaseModel):
    email: str = Field(..., min_length=5, max_length=255)
    full_name: str = Field(..., min_length=2, max_length=200)
    role: UserRole
    password: str = Field(..., min_length=12, max_length=128)

    @field_validator("email")
    @classmethod
    def normalize_email(cls, value: str) -> str:
        cleaned = value.strip().lower()
        if "@" not in cleaned or "." not in cleaned.split("@", 1)[1]:
            raise ValueError("Invalid email format")
        return cleaned


class StudentCreateRequest(BaseModel):
    institution_student_code: str = Field(..., min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_.-]+$")
    full_name: str = Field(..., min_length=2, max_length=200)
    date_of_birth: date | None = None
    gender_category: str | None = Field(default=None, max_length=32)
    department: str | None = Field(default=None, max_length=120)

    @field_validator("date_of_birth")
    @classmethod
    def validate_dob(cls, value: date | None) -> date | None:
        if value is not None and value > date.today():
            raise ValueError("date_of_birth cannot be in the future")
        return value


class StudentUpdateRequest(BaseModel):
    full_name: str | None = Field(default=None, min_length=2, max_length=200)
    date_of_birth: date | None = None
    gender_category: str | None = Field(default=None, max_length=32)
    department: str | None = Field(default=None, max_length=120)
    is_active: bool | None = None

    @field_validator("date_of_birth")
    @classmethod
    def validate_dob(cls, value: date | None) -> date | None:
        if value is not None and value > date.today():
            raise ValueError("date_of_birth cannot be in the future")
        return value


class StudentResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    institution_id: UUID
    institution_student_code: str
    full_name: str
    date_of_birth: date | None
    gender_category: str | None
    department: str | None
    managed_by_user_id: UUID
    is_active: bool
    created_at: datetime
    updated_at: datetime


class AuditLogResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    institution_id: UUID
    actor_user_id: UUID | None
    action: str
    resource_type: str
    resource_id: str
    details_json: dict[str, Any]
    created_at: datetime


class CalibrationCreateRequest(BaseModel):
    device_model: str = Field(..., min_length=2, max_length=120)
    plane_type: CalibrationPlaneType
    method: str = Field(..., min_length=3, max_length=64)
    matrix_json: dict[str, Any]
    reprojection_error_px: float = Field(..., ge=0.0, le=100.0)
    reference_target_spec_json: dict[str, Any]
    calibrated_at: datetime

    @field_validator("reprojection_error_px")
    @classmethod
    def validate_finite_reproj(cls, value: float) -> float:
        if not math.isfinite(value) or value < 0.0:
            raise ValueError("reprojection_error_px must be finite and >= 0")
        return value

    @model_validator(mode="after")
    def validate_matrix_shape(self) -> "CalibrationCreateRequest":
        if self.plane_type in {CalibrationPlaneType.VERTICAL_PLANE, CalibrationPlaneType.GROUND_PLANE}:
            homography = self.matrix_json.get("homography_3x3")
            if (
                not isinstance(homography, list)
                or len(homography) != 9
                or not all(isinstance(v, (int, float)) and math.isfinite(float(v)) for v in homography)
            ):
                raise ValueError("Planar calibration requires matrix_json.homography_3x3 with 9 finite numbers")
        elif self.plane_type == CalibrationPlaneType.INTRINSICS:
            k_mat = self.matrix_json.get("camera_matrix_3x3")
            dist = self.matrix_json.get("dist_coeffs")
            if (
                not isinstance(k_mat, list)
                or len(k_mat) != 9
                or not all(isinstance(v, (int, float)) and math.isfinite(float(v)) for v in k_mat)
            ):
                raise ValueError("Intrinsics calibration requires matrix_json.camera_matrix_3x3 with 9 finite numbers")
            if (
                not isinstance(dist, list)
                or len(dist) < 4
                or not all(isinstance(v, (int, float)) and math.isfinite(float(v)) for v in dist)
            ):
                raise ValueError("Intrinsics calibration requires matrix_json.dist_coeffs with >= 4 finite numbers")
        return self


class CalibrationProfileResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    institution_id: UUID
    device_model: str
    plane_type: CalibrationPlaneType
    method: str
    matrix_json: dict[str, Any]
    reprojection_error_px: float
    reference_target_spec_json: dict[str, Any]
    calibrated_at: datetime
    is_active: bool


class ScoringBandSpec(BaseModel):
    label: str = Field(..., min_length=1, max_length=64)
    min_value: float | None = None
    max_value: float | None = None
    points: float | None = None
    qualified: bool = False


class ScoringStandardCreateRequest(BaseModel):
    name: str = Field(..., min_length=2, max_length=200)
    test_type: TestType
    age_min: int = Field(..., ge=0, le=120)
    age_max: int = Field(..., ge=0, le=120)
    gender_category: str | None = Field(default=None, max_length=32)
    discipline: str | None = Field(default=None, max_length=120)
    metric_unit: str = Field(..., min_length=1, max_length=32)
    direction: str = Field(default="higher_is_better", pattern=r"^(higher_is_better|lower_is_better)$")
    bands: list[ScoringBandSpec] = Field(..., min_length=1)
    source_citation: str = Field(..., min_length=3, max_length=300)

    @model_validator(mode="after")
    def validate_ages(self) -> "ScoringStandardCreateRequest":
        if self.age_max < self.age_min:
            raise ValueError("age_max must be >= age_min")
        return self


class ScoringStandardResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    institution_id: UUID
    name: str
    test_type: TestType
    age_min: int
    age_max: int
    gender_category: str | None
    discipline: str | None
    metric_unit: str
    bands_json: dict[str, Any]
    source_citation: str
    is_active: bool
    created_at: datetime


class ExaminationCreateRequest(BaseModel):
    title: str = Field(..., min_length=2, max_length=200)
    test_types: list[TestType] = Field(..., min_length=1)
    status: str = Field(default="ACTIVE", pattern=r"^(DRAFT|ACTIVE|COMPLETED|ARCHIVED)$")


class ExaminationResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    institution_id: UUID
    title: str
    test_types_json: list[str]
    status: str
    created_by_user_id: UUID
    created_at: datetime


class AttemptSyncItem(BaseModel):
    client_uuid: UUID
    student_id: UUID
    calibration_profile_id: UUID
    test_type: TestType
    value: float
    unit: str = Field(..., min_length=1, max_length=32)
    uncertainty: float = Field(..., ge=0.0)
    confidence_score: float = Field(..., ge=0.0, le=1.0)
    fps_actual: float = Field(..., gt=0.0)
    frame_dt_ms: float = Field(..., gt=0.0)
    dropped_frames: int = Field(default=0, ge=0)
    landmark_provenance: str = Field(..., min_length=2, max_length=64)
    algorithm_version: str = Field(..., min_length=2, max_length=64)
    validity_status: AttemptValidityStatus
    raw_metrics_json: dict[str, Any] = Field(default_factory=dict)
    recorded_at: datetime

    @field_validator("value", "uncertainty", "confidence_score", "fps_actual", "frame_dt_ms")
    @classmethod
    def check_finite(cls, v: float) -> float:
        if not math.isfinite(v):
            raise ValueError("Numeric measurement fields must be finite")
        return v


class AttemptReviewRequest(BaseModel):
    approval_status: ApprovalStatus
    note: str | None = Field(default=None, max_length=500)


class AttemptResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    client_uuid: UUID
    institution_id: UUID
    examination_id: UUID
    student_id: UUID
    calibration_profile_id: UUID
    test_type: TestType
    value: float
    unit: str
    uncertainty: float
    confidence_score: float
    fps_actual: float
    frame_dt_ms: float
    dropped_frames: int
    landmark_provenance: str
    algorithm_version: str
    validity_status: AttemptValidityStatus
    approval_status: ApprovalStatus
    is_best_attempt: bool
    raw_metrics_json: dict[str, Any]
    recorded_at: datetime
    approved_by_user_id: UUID | None
    approved_at: datetime | None


class AttemptSyncResponse(BaseModel):
    attempt: AttemptResponse
    idempotent_replay: bool


class ScoringEvaluationResponse(BaseModel):
    status: str
    standard_id: str | None
    standard_name: str | None
    source_citation: str | None
    category_label: str | None
    score_points: float | None
    qualified: bool | None


class StudentTestTrend(BaseModel):
    test_type: TestType
    latest_value: float
    previous_value: float | None
    delta_from_previous: float | None
    best_value: float
    unit: str
    attempt_count: int
    scoring: ScoringEvaluationResponse


class StudentHistoryResponse(BaseModel):
    student: StudentResponse
    trends: list[StudentTestTrend]
    attempts: list[AttemptResponse]


class ExamTestSummary(BaseModel):
    test_type: TestType
    count: int
    average: float
    best: float
    unit: str


class ExamRankingItem(BaseModel):
    test_type: TestType
    rank: int
    student_id: UUID
    institution_student_code: str
    full_name: str
    department: str | None
    value: float
    unit: str
    category_label: str | None


class ExaminationSummaryResponse(BaseModel):
    examination: ExaminationResponse
    total_students_tested: int
    approved_attempts_count: int
    pending_attempts_count: int
    test_summaries: list[ExamTestSummary]
    rankings: list[ExamRankingItem]


class WebSessionResponse(BaseModel):
    user: UserResponse
    institution: InstitutionResponse
    csrf_token: str
    expires_at: datetime


class UserUpdateRequest(BaseModel):
    full_name: str | None = Field(default=None, min_length=2, max_length=200)
    role: UserRole | None = None
    is_active: bool | None = None


class ExaminationUpdateRequest(BaseModel):
    title: str | None = Field(default=None, min_length=2, max_length=200)
    test_types: list[TestType] | None = Field(default=None, min_length=1)
    status: str | None = Field(default=None, pattern=r"^(DRAFT|ACTIVE|COMPLETED|ARCHIVED)$")


class CharucoSolveResponse(BaseModel):
    plane_type: CalibrationPlaneType
    detected_markers_count: int
    detected_charuco_corners: int
    homography_3x3: list[float] | None
    reprojection_error_px: float | None
    spatial_coverage_ratio: float
    quadrants_covered: int
    image_width: int
    image_height: int
    reference_target_spec_json: dict[str, Any]
    used_intrinsics: bool
    quality_gate_passed: bool
    provisional_thresholds: dict[str, Any]
    warnings: list[str]
    rejection_reason: str | None


class IntrinsicsSolveResponse(BaseModel):
    camera_matrix_3x3: list[float] | None
    dist_coeffs: list[float] | None
    reprojection_error_px: float | None
    views_submitted: int
    views_accepted: int
    views_rejected: int
    image_width: int | None
    image_height: int | None
    quality_gate_passed: bool
    provisional_thresholds: dict[str, Any]
    warnings: list[str]
    rejection_reason: str | None


class VideoFramesFallbackResponse(BaseModel):
    frame_count: int
    fps_estimated: float | None
    duration_seconds: float | None
    width: int | None
    height: int | None
    frames_base64: list[str]
    timestamps_us: list[int]
