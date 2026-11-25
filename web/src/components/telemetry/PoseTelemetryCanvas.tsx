import { useEffect, useRef } from 'react';
import {
  POSE_SKELETON_EDGES,
  PoseLandmarkIndex,
  computeJointAngleDeg,
} from '../../core/biomechanics/poseLandmarkIndex';
import type { LandmarkPoint3D, TestType } from '../../core/model/types';
import type { RefinedHeadCrown } from '../../core/biomechanics/heightCalculator';
import type { RefinedFingertipPoint } from '../../core/biomechanics/verticalJumpCalculator';

export interface CvOverlayOptions {
  showSkeleton: boolean;
  showAngles: boolean;
  showGuides: boolean;
  mirrorX: boolean;
  testType: TestType;
}

/** Head & foot triangle edges complementing POSE_SKELETON_EDGES for full CV visual fidelity. */
const EXTRA_OVERLAY_EDGES: ReadonlyArray<readonly [number, number]> = [
  [PoseLandmarkIndex.LEFT_EAR, PoseLandmarkIndex.LEFT_EYE],
  [PoseLandmarkIndex.LEFT_EYE, PoseLandmarkIndex.NOSE],
  [PoseLandmarkIndex.NOSE, PoseLandmarkIndex.RIGHT_EYE],
  [PoseLandmarkIndex.RIGHT_EYE, PoseLandmarkIndex.RIGHT_EAR],
  [PoseLandmarkIndex.LEFT_ANKLE, PoseLandmarkIndex.LEFT_FOOT_INDEX],
  [PoseLandmarkIndex.RIGHT_ANKLE, PoseLandmarkIndex.RIGHT_FOOT_INDEX],
  [PoseLandmarkIndex.LEFT_WRIST, PoseLandmarkIndex.LEFT_INDEX],
  [PoseLandmarkIndex.RIGHT_WRIST, PoseLandmarkIndex.RIGHT_INDEX],
];

function drawAnglePill(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  text: string,
  accent: string,
) {
  ctx.save();
  ctx.font = '600 10px Inter, system-ui, sans-serif';
  const metrics = ctx.measureText(text);
  const w = metrics.width + 10;
  const h = 16;
  const px = Math.max(4, x);
  const py = Math.max(18, y);

  ctx.fillStyle = 'rgba(15, 23, 42, 0.84)';
  ctx.strokeStyle = accent;
  ctx.lineWidth = 1;
  ctx.beginPath();
  // Safari < 15.4 doesn't have roundRect — fall back to a simple rect
  if (typeof ctx.roundRect === 'function') {
    ctx.roundRect(px, py - h / 2, w, h, 4);
  } else {
    ctx.rect(px, py - h / 2, w, h);
  }
  ctx.fill();
  ctx.stroke();

  ctx.fillStyle = '#f8fafc';
  ctx.textBaseline = 'middle';
  ctx.fillText(text, px + 5, py);
  ctx.restore();
}

