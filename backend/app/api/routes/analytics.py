"""Student performance history, trend deltas, and institutional examination analytics."""

from collections import defaultdict
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.api.deps import require_roles
from app.db.models import (
    ApprovalStatus,
    Attempt,
    AttemptValidityStatus,
    Examination,
    ScoringStandard,
    Student,
    TestType,
    User,
    UserRole,
)
from app.db.session import get_db
from app.schemas import (
    AttemptResponse,
    ExamRankingItem,
    ExamTestSummary,
    ExaminationResponse,
    ExaminationSummaryResponse,
    ScoringEvaluationResponse,
    StudentHistoryResponse,
    StudentResponse,
    StudentTestTrend,
)
from app.services.scoring import compute_age_years, evaluate_against_standard, find_matching_standard

router = APIRouter(prefix="/api/v1/analytics", tags=["analytics"])


@router.get("/students/{student_id}/history", response_model=StudentHistoryResponse)
def get_student_history(
    student_id: UUID,
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR, UserRole.COACH)),
    db: Session = Depends(get_db),
) -> StudentHistoryResponse:
    """Return longitudinal performance history and verified configurable scoring for a student."""
    student = (
        db.query(Student)
        .filter(
            Student.id == student_id,
            Student.institution_id == current_user.institution_id,
        )
        .first()
    )
    if student is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Student not found")

    attempts = (
        db.query(Attempt)
        .filter(
            Attempt.institution_id == current_user.institution_id,
            Attempt.student_id == student.id,
        )
        .order_by(Attempt.recorded_at.asc())
        .all()
    )
    standards = (
        db.query(ScoringStandard)
        .filter(
            ScoringStandard.institution_id == current_user.institution_id,
            ScoringStandard.is_active.is_(True),
        )
        .all()
    )

    age_years = compute_age_years(student.date_of_birth)
    grouped: dict[TestType, list[Attempt]] = defaultdict(list)
    for att in attempts:
        if att.approval_status != ApprovalStatus.REJECTED and att.validity_status != AttemptValidityStatus.INVALID:
            grouped[att.test_type].append(att)

    trends: list[StudentTestTrend] = []
    for test_type, group in grouped.items():
        latest = group[-1]
        previous = group[-2] if len(group) >= 2 else None
        best_val = min(a.value for a in group) if test_type == TestType.SPRINT else max(a.value for a in group)
        delta = (latest.value - previous.value) if previous is not None else None

        matched_std = find_matching_standard(
            standards,
            test_type=test_type,
            age_years=age_years,
            gender_category=student.gender_category,
        )
        eval_result = evaluate_against_standard(best_val, matched_std)
        trends.append(
            StudentTestTrend(
                test_type=test_type,
                latest_value=latest.value,
                previous_value=previous.value if previous else None,
                delta_from_previous=delta,
                best_value=best_val,
                unit=latest.unit,
                attempt_count=len(group),
                scoring=ScoringEvaluationResponse(
                    status=eval_result.status,
                    standard_id=eval_result.standard_id,
                    standard_name=eval_result.standard_name,
                    source_citation=eval_result.source_citation,
                    category_label=eval_result.category_label,
                    score_points=eval_result.score_points,
                    qualified=eval_result.qualified,
                ),
            )
        )

    return StudentHistoryResponse(
        student=StudentResponse.model_validate(student),
        trends=trends,
        attempts=[AttemptResponse.model_validate(a) for a in reversed(attempts)],
    )


@router.get("/examinations/{exam_id}/summary", response_model=ExaminationSummaryResponse)
def get_examination_summary(
    exam_id: UUID,
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR, UserRole.COACH)),
    db: Session = Depends(get_db),
) -> ExaminationSummaryResponse:
    """Compute institutional summary statistics and rankings strictly from approved best attempts."""
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

    all_attempts = (
        db.query(Attempt)
        .filter(
            Attempt.institution_id == current_user.institution_id,
            Attempt.examination_id == exam.id,
        )
        .all()
    )
    approved_best = [
        att
        for att in all_attempts
        if att.approval_status == ApprovalStatus.APPROVED
        and att.is_best_attempt
        and att.validity_status != AttemptValidityStatus.INVALID
    ]
    pending_count = sum(1 for att in all_attempts if att.approval_status == ApprovalStatus.PENDING)
    tested_student_ids = {att.student_id for att in approved_best}

    students_by_id = {
        s.id: s
        for s in db.query(Student)
        .filter(
            Student.institution_id == current_user.institution_id,
            Student.id.in_(tested_student_ids) if tested_student_ids else False,
        )
        .all()
    } if tested_student_ids else {}

    standards = (
        db.query(ScoringStandard)
        .filter(
            ScoringStandard.institution_id == current_user.institution_id,
            ScoringStandard.is_active.is_(True),
        )
        .all()
    )

    by_test: dict[TestType, list[Attempt]] = defaultdict(list)
    for att in approved_best:
        by_test[att.test_type].append(att)

    test_summaries: list[ExamTestSummary] = []
    rankings: list[ExamRankingItem] = []

    for test_type, group in by_test.items():
        values = [a.value for a in group]
        best_val = min(values) if test_type == TestType.SPRINT else max(values)
        test_summaries.append(
            ExamTestSummary(
                test_type=test_type,
                count=len(group),
                average=sum(values) / len(values),
                best=best_val,
                unit=group[0].unit,
            )
        )

        ordered = sorted(group, key=lambda a: a.value, reverse=(test_type != TestType.SPRINT))
        for idx, att in enumerate(ordered, start=1):
            student = students_by_id.get(att.student_id)
            if student is None:
                continue
            age_years = compute_age_years(student.date_of_birth)
            matched_std = find_matching_standard(
                standards,
                test_type=test_type,
                age_years=age_years,
                gender_category=student.gender_category,
            )
            eval_res = evaluate_against_standard(att.value, matched_std)
            rankings.append(
                ExamRankingItem(
                    test_type=test_type,
                    rank=idx,
                    student_id=student.id,
                    institution_student_code=student.institution_student_code,
                    full_name=student.full_name,
                    department=student.department,
                    value=att.value,
                    unit=att.unit,
                    category_label=eval_res.category_label,
                )
            )

    return ExaminationSummaryResponse(
        examination=ExaminationResponse.model_validate(exam),
        total_students_tested=len(tested_student_ids),
        approved_attempts_count=sum(1 for a in all_attempts if a.approval_status == ApprovalStatus.APPROVED),
        pending_attempts_count=pending_count,
        test_summaries=test_summaries,
        rankings=rankings,
    )
