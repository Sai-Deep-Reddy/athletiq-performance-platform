"""OpenCV ChArUco board generator, multi-view intrinsics solver, and planar homography service."""

from dataclasses import dataclass
from typing import Any

import cv2
import numpy as np

DEFAULT_SQUARES_X = 5
DEFAULT_SQUARES_Y = 7
DEFAULT_DICT_NAME = "DICT_4X4_50"
PROVISIONAL_MIN_CORNERS = 6
PROVISIONAL_MAX_REPROJ_PX = 1.5
PROVISIONAL_MIN_QUADRANTS = 2
PROVISIONAL_MIN_INTRINSIC_VIEWS = 3
PROVISIONAL_MIN_VIEW_CENTROID_SPREAD_NORM = 0.08


@dataclass(frozen=True)
class CharucoSolveOutput:
    detected_markers_count: int
    detected_charuco_corners: int
    homography_3x3: list[float] | None
    reprojection_error_px: float | None
    spatial_coverage_ratio: float
    quadrants_covered: int
    image_width: int
    image_height: int
    reference_target_spec_json: dict[str, Any]
    used_intrinsics: bool
    quality_gate_passed: bool
    provisional_thresholds: dict[str, Any]
    warnings: list[str]
    rejection_reason: str | None


@dataclass(frozen=True)
class IntrinsicsSolveOutput:
    camera_matrix_3x3: list[float] | None
    dist_coeffs: list[float] | None
    reprojection_error_px: float | None
    views_submitted: int
    views_accepted: int
    views_rejected: int
    image_width: int | None
    image_height: int | None
    quality_gate_passed: bool
    provisional_thresholds: dict[str, Any]
    warnings: list[str]
    rejection_reason: str | None


def _build_charuco_board(
    *,
    squares_x: int,
    squares_y: int,
    square_length_cm: float,
    marker_length_cm: float,
) -> tuple[cv2.aruco.CharucoBoard, cv2.aruco.CharucoDetector]:
    dictionary = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)
    board = cv2.aruco.CharucoBoard(
        (squares_x, squares_y),
        float(square_length_cm),
        float(marker_length_cm),
        dictionary,
    )
    detector = cv2.aruco.CharucoDetector(board)
    return board, detector


def generate_charuco_board_png(
    *,
    squares_x: int = DEFAULT_SQUARES_X,
    squares_y: int = DEFAULT_SQUARES_Y,
    square_length_cm: float = 4.0,
    marker_length_cm: float = 2.5,
    width_px: int = 1000,
    height_px: int = 1400,
) -> bytes:
    """Render a real printable OpenCV ChArUco board target as PNG bytes."""
    board, _ = _build_charuco_board(
        squares_x=squares_x,
        squares_y=squares_y,
        square_length_cm=square_length_cm,
        marker_length_cm=marker_length_cm,
    )
    img = board.generateImage((width_px, height_px), marginSize=40, borderBits=1)
    ok, encoded = cv2.imencode(".png", img)
    if not ok:
        raise RuntimeError("Failed to encode ChArUco board PNG")
    return encoded.tobytes()


def _decode_image_bytes(image_bytes: bytes) -> np.ndarray | None:
    if not image_bytes:
        return None
    buf = np.frombuffer(image_bytes, dtype=np.uint8)
    return cv2.imdecode(buf, cv2.IMREAD_COLOR)


def _compute_quadrants_and_coverage(
    points_xy: np.ndarray,
    width: int,
    height: int,
) -> tuple[float, int]:
    if len(points_xy) == 0 or width <= 0 or height <= 0:
        return 0.0, 0
    xs = points_xy[:, 0]
    ys = points_xy[:, 1]
    span_x = float(np.max(xs) - np.min(xs)) / float(width)
    span_y = float(np.max(ys) - np.min(ys)) / float(height)
    coverage_ratio = max(0.0, min(1.0, span_x * span_y))

    mid_x = width / 2.0
    mid_y = height / 2.0
    quadrants = set()
    for x, y in points_xy:
        qx = 0 if x < mid_x else 1
        qy = 0 if y < mid_y else 1
        quadrants.add((qx, qy))
    return coverage_ratio, len(quadrants)


