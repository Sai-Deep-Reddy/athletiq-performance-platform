"""Optional server-side video frame extraction fallback when browser WebCodecs is unavailable."""

import base64
import shutil
import subprocess
import tempfile
from pathlib import Path

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status

from app.api.deps import require_roles
from app.db.models import User, UserRole
from app.schemas import VideoFramesFallbackResponse

router = APIRouter(prefix="/api/v1/video", tags=["video"])

_MAX_FALLBACK_BYTES = 50 * 1024 * 1024


@router.post("/extract-frames", response_model=VideoFramesFallbackResponse)
async def extract_video_frames_fallback(
    file: UploadFile = File(...),
    max_frames: int = Form(default=120, ge=1, le=600),
    current_user: User = Depends(require_roles(UserRole.ADMIN, UserRole.INSTRUCTOR)),
) -> VideoFramesFallbackResponse:
    """Extract JPEG frames + timestamps via FFmpeg when browser WebCodecs is unavailable."""
    del current_user  # RBAC validated via dependency.
    ffmpeg_path = shutil.which("ffmpeg")
    ffprobe_path = shutil.which("ffprobe")
    if not ffmpeg_path or not ffprobe_path:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="FFmpeg is not installed on this server; WebCodecs browser decoding is required.",
        )

    payload = await file.read()
    if not payload:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Empty video file",
        )
    if len(payload) > _MAX_FALLBACK_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_CONTENT_TOO_LARGE,
            detail="Video file exceeds 50 MB server fallback limit",
        )

    with tempfile.TemporaryDirectory() as tmpdir:
        tmp_path = Path(tmpdir)
        in_path = tmp_path / "input_video.bin"
        in_path.write_bytes(payload)
        out_pattern = str(tmp_path / "frame_%05d.jpg")

        proc = subprocess.run(
            [
                ffmpeg_path,
                "-hide_banner",
                "-loglevel",
                "error",
                "-i",
                str(in_path),
                "-frames:v",
                str(max_frames),
                "-vsync",
                "0",
                out_pattern,
            ],
            capture_output=True,
            text=True,
            timeout=30,
        )
        if proc.returncode != 0:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail="Unable to decode uploaded video stream",
            )

        frame_files = sorted(tmp_path.glob("frame_*.jpg"))
        frames_b64 = [base64.b64encode(p.read_bytes()).decode("ascii") for p in frame_files]
        # Approximate timestamp index if container fps cannot be probed cleanly
        probe = subprocess.run(
            [
                ffprobe_path,
                "-v",
                "error",
                "-select_streams",
                "v:0",
                "-show_entries",
                "frame=pts_time",
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                str(in_path),
            ],
            capture_output=True,
            text=True,
            timeout=15,
        )
        timestamps_us: list[int] = []
        if probe.returncode == 0:
            for line in probe.stdout.splitlines()[: len(frames_b64)]:
                try:
                    timestamps_us.append(int(round(float(line.strip()) * 1_000_000)))
                except ValueError:
                    pass
        if len(timestamps_us) != len(frames_b64):
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail="Original presentation timestamps could not be verified from video container.",
            )

        duration_s = (
            (timestamps_us[-1] - timestamps_us[0]) / 1_000_000.0
            if len(timestamps_us) >= 2
            else None
        )
        fps_est = (len(timestamps_us) - 1) / duration_s if duration_s and duration_s > 0 else None
        return VideoFramesFallbackResponse(
            frame_count=len(frames_b64),
            fps_estimated=fps_est,
            duration_seconds=duration_s,
            width=None,
            height=None,
            frames_base64=frames_b64,
            timestamps_us=timestamps_us,
        )
