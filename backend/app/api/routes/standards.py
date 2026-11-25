"""Institution-scoped configurable scoring standards routes (ADMIN manages, staff reads)."""

from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session

from app.api.deps import require_roles
from app.db.audit import record_audit_event
from app.db.models import ScoringStandard, TestType, User, UserRole
from app.db.session import get_db
from app.schemas import ScoringStandardCreateRequest, ScoringStandardResponse

router = APIRouter(prefix="/api/v1/standards", tags=["standards"])


@router.post("", response_model=ScoringStandardResponse, status_code=status.HTTP_201_CREATED)
def create_scoring_standard(
    body: ScoringStandardCreateRequest,
    current_user: User = Depends(require_roles(UserRole.ADMIN)),
    db: Session = Depends(get_db),
) -> ScoringStandardResponse:
    """Create an institution-configured scoring rubric with explicit source citation."""
    standard = ScoringStandard(
        institution_id=current_user.institution_id,
        name=body.name.strip(),
        test_type=body.test_type,
        age_min=body.age_min,
        age_max=body.age_max,
        gender_category=body.gender_category.strip() if body.gender_category else None,
        discipline=body.discipline.strip() if body.discipline else None,
        metric_unit=body.metric_unit.strip(),
        bands_json={
            "direction": body.direction,
            "bands": [b.model_dump() for b in body.bands],
        },
        source_citation=body.source_citation.strip(),
        is_active=True,
    )
    db.add(standard)
    db.flush()

    record_audit_event(
        db,
        institution_id=current_user.institution_id,
        actor_user_id=current_user.id,
        action="SCORING_STANDARD_CREATED",
        resource_type="scoring_standard",
        resource_id=str(standard.id),
        details={"test_type": standard.test_type.value, "name": standard.name},
    )
    db.commit()
    db.refresh(standard)
    return ScoringStandardResponse.model_validate(standard)


@router.get("", response_model=list[ScoringStandardResponse])
def list_scoring_standards(
    test_type: TestType | None = Query(default=None),
    active_only: bool = Query(default=True),
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR, UserRole.COACH)),
    db: Session = Depends(get_db),
) -> list[ScoringStandardResponse]:
    """List configurable scoring standards strictly inside the caller's institution."""
    query = db.query(ScoringStandard).filter(
        ScoringStandard.institution_id == current_user.institution_id
    )
    if test_type is not None:
        query = query.filter(ScoringStandard.test_type == test_type)
    if active_only:
        query = query.filter(ScoringStandard.is_active.is_(True))
    rows = query.order_by(ScoringStandard.created_at.desc()).all()
    return [ScoringStandardResponse.model_validate(row) for row in rows]


@router.patch("/{standard_id}/deactivate", response_model=ScoringStandardResponse)
def deactivate_scoring_standard(
    standard_id: UUID,
    current_user: User = Depends(require_roles(UserRole.ADMIN)),
    db: Session = Depends(get_db),
) -> ScoringStandardResponse:
    """Deactivate a scoring standard inside the caller's institution."""
    standard = (
        db.query(ScoringStandard)
        .filter(
            ScoringStandard.id == standard_id,
            ScoringStandard.institution_id == current_user.institution_id,
        )
        .first()
    )
    if standard is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Scoring standard not found")

    if standard.is_active:
        standard.is_active = False
        record_audit_event(
            db,
            institution_id=current_user.institution_id,
            actor_user_id=current_user.id,
            action="SCORING_STANDARD_DEACTIVATED",
            resource_type="scoring_standard",
            resource_id=str(standard.id),
            details={"test_type": standard.test_type.value},
        )
        db.commit()
        db.refresh(standard)

    return ScoringStandardResponse.model_validate(standard)
