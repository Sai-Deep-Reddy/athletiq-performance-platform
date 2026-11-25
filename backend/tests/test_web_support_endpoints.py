"""Tests for web support endpoints: HttpOnly cookie + CSRF auth, OpenCV ChArUco, exams/users PATCH, and FFmpeg fallback."""

import cv2
import numpy as np
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session

from app.core.security import hash_password
from app.db.models import AuditLog, Institution, User, UserRole


def _make_pinhole_charuco_bytes(
    board_png: bytes,
    rvec: tuple[float, float, float],
    tvec: tuple[float, float, float],
) -> bytes:
    arr = np.frombuffer(board_png, dtype=np.uint8)
    board_img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    h, w = board_img.shape[:2]
    canvas_w, canvas_h = 1280, 960
    k_true = np.array([[1000.0, 0.0, canvas_w / 2.0], [0.0, 1000.0, canvas_h / 2.0], [0.0, 0.0, 1.0]], dtype=np.float64)
    dist_zero = np.zeros(5, dtype=np.float64)
    # Board physical dimensions in cm (5x7 board with 4cm squares -> 20cm x 28cm + margin)
    obj_corners = np.array([[-10.0, -14.0, 0.0], [10.0, -14.0, 0.0], [10.0, 14.0, 0.0], [-10.0, 14.0, 0.0]], dtype=np.float64)
    proj, _ = cv2.projectPoints(
        obj_corners,
        np.array(rvec, dtype=np.float64),
        np.array(tvec, dtype=np.float64),
        k_true,
        dist_zero,
    )
    src = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
    dst = proj.reshape(4, 2).astype(np.float32)
    mat = cv2.getPerspectiveTransform(src, dst)
    warped = cv2.warpPerspective(board_img, mat, (canvas_w, canvas_h), borderValue=(245, 245, 245))
    ok, encoded = cv2.imencode(".png", warped)
    assert ok
    return encoded.tobytes()


def test_web_cookie_auth_csrf_and_institution_endpoints(
    client: TestClient,
    db_session: Session,
) -> None:
    inst = Institution(name="Web Test Academy", code="WEB_AC")
    db_session.add(inst)
    db_session.flush()

    admin = User(
        institution_id=inst.id,
        email="web_admin@test.local",
        full_name="Web Admin",
        role=UserRole.ADMIN,
        password_hash=hash_password("Web-Admin-Pass-123!"),
    )
    coach = User(
        institution_id=inst.id,
        email="web_coach@test.local",
        full_name="Web Coach",
        role=UserRole.COACH,
        password_hash=hash_password("Web-Coach-Pass-123!"),
    )
    db_session.add_all([admin, coach])
    db_session.commit()

    login_resp = client.post(
        "/api/v1/auth/web/login",
        json={"email": "web_admin@test.local", "password": "Web-Admin-Pass-123!"},
    )
    assert login_resp.status_code == 200
    body = login_resp.json()
    assert "access_token" not in body
    assert body["institution"]["name"] == "Web Test Academy"
    csrf_token = body["csrf_token"]
    assert "athletiq_access" in login_resp.cookies

    # GET endpoints work via HttpOnly cookie without Authorization header
    inst_resp = client.get("/api/v1/auth/institution")
    assert inst_resp.status_code == 200
    assert inst_resp.json()["code"] == "WEB_AC"

    # Mutating cookie request WITHOUT X-CSRF-Token must be rejected (403)
    blocked_create = client.post(
        "/api/v1/examinations",
        json={"title": "Should Fail Without CSRF", "test_types": ["HEIGHT"]},
    )
    assert blocked_create.status_code == 403

    # Mutating cookie request WITH matching X-CSRF-Token succeeds
    created_exam = client.post(
        "/api/v1/examinations",
        headers={"X-CSRF-Token": csrf_token},
        json={"title": "Term 1 Web Exam", "test_types": ["HEIGHT", "VERTICAL_JUMP"]},
    )
    assert created_exam.status_code == 201
    exam_id = created_exam.json()["id"]

    # GET + PATCH examination lifecycle
    fetched_exam = client.get(f"/api/v1/examinations/{exam_id}")
    assert fetched_exam.status_code == 200
    patched_exam = client.patch(
        f"/api/v1/examinations/{exam_id}",
        headers={"X-CSRF-Token": csrf_token},
        json={"status": "COMPLETED"},
    )
    assert patched_exam.status_code == 200
    assert patched_exam.json()["status"] == "COMPLETED"

    # PATCH user: cannot deactivate own ADMIN account, can update coach
    self_deact = client.patch(
        f"/api/v1/users/{admin.id}",
        headers={"X-CSRF-Token": csrf_token},
        json={"is_active": False},
    )
    assert self_deact.status_code == 422

    coach_patch = client.patch(
        f"/api/v1/users/{coach.id}",
        headers={"X-CSRF-Token": csrf_token},
        json={"is_active": False},
    )
    assert coach_patch.status_code == 200
    assert coach_patch.json()["is_active"] is False

    # Logout requires CSRF and clears session
    logout_resp = client.post("/api/v1/auth/web/logout", headers={"X-CSRF-Token": csrf_token})
    assert logout_resp.status_code == 200
    assert client.get("/api/v1/auth/me").status_code == 401
    assert db_session.query(AuditLog).filter(AuditLog.action == "LOGOUT_SUCCESS").count() == 1