def solve_planar_charuco(
    *,
    image_bytes: bytes,
    plane_type: str,
    squares_x: int = DEFAULT_SQUARES_X,
    squares_y: int = DEFAULT_SQUARES_Y,
    square_length_cm: float = 4.0,
    marker_length_cm: float = 2.5,
    camera_matrix_3x3: list[float] | None = None,
    dist_coeffs: list[float] | None = None,
) -> CharucoSolveOutput:
    """Detect ChArUco corners, optionally undistort, and solve image->plane homography (cm)."""
    image = _decode_image_bytes(image_bytes)
    if image is None:
        raise ValueError("Invalid or unreadable image payload")

    height, width = image.shape[:2]
    used_intrinsics = False
    warnings: list[str] = [
        "Reprojection error <= 1.5 px and corner count >= 6 are provisional calibration thresholds; "
        "perform independent physical target distance verification before examination use."
    ]

    if camera_matrix_3x3 is not None and dist_coeffs is not None:
        k_mat = np.array(camera_matrix_3x3, dtype=np.float64).reshape((3, 3))
        d_vec = np.array(dist_coeffs, dtype=np.float64)
        image = cv2.undistort(image, k_mat, d_vec)
        used_intrinsics = True
    else:
        warnings.append(
            "No active INTRINSICS profile applied; radial lens distortion has not been removed."
        )

    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    board, detector = _build_charuco_board(
        squares_x=squares_x,
        squares_y=squares_y,
        square_length_cm=square_length_cm,
        marker_length_cm=marker_length_cm,
    )
    charuco_corners, charuco_ids, _marker_corners, marker_ids = detector.detectBoard(gray)

    markers_count = int(len(marker_ids)) if marker_ids is not None else 0
    corners_count = int(len(charuco_ids)) if charuco_ids is not None else 0
    spec = {
        "dictionary": DEFAULT_DICT_NAME,
        "squares_x": squares_x,
        "squares_y": squares_y,
        "square_length_cm": square_length_cm,
        "marker_length_cm": marker_length_cm,
        "plane_type": plane_type,
    }
    thresholds = {
        "min_corners": PROVISIONAL_MIN_CORNERS,
        "max_reprojection_error_px": PROVISIONAL_MAX_REPROJ_PX,
        "min_quadrants": PROVISIONAL_MIN_QUADRANTS,
        "provisional": True,
    }

    if corners_count < PROVISIONAL_MIN_CORNERS or charuco_corners is None or charuco_ids is None:
        return CharucoSolveOutput(
            detected_markers_count=markers_count,
            detected_charuco_corners=corners_count,
            homography_3x3=None,
            reprojection_error_px=None,
            spatial_coverage_ratio=0.0,
            quadrants_covered=0,
            image_width=width,
            image_height=height,
            reference_target_spec_json=spec,
            used_intrinsics=used_intrinsics,
            quality_gate_passed=False,
            provisional_thresholds=thresholds,
            warnings=warnings,
            rejection_reason=(
                f"Detected {corners_count} ChArUco corners; minimum {PROVISIONAL_MIN_CORNERS} required"
            ),
        )

    obj_pts, img_pts = board.matchImagePoints(charuco_corners, charuco_ids)
    if obj_pts is None or img_pts is None or len(obj_pts) < PROVISIONAL_MIN_CORNERS:
        return CharucoSolveOutput(
            detected_markers_count=markers_count,
            detected_charuco_corners=corners_count,
            homography_3x3=None,
            reprojection_error_px=None,
            spatial_coverage_ratio=0.0,
            quadrants_covered=0,
            image_width=width,
            image_height=height,
            reference_target_spec_json=spec,
            used_intrinsics=used_intrinsics,
            quality_gate_passed=False,
            provisional_thresholds=thresholds,
            warnings=warnings,
            rejection_reason="Matched ChArUco object-image points below minimum threshold",
        )

    img_xy = img_pts.reshape(-1, 2).astype(np.float64)
    plane_xy = obj_pts.reshape(-1, 3)[:, :2].astype(np.float64)
    coverage_ratio, quadrants_covered = _compute_quadrants_and_coverage(img_xy, width, height)

    h_mat, _mask = cv2.findHomography(img_xy, plane_xy, method=0)
    if h_mat is None or not np.all(np.isfinite(h_mat)):
        return CharucoSolveOutput(
            detected_markers_count=markers_count,
            detected_charuco_corners=corners_count,
            homography_3x3=None,
            reprojection_error_px=None,
            spatial_coverage_ratio=coverage_ratio,
            quadrants_covered=quadrants_covered,
            image_width=width,
            image_height=height,
            reference_target_spec_json=spec,
            used_intrinsics=used_intrinsics,
            quality_gate_passed=False,
            provisional_thresholds=thresholds,
            warnings=warnings,
            rejection_reason="Homography matrix estimation was degenerate",
        )

    try:
        h_inv = np.linalg.inv(h_mat)
    except np.linalg.LinAlgError:
        return CharucoSolveOutput(
            detected_markers_count=markers_count,
            detected_charuco_corners=corners_count,
            homography_3x3=None,
            reprojection_error_px=None,
            spatial_coverage_ratio=coverage_ratio,
            quadrants_covered=quadrants_covered,
            image_width=width,
            image_height=height,
            reference_target_spec_json=spec,
            used_intrinsics=used_intrinsics,
            quality_gate_passed=False,
            provisional_thresholds=thresholds,
            warnings=warnings,
            rejection_reason="Homography matrix is singular and cannot invert to image space",
        )

    plane_homog = np.column_stack([plane_xy, np.ones(len(plane_xy), dtype=np.float64)])
    reproj_homog = (h_inv @ plane_homog.T).T
    reproj_xy = reproj_homog[:, :2] / reproj_homog[:, 2:3]
    errors_px = np.linalg.norm(reproj_xy - img_xy, axis=1)
    rms_px = float(np.sqrt(np.mean(errors_px**2)))

    if coverage_ratio < 0.08:
        warnings.append(
            f"Calibration target covers only {coverage_ratio * 100:.1f}% of the frame; "
            "do not extrapolate across large distances without independent target validation."
        )

    passed = True
    rejection_reason: str | None = None
    if rms_px > PROVISIONAL_MAX_REPROJ_PX:
        passed = False
        rejection_reason = (
            f"RMS reprojection error {rms_px:.2f} px exceeds provisional limit {PROVISIONAL_MAX_REPROJ_PX:.2f} px"
        )
    elif quadrants_covered < PROVISIONAL_MIN_QUADRANTS:
        warnings.append(
            f"Detected corners occupy only {quadrants_covered} image quadrant(s); "
            "wider target placement improves perspective stability."
        )

    scale = float(h_mat[2, 2]) if abs(float(h_mat[2, 2])) > 1e-12 else 1.0
    h_flat = [float(v / scale) for v in h_mat.reshape(-1)]

    return CharucoSolveOutput(
        detected_markers_count=markers_count,
        detected_charuco_corners=corners_count,
        homography_3x3=h_flat,
        reprojection_error_px=rms_px,
        spatial_coverage_ratio=coverage_ratio,
        quadrants_covered=quadrants_covered,
        image_width=width,
        image_height=height,
        reference_target_spec_json=spec,
        used_intrinsics=used_intrinsics,
        quality_gate_passed=passed,
        provisional_thresholds=thresholds,
        warnings=warnings,
        rejection_reason=rejection_reason,
    )


