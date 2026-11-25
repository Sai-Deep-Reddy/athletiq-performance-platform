import { type MouseEvent, useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  ChevronRight,
  Crosshair,
  Download,
  RotateCcw,
  Sliders,
  Trash2,
} from 'lucide-react';
import type { CharucoSolveResponse, IntrinsicsSolveResponse } from '../../core/api/schemas';
import { PlanarHomography } from '../../core/biomechanics/planarHomography';
import type { useAthletiqCore } from '../../core/domain/useAthletiqCore';
import type { CalibrationPlaneType, Point2D } from '../../core/model/types';

const STAGE_LABELS = ['Intrinsics', 'V-Plane', 'G-Plane'] as const;
const CORNER_LABELS = ['Top-Left', 'Top-Right', 'Bottom-Right', 'Bottom-Left'] as const;

function StageIndicator({ activeStage }: { activeStage: 0 | 1 | 2 }) {
  return (
    <div className="flex flex-wrap items-center gap-1">
      {STAGE_LABELS.map((label, idx) => {
        const done = idx < activeStage;
        const active = idx === activeStage;
        return (
          <div key={label} className="flex items-center">
            <div
              className={[
                'flex h-7 items-center rounded-full px-2.5 text-[11px] font-bold transition-all',
                done
                  ? 'bg-emerald-500/15 text-tertiary'
                  : active
                    ? 'bg-primary/15 text-primary ring-1 ring-primary/30'
                    : 'bg-surface-container-low text-on-surface-variant',
              ].join(' ')}
            >
              {done && <CheckCircle2 className="mr-1 h-3 w-3" />}
              {label}
            </div>
            {idx < 2 && <ChevronRight className="mx-0.5 h-3.5 w-3.5 text-outline" />}
          </div>
        );
      })}
    </div>
  );
}

