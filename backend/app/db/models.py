"""SQLAlchemy 2.0 ORM models for the AthletiQ multi-tenant schema."""

import enum
import uuid
from datetime import date, datetime, timezone
from typing import Any

from sqlalchemy import (
    JSON,
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    Enum,
    Float,
    ForeignKey,
    Integer,
    String,
    UniqueConstraint,
    Uuid,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base


def utc_now() -> datetime:
    """Return timezone-aware UTC timestamp."""
    return datetime.now(timezone.utc)


class UserRole(str, enum.Enum):
    ADMIN = "ADMIN"
    INSTRUCTOR = "INSTRUCTOR"
    COACH = "COACH"


class TestType(str, enum.Enum):
    HEIGHT = "HEIGHT"
    VERTICAL_JUMP = "VERTICAL_JUMP"
    STANDING_BROAD_JUMP = "STANDING_BROAD_JUMP"
    SPRINT = "SPRINT"


class CalibrationPlaneType(str, enum.Enum):
    INTRINSICS = "INTRINSICS"
    VERTICAL_PLANE = "VERTICAL_PLANE"
    GROUND_PLANE = "GROUND_PLANE"


class AttemptValidityStatus(str, enum.Enum):
    VALID = "VALID"
    FLAGGED = "FLAGGED"
    INVALID = "INVALID"


class ApprovalStatus(str, enum.Enum):
    PENDING = "PENDING"
    APPROVED = "APPROVED"
    REJECTED = "REJECTED"


class Institution(Base):
    __tablename__ = "institutions"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    code: Mapped[str] = mapped_column(String(64), nullable=False, unique=True, index=True)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utc_now)


class User(Base):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    institution_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("institutions.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    email: Mapped[str] = mapped_column(String(255), nullable=False, unique=True, index=True)
    full_name: Mapped[str] = mapped_column(String(200), nullable=False)
    role: Mapped[UserRole] = mapped_column(Enum(UserRole, native_enum=False, length=32), nullable=False)
    password_hash: Mapped[str] = mapped_column(String(512), nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utc_now)

    institution: Mapped[Institution] = relationship()


class Student(Base):
    __tablename__ = "students"
    __table_args__ = (
        UniqueConstraint("institution_id", "institution_student_code", name="uq_student_inst_code"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    institution_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("institutions.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    institution_student_code: Mapped[str] = mapped_column(String(64), nullable=False)
    full_name: Mapped[str] = mapped_column(String(200), nullable=False)
    date_of_birth: Mapped[date | None] = mapped_column(Date, nullable=True)
    gender_category: Mapped[str | None] = mapped_column(String(32), nullable=True)
    department: Mapped[str | None] = mapped_column(String(120), nullable=True)
    managed_by_user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=utc_now, onupdate=utc_now
    )


class CalibrationProfile(Base):
    __tablename__ = "calibration_profiles"
    __table_args__ = (
        CheckConstraint("reprojection_error_px >= 0.0", name="reproj_non_negative"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    institution_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("institutions.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    device_model: Mapped[str] = mapped_column(String(120), nullable=False)
    plane_type: Mapped[CalibrationPlaneType] = mapped_column(
        Enum(CalibrationPlaneType, native_enum=False, length=32), nullable=False
    )
    method: Mapped[str] = mapped_column(String(64), nullable=False)
    matrix_json: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    reprojection_error_px: Mapped[float] = mapped_column(Float, nullable=False)
    reference_target_spec_json: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    calibrated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)


class ScoringStandard(Base):
    __tablename__ = "scoring_standards"
    __table_args__ = (
        CheckConstraint("age_min >= 0 AND age_max >= age_min", name="valid_age_range"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    institution_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("institutions.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    test_type: Mapped[TestType] = mapped_column(Enum(TestType, native_enum=False, length=32), nullable=False)
    age_min: Mapped[int] = mapped_column(Integer, nullable=False)
    age_max: Mapped[int] = mapped_column(Integer, nullable=False)
    gender_category: Mapped[str | None] = mapped_column(String(32), nullable=True)
    discipline: Mapped[str | None] = mapped_column(String(120), nullable=True)
    metric_unit: Mapped[str] = mapped_column(String(32), nullable=False)
    bands_json: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    source_citation: Mapped[str] = mapped_column(String(300), nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utc_now)


class Examination(Base):
    __tablename__ = "examinations"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    institution_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("institutions.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    test_types_json: Mapped[list[str]] = mapped_column(JSON, nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False, default="DRAFT")
    created_by_user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utc_now)


class Attempt(Base):
    __tablename__ = "attempts"
    __table_args__ = (
        UniqueConstraint("institution_id", "client_uuid", name="uq_attempt_inst_client_uuid"),
        CheckConstraint("confidence_score >= 0.0 AND confidence_score <= 1.0", name="confidence_range"),
        CheckConstraint("uncertainty >= 0.0", name="uncertainty_non_negative"),
        CheckConstraint("fps_actual > 0.0", name="fps_positive"),
        CheckConstraint("dropped_frames >= 0", name="dropped_frames_non_negative"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    client_uuid: Mapped[uuid.UUID] = mapped_column(Uuid, nullable=False)
    institution_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("institutions.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    examination_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("examinations.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    student_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("students.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    calibration_profile_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("calibration_profiles.id", ondelete="RESTRICT"), nullable=False
    )
    test_type: Mapped[TestType] = mapped_column(Enum(TestType, native_enum=False, length=32), nullable=False)
    value: Mapped[float] = mapped_column(Float, nullable=False)
    unit: Mapped[str] = mapped_column(String(32), nullable=False)
    uncertainty: Mapped[float] = mapped_column(Float, nullable=False)
    confidence_score: Mapped[float] = mapped_column(Float, nullable=False)
    fps_actual: Mapped[float] = mapped_column(Float, nullable=False)
    frame_dt_ms: Mapped[float] = mapped_column(Float, nullable=False)
    dropped_frames: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    landmark_provenance: Mapped[str] = mapped_column(String(64), nullable=False)
    algorithm_version: Mapped[str] = mapped_column(String(64), nullable=False)
    validity_status: Mapped[AttemptValidityStatus] = mapped_column(
        Enum(AttemptValidityStatus, native_enum=False, length=32), nullable=False
    )
    approval_status: Mapped[ApprovalStatus] = mapped_column(
        Enum(ApprovalStatus, native_enum=False, length=32), nullable=False, default=ApprovalStatus.PENDING
    )
    is_best_attempt: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    raw_metrics_json: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    approved_by_user_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("users.id", ondelete="RESTRICT"), nullable=True
    )
    approved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class AuditLog(Base):
    __tablename__ = "audit_logs"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    institution_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("institutions.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    actor_user_id: Mapped[uuid.UUID | None] = mapped_column(
        Uuid, ForeignKey("users.id", ondelete="RESTRICT"), nullable=True
    )
    action: Mapped[str] = mapped_column(String(120), nullable=False, index=True)
    resource_type: Mapped[str] = mapped_column(String(64), nullable=False)
    resource_id: Mapped[str] = mapped_column(String(128), nullable=False)
    details_json: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, default=utc_now)
