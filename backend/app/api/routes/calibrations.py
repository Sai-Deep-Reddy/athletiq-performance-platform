"""Institution-scoped camera & plane calibration profile routes."""

from uuid import UUID

from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, Response, UploadFile, status
from sqlalchemy.orm import Session

from app.api.deps import require_roles
from app.db.audit import record_audit_event
from app.db.models import CalibrationPlaneType, CalibrationProfile, User, UserRole
from app.db.session import get_db
from app.schemas import (
    CalibrationCreateRequest,
    CalibrationProfileResponse,
    CharucoSolveResponse,
    IntrinsicsSolveResponse,
)
from app.services.charuco import (
    DEFAULT_SQUARES_X,
    DEFAULT_SQUARES_Y,
    generate_charuco_board_png,
    solve_multi_view_intrinsics,
    solve_planar_charuco,
)

router = APIRouter(prefix="/api/v1/calibrations", tags=["calibrations"])


@router.post("", response_model=CalibrationProfileResponse, status_code=status.HTTP_201_CREATED)
def create_calibration(
    body: CalibrationCreateRequest,
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR)),
    db: Session = Depends(get_db),
) -> CalibrationProfileResponse:
    """Persist a verified camera intrinsics or planar calibration profile."""
    profile = CalibrationProfile(
        institution_id=current_user.institution_id,
        device_model=body.device_model.strip(),
        plane_type=body.plane_type,
        method=body.method.strip(),
        matrix_json=body.matrix_json,
        reprojection_error_px=body.reprojection_error_px,
        reference_target_spec_json=body.reference_target_spec_json,
        calibrated_at=body.calibrated_at,
        is_active=True,
    )
    db.add(profile)
    db.flush()

    record_audit_event(
        db,
        institution_id=current_user.institution_id,
        actor_user_id=current_user.id,
        action="CALIBRATION_CREATED",
        resource_type="calibration_profile",
        resource_id=str(profile.id),
        details={
            "plane_type": profile.plane_type.value,
            "method": profile.method,
            "reprojection_error_px": profile.reprojection_error_px,
        },
    )
    db.commit()
    db.refresh(profile)
    return CalibrationProfileResponse.model_validate(profile)


@router.get("", response_model=list[CalibrationProfileResponse])
def list_calibrations(
    plane_type: CalibrationPlaneType | None = Query(default=None),
    active_only: bool = Query(default=True),
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR, UserRole.COACH)),
    db: Session = Depends(get_db),
) -> list[CalibrationProfileResponse]:
    """List calibration profiles scoped strictly to the caller's institution."""
    query = db.query(CalibrationProfile).filter(
        CalibrationProfile.institution_id == current_user.institution_id
    )
    if plane_type is not None:
        query = query.filter(CalibrationProfile.plane_type == plane_type)
    if active_only:
        query = query.filter(CalibrationProfile.is_active.is_(True))
    rows = query.order_by(CalibrationProfile.calibrated_at.desc()).all()
    return [CalibrationProfileResponse.model_validate(row) for row in rows]


@router.patch("/{calibration_id}/deactivate", response_model=CalibrationProfileResponse)
def deactivate_calibration(
    calibration_id: UUID,
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR)),
    db: Session = Depends(get_db),
) -> CalibrationProfileResponse:
    """Deactivate an outdated or superseded calibration profile."""
    profile = (
        db.query(CalibrationProfile)
        .filter(
            CalibrationProfile.id == calibration_id,
            CalibrationProfile.institution_id == current_user.institution_id,
        )
        .first()
    )
    if profile is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Calibration profile not found")

    if profile.is_active:
        profile.is_active = False
        record_audit_event(
            db,
            institution_id=current_user.institution_id,
            actor_user_id=current_user.id,
            action="CALIBRATION_DEACTIVATED",
            resource_type="calibration_profile",
            resource_id=str(profile.id),
            details={"plane_type": profile.plane_type.value},
        )
        db.commit()
        db.refresh(profile)

    return CalibrationProfileResponse.model_validate(profile)


@router.get("/charuco-board.png")
def get_charuco_board_png(
    squares_x: int = Query(default=DEFAULT_SQUARES_X, ge=3, le=12),
    squares_y: int = Query(default=DEFAULT_SQUARES_Y, ge=3, le=12),
    square_length_cm: float = Query(default=4.0, gt=0.5, le=100.0),
    marker_length_cm: float = Query(default=2.5, gt=0.3, le=90.0),
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR, UserRole.COACH)),
) -> Response:
    """Generate a printable OpenCV DICT_4X4_50 ChArUco target image."""
    del current_user
    if marker_length_cm >= square_length_cm:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="marker_length_cm must be smaller than square_length_cm",
        )
    png_bytes = generate_charuco_board_png(
        squares_x=squares_x,
        squares_y=squares_y,
        square_length_cm=square_length_cm,
        marker_length_cm=marker_length_cm,
    )
    return Response(
        content=png_bytes,
        media_type="image/png",
        headers={"Content-Disposition": 'inline; filename="athletiq_charuco_board.png"'},
    )


