"""Examination management, idempotent attempt sync, best-attempt selection, and examiner review routes."""

from datetime import datetime, timezone
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session

from app.api.deps import require_roles
from app.db.audit import record_audit_event
from app.db.models import (
    ApprovalStatus,
    Attempt,
    AttemptValidityStatus,
    CalibrationPlaneType,
    CalibrationProfile,
    Examination,
    Student,
    TestType,
    User,
    UserRole,
)
from app.db.session import get_db
from app.schemas import (
    AttemptResponse,
    AttemptReviewRequest,
    AttemptSyncItem,
    AttemptSyncResponse,
    ExaminationCreateRequest,
    ExaminationResponse,
    ExaminationUpdateRequest,
)

router = APIRouter(prefix="/api/v1/examinations", tags=["examinations"])


def _expected_plane_for_test(test_type: TestType) -> CalibrationPlaneType:
    if test_type in {TestType.HEIGHT, TestType.VERTICAL_JUMP}:
        return CalibrationPlaneType.VERTICAL_PLANE
    return CalibrationPlaneType.GROUND_PLANE


def _recompute_best_attempt(
    db: Session,
    *,
    institution_id: UUID,
    examination_id: UUID,
    student_id: UUID,
    test_type: TestType,
) -> None:
    """Recompute is_best_attempt atomically within an active transaction."""
    attempts = (
        db.query(Attempt)
        .filter(
            Attempt.institution_id == institution_id,
            Attempt.examination_id == examination_id,
            Attempt.student_id == student_id,
            Attempt.test_type == test_type,
        )
        .all()
    )

    for att in attempts:
        att.is_best_attempt = False

    eligible = [
        att
        for att in attempts
        if att.approval_status != ApprovalStatus.REJECTED
        and (
            att.validity_status == AttemptValidityStatus.VALID
            or (
                att.validity_status == AttemptValidityStatus.FLAGGED
                and att.approval_status == ApprovalStatus.APPROVED
            )
        )
    ]
    if not eligible:
        return

    # Prefer APPROVED attempts over PENDING if any approved eligible attempt exists.
    approved_eligible = [att for att in eligible if att.approval_status == ApprovalStatus.APPROVED]
    pool = approved_eligible if approved_eligible else eligible

    best = (
        min(pool, key=lambda a: a.value)
        if test_type == TestType.SPRINT
        else max(pool, key=lambda a: a.value)
    )
    best.is_best_attempt = True


@router.post("", response_model=ExaminationResponse, status_code=status.HTTP_201_CREATED)
def create_examination(
    body: ExaminationCreateRequest,
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR)),
    db: Session = Depends(get_db),
) -> ExaminationResponse:
    """Create a new institutional examination."""
    exam = Examination(
        institution_id=current_user.institution_id,
        title=body.title.strip(),
        test_types_json=[t.value for t in body.test_types],
        status=body.status,
        created_by_user_id=current_user.id,
    )
    db.add(exam)
    db.flush()

    record_audit_event(
        db,
        institution_id=current_user.institution_id,
        actor_user_id=current_user.id,
        action="EXAMINATION_CREATED",
        resource_type="examination",
        resource_id=str(exam.id),
        details={"title": exam.title, "test_types": exam.test_types_json},
    )
    db.commit()
    db.refresh(exam)
    return ExaminationResponse.model_validate(exam)


@router.get("", response_model=list[ExaminationResponse])
def list_examinations(
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR, UserRole.COACH)),
    db: Session = Depends(get_db),
) -> list[ExaminationResponse]:
    """List examinations strictly inside the caller's institution."""
    rows = (
        db.query(Examination)
        .filter(Examination.institution_id == current_user.institution_id)
        .order_by(Examination.created_at.desc())
        .all()
    )
    return [ExaminationResponse.model_validate(row) for row in rows]


@router.get("/{exam_id}", response_model=ExaminationResponse)
def get_examination(
    exam_id: UUID,
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR, UserRole.COACH)),
    db: Session = Depends(get_db),
) -> ExaminationResponse:
    """Fetch a single examination scoped strictly to the caller's institution."""
    exam = (
        db.query(Examination)
        .filter(
            Examination.id == exam_id,
            Examination.institution_id == current_user.institution_id,
        )
        .first()
    )
    if exam is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Examination not found")
    return ExaminationResponse.model_validate(exam)


@router.patch("/{exam_id}", response_model=ExaminationResponse)
def update_examination(
    exam_id: UUID,
    body: ExaminationUpdateRequest,
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR)),
    db: Session = Depends(get_db),
) -> ExaminationResponse:
    """Update an examination title, assigned tests, or lifecycle status."""
    exam = (
        db.query(Examination)
        .filter(
            Examination.id == exam_id,
            Examination.institution_id == current_user.institution_id,
        )
        .first()
    )
    if exam is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Examination not found")

    changed_fields: list[str] = []
    if body.title is not None:
        exam.title = body.title.strip()
        changed_fields.append("title")
    if body.test_types is not None:
        exam.test_types_json = [t.value for t in body.test_types]
        changed_fields.append("test_types")
    if body.status is not None and body.status != exam.status:
        exam.status = body.status
        changed_fields.append("status")

    if changed_fields:
        record_audit_event(
            db,
            institution_id=current_user.institution_id,
            actor_user_id=current_user.id,
            action="EXAMINATION_UPDATED",
            resource_type="examination",
            resource_id=str(exam.id),
            details={"changed_fields": changed_fields, "status": exam.status},
        )
        db.commit()
        db.refresh(exam)

    return ExaminationResponse.model_validate(exam)


