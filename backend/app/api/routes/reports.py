"""PDF report export routes for individual students and institutional examinations."""

from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy.orm import Session

from app.api.deps import require_roles
from app.api.routes.analytics import get_examination_summary
from app.db.models import Attempt, Examination, Institution, Student, User, UserRole
from app.db.session import get_db
from app.services.reports import render_examination_report_pdf, render_student_report_pdf

router = APIRouter(prefix="/api/v1/reports", tags=["reports"])


@router.get("/students/{student_id}/pdf")
def export_student_pdf(
    student_id: UUID,
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR, UserRole.COACH)),
    db: Session = Depends(get_db),
) -> Response:
    """Export an individual student performance PDF scoped to the caller's institution."""
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

    institution = db.query(Institution).filter(Institution.id == current_user.institution_id).first()
    attempts = (
        db.query(Attempt)
        .filter(
            Attempt.institution_id == current_user.institution_id,
            Attempt.student_id == student.id,
        )
        .order_by(Attempt.recorded_at.desc())
        .all()
    )

    pdf_bytes = render_student_report_pdf(
        institution_name=institution.name if institution else "Institution",
        student_code=student.institution_student_code,
        student_name=student.full_name,
        department=student.department,
        attempts_summary=[
            {
                "test_type": a.test_type.value,
                "value": a.value,
                "unit": a.unit,
                "uncertainty": a.uncertainty,
                "confidence_score": a.confidence_score,
                "validity_status": a.validity_status.value,
                "approval_status": a.approval_status.value,
                "recorded_at": a.recorded_at.isoformat(),
            }
            for a in attempts
        ],
    )
    return Response(
        content=pdf_bytes,
        media_type="application/pdf",
        headers={
            "Content-Disposition": f'attachment; filename="student_{student.institution_student_code}_report.pdf"'
        },
    )


@router.get("/examinations/{exam_id}/pdf")
def export_examination_pdf(
    exam_id: UUID,
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR, UserRole.COACH)),
    db: Session = Depends(get_db),
) -> Response:
    """Export an institutional examination summary PDF scoped to the caller's institution."""
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

    institution = db.query(Institution).filter(Institution.id == current_user.institution_id).first()
    summary = get_examination_summary(exam_id=exam_id, current_user=current_user, db=db)

    pdf_bytes = render_examination_report_pdf(
        institution_name=institution.name if institution else "Institution",
        examination_title=summary.examination.title,
        examination_status=summary.examination.status,
        total_students_tested=summary.total_students_tested,
        approved_attempts_count=summary.approved_attempts_count,
        test_summaries=[
            {
                "test_type": ts.test_type.value,
                "count": ts.count,
                "average": ts.average,
                "best": ts.best,
                "unit": ts.unit,
            }
            for ts in summary.test_summaries
        ],
        rankings=[
            {
                "test_type": r.test_type.value,
                "rank": r.rank,
                "institution_student_code": r.institution_student_code,
                "full_name": r.full_name,
                "value": r.value,
                "unit": r.unit,
                "category_label": r.category_label,
            }
            for r in summary.rankings
        ],
    )
    return Response(
        content=pdf_bytes,
        media_type="application/pdf",
        headers={"Content-Disposition": f'attachment; filename="exam_{exam_id}_summary.pdf"'},
    )