@router.post("/solve-intrinsics", response_model=IntrinsicsSolveResponse)
async def solve_intrinsics_endpoint(
    files: list[UploadFile] = File(...),
    squares_x: int = Form(default=DEFAULT_SQUARES_X, ge=3, le=12),
    squares_y: int = Form(default=DEFAULT_SQUARES_Y, ge=3, le=12),
    square_length_cm: float = Form(default=4.0, gt=0.5, le=100.0),
    marker_length_cm: float = Form(default=2.5, gt=0.3, le=90.0),
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR)),
) -> IntrinsicsSolveResponse:
    """Solve camera intrinsics and distortion across multiple diverse ChArUco views."""
    del current_user
    if marker_length_cm >= square_length_cm:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="marker_length_cm must be smaller than square_length_cm",
        )
    if len(files) < 3:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="At least 3 distinct ChArUco images are required for intrinsic calibration",
        )
    images_bytes = [await f.read() for f in files[:20]]
    result = solve_multi_view_intrinsics(
        images_bytes=images_bytes,
        squares_x=squares_x,
        squares_y=squares_y,
        square_length_cm=square_length_cm,
        marker_length_cm=marker_length_cm,
    )
    return IntrinsicsSolveResponse(
        camera_matrix_3x3=result.camera_matrix_3x3,
        dist_coeffs=result.dist_coeffs,
        reprojection_error_px=result.reprojection_error_px,
        views_submitted=result.views_submitted,
        views_accepted=result.views_accepted,
        views_rejected=result.views_rejected,
        image_width=result.image_width,
        image_height=result.image_height,
        quality_gate_passed=result.quality_gate_passed,
        provisional_thresholds=result.provisional_thresholds,
        warnings=result.warnings,
        rejection_reason=result.rejection_reason,
    )


@router.post("/solve-charuco", response_model=CharucoSolveResponse)
async def solve_charuco_endpoint(
    file: UploadFile = File(...),
    plane_type: CalibrationPlaneType = Form(...),
    squares_x: int = Form(default=DEFAULT_SQUARES_X, ge=3, le=12),
    squares_y: int = Form(default=DEFAULT_SQUARES_Y, ge=3, le=12),
    square_length_cm: float = Form(default=4.0, gt=0.5, le=100.0),
    marker_length_cm: float = Form(default=2.5, gt=0.3, le=90.0),
    intrinsics_profile_id: UUID | None = Form(default=None),
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR)),
    db: Session = Depends(get_db),
) -> CharucoSolveResponse:
    """Detect ChArUco corners on a planar calibration image and solve image->plane homography."""
    if plane_type == CalibrationPlaneType.INTRINSICS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Use /solve-intrinsics for multi-view INTRINSICS calibration",
        )
    if marker_length_cm >= square_length_cm:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="marker_length_cm must be smaller than square_length_cm",
        )

    camera_matrix_3x3: list[float] | None = None
    dist_coeffs: list[float] | None = None
    if intrinsics_profile_id is not None:
        intr = (
            db.query(CalibrationProfile)
            .filter(
                CalibrationProfile.id == intrinsics_profile_id,
                CalibrationProfile.institution_id == current_user.institution_id,
                CalibrationProfile.plane_type == CalibrationPlaneType.INTRINSICS,
            )
            .first()
        )
        if intr is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Intrinsics calibration profile not found",
            )
        camera_matrix_3x3 = intr.matrix_json.get("camera_matrix_3x3")
        dist_coeffs = intr.matrix_json.get("dist_coeffs")

    image_bytes = await file.read()
    try:
        solved = solve_planar_charuco(
            image_bytes=image_bytes,
            plane_type=plane_type.value,
            squares_x=squares_x,
            squares_y=squares_y,
            square_length_cm=square_length_cm,
            marker_length_cm=marker_length_cm,
            camera_matrix_3x3=camera_matrix_3x3,
            dist_coeffs=dist_coeffs,
        )
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc))

    return CharucoSolveResponse(
        plane_type=plane_type,
        detected_markers_count=solved.detected_markers_count,
        detected_charuco_corners=solved.detected_charuco_corners,
        homography_3x3=solved.homography_3x3,
        reprojection_error_px=solved.reprojection_error_px,
        spatial_coverage_ratio=solved.spatial_coverage_ratio,
        quadrants_covered=solved.quadrants_covered,
        image_width=solved.image_width,
        image_height=solved.image_height,
        reference_target_spec_json=solved.reference_target_spec_json,
        used_intrinsics=solved.used_intrinsics,
        quality_gate_passed=solved.quality_gate_passed,
        provisional_thresholds=solved.provisional_thresholds,
        warnings=solved.warnings,
        rejection_reason=solved.rejection_reason,
    )
