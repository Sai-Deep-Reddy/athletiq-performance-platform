"""Authentication and one-time institution bootstrap routes."""

import secrets

from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy import func
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.api.deps import get_current_user
from app.core.config import Settings, get_settings
from app.core.security import create_access_token, hash_password, verify_password
from app.db.audit import record_audit_event
from app.db.models import Institution, User, UserRole
from app.db.session import get_db
from app.schemas import (
    AuthTokenResponse,
    BootstrapResponse,
    InstitutionBootstrapRequest,
    InstitutionResponse,
    LoginRequest,
    UserResponse,
    WebSessionResponse,
)

router = APIRouter(prefix="/api/v1/auth", tags=["auth"])


def _authenticate_user(body: LoginRequest, db: Session) -> User:
    user = (
        db.query(User)
        .join(Institution, User.institution_id == Institution.id)
        .filter(User.email == body.email)
        .first()
    )
    if user is None or not user.is_active or not user.institution.is_active:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid credentials",
        )

    if not verify_password(body.password, user.password_hash):
        record_audit_event(
            db,
            institution_id=user.institution_id,
            actor_user_id=user.id,
            action="LOGIN_FAILED",
            resource_type="user",
            resource_id=str(user.id),
            details={"reason": "invalid_password"},
        )
        db.commit()
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid credentials",
        )
    return user


@router.post(
    "/bootstrap-institution",
    response_model=BootstrapResponse,
    status_code=status.HTTP_201_CREATED,
)
def bootstrap_institution(
    body: InstitutionBootstrapRequest,
    db: Session = Depends(get_db),
) -> BootstrapResponse:
    """Create the initial institution and ADMIN account only when no institutions exist."""
    existing_count = db.query(func.count(Institution.id)).scalar() or 0
    if existing_count > 0:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="System is already initialized",
        )

    institution = Institution(
        name=body.institution_name.strip(),
        code=body.institution_code.strip(),
    )
    db.add(institution)
    db.flush()

    admin_user = User(
        institution_id=institution.id,
        email=body.admin_email,
        full_name=body.admin_full_name.strip(),
        role=UserRole.ADMIN,
        password_hash=hash_password(body.admin_password),
    )
    db.add(admin_user)
    db.flush()

    record_audit_event(
        db,
        institution_id=institution.id,
        actor_user_id=admin_user.id,
        action="INSTITUTION_BOOTSTRAPPED",
        resource_type="institution",
        resource_id=str(institution.id),
        details={"institution_code": institution.code, "admin_role": admin_user.role.value},
    )

    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Institution or admin account conflict",
        )

    db.refresh(institution)
    db.refresh(admin_user)
    return BootstrapResponse(institution=institution, admin_user=admin_user)


@router.post("/login", response_model=AuthTokenResponse)
def login(
    body: LoginRequest,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> AuthTokenResponse:
    """Authenticate an institution user and issue a signed JWT."""
    user = _authenticate_user(body, db)
    token, expires_at = create_access_token(
        user_id=user.id,
        institution_id=user.institution_id,
        role=user.role.value,
        settings=settings,
    )
    record_audit_event(
        db,
        institution_id=user.institution_id,
        actor_user_id=user.id,
        action="LOGIN_SUCCESS",
        resource_type="user",
        resource_id=str(user.id),
        details={"role": user.role.value, "mode": "bearer"},
    )
    db.commit()

    return AuthTokenResponse(
        access_token=token,
        expires_at=expires_at,
        user=UserResponse.model_validate(user),
    )


@router.post("/web/login", response_model=WebSessionResponse)
def web_login(
    body: LoginRequest,
    response: Response,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> WebSessionResponse:
    """Authenticate a browser session using an HttpOnly JWT cookie and CSRF token."""
    user = _authenticate_user(body, db)
    token, expires_at = create_access_token(
        user_id=user.id,
        institution_id=user.institution_id,
        role=user.role.value,
        settings=settings,
    )
    csrf_token = secrets.token_urlsafe(32)
    max_age_seconds = settings.access_token_expire_minutes * 60

    response.set_cookie(
        key=settings.auth_cookie_name,
        value=token,
        httponly=True,
        secure=settings.is_cookie_secure,
        samesite=settings.cookie_samesite,
        max_age=max_age_seconds,
        path="/",
    )
    response.set_cookie(
        key=settings.csrf_cookie_name,
        value=csrf_token,
        httponly=False,
        secure=settings.is_cookie_secure,
        samesite=settings.cookie_samesite,
        max_age=max_age_seconds,
        path="/",
    )

    record_audit_event(
        db,
        institution_id=user.institution_id,
        actor_user_id=user.id,
        action="LOGIN_SUCCESS",
        resource_type="user",
        resource_id=str(user.id),
        details={"role": user.role.value, "mode": "web_cookie"},
    )
    db.commit()

    return WebSessionResponse(
        user=UserResponse.model_validate(user),
        institution=InstitutionResponse.model_validate(user.institution),
        csrf_token=csrf_token,
        expires_at=expires_at,
    )


@router.post("/web/logout")
def web_logout(
    response: Response,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> dict[str, str]:
    """Clear browser auth and CSRF cookies and record logout audit event."""
    response.delete_cookie(key=settings.auth_cookie_name, path="/")
    response.delete_cookie(key=settings.csrf_cookie_name, path="/")
    record_audit_event(
        db,
        institution_id=current_user.institution_id,
        actor_user_id=current_user.id,
        action="LOGOUT_SUCCESS",
        resource_type="user",
        resource_id=str(current_user.id),
        details={"mode": "web_cookie"},
    )
    db.commit()
    return {"status": "logged_out"}


@router.get("/me", response_model=UserResponse)
def get_me(current_user: User = Depends(get_current_user)) -> UserResponse:
    """Return the currently authenticated user profile."""
    return UserResponse.model_validate(current_user)


@router.get("/institution", response_model=InstitutionResponse)
def get_institution(current_user: User = Depends(get_current_user)) -> InstitutionResponse:
    """Return the institution metadata for the currently authenticated user."""
    return InstitutionResponse.model_validate(current_user.institution)
