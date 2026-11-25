"""Institution-scoped read-only audit log endpoint (ADMIN only)."""

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.api.deps import require_roles
from app.db.models import AuditLog, User, UserRole
from app.db.session import get_db
from app.schemas import AuditLogResponse

router = APIRouter(prefix="/api/v1/audit-logs", tags=["audit"])


@router.get("", response_model=list[AuditLogResponse])
def list_audit_logs(
    limit: int = Query(default=100, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
    current_user: User = Depends(require_roles(UserRole.ADMIN)),
    db: Session = Depends(get_db),
) -> list[AuditLogResponse]:
    """Return immutable audit trail entries scoped to the caller's institution."""
    rows = (
        db.query(AuditLog)
        .filter(AuditLog.institution_id == current_user.institution_id)
        .order_by(AuditLog.created_at.desc())
        .offset(offset)
        .limit(limit)
        .all()
    )
    return [AuditLogResponse.model_validate(row) for row in rows]