export function CalibrationCenter({ core }: { core: ReturnType<typeof useAthletiqCore> }) {
  const [plane, setPlane] = useState<Exclude<CalibrationPlaneType, 'INTRINSICS'>>('VERTICAL_PLANE');
  const [deviceModel, setDeviceModel] = useState('Desktop/Mobile Browser Camera');
  const [squareCm, setSquareCm] = useState('4.0');
  const [markerCm, setMarkerCm] = useState('2.5');
  const [distanceVerified, setDistanceVerified] = useState(false);
  const [intrinsicsFiles, setIntrinsicsFiles] = useState<File[]>([]);
  const [planarFile, setPlanarFile] = useState<File | null>(null);
  const [intrinsicsRes, setIntrinsicsRes] = useState<IntrinsicsSolveResponse | null>(null);
  const [planarRes, setPlanarRes] = useState<CharucoSolveResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  // Local client-side 4-point reference calibration (available in Guest Field Mode)
  const [guestImageUrl, setGuestImageUrl] = useState<string | null>(null);
  const [guestImgSize, setGuestImgSize] = useState<{ w: number; h: number } | null>(null);
  const [guestPoints, setGuestPoints] = useState<Point2D[]>([]);
  const [refWidthCm, setRefWidthCm] = useState('100');
  const [refHeightCm, setRefHeightCm] = useState('100');

  const activeIntrinsics = core.workspace.calibrations.find(
    (c) => c.plane_type === 'INTRINSICS' && c.is_active,
  );
  const activeVertical = core.workspace.calibrations.find(
    (c) => c.plane_type === 'VERTICAL_PLANE' && c.is_active,
  );
  const activeGround = core.workspace.calibrations.find(
    (c) => c.plane_type === 'GROUND_PLANE' && c.is_active,
  );

  const activeStage: 0 | 1 | 2 =
    activeIntrinsics && activeVertical && activeGround ? 2 : activeIntrinsics || activeVertical ? 1 : 0;

  const canModifyProfiles = core.auth.canEditAssessments || core.auth.isGuest;

  const downloadTargetBoard = async () => {
    setError(null);
    try {
      const blob = await core.api.fetchCharucoBoardPngBlob({
        squareLengthCm: Number(squareCm) || 4.0,
        markerLengthCm: Number(markerCm) || 2.5,
      });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'athletiq_charuco_5x7.png';
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to download ChArUco board.');
    }
  };

  const solveIntrinsics = async () => {
    if (intrinsicsFiles.length < 3) {
      setError('Select at least 3 ChArUco images at varied positions/angles.');
      return;
    }
    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      const fd = new FormData();
      for (const f of intrinsicsFiles) fd.append('files', f);
      fd.append('square_length_cm', squareCm);
      fd.append('marker_length_cm', markerCm);
      const res = await core.api.solveIntrinsics(fd);
      setIntrinsicsRes(res);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Intrinsics solve failed.');
    } finally {
      setBusy(false);
    }
  };

  const saveIntrinsicsProfile = async () => {
    if (!intrinsicsRes?.quality_gate_passed || !intrinsicsRes.camera_matrix_3x3 || !intrinsicsRes.dist_coeffs) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await core.saveCalibration({
        device_model: deviceModel,
        plane_type: 'INTRINSICS',
        method: 'OPENCV_CHARUCO_MULTIVIEW',
        matrix_json: {
          camera_matrix_3x3: intrinsicsRes.camera_matrix_3x3,
          dist_coeffs: intrinsicsRes.dist_coeffs,
        },
        reprojection_error_px: intrinsicsRes.reprojection_error_px ?? 0,
        reference_target_spec_json: {
          views_accepted: intrinsicsRes.views_accepted,
          square_length_cm: Number(squareCm),
          marker_length_cm: Number(markerCm),
        },
      });
      setSuccess('Camera intrinsics profile saved.');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save intrinsics profile.');
    } finally {
      setBusy(false);
    }
  };

  const solvePlanar = async () => {
    if (!planarFile) {
      setError('Select or upload a calibration frame image first.');
      return;
    }
    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      const fd = new FormData();
      fd.append('file', planarFile);
      fd.append('plane_type', plane);
      fd.append('square_length_cm', squareCm);
      fd.append('marker_length_cm', markerCm);
      if (activeIntrinsics) fd.append('intrinsics_profile_id', activeIntrinsics.id);
      const res = await core.api.solveCharuco(fd);
      setPlanarRes(res);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Planar ChArUco solve failed.');
    } finally {
      setBusy(false);
    }
  };

  const savePlanarProfile = async () => {
    if (!planarRes?.quality_gate_passed || !planarRes.homography_3x3) return;
    setBusy(true);
    setError(null);
    try {
      await core.saveCalibration({
        device_model: deviceModel,
        plane_type: plane,
        method: 'OPENCV_CHARUCO',
        matrix_json: { homography_3x3: planarRes.homography_3x3 },
        reprojection_error_px: planarRes.reprojection_error_px ?? 0,
        reference_target_spec_json: {
          ...planarRes.reference_target_spec_json,
          spatial_coverage_ratio: planarRes.spatial_coverage_ratio,
          quadrants_covered: planarRes.quadrants_covered,
          independent_distance_verified: distanceVerified,
        },
      });
      setSuccess(`${plane} profile saved.`);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save planar calibration.');
    } finally {
      setBusy(false);
    }
  };

  const handleGuestImagePick = (file: File | undefined) => {
    if (!file) return;
    if (guestImageUrl) URL.revokeObjectURL(guestImageUrl);
    const url = URL.createObjectURL(file);
    setGuestImageUrl(url);
    setGuestPoints([]);
    setGuestImgSize(null);
    setError(null);
    setSuccess(null);
  };

  const handleGuestCornerClick = (e: MouseEvent<HTMLImageElement>) => {
    if (guestPoints.length >= 4 || !guestImgSize) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const xPx = ((e.clientX - rect.left) / rect.width) * guestImgSize.w;
    const yPx = ((e.clientY - rect.top) / rect.height) * guestImgSize.h;
    setGuestPoints((prev) => [...prev, { x: xPx, y: yPx }]);
  };

  const solveAndSaveGuestHomography = async () => {
    if (guestPoints.length !== 4 || !guestImgSize) {
      setError('Click 4 corners (Top-Left, Top-Right, Bottom-Right, Bottom-Left) on your reference target.');
      return;
    }
    const wCm = Number(refWidthCm);
    const hCm = Number(refHeightCm);
    if (!Number.isFinite(wCm) || wCm <= 0 || !Number.isFinite(hCm) || hCm <= 0) {
      setError('Enter valid positive reference width and height in cm.');
      return;
    }

    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      // Plane coordinates: Top-Left (0, hCm), Top-Right (wCm, hCm), Bottom-Right (wCm, 0), Bottom-Left (0, 0)
      const planePoints: Point2D[] = [
        { x: 0, y: hCm },
        { x: wCm, y: hCm },
        { x: wCm, y: 0 },
        { x: 0, y: 0 },
      ];
      const homography = PlanarHomography.solveFromCorrespondences(guestPoints, planePoints);
      const rmsPx = homography.computeRmsReprojectionErrorPx(guestPoints, planePoints);
      await core.saveCalibration({
        device_model: `${deviceModel} (Guest Local)`,
        plane_type: plane,
        method: 'LOCAL_4POINT_HOMOGRAPHY',
        matrix_json: { homography_3x3: homography.imageToPlaneMatrix },
        reprojection_error_px: rmsPx,
        reference_target_spec_json: {
          width_cm: wCm,
          height_cm: hCm,
          image_width: guestImgSize.w,
          image_height: guestImgSize.h,
          independent_distance_verified: false,
        },
      });
      setSuccess(
        `Local ${plane} homography saved to IndexedDB (RMS ${rmsPx.toFixed(3)} px). Ready for Capture Workbench.`,
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to solve planar homography from clicked points.');
    } finally {
      setBusy(false);
    }
  };

  const inputCls =
    'mt-1 min-h-[44px] w-full rounded-xl border border-outline-variant bg-surface-container-low px-3 py-2 text-xs font-medium text-on-surface outline-none transition focus:border-primary focus:ring-2 focus:ring-primary/20';
  const btnPrimary =
    'inline-flex min-h-[46px] items-center justify-center gap-2 rounded-xl bg-primary-container px-4 py-2 text-xs font-bold text-white shadow-xs transition hover:brightness-110 active:scale-[0.98] disabled:opacity-40';
  const btnSecondary =
    'inline-flex min-h-[46px] items-center justify-center gap-2 rounded-xl border border-outline-variant bg-surface-container-low px-4 py-2 text-xs font-bold text-on-surface transition hover:bg-surface-container active:scale-[0.98] disabled:opacity-40';

  return (
    <div className="space-y-4">
      <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2.5">
            <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-surface-container text-primary">
              <Sliders className="h-4 w-4" />
            </div>
            <div>
              <h2 className="text-sm font-bold text-on-surface sm:text-base">
                Optical Calibration &amp; Homography Gate
              </h2>
              <p className="text-[11px] text-on-surface-variant">
                Separates lens intrinsics from Vertical-Plane (Height / VJ) and Ground-Plane (Broad / Sprint) calibration.
              </p>
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <StageIndicator activeStage={activeStage} />
            {core.auth.canEditAssessments && (
              <button type="button" onClick={() => void downloadTargetBoard()} className={btnSecondary}>
                <Download className="h-4 w-4" /> ChArUco Target
              </button>
            )}
          </div>
        </div>

        <div className="grid gap-3 sm:grid-cols-3">
          {[
            { label: 'Camera Intrinsics', item: activeIntrinsics },
            { label: 'Vertical Plane (Height / VJ)', item: activeVertical },
            { label: 'Ground Plane (Broad / Sprint)', item: activeGround },
          ].map(({ label, item }) => (
            <div
              key={label}
              className={`rounded-xl border p-3.5 transition ${
                item
                  ? 'border-tertiary/30 bg-emerald-500/10'
                  : 'border-outline-variant bg-surface-container-low'
              }`}
            >
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-bold text-on-surface">{label}</span>
                {item ? (
                  <span className="inline-flex items-center gap-1 rounded-full bg-emerald-500/15 px-2 py-0.5 text-[10px] font-bold text-tertiary">
                    <CheckCircle2 className="h-3 w-3" /> Active
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1 rounded-full bg-amber-500/15 px-2 py-0.5 text-[10px] font-bold text-amber-700 dark:text-amber-300">
                    <AlertTriangle className="h-3 w-3" /> Required
                  </span>
                )}
              </div>
              {item ? (
                <div className="mt-2 space-y-1 text-xs text-on-surface-variant tnum">
                  <p>
                    RMS: {item.reprojection_error_px.toFixed(3)} px • {item.method}
                  </p>
                  <p className="truncate text-[11px]">{item.device_model}</p>
                  {canModifyProfiles && (
                    <button
                      type="button"
                      onClick={() => void core.deactivateCalibration(item.id)}
                      className="mt-1 inline-flex items-center gap-1 text-xs font-semibold text-error hover:underline"
                    >
                      <Trash2 className="h-3.5 w-3.5" /> Deactivate
                    </button>
                  )}
                </div>
              ) : (
                <p className="mt-2 text-xs text-on-surface-variant">No active profile loaded.</p>
              )}
            </div>
          ))}
        </div>
      </div>

      {error && (
        <div className="rounded-xl border border-error/30 bg-error-container/50 p-3 text-xs text-error">
          {error}
        </div>
      )}
      {success && (
        <div className="rounded-xl border border-tertiary/30 bg-emerald-500/15 p-3 text-xs text-tertiary">
          {success}
        </div>
      )}

      {/* Authenticated Institutional OpenCV ChArUco Pipeline */}
      {core.auth.canEditAssessments && (
        <>
          <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-3">
            <h3 className="text-[11px] font-bold uppercase tracking-wider text-on-surface-variant">
              ChArUco Board &amp; Rig Configuration
            </h3>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <label className="text-xs text-on-surface-variant">
                Square Size (cm)
                <input
                  value={squareCm}
                  onChange={(e) => setSquareCm(e.target.value)}
                  type="number"
                  step="0.1"
                  className={inputCls + ' tnum'}
                />
              </label>
              <label className="text-xs text-on-surface-variant">
                Marker Size (cm)
                <input
                  value={markerCm}
                  onChange={(e) => setMarkerCm(e.target.value)}
                  type="number"
                  step="0.1"
                  className={inputCls + ' tnum'}
                />
              </label>
              <label className="col-span-2 text-xs text-on-surface-variant">
                Camera / Rig Label
                <input
                  value={deviceModel}
                  onChange={(e) => setDeviceModel(e.target.value)}
                  className={inputCls}
                />
              </label>
            </div>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-3">
              <h3 className="text-sm font-bold text-on-surface">
                1. Multi-View Camera Intrinsics (Optional Undistortion)
              </h3>
              <p className="text-xs text-on-surface-variant">
                Upload 3+ ChArUco snapshots at varied tilt angles. Stationary duplicates are rejected.
              </p>
              <input
                type="file"
                accept="image/*"
                multiple
                onChange={(e) => setIntrinsicsFiles(Array.from(e.target.files ?? []))}
                className="block w-full rounded-xl border border-outline-variant bg-surface-container-low p-2.5 text-xs text-on-surface file:mr-3 file:rounded-lg file:border-0 file:bg-primary-container file:px-3 file:py-1.5 file:text-xs file:font-semibold file:text-white"
              />
              <div className="flex flex-wrap gap-2">
                <button type="button" disabled={busy} onClick={() => void solveIntrinsics()} className={btnPrimary}>
                  Solve ({intrinsicsFiles.length} views)
                </button>
                {intrinsicsRes?.quality_gate_passed && (
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => void saveIntrinsicsProfile()}
                    className={btnSecondary}
                  >
                    Save Intrinsics
                  </button>
                )}
              </div>
              {intrinsicsRes && (
                <div className="rounded-xl bg-telemetry-dark p-3 text-xs text-slate-100 space-y-1">
                  <p className={`font-bold ${intrinsicsRes.quality_gate_passed ? 'text-emerald-400' : 'text-red-400'}`}>
                    Gate: {intrinsicsRes.quality_gate_passed ? 'PASSED' : 'REJECTED'}
                  </p>
                  <p className="tnum text-slate-300">
                    Views: {intrinsicsRes.views_accepted}/{intrinsicsRes.views_submitted} • RMS:{' '}
                    {intrinsicsRes.reprojection_error_px?.toFixed(3) ?? 'N/A'} px
                  </p>
                  {intrinsicsRes.rejection_reason && (
                    <p className="text-red-300">{intrinsicsRes.rejection_reason}</p>
                  )}
                </div>
              )}
            </div>

            <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-3">
              <h3 className="text-sm font-bold text-on-surface">2. Planar ChArUco Homography</h3>
              <label className="block text-xs text-on-surface-variant">
                Plane Type
                <select
                  value={plane}
                  onChange={(e) => setPlane(e.target.value as 'VERTICAL_PLANE' | 'GROUND_PLANE')}
                  className={inputCls}
                >
                  <option value="VERTICAL_PLANE">VERTICAL_PLANE (Height / VJ)</option>
                  <option value="GROUND_PLANE">GROUND_PLANE (Broad / Sprint)</option>
                </select>
              </label>
              <input
                type="file"
                accept="image/*"
                onChange={(e) => setPlanarFile(e.target.files?.[0] ?? null)}
                className="block w-full rounded-xl border border-outline-variant bg-surface-container-low p-2.5 text-xs text-on-surface file:mr-3 file:rounded-lg file:border-0 file:bg-primary-container file:px-3 file:py-1.5 file:text-xs file:font-semibold file:text-white"
              />
              <label className="flex items-center gap-2 text-xs text-on-surface-variant">
                <input
                  type="checkbox"
                  checked={distanceVerified}
                  onChange={(e) => setDistanceVerified(e.target.checked)}
                  className="h-4 w-4 rounded accent-primary"
                />
                Independent physical target distance verified across corridor
              </label>
              <div className="flex flex-wrap gap-2">
                <button type="button" disabled={busy} onClick={() => void solvePlanar()} className={btnPrimary}>
                  Detect &amp; Solve ChArUco
                </button>
                {planarRes?.quality_gate_passed && (
                  <button type="button" disabled={busy} onClick={() => void savePlanarProfile()} className={btnSecondary}>
                    Save {plane === 'VERTICAL_PLANE' ? 'V-Plane' : 'G-Plane'}
                  </button>
                )}
              </div>
              {planarRes && (
                <div className="rounded-xl bg-telemetry-dark p-3 text-xs text-slate-100 space-y-1">
                  <p className={`font-bold ${planarRes.quality_gate_passed ? 'text-emerald-400' : 'text-red-400'}`}>
                    Gate: {planarRes.quality_gate_passed ? 'PASSED' : 'REJECTED'}
                  </p>
                  <p className="tnum text-slate-300">
                    Corners: {planarRes.detected_charuco_corners} • Quadrants: {planarRes.quadrants_covered}/4 • Coverage:{' '}
                    {(planarRes.spatial_coverage_ratio * 100).toFixed(1)}% • RMS:{' '}
                    {planarRes.reprojection_error_px?.toFixed(3) ?? 'N/A'} px
                  </p>
                  {planarRes.warnings.map((w, idx) => (
                    <p key={idx} className="text-amber-300">
                      {w}
                    </p>
                  ))}
                  {planarRes.rejection_reason && (
                    <p className="text-red-300">{planarRes.rejection_reason}</p>
                  )}
                </div>
              )}
            </div>
          </div>
        </>
      )}

      {/* Client-Side 4-Point Planar Reference Calibration (for Guest Mode or Field Ad-Hoc Use) */}
      {core.auth.isGuest && (
        <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <span className="inline-flex items-center gap-1 rounded bg-primary/10 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider text-primary">
                <Crosshair className="h-3 w-3" /> Guest Local Calibration
              </span>
              <h3 className="mt-1 text-sm font-bold text-on-surface">
                4-Corner Physical Reference Homography
              </h3>
              <p className="text-xs text-on-surface-variant">
                Upload a snapshot showing a rectangular physical object of known dimensions (poster, mat, door frame, whiteboard) in the test plane, enter its width/height in cm, and tap its 4 corners in order: Top-Left → Top-Right → Bottom-Right → Bottom-Left.
              </p>
            </div>
          </div>

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <label className="text-xs text-on-surface-variant">
              Plane Type
              <select
                value={plane}
                onChange={(e) => setPlane(e.target.value as 'VERTICAL_PLANE' | 'GROUND_PLANE')}
                className={inputCls}
              >
                <option value="VERTICAL_PLANE">VERTICAL_PLANE (Height / VJ)</option>
                <option value="GROUND_PLANE">GROUND_PLANE (Broad / Sprint)</option>
              </select>
            </label>
            <label className="text-xs text-on-surface-variant">
              Target Width (cm)
              <input
                value={refWidthCm}
                onChange={(e) => setRefWidthCm(e.target.value)}
                type="number"
                step="0.5"
                className={inputCls + ' tnum'}
              />
            </label>
            <label className="text-xs text-on-surface-variant">
              Target Height / Depth (cm)
              <input
                value={refHeightCm}
                onChange={(e) => setRefHeightCm(e.target.value)}
                type="number"
                step="0.5"
                className={inputCls + ' tnum'}
              />
            </label>
          </div>

          <input
            type="file"
            accept="image/*"
            onChange={(e) => handleGuestImagePick(e.target.files?.[0])}
            className="block w-full rounded-xl border border-outline-variant bg-surface-container-low p-2.5 text-xs text-on-surface file:mr-3 file:rounded-lg file:border-0 file:bg-primary-container file:px-3 file:py-1.5 file:text-xs file:font-semibold file:text-white"
          />

          {guestImageUrl && (
            <div className="space-y-3">
              <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
                <span className="font-semibold text-primary">
                  {guestPoints.length < 4
                    ? `Tap corner ${guestPoints.length + 1}/4: ${CORNER_LABELS[guestPoints.length]}`
                    : 'All 4 corners marked — ready to solve homography'}
                </span>
                <button
                  type="button"
                  onClick={() => setGuestPoints([])}
                  className="inline-flex items-center gap-1 text-xs font-semibold text-on-surface-variant hover:text-on-surface"
                >
                  <RotateCcw className="h-3.5 w-3.5" /> Reset Points
                </button>
              </div>

              <div className="relative inline-block w-full overflow-hidden rounded-xl border border-outline-variant bg-black">
                <img
                  src={guestImageUrl}
                  alt="Reference calibration frame"
                  onLoad={(e) =>
                    setGuestImgSize({
                      w: e.currentTarget.naturalWidth,
                      h: e.currentTarget.naturalHeight,
                    })
                  }
                  onClick={handleGuestCornerClick}
                  className="max-h-[420px] w-full cursor-crosshair object-contain"
                />
                {guestImgSize &&
                  guestPoints.map((pt, idx) => (
                    <div
                      key={idx}
                      style={{
                        left: `${(pt.x / guestImgSize.w) * 100}%`,
                        top: `${(pt.y / guestImgSize.h) * 100}%`,
                      }}
                      className="pointer-events-none absolute -translate-x-1/2 -translate-y-1/2"
                    >
                      <span className="flex h-6 w-6 items-center justify-center rounded-full border-2 border-white bg-cyan-500 text-[10px] font-bold text-black shadow">
                        {idx + 1}
                      </span>
                    </div>
                  ))}
              </div>

              <button
                type="button"
                disabled={busy || guestPoints.length !== 4}
                onClick={() => void solveAndSaveGuestHomography()}
                className={btnPrimary + ' w-full sm:w-auto'}
              >
                Solve &amp; Save Local {plane === 'VERTICAL_PLANE' ? 'Vertical' : 'Ground'} Calibration
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
