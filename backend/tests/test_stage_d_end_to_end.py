"""End-to-end Stage D backend tests: standards, exam sync, best-attempt, analytics, PDF, and isolation."""

import uuid
from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient
from sqlalchemy.orm import Session

from app.core.security import hash_password
from app.db.models import AuditLog, Institution, User, UserRole


def _login_headers(client: TestClient, email: str, password: str) -> dict[str, str]:
    resp = client.post("/api/v1/auth/login", json={"email": email, "password": password})
    assert resp.status_code == 200
    return {"Authorization": f"Bearer {resp.json()['access_token']}"}


def test_stage_d_exam_sync_scoring_analytics_pdf_and_isolation(
    client: TestClient,
    db_session: Session,
) -> None:
    """Verify full Stage D workflow across two isolated institutions."""
    inst_a = Institution(name="StageD Academy A", code="STGD_A")
    inst_b = Institution(name="StageD Academy B", code="STGD_B")
    db_session.add_all([inst_a, inst_b])
    db_session.flush()

    admin_a = User(
        institution_id=inst_a.id,
        email="admin_a@stgd.test",
        full_name="Admin A",
        role=UserRole.ADMIN,
        password_hash=hash_password("Admin-Pass-1234!"),
    )
    instructor_a = User(
        institution_id=inst_a.id,
        email="inst_a@stgd.test",
        full_name="Instructor A",
        role=UserRole.INSTRUCTOR,
        password_hash=hash_password("Inst-Pass-1234!"),
    )
    coach_a = User(
        institution_id=inst_a.id,
        email="coach_a@stgd.test",
        full_name="Coach A",
        role=UserRole.COACH,
        password_hash=hash_password("Coach-Pass-1234!"),
    )
    admin_b = User(
        institution_id=inst_b.id,
        email="admin_b@stgd.test",
        full_name="Admin B",
        role=UserRole.ADMIN,
        password_hash=hash_password("AdminB-Pass-1234!"),
    )
    db_session.add_all([admin_a, instructor_a, coach_a, admin_b])
    db_session.commit()

    h_admin_a = _login_headers(client, "admin_a@stgd.test", "Admin-Pass-1234!")
    h_inst_a = _login_headers(client, "inst_a@stgd.test", "Inst-Pass-1234!")
    h_coach_a = _login_headers(client, "coach_a@stgd.test", "Coach-Pass-1234!")
    h_admin_b = _login_headers(client, "admin_b@stgd.test", "AdminB-Pass-1234!")

    # 1. Create calibrations (vertical & ground) and student.
    now = datetime.now(timezone.utc)
    cal_vert = client.post(
        "/api/v1/calibrations",
        headers=h_inst_a,
        json={
            "device_model": "TestPhone",
            "plane_type": "VERTICAL_PLANE",
            "method": "OPENCV_CHARUCO",
            "matrix_json": {"homography_3x3": [1, 0, 0, 0, 1, 0, 0, 0, 1]},
            "reprojection_error_px": 0.4,
            "reference_target_spec_json": {"squares_x": 5, "squares_y": 7},
            "calibrated_at": now.isoformat(),
        },
    )
    assert cal_vert.status_code == 201
    cal_vert_id = cal_vert.json()["id"]

    cal_ground = client.post(
        "/api/v1/calibrations",
        headers=h_inst_a,
        json={
            "device_model": "TestPhone",
            "plane_type": "GROUND_PLANE",
            "method": "OPENCV_CHARUCO",
            "matrix_json": {"homography_3x3": [1, 0, 0, 0, 1, 0, 0, 0, 1]},
            "reprojection_error_px": 0.5,
            "reference_target_spec_json": {"squares_x": 5, "squares_y": 7},
            "calibrated_at": now.isoformat(),
        },
    )
    assert cal_ground.status_code == 201
    cal_ground_id = cal_ground.json()["id"]

    student_resp = client.post(
        "/api/v1/students",
        headers=h_inst_a,
        json={
            "institution_student_code": "ATH-101",
            "full_name": "Athlete One",
            "date_of_birth": "2010-01-15",
            "gender_category": "MALE",
            "department": "Track",
        },
    )
    assert student_resp.status_code == 201
    student_id = student_resp.json()["id"]

    # 2. Before standard is configured, history reports NO_STANDARD_CONFIGURED (never invented cutoffs).
    exam_resp = client.post(
        "/api/v1/examinations",
        headers=h_inst_a,
        json={"title": "Annual Fitness Term 1", "test_types": ["VERTICAL_JUMP", "SPRINT"], "status": "ACTIVE"},
    )
    assert exam_resp.status_code == 201
    exam_id = exam_resp.json()["id"]

    # Coach cannot create standards or examinations.
    assert (
        client.post(
            "/api/v1/examinations",
            headers=h_coach_a,
            json={"title": "Blocked", "test_types": ["HEIGHT"]},
        ).status_code
        == 403
    )

    # Incompatible plane calibration rejected with 422.
    bad_plane = client.post(
        f"/api/v1/examinations/{exam_id}/attempts/sync",
        headers=h_inst_a,
        json={
            "client_uuid": str(uuid.uuid4()),
            "student_id": student_id,
            "calibration_profile_id": cal_ground_id,
            "test_type": "VERTICAL_JUMP",
            "value": 42.0,
            "unit": "CENTIMETERS",
            "uncertainty": 0.8,
            "confidence_score": 0.92,
            "fps_actual": 120.0,
            "frame_dt_ms": 8.33,
            "dropped_frames": 0,
            "landmark_provenance": "MEDIAPIPE_HAND_LANDMARKER_TIP",
            "algorithm_version": "vjump-c2-v1.0",
            "validity_status": "VALID",
            "recorded_at": now.isoformat(),
        },
    )
    assert bad_plane.status_code == 422

    # 3. Sync two vertical jump attempts (42.5 cm then 48.0 cm) + test idempotent replay.
    vj1_uuid = str(uuid.uuid4())
    vj1_payload = {
        "client_uuid": vj1_uuid,
        "student_id": student_id,
        "calibration_profile_id": cal_vert_id,
        "test_type": "VERTICAL_JUMP",
        "value": 42.5,
        "unit": "CENTIMETERS",
        "uncertainty": 0.9,
        "confidence_score": 0.94,
        "fps_actual": 120.0,
        "frame_dt_ms": 8.33,
        "dropped_frames": 0,
        "landmark_provenance": "MEDIAPIPE_HAND_LANDMARKER_TIP",
        "algorithm_version": "vjump-c2-v1.0",
        "validity_status": "VALID",
        "recorded_at": now.isoformat(),
    }
    sync1 = client.post(f"/api/v1/examinations/{exam_id}/attempts/sync", headers=h_inst_a, json=vj1_payload)
    assert sync1.status_code == 200
    assert sync1.json()["idempotent_replay"] is False
    vj1_id = sync1.json()["attempt"]["id"]

    # Replay same client_uuid -> idempotent_replay True
    replay1 = client.post(f"/api/v1/examinations/{exam_id}/attempts/sync", headers=h_inst_a, json=vj1_payload)
    assert replay1.status_code == 200
    assert replay1.json()["idempotent_replay"] is True
    assert replay1.json()["attempt"]["id"] == vj1_id

    vj2_payload = {
        **vj1_payload,
        "client_uuid": str(uuid.uuid4()),
        "value": 48.0,
        "recorded_at": (now + timedelta(minutes=2)).isoformat(),
    }
    sync2 = client.post(f"/api/v1/examinations/{exam_id}/attempts/sync", headers=h_inst_a, json=vj2_payload)
    assert sync2.status_code == 200
    vj2_id = sync2.json()["attempt"]["id"]
    assert sync2.json()["attempt"]["is_best_attempt"] is True

    # Sync two sprint attempts (4.35s then 4.10s) -> lowest time must win best_attempt.
    sp1 = client.post(
        f"/api/v1/examinations/{exam_id}/attempts/sync",
        headers=h_inst_a,
        json={
            "client_uuid": str(uuid.uuid4()),
            "student_id": student_id,
            "calibration_profile_id": cal_ground_id,
            "test_type": "SPRINT",
            "value": 4.35,
            "unit": "SECONDS",
            "uncertainty": 0.017,
            "confidence_score": 0.90,
            "fps_actual": 60.0,
            "frame_dt_ms": 16.67,
            "dropped_frames": 0,
            "landmark_provenance": "MEDIAPIPE_POSE_HEEL_AND_MASK",
            "algorithm_version": "sprint-d-v1.0",
            "validity_status": "VALID",
            "recorded_at": (now + timedelta(minutes=4)).isoformat(),
        },
    )
    assert sp1.status_code == 200
    sp2 = client.post(
        f"/api/v1/examinations/{exam_id}/attempts/sync",
        headers=h_inst_a,
        json={
            "client_uuid": str(uuid.uuid4()),
            "student_id": student_id,
            "calibration_profile_id": cal_ground_id,
            "test_type": "SPRINT",
            "value": 4.10,
            "unit": "SECONDS",
            "uncertainty": 0.017,
            "confidence_score": 0.91,
            "fps_actual": 60.0,
            "frame_dt_ms": 16.67,
            "dropped_frames": 0,
            "landmark_provenance": "MEDIAPIPE_POSE_HEEL_AND_MASK",
            "algorithm_version": "sprint-d-v1.0",
            "validity_status": "VALID",
            "recorded_at": (now + timedelta(minutes=6)).isoformat(),
        },
    )
    assert sp2.status_code == 200
    sp2_id = sp2.json()["attempt"]["id"]
    assert sp2.json()["attempt"]["is_best_attempt"] is True

    # Verify history reports NO_STANDARD_CONFIGURED before admin adds rubric.
    hist_unconfigured = client.get(f"/api/v1/analytics/students/{student_id}/history", headers=h_coach_a)
    assert hist_unconfigured.status_code == 200
    vj_trend = next(t for t in hist_unconfigured.json()["trends"] if t["test_type"] == "VERTICAL_JUMP")
    assert vj_trend["scoring"]["status"] == "NO_STANDARD_CONFIGURED"
    assert abs(vj_trend["delta_from_previous"] - 5.5) < 1e-6

    # 4. Examiner approves vj1 (42.5) and REJECTS vj2 (48.0) -> vj1 becomes best! Approve sp2.
    assert (
        client.post(
            f"/api/v1/examinations/{exam_id}/attempts/{vj1_id}/review",
            headers=h_inst_a,
            json={"approval_status": "APPROVED"},
        ).status_code
        == 200
    )
    assert (
        client.post(
            f"/api/v1/examinations/{exam_id}/attempts/{vj2_id}/review",
            headers=h_inst_a,
            json={"approval_status": "REJECTED", "note": "Hand clipped at peak"},
        ).status_code
        == 200
    )
    assert (
        client.post(
            f"/api/v1/examinations/{exam_id}/attempts/{sp2_id}/review",
            headers=h_inst_a,
            json={"approval_status": "APPROVED"},
        ).status_code
        == 200
    )

    attempts_after = client.get(f"/api/v1/examinations/{exam_id}/attempts", headers=h_coach_a).json()
    best_vj = [a for a in attempts_after if a["test_type"] == "VERTICAL_JUMP" and a["is_best_attempt"]]
    assert len(best_vj) == 1
    assert best_vj[0]["id"] == vj1_id

    # 5. Admin configures ScoringStandard (Instructor gets 403).
    std_payload = {
        "name": "School Boys 14-18 Vertical Jump",
        "test_type": "VERTICAL_JUMP",
        "age_min": 14,
        "age_max": 18,
        "gender_category": "MALE",
        "metric_unit": "CENTIMETERS",
        "direction": "higher_is_better",
        "bands": [
            {"label": "Developing", "min_value": 0.0, "max_value": 34.9, "points": 50, "qualified": False},
            {"label": "Proficient", "min_value": 35.0, "max_value": 49.9, "points": 80, "qualified": True},
            {"label": "Elite", "min_value": 50.0, "max_value": None, "points": 100, "qualified": True},
        ],
        "source_citation": "School Athletic Board Standard 2026",
    }
    assert client.post("/api/v1/standards", headers=h_inst_a, json=std_payload).status_code == 403
    assert client.post("/api/v1/standards", headers=h_admin_a, json=std_payload).status_code == 201

    # Summary & PDF reports reflect approved best attempts + configured standard.
    summary = client.get(f"/api/v1/analytics/examinations/{exam_id}/summary", headers=h_coach_a)
    assert summary.status_code == 200
    summary_data = summary.json()
    assert summary_data["total_students_tested"] == 1
    assert summary_data["approved_attempts_count"] == 2
    vj_rank = next(r for r in summary_data["rankings"] if r["test_type"] == "VERTICAL_JUMP")
    assert vj_rank["value"] == 42.5
    assert vj_rank["category_label"] == "Proficient"

    student_pdf = client.get(f"/api/v1/reports/students/{student_id}/pdf", headers=h_coach_a)
    assert student_pdf.status_code == 200
    assert student_pdf.headers["content-type"] == "application/pdf"
    assert student_pdf.content.startswith(b"%PDF-")

    exam_pdf = client.get(f"/api/v1/reports/examinations/{exam_id}/pdf", headers=h_coach_a)
    assert exam_pdf.status_code == 200
    assert exam_pdf.content.startswith(b"%PDF-")

    # 6. Cross-institution isolation: Institution B gets 404 on everything belonging to Institution A.
    assert client.get(f"/api/v1/examinations/{exam_id}/attempts", headers=h_admin_b).status_code == 404
    assert client.get(f"/api/v1/analytics/students/{student_id}/history", headers=h_admin_b).status_code == 404
    assert client.get(f"/api/v1/analytics/examinations/{exam_id}/summary", headers=h_admin_b).status_code == 404
    assert client.get(f"/api/v1/reports/students/{student_id}/pdf", headers=h_admin_b).status_code == 404
    assert client.get(f"/api/v1/reports/examinations/{exam_id}/pdf", headers=h_admin_b).status_code == 404
    assert db_session.query(AuditLog).filter(AuditLog.action == "ATTEMPT_REVIEWED").count() == 3
