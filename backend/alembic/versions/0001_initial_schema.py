"""Initial AthletiQ schema."""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "0001_initial_schema"
down_revision: Union[str, Sequence[str], None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "institutions",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("name", sa.String(length=200), nullable=False),
        sa.Column("code", sa.String(length=64), nullable=False),
        sa.Column("is_active", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_institutions")),
    )
    op.create_index(op.f("ix_institutions_code"), "institutions", ["code"], unique=True)

    op.create_table(
        "users",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("institution_id", sa.Uuid(), nullable=False),
        sa.Column("email", sa.String(length=255), nullable=False),
        sa.Column("full_name", sa.String(length=200), nullable=False),
        sa.Column("role", sa.Enum("ADMIN", "INSTRUCTOR", "COACH", name="userrole", native_enum=False, length=32), nullable=False),
        sa.Column("password_hash", sa.String(length=512), nullable=False),
        sa.Column("is_active", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["institution_id"], ["institutions.id"], name=op.f("fk_users_institution_id_institutions"), ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_users")),
    )
    op.create_index(op.f("ix_users_email"), "users", ["email"], unique=True)
    op.create_index(op.f("ix_users_institution_id"), "users", ["institution_id"], unique=False)

    op.create_table(
        "calibration_profiles",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("institution_id", sa.Uuid(), nullable=False),
        sa.Column("device_model", sa.String(length=120), nullable=False),
        sa.Column("plane_type", sa.Enum("INTRINSICS", "VERTICAL_PLANE", "GROUND_PLANE", name="calibrationplanetype", native_enum=False, length=32), nullable=False),
        sa.Column("method", sa.String(length=64), nullable=False),
        sa.Column("matrix_json", sa.JSON(), nullable=False),
        sa.Column("reprojection_error_px", sa.Float(), nullable=False),
        sa.Column("reference_target_spec_json", sa.JSON(), nullable=False),
        sa.Column("calibrated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("is_active", sa.Boolean(), nullable=False),
        sa.CheckConstraint("reprojection_error_px >= 0.0", name=op.f("ck_calibration_profiles_reproj_non_negative")),
        sa.ForeignKeyConstraint(["institution_id"], ["institutions.id"], name=op.f("fk_calibration_profiles_institution_id_institutions"), ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_calibration_profiles")),
    )
    op.create_index(op.f("ix_calibration_profiles_institution_id"), "calibration_profiles", ["institution_id"], unique=False)

    op.create_table(
        "scoring_standards",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("institution_id", sa.Uuid(), nullable=False),
        sa.Column("name", sa.String(length=200), nullable=False),
        sa.Column("test_type", sa.Enum("HEIGHT", "VERTICAL_JUMP", "STANDING_BROAD_JUMP", "SPRINT", name="testtype", native_enum=False, length=32), nullable=False),
        sa.Column("age_min", sa.Integer(), nullable=False),
        sa.Column("age_max", sa.Integer(), nullable=False),
        sa.Column("gender_category", sa.String(length=32), nullable=True),
        sa.Column("discipline", sa.String(length=120), nullable=True),
        sa.Column("metric_unit", sa.String(length=32), nullable=False),
        sa.Column("bands_json", sa.JSON(), nullable=False),
        sa.Column("source_citation", sa.String(length=300), nullable=False),
        sa.Column("is_active", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("age_min >= 0 AND age_max >= age_min", name=op.f("ck_scoring_standards_valid_age_range")),
        sa.ForeignKeyConstraint(["institution_id"], ["institutions.id"], name=op.f("fk_scoring_standards_institution_id_institutions"), ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_scoring_standards")),
    )
    op.create_index(op.f("ix_scoring_standards_institution_id"), "scoring_standards", ["institution_id"], unique=False)

    op.create_table(
        "students",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("institution_id", sa.Uuid(), nullable=False),
        sa.Column("institution_student_code", sa.String(length=64), nullable=False),
        sa.Column("full_name", sa.String(length=200), nullable=False),
        sa.Column("date_of_birth", sa.Date(), nullable=True),
        sa.Column("gender_category", sa.String(length=32), nullable=True),
        sa.Column("department", sa.String(length=120), nullable=True),
        sa.Column("managed_by_user_id", sa.Uuid(), nullable=False),
        sa.Column("is_active", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["institution_id"], ["institutions.id"], name=op.f("fk_students_institution_id_institutions"), ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["managed_by_user_id"], ["users.id"], name=op.f("fk_students_managed_by_user_id_users"), ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_students")),
        sa.UniqueConstraint("institution_id", "institution_student_code", name="uq_student_inst_code"),
    )
    op.create_index(op.f("ix_students_institution_id"), "students", ["institution_id"], unique=False)

    op.create_table(
        "examinations",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("institution_id", sa.Uuid(), nullable=False),
        sa.Column("title", sa.String(length=200), nullable=False),
        sa.Column("test_types_json", sa.JSON(), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("created_by_user_id", sa.Uuid(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["created_by_user_id"], ["users.id"], name=op.f("fk_examinations_created_by_user_id_users"), ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["institution_id"], ["institutions.id"], name=op.f("fk_examinations_institution_id_institutions"), ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_examinations")),
    )
    op.create_index(op.f("ix_examinations_institution_id"), "examinations", ["institution_id"], unique=False)

    op.create_table(
        "attempts",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("client_uuid", sa.Uuid(), nullable=False),
        sa.Column("institution_id", sa.Uuid(), nullable=False),
        sa.Column("examination_id", sa.Uuid(), nullable=False),
        sa.Column("student_id", sa.Uuid(), nullable=False),
        sa.Column("calibration_profile_id", sa.Uuid(), nullable=False),
        sa.Column("test_type", sa.Enum("HEIGHT", "VERTICAL_JUMP", "STANDING_BROAD_JUMP", "SPRINT", name="testtype", native_enum=False, length=32), nullable=False),
        sa.Column("value", sa.Float(), nullable=False),
        sa.Column("unit", sa.String(length=32), nullable=False),
        sa.Column("uncertainty", sa.Float(), nullable=False),
        sa.Column("confidence_score", sa.Float(), nullable=False),
        sa.Column("fps_actual", sa.Float(), nullable=False),
        sa.Column("frame_dt_ms", sa.Float(), nullable=False),
        sa.Column("dropped_frames", sa.Integer(), nullable=False),
        sa.Column("landmark_provenance", sa.String(length=64), nullable=False),
        sa.Column("algorithm_version", sa.String(length=64), nullable=False),
        sa.Column("validity_status", sa.Enum("VALID", "FLAGGED", "INVALID", name="attemptvaliditystatus", native_enum=False, length=32), nullable=False),
        sa.Column("approval_status", sa.Enum("PENDING", "APPROVED", "REJECTED", name="approvalstatus", native_enum=False, length=32), nullable=False),
        sa.Column("is_best_attempt", sa.Boolean(), nullable=False),
        sa.Column("raw_metrics_json", sa.JSON(), nullable=False),
        sa.Column("recorded_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("approved_by_user_id", sa.Uuid(), nullable=True),
        sa.Column("approved_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("confidence_score >= 0.0 AND confidence_score <= 1.0", name=op.f("ck_attempts_confidence_range")),
        sa.CheckConstraint("dropped_frames >= 0", name=op.f("ck_attempts_dropped_frames_non_negative")),
        sa.CheckConstraint("fps_actual > 0.0", name=op.f("ck_attempts_fps_positive")),
        sa.CheckConstraint("uncertainty >= 0.0", name=op.f("ck_attempts_uncertainty_non_negative")),
        sa.ForeignKeyConstraint(["approved_by_user_id"], ["users.id"], name=op.f("fk_attempts_approved_by_user_id_users"), ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["calibration_profile_id"], ["calibration_profiles.id"], name=op.f("fk_attempts_calibration_profile_id_calibration_profiles"), ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["examination_id"], ["examinations.id"], name=op.f("fk_attempts_examination_id_examinations"), ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["institution_id"], ["institutions.id"], name=op.f("fk_attempts_institution_id_institutions"), ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["student_id"], ["students.id"], name=op.f("fk_attempts_student_id_students"), ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_attempts")),
        sa.UniqueConstraint("institution_id", "client_uuid", name="uq_attempt_inst_client_uuid"),
    )
    op.create_index(op.f("ix_attempts_examination_id"), "attempts", ["examination_id"], unique=False)
    op.create_index(op.f("ix_attempts_institution_id"), "attempts", ["institution_id"], unique=False)
    op.create_index(op.f("ix_attempts_student_id"), "attempts", ["student_id"], unique=False)

    op.create_table(
        "audit_logs",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("institution_id", sa.Uuid(), nullable=False),
        sa.Column("actor_user_id", sa.Uuid(), nullable=True),
        sa.Column("action", sa.String(length=120), nullable=False),
        sa.Column("resource_type", sa.String(length=64), nullable=False),
        sa.Column("resource_id", sa.String(length=128), nullable=False),
        sa.Column("details_json", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["actor_user_id"], ["users.id"], name=op.f("fk_audit_logs_actor_user_id_users"), ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["institution_id"], ["institutions.id"], name=op.f("fk_audit_logs_institution_id_institutions"), ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_audit_logs")),
    )
    op.create_index(op.f("ix_audit_logs_action"), "audit_logs", ["action"], unique=False)
    op.create_index(op.f("ix_audit_logs_institution_id"), "audit_logs", ["institution_id"], unique=False)


def downgrade() -> None:
    op.drop_index(op.f("ix_audit_logs_institution_id"), table_name="audit_logs")
    op.drop_index(op.f("ix_audit_logs_action"), table_name="audit_logs")
    op.drop_table("audit_logs")
    op.drop_index(op.f("ix_attempts_student_id"), table_name="attempts")
    op.drop_index(op.f("ix_attempts_institution_id"), table_name="attempts")
    op.drop_index(op.f("ix_attempts_examination_id"), table_name="attempts")
    op.drop_table("attempts")
    op.drop_index(op.f("ix_examinations_institution_id"), table_name="examinations")
    op.drop_table("examinations")
    op.drop_index(op.f("ix_students_institution_id"), table_name="students")
    op.drop_table("students")
    op.drop_index(op.f("ix_scoring_standards_institution_id"), table_name="scoring_standards")
    op.drop_table("scoring_standards")
    op.drop_index(op.f("ix_calibration_profiles_institution_id"), table_name="calibration_profiles")
    op.drop_table("calibration_profiles")
    op.drop_index(op.f("ix_users_institution_id"), table_name="users")
    op.drop_index(op.f("ix_users_email"), table_name="users")
    op.drop_table("users")
    op.drop_index(op.f("ix_institutions_code"), table_name="institutions")
    op.drop_table("institutions")