def solve_multi_view_intrinsics(
    *,
    images_bytes: list[bytes],
    squares_x: int = DEFAULT_SQUARES_X,
    squares_y: int = DEFAULT_SQUARES_Y,
    square_length_cm: float = 4.0,
    marker_length_cm: float = 2.5,
) -> IntrinsicsSolveOutput:
    """Calibrate camera matrix & distortion across diverse ChArUco board views."""
    thresholds = {
        "min_distinct_views": PROVISIONAL_MIN_INTRINSIC_VIEWS,
        "min_corners_per_view": PROVISIONAL_MIN_CORNERS,
        "max_reprojection_error_px": PROVISIONAL_MAX_REPROJ_PX,
        "min_centroid_spread_norm": PROVISIONAL_MIN_VIEW_CENTROID_SPREAD_NORM,
        "provisional": True,
    }
    warnings = [
        "Intrinsic calibration requires multiple distinct board positions and orientations; "
        "reprojection error is a provisional fit metric."
    ]

    board, detector = _build_charuco_board(
        squares_x=squares_x,
        squares_y=squares_y,
        square_length_cm=square_length_cm,
        marker_length_cm=marker_length_cm,
    )

    all_obj_points: list[np.ndarray] = []
    all_img_points: list[np.ndarray] = []
    centroids_norm: list[tuple[float, float]] = []
    accepted = 0
    rejected = 0
    image_size: tuple[int, int] | None = None

    for raw in images_bytes:
        image = _decode_image_bytes(raw)
        if image is None:
            rejected += 1
            continue
        h, w = image.shape[:2]
        if image_size is None:
            image_size = (w, h)
        elif image_size != (w, h):
            rejected += 1
            warnings.append("Skipped calibration view with mismatched image resolution.")
            continue

        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        charuco_corners, charuco_ids, _mc, _mi = detector.detectBoard(gray)
        if charuco_ids is None or charuco_corners is None or len(charuco_ids) < PROVISIONAL_MIN_CORNERS:
            rejected += 1
            continue

        obj_pts, img_pts = board.matchImagePoints(charuco_corners, charuco_ids)
        if obj_pts is None or img_pts is None or len(obj_pts) < PROVISIONAL_MIN_CORNERS:
            rejected += 1
            continue

        pts_2d = img_pts.reshape(-1, 2)
        cx = float(np.mean(pts_2d[:, 0])) / float(w)
        cy = float(np.mean(pts_2d[:, 1])) / float(h)
        centroids_norm.append((cx, cy))
        all_obj_points.append(obj_pts)
        all_img_points.append(img_pts)
        accepted += 1

    if accepted < PROVISIONAL_MIN_INTRINSIC_VIEWS or image_size is None:
        return IntrinsicsSolveOutput(
            camera_matrix_3x3=None,
            dist_coeffs=None,
            reprojection_error_px=None,
            views_submitted=len(images_bytes),
            views_accepted=accepted,
            views_rejected=rejected,
            image_width=image_size[0] if image_size else None,
            image_height=image_size[1] if image_size else None,
            quality_gate_passed=False,
            provisional_thresholds=thresholds,
            warnings=warnings,
            rejection_reason=(
                f"Need at least {PROVISIONAL_MIN_INTRINSIC_VIEWS} valid ChArUco views; got {accepted}"
            ),
        )

    c_arr = np.array(centroids_norm, dtype=np.float64)
    centroid_spread = float(np.hypot(np.ptp(c_arr[:, 0]), np.ptp(c_arr[:, 1])))
    if centroid_spread < PROVISIONAL_MIN_VIEW_CENTROID_SPREAD_NORM:
        return IntrinsicsSolveOutput(
            camera_matrix_3x3=None,
            dist_coeffs=None,
            reprojection_error_px=None,
            views_submitted=len(images_bytes),
            views_accepted=accepted,
            views_rejected=rejected,
            image_width=image_size[0],
            image_height=image_size[1],
            quality_gate_passed=False,
            provisional_thresholds=thresholds,
            warnings=warnings,
            rejection_reason=(
                "Calibration views are too similar in position; move and tilt the ChArUco board across the frame"
            ),
        )

    rms, camera_matrix, dist_coeffs, _rvecs, _tvecs = cv2.calibrateCamera(
        all_obj_points,
        all_img_points,
        image_size,
        None,
        None,
    )
    rms_val = float(rms)
    passed = rms_val <= PROVISIONAL_MAX_REPROJ_PX
    rejection_reason = (
        None
        if passed
        else f"Intrinsic RMS reprojection error {rms_val:.2f} px exceeds provisional {PROVISIONAL_MAX_REPROJ_PX:.2f} px"
    )

    return IntrinsicsSolveOutput(
        camera_matrix_3x3=[float(v) for v in camera_matrix.reshape(-1)],
        dist_coeffs=[float(v) for v in dist_coeffs.reshape(-1)],
        reprojection_error_px=rms_val,
        views_submitted=len(images_bytes),
        views_accepted=accepted,
        views_rejected=rejected,
        image_width=image_size[0],
        image_height=image_size[1],
        quality_gate_passed=passed,
        provisional_thresholds=thresholds,
        warnings=warnings,
        rejection_reason=rejection_reason,
    )
