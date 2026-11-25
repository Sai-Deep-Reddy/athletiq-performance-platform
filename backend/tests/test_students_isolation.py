"""Student management, validation, and strict multi-institution tenant isolation tests."""

from datetime import date, timedelta

from fastapi.testclient import TestClient
from sqlalchemy.orm import Session

from app.core.security import hash_password
from app.db.models import Institution, User, UserRole


def _login_headers(client: TestClient, email: str, password: str) -> dict[str, str]:
    resp = client.post("/api/v1/auth/login", json={"email": email, "password": password})
    assert resp.status_code == 200
    return {"Authorization": f"Bearer {resp.json()['access_token']}"}


def test_student_rbac_constraints_and_cross_institution_isolation(
    client: TestClient,
    db_session: Session,
) -> None:
    """Verify student RBAC, duplicate codes, boundary checks, and strict tenant isolation."""
    # Setup two separate institutions directly in the isolated test DB.
    inst_a = Institution(name="School A", code="SCH_A")
    inst_b = Institution(name="School B", code="SCH_B")
    db_session.add_all([inst_a, inst_b])
    db_session.flush()

    instructor_a = User(
        institution_id=inst_a.id,
        email="inst_a@test.local",
        full_name="Instructor A",
        role=UserRole.INSTRUCTOR,
        password_hash=hash_password("Alpha-Pass-1234!"),
    )
    coach_a = User(
        institution_id=inst_a.id,
        email="coach_a@test.local",
        full_name="Coach A",
        role=UserRole.COACH,
        password_hash=hash_password("Coach-Pass-1234!"),
    )
    instructor_b = User(
        institution_id=inst_b.id,
        email="inst_b@test.local",
        full_name="Instructor B",
        role=UserRole.INSTRUCTOR,
        password_hash=hash_password("Beta-Pass-1234!"),
    )
    db_session.add_all([instructor_a, coach_a, instructor_b])
    db_session.commit()

    headers_inst_a = _login_headers(client, "inst_a@test.local", "Alpha-Pass-1234!")
    headers_coach_a = _login_headers(client, "coach_a@test.local", "Coach-Pass-1234!")
    headers_inst_b = _login_headers(client, "inst_b@test.local", "Beta-Pass-1234!")

    # Instructor A registers student STU-001.
    create_a = client.post(
        "/api/v1/students",
        headers=headers_inst_a,
        json={
            "institution_student_code": "STU-001",
            "full_name": "Test Athlete A",
            "date_of_birth": "2010-05-14",
            "gender_category": "FEMALE",
            "department": "Athletics",
        },
    )
    assert create_a.status_code == 201
    student_a_id = create_a.json()["id"]

    # Duplicate student code inside the SAME institution returns 409 Conflict.
    dup_a = client.post(
        "/api/v1/students",
        headers=headers_inst_a,
        json={
            "institution_student_code": "STU-001",
            "full_name": "Duplicate Code",
        },
    )
    assert dup_a.status_code == 409

    # Future date_of_birth returns 422 Validation Error.
    future_dob = (date.today() + timedelta(days=30)).isoformat()
    bad_dob = client.post(
        "/api/v1/students",
        headers=headers_inst_a,
        json={
            "institution_student_code": "STU-002",
            "full_name": "Future Birth",
            "date_of_birth": future_dob,
        },
    )
    assert bad_dob.status_code == 422

    # Coach A can read students in Institution A, but gets 403 on create/update.
    assert client.get("/api/v1/students", headers=headers_coach_a).status_code == 200
    assert (
        client.post(
            "/api/v1/students",
            headers=headers_coach_a,
            json={"institution_student_code": "STU-003", "full_name": "Blocked"},
        ).status_code
        == 403
    )
    assert (
        client.patch(
            f"/api/v1/students/{student_a_id}",
            headers=headers_coach_a,
            json={"department": "Blocked"},
        ).status_code
        == 403
    )

    # Institution B can use the same student code STU-001 in its own tenant,
    # and CANNOT list, read, or modify Institution A's student.
    create_b = client.post(
        "/api/v1/students",
        headers=headers_inst_b,
        json={
            "institution_student_code": "STU-001",
            "full_name": "Test Athlete B",
        },
    )
    assert create_b.status_code == 201

    list_b = client.get("/api/v1/students", headers=headers_inst_b)
    assert list_b.status_code == 200
    assert len(list_b.json()) == 1
    assert list_b.json()[0]["full_name"] == "Test Athlete B"

    assert client.get(f"/api/v1/students/{student_a_id}", headers=headers_inst_b).status_code == 404
    assert (
        client.patch(
            f"/api/v1/students/{student_a_id}",
            headers=headers_inst_b,
            json={"full_name": "Cross Tenant Tamper"},
        ).status_code
        == 404
    )
