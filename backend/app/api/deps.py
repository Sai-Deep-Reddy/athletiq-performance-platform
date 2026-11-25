"""Authentication and RBAC FastAPI dependencies."""

import hmac
from collections.abc import Callable
from uuid import UUID

import jwt
from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from app.core.config import Settings, get_settings
from app.core.security import decode_access_token
from app.db.models import Institution, User, UserRole
from app.db.session import get_db

_bearer_scheme = HTTPBearer(auto_error=False)
_MUTATING_METHODS = {"POST", "PUT", "PATCH", "DELETE"}


def _verify_csrf_for_cookie_session(request: Request, settings: Settings) -> None:
    if request.method.upper() not in _MUTATING_METHODS:
        return
    csrf_cookie = request.cookies.get(settings.csrf_cookie_name)
    csrf_header = request.headers.get("X-CSRF-Token")
    if not csrf_cookie or not csrf_header or not hmac.compare_digest(csrf_cookie, csrf_header):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="CSRF validation failed",
        )


def get_current_user(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer_scheme),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> User:
    """Authenticate Bearer JWT or HttpOnly cookie session (with CSRF protection) and return active User."""
    token: str | None = None
    using_cookie = False

    if credentials is not None and credentials.scheme.lower() == "bearer":
        token = credentials.credentials
    else:
        cookie_token = request.cookies.get(settings.auth_cookie_name)
        if cookie_token:
            token = cookie_token
            using_cookie = True

    if not token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authentication required",
        )

    if using_cookie:
        _verify_csrf_for_cookie_session(request, settings)

    try:
        payload = decode_access_token(token, settings)
        user_id = UUID(str(payload["sub"]))
        institution_id = UUID(str(payload["inst"]))
    except (jwt.PyJWTError, ValueError, KeyError):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired token",
        )

    user = (
        db.query(User)
        .join(Institution, User.institution_id == Institution.id)
        .filter(
            User.id == user_id,
            User.institution_id == institution_id,
            User.is_active.is_(True),
            Institution.is_active.is_(True),
        )
        .first()
    )
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or inactive account",
        )
    return user


def require_roles(*allowed_roles: UserRole) -> Callable[[User], User]:
    """Enforce server-side role-based access control."""

    def _checker(current_user: User = Depends(get_current_user)) -> User:
        if current_user.role not in allowed_roles:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Insufficient permissions for this operation",
            )
        return current_user

    return _checker