@router.post("/{exam_id}/attempts/sync", response_model=AttemptSyncResponse)
def sync_attempt(
    exam_id: UUID,
    body: AttemptSyncItem,
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR)),
    db: Session = Depends(get_db),
) -> AttemptSyncResponse:
    """Idempotently sync an on-device attempt keyed by (institution_id, client_uuid)."""
    exam = (
        db.query(Examination)
        .filter(
            Examination.id == exam_id,
            Examination.institution_id == current_user.institution_id,
        )
        .first()
    )
    if exam is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Examination not found")

    if body.test_type.value not in exam.test_types_json:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Test type {body.test_type.value} is not assigned to this examination",
        )

    # Idempotent replay check inside the caller's institution.
    existing = (
        db.query(Attempt)
        .filter(
            Attempt.institution_id == current_user.institution_id,
            Attempt.client_uuid == body.client_uuid,
        )
        .first()
    )
    if existing is not None:
        return AttemptSyncResponse(
            attempt=AttemptResponse.model_validate(existing),
            idempotent_replay=True,
        )

    student = (
        db.query(Student)
        .filter(
            Student.id == body.student_id,
            Student.institution_id == current_user.institution_id,
        )
        .first()
    )
    if student is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Student not found")

    calibration = (
        db.query(CalibrationProfile)
        .filter(
            CalibrationProfile.id == body.calibration_profile_id,
            CalibrationProfile.institution_id == current_user.institution_id,
        )
        .first()
    )
    if calibration is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Calibration profile not found")

    expected_plane = _expected_plane_for_test(body.test_type)
    if calibration.plane_type != expected_plane:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"{body.test_type.value} requires {expected_plane.value} calibration, got {calibration.plane_type.value}",
        )

    attempt = Attempt(
        client_uuid=body.client_uuid,
        institution_id=current_user.institution_id,
        examination_id=exam.id,
        student_id=student.id,
        calibration_profile_id=calibration.id,
        test_type=body.test_type,
        value=body.value,
        unit=body.unit.strip(),
        uncertainty=body.uncertainty,
        confidence_score=body.confidence_score,
        fps_actual=body.fps_actual,
        frame_dt_ms=body.frame_dt_ms,
        dropped_frames=body.dropped_frames,
        landmark_provenance=body.landmark_provenance.strip(),
        algorithm_version=body.algorithm_version.strip(),
        validity_status=body.validity_status,
        approval_status=ApprovalStatus.PENDING,
        is_best_attempt=False,
        raw_metrics_json=body.raw_metrics_json,
        recorded_at=body.recorded_at,
    )
    db.add(attempt)
    db.flush()

    _recompute_best_attempt(
        db,
        institution_id=current_user.institution_id,
        examination_id=exam.id,
        student_id=student.id,
        test_type=body.test_type,
    )

    record_audit_event(
        db,
        institution_id=current_user.institution_id,
        actor_user_id=current_user.id,
        action="ATTEMPT_SYNCED",
        resource_type="attempt",
        resource_id=str(attempt.id),
        details={
            "client_uuid": str(attempt.client_uuid),
            "test_type": attempt.test_type.value,
            "validity_status": attempt.validity_status.value,
        },
    )
    db.commit()
    db.refresh(attempt)
    return AttemptSyncResponse(
        attempt=AttemptResponse.model_validate(attempt),
        idempotent_replay=False,
    )


@router.get("/{exam_id}/attempts", response_model=list[AttemptResponse])
def list_exam_attempts(
    exam_id: UUID,
    student_id: UUID | None = Query(default=None),
    test_type: TestType | None = Query(default=None),
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR, UserRole.COACH)),
    db: Session = Depends(get_db),
) -> list[AttemptResponse]:
    """List examination attempts scoped strictly to the caller's institution."""
    exam = (
        db.query(Examination)
        .filter(
            Examination.id == exam_id,
            Examination.institution_id == current_user.institution_id,
        )
        .first()
    )
    if exam is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Examination not found")

    query = db.query(Attempt).filter(
        Attempt.institution_id == current_user.institution_id,
        Attempt.examination_id == exam.id,
    )
    if student_id is not None:
        query = query.filter(Attempt.student_id == student_id)
    if test_type is not None:
        query = query.filter(Attempt.test_type == test_type)

    rows = query.order_by(Attempt.recorded_at.desc()).all()
    return [AttemptResponse.model_validate(row) for row in rows]


@router.post("/{exam_id}/attempts/{attempt_id}/review", response_model=AttemptResponse)
def review_attempt(
    exam_id: UUID,
    attempt_id: UUID,
    body: AttemptReviewRequest,
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR)),
    db: Session = Depends(get_db),
) -> AttemptResponse:
    """Approve or reject an attempt and recompute best-attempt selection atomically."""
    attempt = (
        db.query(Attempt)
        .filter(
            Attempt.id == attempt_id,
            Attempt.examination_id == exam_id,
            Attempt.institution_id == current_user.institution_id,
        )
        .first()
    )
    if attempt is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Attempt not found")

    attempt.approval_status = body.approval_status
    attempt.approved_by_user_id = current_user.id
    attempt.approved_at = datetime.now(timezone.utc)

    _recompute_best_attempt(
        db,
        institution_id=current_user.institution_id,
        examination_id=attempt.examination_id,
        student_id=attempt.student_id,
        test_type=attempt.test_type,
    )

    record_audit_event(
        db,
        institution_id=current_user.institution_id,
        actor_user_id=current_user.id,
        action="ATTEMPT_REVIEWED",
        resource_type="attempt",
        resource_id=str(attempt.id),
        details={
            "approval_status": body.approval_status.value,
            "note": body.note,
        },
    )
    db.commit()
    db.refresh(attempt)
    return AttemptResponse.model_validate(attempt)