export function PoseTelemetryCanvas({
  videoEl,
  landmarks,
  handTip,
  headCrown,
  sourceWidth,
  sourceHeight,
  options,
}: {
  videoEl: HTMLVideoElement | null;
  landmarks: LandmarkPoint3D[];
  handTip?: RefinedFingertipPoint | null;
  headCrown?: RefinedHeadCrown | null;
  sourceWidth?: number;
  sourceHeight?: number;
  options: CvOverlayOptions;
}) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const clientW = canvas.clientWidth || 640;
    const clientH = canvas.clientHeight || 360;
    const dpr = typeof window !== 'undefined' ? Math.min(2, window.devicePixelRatio || 1) : 1;
    const targetW = Math.max(1, Math.round(clientW * dpr));
    const targetH = Math.max(1, Math.round(clientH * dpr));

    if (canvas.width !== targetW) canvas.width = targetW;
    if (canvas.height !== targetH) canvas.height = targetH;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, clientW, clientH);

    // Compute exact object-contain letterbox rectangle so normalized coords align frame-accurately
    const vidW = (videoEl && videoEl.videoWidth > 0 ? videoEl.videoWidth : sourceWidth) || clientW;
    const vidH = (videoEl && videoEl.videoHeight > 0 ? videoEl.videoHeight : sourceHeight) || clientH;
    const scale = Math.min(clientW / vidW, clientH / vidH);
    const drawW = vidW * scale;
    const drawH = vidH * scale;
    const offsetX = (clientW - drawW) / 2;
    const offsetY = (clientH - drawH) / 2;

    const mapPt = (nx: number, ny: number) => ({
      x: offsetX + (options.mirrorX ? 1 - nx : nx) * drawW,
      y: offsetY + ny * drawH,
    });

    // Subtle calibration grid inside active video region when guides are enabled
    if (options.showGuides) {
      ctx.save();
      ctx.strokeStyle = 'rgba(148, 163, 184, 0.14)';
      ctx.lineWidth = 1;
      ctx.setLineDash([3, 4]);
      const step = 48;
      for (let gx = offsetX + step; gx < offsetX + drawW; gx += step) {
        ctx.beginPath();
        ctx.moveTo(gx, offsetY);
        ctx.lineTo(gx, offsetY + drawH);
        ctx.stroke();
      }
      for (let gy = offsetY + step; gy < offsetY + drawH; gy += step) {
        ctx.beginPath();
        ctx.moveTo(offsetX, gy);
        ctx.lineTo(offsetX + drawW, gy);
        ctx.stroke();
      }
      ctx.restore();
    }

    if (landmarks.length === 0) return;

    // 1. Skeleton bone vectors
    if (options.showSkeleton) {
      const allEdges = [...POSE_SKELETON_EDGES, ...EXTRA_OVERLAY_EDGES];
      ctx.save();
      ctx.lineCap = 'round';
      ctx.shadowColor = 'rgba(0, 242, 254, 0.55)';
      ctx.shadowBlur = 8;

      for (const [aIdx, bIdx] of allEdges) {
        const a = landmarks[aIdx];
        const b = landmarks[bIdx];
        if (!a || !b || a.visibility < 0.4 || b.visibility < 0.4) continue;
        const pa = mapPt(a.x, a.y);
        const pb = mapPt(b.x, b.y);
        const avgVis = (a.visibility + b.visibility) / 2;
        ctx.strokeStyle = avgVis >= 0.7 ? '#00f2fe' : 'rgba(56, 189, 248, 0.65)';
        ctx.lineWidth = avgVis >= 0.7 ? 2.8 : 2;
        ctx.beginPath();
        ctx.moveTo(pa.x, pa.y);
        ctx.lineTo(pb.x, pb.y);
        ctx.stroke();
      }
      ctx.restore();

      // Joint markers
      for (let i = 0; i < landmarks.length; i += 1) {
        const lm = landmarks[i];
        if (!lm || lm.visibility < 0.45) continue;
        // Skip extra face mesh dots except nose & ears for clean clinical view
        if (i >= 1 && i <= 10 && i !== PoseLandmarkIndex.LEFT_EAR && i !== PoseLandmarkIndex.RIGHT_EAR) {
          continue;
        }
        const p = mapPt(lm.x, lm.y);
        ctx.save();
        ctx.beginPath();
        ctx.arc(p.x, p.y, lm.visibility >= 0.75 ? 4.2 : 3.2, 0, Math.PI * 2);
        ctx.fillStyle = lm.visibility >= 0.75 ? '#10b981' : '#f59e0b';
        ctx.fill();
        ctx.lineWidth = 1.4;
        ctx.strokeStyle = '#ffffff';
        ctx.stroke();
        ctx.restore();
      }
    }

    // 2. Center of Mass & Biomechanical Guides
    if (options.showGuides) {
      const ls = landmarks[PoseLandmarkIndex.LEFT_SHOULDER];
      const rs = landmarks[PoseLandmarkIndex.RIGHT_SHOULDER];
      const lh = landmarks[PoseLandmarkIndex.LEFT_HIP];
      const rh = landmarks[PoseLandmarkIndex.RIGHT_HIP];

      if (
        ls &&
        rs &&
        lh &&
        rh &&
        Math.min(ls.visibility, rs.visibility, lh.visibility, rh.visibility) >= 0.45
      ) {
        const midS = mapPt((ls.x + rs.x) / 2, (ls.y + rs.y) / 2);
        const midH = mapPt((lh.x + rh.x) / 2, (lh.y + rh.y) / 2);
        const com = {
          x: midS.x * 0.42 + midH.x * 0.58,
          y: midS.y * 0.42 + midH.y * 0.58,
        };

        // Trunk vertical plumb line
        ctx.save();
        ctx.strokeStyle = 'rgba(251, 191, 36, 0.7)';
        ctx.lineWidth = 1.5;
        ctx.setLineDash([4, 3]);
        ctx.beginPath();
        ctx.moveTo(midH.x, midH.y);
        ctx.lineTo(midH.x, midS.y - 12);
        ctx.stroke();

        // CoM target reticle
        ctx.setLineDash([]);
        ctx.strokeStyle = '#f59e0b';
        ctx.lineWidth = 1.8;
        ctx.beginPath();
        ctx.arc(com.x, com.y, 9, 0, Math.PI * 2);
        ctx.stroke();
        ctx.beginPath();
        ctx.moveTo(com.x - 13, com.y);
        ctx.lineTo(com.x + 13, com.y);
        ctx.moveTo(com.x, com.y - 13);
        ctx.lineTo(com.x, com.y + 13);
        ctx.stroke();
        drawAnglePill(ctx, com.x + 14, com.y, 'CoM', '#f59e0b');
        ctx.restore();
      }

      // Ground contact foot highlights
      const footIds = [
        PoseLandmarkIndex.LEFT_HEEL,
        PoseLandmarkIndex.RIGHT_HEEL,
        PoseLandmarkIndex.LEFT_FOOT_INDEX,
        PoseLandmarkIndex.RIGHT_FOOT_INDEX,
      ];
      let lowestFootY = -1;
      for (const fId of footIds) {
        const fLm = landmarks[fId];
        if (!fLm || fLm.visibility < 0.5) continue;
        const fp = mapPt(fLm.x, fLm.y);
        lowestFootY = Math.max(lowestFootY, fp.y);
        ctx.save();
        ctx.strokeStyle = '#10b981';
        ctx.lineWidth = 1.8;
        ctx.beginPath();
        ctx.arc(fp.x, fp.y, 6, 0, Math.PI * 2);
        ctx.stroke();
        ctx.restore();
      }
      if (lowestFootY > 0) {
        ctx.save();
        ctx.strokeStyle = 'rgba(16, 185, 129, 0.5)';
        ctx.lineWidth = 1.4;
        ctx.setLineDash([6, 4]);
        ctx.beginPath();
        ctx.moveTo(offsetX, lowestFootY);
        ctx.lineTo(offsetX + drawW, lowestFootY);
        ctx.stroke();
        ctx.restore();
      }

      // Vertical Jump overhead reach line & fingertip halo
      if (options.testType === 'VERTICAL_JUMP') {
        const lIdx = landmarks[PoseLandmarkIndex.LEFT_INDEX];
        const rIdx = landmarks[PoseLandmarkIndex.RIGHT_INDEX];
        const poseTop =
          lIdx && rIdx && lIdx.visibility >= 0.45 && rIdx.visibility >= 0.45
            ? lIdx.y < rIdx.y
              ? lIdx
              : rIdx
            : lIdx && lIdx.visibility >= 0.45
              ? lIdx
              : rIdx && rIdx.visibility >= 0.45
                ? rIdx
                : null;
        const activeTip =
          handTip &&
          handTip.confidence >= 0.55 &&
          (!poseTop || handTip.yNormalized <= poseTop.y + 0.05)
            ? { x: handTip.xNormalized, y: handTip.yNormalized, refined: true }
            : poseTop
              ? { x: poseTop.x, y: poseTop.y, refined: false }
              : null;

        if (activeTip) {
          const tp = mapPt(activeTip.x, activeTip.y);
          ctx.save();
          ctx.strokeStyle = activeTip.refined ? '#34d399' : '#00f2fe';
          ctx.lineWidth = 1.6;
          ctx.setLineDash([5, 4]);
          ctx.beginPath();
          ctx.moveTo(offsetX, tp.y);
          ctx.lineTo(offsetX + drawW, tp.y);
          ctx.stroke();

          ctx.setLineDash([]);
          ctx.beginPath();
          ctx.arc(tp.x, tp.y, 10, 0, Math.PI * 2);
          ctx.stroke();
          drawAnglePill(
            ctx,
            Math.min(offsetX + drawW - 76, tp.x + 12),
            tp.y,
            activeTip.refined ? 'HAND TIP' : 'REACH',
            activeTip.refined ? '#34d399' : '#00f2fe',
          );
          ctx.restore();
        }
      }

      // Height head crown caliper line
      if (options.testType === 'HEIGHT' && headCrown) {
        const cp = mapPt(headCrown.xNormalized, headCrown.yNormalized);
        ctx.save();
        ctx.strokeStyle = '#00f2fe';
        ctx.lineWidth = 1.6;
        ctx.setLineDash([5, 3]);
        ctx.beginPath();
        ctx.moveTo(offsetX, cp.y);
        ctx.lineTo(offsetX + drawW, cp.y);
        ctx.stroke();
        ctx.setLineDash([]);
        ctx.beginPath();
        ctx.arc(cp.x, cp.y, 7, 0, Math.PI * 2);
        ctx.stroke();
        drawAnglePill(ctx, cp.x + 10, cp.y, 'CROWN', '#00f2fe');
        ctx.restore();
      }
    }

    // 3. Real-time Joint Flexion Angles (θ)
    if (options.showAngles) {
      const renderKnee = (
        hipIdx: number,
        kneeIdx: number,
        ankleIdx: number,
        label: string,
        xShift: number,
      ) => {
        const h = landmarks[hipIdx];
        const k = landmarks[kneeIdx];
        const a = landmarks[ankleIdx];
        if (!h || !k || !a || Math.min(h.visibility, k.visibility, a.visibility) < 0.5) return;
        const ph = mapPt(h.x, h.y);
        const pk = mapPt(k.x, k.y);
        const pa = mapPt(a.x, a.y);
        const deg = computeJointAngleDeg(ph, pk, pa);
        if (deg === null) return;
        drawAnglePill(ctx, pk.x + xShift, pk.y, `${label} ${deg.toFixed(0)}°`, '#00f2fe');
      };

      renderKnee(
        PoseLandmarkIndex.LEFT_HIP,
        PoseLandmarkIndex.LEFT_KNEE,
        PoseLandmarkIndex.LEFT_ANKLE,
        'L',
        10,
      );
      renderKnee(
        PoseLandmarkIndex.RIGHT_HIP,
        PoseLandmarkIndex.RIGHT_KNEE,
        PoseLandmarkIndex.RIGHT_ANKLE,
        'R',
        -56,
      );
    }
  }, [handTip, headCrown, landmarks, options, sourceHeight, sourceWidth, videoEl]);

  return (
    <canvas
      ref={canvasRef}
      className="pointer-events-none absolute inset-0 h-full w-full"
      aria-label="Live computer vision skeletal and kinematic overlay"
    />
  );
}
