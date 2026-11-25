"""Calibration profile RBAC, matrix validation, and tenant isolation tests."""

from datetime import datetime, timezone

from fastapi.testclient import TestClient
from sqlalchemy.orm import Session

from app.core.security import hash_password
from app.db.models import AuditLog, Institution, User, UserRole


def _login_headers(client: TestClient, email: str, password: str) -> dict[str, str]:
    resp = client.post("/api/v1/auth/login", json={"email": email, "password": password})
    assert resp.status_code == 200
    return {"Authorization": f"Bearer {resp.json()['access_token']}"}


def test_calibration_crud_validation_and_isolation(
    client: TestClient,
    db_session: Session,
) -> None:
    """Verify calibration validation, RBAC, deactivation audit, and tenant isolation."""
    inst_a = Institution(name="Cal Academy A", code="CAL_A")
    inst_b = Institution(name="Cal Academy B", code="CAL_B")
    db_session.add_all([inst_a, inst_b])
    db_session.flush()

    instructor_a = User(
        institution_id=inst_a.id,
        email="cal_inst_a@test.local",
        full_name="Cal Instructor A",
        role=UserRole.INSTRUCTOR,
        password_hash=hash_password("Cal-Instructor-A-123!"),
    )
    coach_a = User(
        institution_id=inst_a.id,
        email="cal_coach_a@test.local",
        full_name="Cal Coach A",
        role=UserRole.COACH,
        password_hash=hash_password("Cal-Coach-A-123!"),
    )
    instructor_b = User(
        institution_id=inst_b.id,
        email="cal_inst_b@test.local",
        full_name="Cal Instructor B",
        role=UserRole.INSTRUCTOR,
        password_hash=hash_password("Cal-Instructor-B-123!"),
    )
    db_session.add_all([instructor_a, coach_a, instructor_b])
    db_session.commit()

    headers_a = _login_headers(client, "cal_inst_a@test.local", "Cal-Instructor-A-123!")
    headers_coach_a = _login_headers(client, "cal_coach_a@test.local", "Cal-Coach-A-123!")
    headers_b = _login_headers(client, "cal_inst_b@test.local", "Cal-Instructor-B-123!")

    # Reject malformed planar homography matrix (wrong element count).
    bad_matrix = client.post(
        "/api/v1/calibrations",
        headers=headers_a,
        json={
            "device_model": "Pixel 8",
            "plane_type": "VERTICAL_PLANE",
            "method": "OPENCV_CHARUCO",
            "matrix_json": {"homography_3x3": [1.0, 0.0, 0.0]},
            "reprojection_error_px": 0.45,
            "reference_target_spec_json": {"squares_x": 5, "squares_y": 7, "square_length_cm": 4.0},
            "calibrated_at": datetime.now(timezone.utc).isoformat(),
        },
    )
    assert bad_matrix.status_code == 422

    # Valid vertical-plane calibration created by Instructor A.
    created = client.post(
        "/api/v1/calibrations",
        headers=headers_a,
        json={
            "device_model": "Pixel 8",
            "plane_type": "VERTICAL_PLANE",
            "method": "OPENCV_CHARUCO",
            "matrix_json": {"homography_3x3": [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]},
            "reprojection_error_px": 0.42,
            "reference_target_spec_json": {"squares_x": 5, "squares_y": 7, "square_length_cm": 4.0},
            "calibrated_at": datetime.now(timezone.utc).isoformat(),
        },
    )
    assert created.status_code == 201
    cal_id = created.json()["id"]

    # Coach A can read calibrations, but cannot create or deactivate.
    list_coach = client.get("/api/v1/calibrations?plane_type=VERTICAL_PLANE", headers=headers_coach_a)
    assert list_coach.status_code == 200
    assert len(list_coach.json()) == 1
    assert client.patch(f"/api/v1/calibrations/{cal_id}/deactivate", headers=headers_coach_a).status_code == 403

    # Cross-institution isolation: Institution B cannot see or deactivate A's calibration.
    assert client.get("/api/v1/calibrations", headers=headers_b).json() == []
    assert client.patch(f"/api/v1/calibrations/{cal_id}/deactivate", headers=headers_b).status_code == 404

    # Instructor A deactivates calibration and audit log records it.
    deactivated = client.patch(f"/api/v1/calibrations/{cal_id}/deactivate", headers=headers_a)
    assert deactivated.status_code == 200
    assert deactivated.json()["is_active"] is False
    assert client.get("/api/v1/calibrations?active_only=true", headers=headers_a).json() == []
    assert db_session.query(AuditLog).filter(AuditLog.action == "CALIBRATION_DEACTIVATED").count() == 1