def test_opencv_charuco_board_intrinsics_homography_and_ffmpeg_gate(
    client: TestClient,
    db_session: Session,
    monkeypatch,
) -> None:
    inst = Institution(name="Vision Lab", code="VIS_LAB")
    db_session.add(inst)
    db_session.flush()
    instructor = User(
        institution_id=inst.id,
        email="vision_inst@test.local",
        full_name="Vision Instructor",
        role=UserRole.INSTRUCTOR,
        password_hash=hash_password("Vision-Pass-123!"),
    )
    db_session.add(instructor)
    db_session.commit()

    login_resp = client.post(
        "/api/v1/auth/web/login",
        json={"email": "vision_inst@test.local", "password": "Vision-Pass-123!"},
    )
    csrf = login_resp.json()["csrf_token"]

    # 1. Generate printable ChArUco target PNG
    board_resp = client.get("/api/v1/calibrations/charuco-board.png")
    assert board_resp.status_code == 200
    assert board_resp.headers["content-type"] == "image/png"
    board_png = board_resp.content
    assert len(board_png) > 1000

    # 2. Solve planar ChArUco homography on pinhole-projected board image
    warped_png = _make_pinhole_charuco_bytes(board_png, rvec=(0.1, -0.15, 0.05), tvec=(0.0, 0.0, 48.0))
    solve_resp = client.post(
        "/api/v1/calibrations/solve-charuco",
        headers={"X-CSRF-Token": csrf},
        data={"plane_type": "VERTICAL_PLANE", "square_length_cm": "4.0", "marker_length_cm": "2.5"},
        files={"file": ("cal.png", warped_png, "image/png")},
    )
    assert solve_resp.status_code == 200
    solved = solve_resp.json()
    assert solved["quality_gate_passed"] is True
    assert solved["detected_charuco_corners"] >= 6
    assert len(solved["homography_3x3"]) == 9
    assert solved["reprojection_error_px"] < 1.5
    assert solved["provisional_thresholds"]["provisional"] is True

    # Blank image must be cleanly rejected by quality gate (never dummy matrix)
    blank = np.full((240, 320, 3), 255, dtype=np.uint8)
    ok, blank_enc = cv2.imencode(".png", blank)
    assert ok
    blank_resp = client.post(
        "/api/v1/calibrations/solve-charuco",
        headers={"X-CSRF-Token": csrf},
        data={"plane_type": "GROUND_PLANE"},
        files={"file": ("blank.png", blank_enc.tobytes(), "image/png")},
    )
    assert blank_resp.status_code == 200
    assert blank_resp.json()["quality_gate_passed"] is False
    assert blank_resp.json()["homography_3x3"] is None

    # 3. Multi-view intrinsics rejects static duplicate views, succeeds on diverse 3D pinhole views
    static_resp = client.post(
        "/api/v1/calibrations/solve-intrinsics",
        headers={"X-CSRF-Token": csrf},
        files=[
            ("files", ("v1.png", warped_png, "image/png")),
            ("files", ("v2.png", warped_png, "image/png")),
            ("files", ("v3.png", warped_png, "image/png")),
        ],
    )
    assert static_resp.status_code == 200
    assert static_resp.json()["quality_gate_passed"] is False

    view_a = _make_pinhole_charuco_bytes(board_png, rvec=(0.25, -0.20, 0.08), tvec=(-10.0, -4.0, 52.0))
    view_b = _make_pinhole_charuco_bytes(board_png, rvec=(-0.22, 0.25, -0.10), tvec=(9.0, -3.0, 50.0))
    view_c = _make_pinhole_charuco_bytes(board_png, rvec=(0.15, 0.28, 0.12), tvec=(2.0, 6.0, 46.0))
    view_d = _make_pinhole_charuco_bytes(board_png, rvec=(-0.28, -0.18, -0.05), tvec=(-6.0, 5.0, 55.0))
    diverse_resp = client.post(
        "/api/v1/calibrations/solve-intrinsics",
        headers={"X-CSRF-Token": csrf},
        files=[
            ("files", ("va.png", view_a, "image/png")),
            ("files", ("vb.png", view_b, "image/png")),
            ("files", ("vc.png", view_c, "image/png")),
            ("files", ("vd.png", view_d, "image/png")),
        ],
    )
    assert diverse_resp.status_code == 200
    intr = diverse_resp.json()
    assert intr["views_accepted"] == 4
    assert intr["quality_gate_passed"] is True
    assert len(intr["camera_matrix_3x3"]) == 9
    assert len(intr["dist_coeffs"]) >= 4

    # 4. FFmpeg fallback returns explicit 503 when ffmpeg is not installed
    monkeypatch.setattr("app.api.routes.video.shutil.which", lambda _cmd: None)
    ff_resp = client.post(
        "/api/v1/video/extract-frames",
        headers={"X-CSRF-Token": csrf},
        files={"file": ("clip.mp4", b"dummy", "video/mp4")},
    )
    assert ff_resp.status_code == 503
