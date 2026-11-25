"""Append-only audit logging helper."""

from typing import Any
from uuid import UUID

from sqlalchemy.orm import Session

from app.db.models import AuditLog


def record_audit_event(
    db: Session,
    *,
    institution_id: UUID,
    actor_user_id: UUID | None,
    action: str,
    resource_type: str,
    resource_id: str,
    details: dict[str, Any] | None = None,
) -> AuditLog:
    """Append an immutable audit log entry within the active DB transaction.

    Callers must avoid placing passwords, tokens, or unnecessary PII in `details`.
    """
    entry = AuditLog(
        institution_id=institution_id,
        actor_user_id=actor_user_id,
        action=action,
        resource_type=resource_type,
        resource_id=resource_id,
        details_json=details or {},
    )
    db.add(entry)
    return entry
