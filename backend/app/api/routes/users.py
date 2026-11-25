"""Institution-scoped user management routes (ADMIN only)."""

from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.api.deps import require_roles
from app.core.security import hash_password
from app.db.audit import record_audit_event
from app.db.models import User, UserRole
from app.db.session import get_db
from app.schemas import UserCreateRequest, UserResponse, UserUpdateRequest

router = APIRouter(prefix="/api/v1/users", tags=["users"])


@router.post("", response_model=UserResponse, status_code=status.HTTP_201_CREATED)
def create_user(
    body: UserCreateRequest,
    current_user: User = Depends(require_roles(UserRole.ADMIN)),
    db: Session = Depends(get_db),
) -> UserResponse:
    """Create a staff user strictly inside the current admin's institution."""
    new_user = User(
        institution_id=current_user.institution_id,
        email=body.email,
        full_name=body.full_name.strip(),
        role=body.role,
        password_hash=hash_password(body.password),
    )
    db.add(new_user)
    try:
        db.flush()
    except IntegrityError:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="A user with this email already exists",
        )

    record_audit_event(
        db,
        institution_id=current_user.institution_id,
        actor_user_id=current_user.id,
        action="USER_CREATED",
        resource_type="user",
        resource_id=str(new_user.id),
        details={"role": new_user.role.value},
    )
    db.commit()
    db.refresh(new_user)
    return UserResponse.model_validate(new_user)


@router.get("", response_model=list[UserResponse])
def list_users(
    current_user: User = Depends(require_roles(UserRole.ADMIN)),
    db: Session = Depends(get_db),
) -> list[UserResponse]:
    """List users belonging strictly to the current admin's institution."""
    rows = (
        db.query(User)
        .filter(User.institution_id == current_user.institution_id)
        .order_by(User.created_at.asc())
        .all()
    )
    return [UserResponse.model_validate(row) for row in rows]


@router.patch("/{user_id}", response_model=UserResponse)
def update_user(
    user_id: UUID,
    body: UserUpdateRequest,
    current_user: User = Depends(require_roles(UserRole.ADMIN)),
    db: Session = Depends(get_db),
) -> UserResponse:
    """Update staff user role, full name, or active status inside the admin's institution."""
    target = (
        db.query(User)
        .filter(
            User.id == user_id,
            User.institution_id == current_user.institution_id,
        )
        .first()
    )
    if target is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")

    if target.id == current_user.id:
        if body.is_active is False:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail="Administrators cannot deactivate their own active account",
            )
        if body.role is not None and body.role != UserRole.ADMIN:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail="Administrators cannot demote their own role",
            )

    changed_fields: list[str] = []
    if body.full_name is not None:
        target.full_name = body.full_name.strip()
        changed_fields.append("full_name")
    if body.role is not None and body.role != target.role:
        target.role = body.role
        changed_fields.append("role")
    if body.is_active is not None and body.is_active != target.is_active:
        target.is_active = body.is_active
        changed_fields.append("is_active")

    if changed_fields:
        record_audit_event(
            db,
            institution_id=current_user.institution_id,
            actor_user_id=current_user.id,
            action="USER_UPDATED",
            resource_type="user",
            resource_id=str(target.id),
            details={"changed_fields": changed_fields},
        )
        db.commit()
        db.refresh(target)

    return UserResponse.model_validate(target)
