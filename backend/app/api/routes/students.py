"""Examiner-managed student profile routes with strict institution isolation."""

from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.api.deps import require_roles
from app.db.audit import record_audit_event
from app.db.models import Student, User, UserRole
from app.db.session import get_db
from app.schemas import StudentCreateRequest, StudentResponse, StudentUpdateRequest

router = APIRouter(prefix="/api/v1/students", tags=["students"])


@router.post("", response_model=StudentResponse, status_code=status.HTTP_201_CREATED)
def create_student(
    body: StudentCreateRequest,
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR)),
    db: Session = Depends(get_db),
) -> StudentResponse:
    """Register an examiner-managed student inside the caller's institution."""
    student = Student(
        institution_id=current_user.institution_id,
        institution_student_code=body.institution_student_code.strip(),
        full_name=body.full_name.strip(),
        date_of_birth=body.date_of_birth,
        gender_category=body.gender_category.strip() if body.gender_category else None,
        department=body.department.strip() if body.department else None,
        managed_by_user_id=current_user.id,
    )
    db.add(student)
    try:
        db.flush()
    except IntegrityError:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Student code already exists in this institution",
        )

    record_audit_event(
        db,
        institution_id=current_user.institution_id,
        actor_user_id=current_user.id,
        action="STUDENT_CREATED",
        resource_type="student",
        resource_id=str(student.id),
        details={"institution_student_code": student.institution_student_code},
    )
    db.commit()
    db.refresh(student)
    return StudentResponse.model_validate(student)


@router.get("", response_model=list[StudentResponse])
def list_students(
    limit: int = Query(default=100, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR, UserRole.COACH)),
    db: Session = Depends(get_db),
) -> list[StudentResponse]:
    """List students belonging strictly to the caller's institution."""
    rows = (
        db.query(Student)
        .filter(Student.institution_id == current_user.institution_id)
        .order_by(Student.institution_student_code.asc())
        .offset(offset)
        .limit(limit)
        .all()
    )
    return [StudentResponse.model_validate(row) for row in rows]


@router.get("/{student_id}", response_model=StudentResponse)
def get_student(
    student_id: UUID,
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR, UserRole.COACH)),
    db: Session = Depends(get_db),
) -> StudentResponse:
    """Fetch a single student profile scoped to the caller's institution."""
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
    return StudentResponse.model_validate(student)


@router.patch("/{student_id}", response_model=StudentResponse)
def update_student(
    student_id: UUID,
    body: StudentUpdateRequest,
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR)),
    db: Session = Depends(get_db),
) -> StudentResponse:
    """Update an examiner-managed student profile inside the caller's institution."""
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

    changed_fields: list[str] = []
    if body.full_name is not None:
        student.full_name = body.full_name.strip()
        changed_fields.append("full_name")
    if body.date_of_birth is not None:
        student.date_of_birth = body.date_of_birth
        changed_fields.append("date_of_birth")
    if body.gender_category is not None:
        student.gender_category = body.gender_category.strip()
        changed_fields.append("gender_category")
    if body.department is not None:
        student.department = body.department.strip()
        changed_fields.append("department")
    if body.is_active is not None:
        student.is_active = body.is_active
        changed_fields.append("is_active")

    if changed_fields:
        record_audit_event(
            db,
            institution_id=current_user.institution_id,
            actor_user_id=current_user.id,
            action="STUDENT_UPDATED",
            resource_type="student",
            resource_id=str(student.id),
            details={"changed_fields": changed_fields},
        )
        db.commit()
        db.refresh(student)

    return StudentResponse.model_validate(student)
