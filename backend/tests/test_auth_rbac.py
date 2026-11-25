"""Authentication, RBAC, health check, and audit log tests."""

from datetime import datetime, timedelta, timezone

import jwt
from fastapi.testclient import TestClient
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.core.config import get_settings
from app.db.models import AuditLog
from app.db.session import get_db


def test_health_endpoint_checks_database(client: TestClient) -> None:
    """Health endpoint returns ok when DB responds and 503 when DB fails."""
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok", "database": "connected"}

    class _BrokenSession:
        def execute(self, _stmt):
            raise SQLAlchemyError("simulated db outage")

    client.app.dependency_overrides[get_db] = lambda: _BrokenSession()
    degraded = client.get("/health")
    assert degraded.status_code == 503


def test_bootstrap_login_rbac_and_audit_trail(
    client: TestClient,
    db_session: Session,
) -> None:
    """Verify one-time bootstrap, Argon2 login, JWT enforcement, RBAC, and audit logs."""
    bootstrap_payload = {
        "institution_name": "Test Academy Alpha",
        "institution_code": "ALPHA_01",
        "admin_email": "admin@alpha.test",
        "admin_full_name": "Alpha Admin",
        "admin_password": "Test-Strong-Password-123!",
    }
    boot = client.post("/api/v1/auth/bootstrap-institution", json=bootstrap_payload)
    assert boot.status_code == 201
    boot_data = boot.json()
    assert boot_data["institution"]["code"] == "ALPHA_01"
    assert boot_data["admin_user"]["role"] == "ADMIN"

    # Second bootstrap attempt must fail with 409 Conflict.
    second_boot = client.post("/api/v1/auth/bootstrap-institution", json=bootstrap_payload)
    assert second_boot.status_code == 409

    # Wrong password returns 401 and appends LOGIN_FAILED audit event.
    bad_login = client.post(
        "/api/v1/auth/login",
        json={"email": "admin@alpha.test", "password": "WrongPassword999!"},
    )
    assert bad_login.status_code == 401

    # Valid login issues bearer token.
    login = client.post(
        "/api/v1/auth/login",
        json={"email": "admin@alpha.test", "password": "Test-Strong-Password-123!"},
    )
    assert login.status_code == 200
    admin_token = login.json()["access_token"]
    admin_headers = {"Authorization": f"Bearer {admin_token}"}

    # Unauthenticated /me returns 401; valid token returns 200.
    assert client.get("/api/v1/auth/me").status_code == 401
    me = client.get("/api/v1/auth/me", headers=admin_headers)
    assert me.status_code == 200
    assert me.json()["email"] == "admin@alpha.test"

    # Expired token returns 401.
    settings = get_settings()
    expired_token = jwt.encode(
        {
            "sub": boot_data["admin_user"]["id"],
            "inst": boot_data["institution"]["id"],
            "role": "ADMIN",
            "iat": int((datetime.now(timezone.utc) - timedelta(hours=2)).timestamp()),
            "exp": int((datetime.now(timezone.utc) - timedelta(hours=1)).timestamp()),
        },
        settings.jwt_secret,
        algorithm=settings.jwt_algorithm,
    )
    assert (
        client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {expired_token}"}).status_code
        == 401
    )

    # Admin creates an INSTRUCTOR and a COACH.
    inst_resp = client.post(
        "/api/v1/users",
        headers=admin_headers,
        json={
            "email": "instructor@alpha.test",
            "full_name": "PE Instructor",
            "role": "INSTRUCTOR",
            "password": "Instructor-Pass-123!",
        },
    )
    assert inst_resp.status_code == 201

    coach_resp = client.post(
        "/api/v1/users",
        headers=admin_headers,
        json={
            "email": "coach@alpha.test",
            "full_name": "Track Coach",
            "role": "COACH",
            "password": "Coach-Password-123!",
        },
    )
    assert coach_resp.status_code == 201

    # Instructor cannot list/create users or read audit logs (ADMIN-only -> 403).
    inst_login = client.post(
        "/api/v1/auth/login",
        json={"email": "instructor@alpha.test", "password": "Instructor-Pass-123!"},
    )
    inst_headers = {"Authorization": f"Bearer {inst_login.json()['access_token']}"}
    assert client.get("/api/v1/users", headers=inst_headers).status_code == 403
    assert client.get("/api/v1/audit-logs", headers=inst_headers).status_code == 403

    # Admin can read audit logs; verify expected actions exist and no password hashes leaked.
    audit_resp = client.get("/api/v1/audit-logs", headers=admin_headers)
    assert audit_resp.status_code == 200
    actions = {row["action"] for row in audit_resp.json()}
    assert {"INSTITUTION_BOOTSTRAPPED", "LOGIN_FAILED", "LOGIN_SUCCESS", "USER_CREATED"}.issubset(actions)
    assert "Test-Strong-Password-123!" not in audit_resp.text
    assert db_session.query(AuditLog).count() >= 5
