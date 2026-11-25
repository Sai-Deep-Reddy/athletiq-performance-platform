import { describe, expect, it } from 'vitest';
import type { CalibrationQuality, FrameTimingMetadata, LandmarkPoint3D } from '../../model/types';
import { BiomechanicsAnalyzer } from '../biomechanicsAnalyzer';
import { BroadJumpCalculator } from '../broadJumpCalculator';
import { HeightCalculator } from '../heightCalculator';
import { OneEuroFilter, LandmarkSmoother3D } from '../oneEuroFilter';
import { CalibrationQualityGate, PlanarHomography, undistortPixelPoint } from '../planarHomography';
import { PoseLandmarkIndex } from '../poseLandmarkIndex';
import { SprintTimerCalculator } from '../sprintTimerCalculator';
import { VerticalJumpCalculator } from '../verticalJumpCalculator';

function blankPose(visibility = 0.95): LandmarkPoint3D[] {
  return Array.from({ length: PoseLandmarkIndex.LANDMARK_COUNT }, () => ({
    x: 0.5,
    y: 0.5,
    z: 0,
    visibility,
  }));
}

function makeTiming(fps = 60, frameCount = 30): FrameTimingMetadata {
  const frameDtMs = 1000 / fps;
  return {
    fpsActual: fps,
    frameDtMs,
    droppedFrames: 0,
    isVariableFrameRate: false,
    frameCount,
    durationMs: (frameCount - 1) * frameDtMs,
    sourceKind: 'SYNTHETIC_TEST',
    firstTimestampUs: 0,
    lastTimestampUs: Math.round((frameCount - 1) * frameDtMs * 1000),
  };
}

function makeCalibration(planeType: 'VERTICAL_PLANE' | 'GROUND_PLANE'): CalibrationQuality {
  return {
    calibrationProfileId: '00000000-0000-0000-0000-000000000099',
    planeType,
    method: 'OPENCV_CHARUCO',
    reprojectionErrorPx: 0.45,
    isActive: true,
    usedIntrinsics: true,
    independentDistanceVerified: true,
  };
}

describe('OneEuroFilter & PlanarHomography', () => {
  it('smooths noisy signal and rejects non-monotonic timestamps', () => {
    const filter = new OneEuroFilter(1.0, 0.007);
    const v0 = filter.filter(10, 0);
    const v1 = filter.filter(12, 16_667);
    expect(v0).toBe(10);
    expect(v1).toBeGreaterThan(10);
    expect(v1).toBeLessThan(12);
    expect(() => filter.filter(11, 16_667)).toThrow();
  });

  it('solves 4-point DLT homography with sub-pixel reprojection and supports Brown-Conrady undistortion', () => {
    const imgPts = [
      { x: 100, y: 100 },
      { x: 500, y: 100 },
      { x: 500, y: 500 },
      { x: 100, y: 500 },
    ];
    const planePts = [
      { x: 0, y: 200 },
      { x: 100, y: 200 },
      { x: 100, y: 0 },
      { x: 0, y: 0 },
    ];
    const homography = PlanarHomography.solveFromCorrespondences(imgPts, planePts);
    expect(homography.computeRmsReprojectionErrorPx(imgPts, planePts)).toBeLessThan(0.01);
    const mid = homography.imageToPlane({ x: 300, y: 300 });
    expect(mid.x).toBeCloseTo(50, 2);
    expect(mid.y).toBeCloseTo(100, 2);

    const undistorted = undistortPixelPoint(
      { x: 320, y: 240 },
      { cameraMatrix3x3: [1000, 0, 320, 0, 1000, 240, 0, 0, 1], distCoeffs: [-0.2, 0.05, 0, 0, 0] },
    );
    expect(undistorted.x).toBeCloseTo(320, 4);
    expect(undistorted.y).toBeCloseTo(240, 4);
  });

  it('enforces strict VERTICAL_PLANE vs GROUND_PLANE separation', () => {
    const wrong = CalibrationQualityGate.validate('HEIGHT', makeCalibration('GROUND_PLANE'));
    expect(wrong.kind).toBe('REJECTED');
  });

  it('tracks fast normalized [0..1] ballistic velocity in LandmarkSmoother3D without excessive lag', () => {
    const smoother = new LandmarkSmoother3D(PoseLandmarkIndex.LANDMARK_COUNT);
    for (let i = 0; i < 5; i += 1) {
      const lms = blankPose(0.95);
      lms[PoseLandmarkIndex.LEFT_HEEL].y = 0.8 + (i % 2 === 0 ? 0.002 : -0.002);
      smoother.smooth(lms, i * 16_667);
    }
    let lastY = 0.8;
    for (let i = 5; i < 12; i += 1) {
      const lms = blankPose(0.95);
      const targetY = 0.8 - (i - 4) * 0.04; // rapid upward velocity ~2.4 norm/s
      lms[PoseLandmarkIndex.LEFT_HEEL].y = targetY;
      const smoothed = smoother.smooth(lms, i * 16_667);
      lastY = smoothed[PoseLandmarkIndex.LEFT_HEEL].y;
    }
    expect(lastY).toBeLessThan(0.6);
  });

  it('handles OpenCV top-left (+Y downward) ChArUco VERTICAL_PLANE homographies accurately', () => {
    // OpenCV board convention: y=0 at top (py=0), y=500 at bottom (py=1000)
    const yDownHomography = PlanarHomography.solveFromCorrespondences(
      [
        { x: 0, y: 0 },
        { x: 1000, y: 0 },
        { x: 1000, y: 1000 },
        { x: 0, y: 1000 },
      ],
      [
        { x: 0, y: 0 },
        { x: 500, y: 0 },
        { x: 500, y: 500 },
        { x: 0, y: 500 },
      ],
    );

    const frames = [];
    for (let i = 0; i < 50; i += 1) {
      const lms = blankPose(0.95);
      let liftCm = 0;
      if (i >= 10 && i <= 45) {
        const u = (i - 10) / 35;
        liftCm = 4 * 45 * u * (1 - u);
      }
      const heelYNorm = (500 - (20 + liftCm)) / 500;
      const tipYNorm = (500 - (240 + liftCm)) / 500;
      lms[PoseLandmarkIndex.LEFT_HEEL] = { x: 0.45, y: heelYNorm, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_HEEL] = { x: 0.55, y: heelYNorm, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.LEFT_FOOT_INDEX] = { x: 0.43, y: heelYNorm, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX] = { x: 0.57, y: heelYNorm, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.LEFT_SHOULDER] = { x: 0.45, y: heelYNorm - 0.25, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_SHOULDER] = { x: 0.55, y: heelYNorm - 0.25, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.LEFT_HIP] = { x: 0.45, y: heelYNorm - 0.12, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_HIP] = { x: 0.55, y: heelYNorm - 0.12, z: 0, visibility: 0.95 };
      frames.push({
        timestampUs: Math.round(i * 16_666.667),
        imageWidth: 1000,
        imageHeight: 1000,
        landmarks: lms,
        refinedHandTip: {
          xNormalized: 0.55,
          yNormalized: tipYNorm,
          confidence: 0.93,
          side: 'RIGHT' as const,
        },
      });
    }

    const vj = new VerticalJumpCalculator().calculate(
      frames,
      makeCalibration('VERTICAL_PLANE'),
      yDownHomography,
      makeTiming(60, 50),
      Date.now(),
    );
    expect(vj.kind).toBe('VALID');
    if (vj.kind === 'VALID') {
      expect(vj.value).toBeCloseTo(45.0, 0);
      expect(vj.detail.flightTimeEstimate.flightHeightCm).toBeGreaterThan(35);
    }
  });
});

describe('HeightCalculator (C1)', () => {
  it('computes calibrated standing height in cm from upright frames and flags hair/headwear uncertainty', () => {
    // Vertical plane: image (0..1000 px) -> plane Y = 300 - 0.3*py cm (floor at py=900 -> 30 cm, crown at py=320 -> 204 cm => 174 cm)
    const homography = PlanarHomography.solveFromCorrespondences(
      [
        { x: 100, y: 100 },
        { x: 900, y: 100 },
        { x: 900, y: 900 },
        { x: 100, y: 900 },
      ],
      [
        { x: 0, y: 270 },
        { x: 240, y: 270 },
        { x: 240, y: 30 },
        { x: 0, y: 30 },
      ],
    );

    const samples = Array.from({ length: 15 }, (_, i) => {
      const lms = blankPose(0.95);
      lms[PoseLandmarkIndex.LEFT_HIP] = { x: 0.45, y: 0.55, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_HIP] = { x: 0.55, y: 0.55, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.LEFT_KNEE] = { x: 0.45, y: 0.72, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_KNEE] = { x: 0.55, y: 0.72, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.LEFT_ANKLE] = { x: 0.45, y: 0.88, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_ANKLE] = { x: 0.55, y: 0.88, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.LEFT_HEEL] = { x: 0.45, y: 0.9, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_HEEL] = { x: 0.55, y: 0.9, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.LEFT_FOOT_INDEX] = { x: 0.43, y: 0.9, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX] = { x: 0.57, y: 0.9, z: 0, visibility: 0.95 };
      return {
        timestampUs: i * 100_000,
        imageWidth: 1000,
        imageHeight: 1000,
        landmarks: lms,
        headCrown: {
          xNormalized: 0.5,
          yNormalized: 0.32,
          boundaryConfidence: 0.9,
          possibleHairOrHeadwear: false,
        },
      };
    });

    const outcome = new HeightCalculator().calculate(
      samples,
      makeCalibration('VERTICAL_PLANE'),
      homography,
      makeTiming(10, 15),
      Date.now(),
    );
    expect(outcome.kind).toBe('VALID');
    if (outcome.kind === 'VALID') {
      expect(outcome.value).toBeCloseTo(174.0, 1);
      expect(outcome.provenance.landmarkProvenance).toBe('MEDIAPIPE_POSE_HEAD_CROWN_REFINED');
    }
  });
});

describe('VerticalJumpCalculator (C2) & BiomechanicsAnalyzer (C4)', () => {
  it('computes reach-delta jump height, flight-time cross-check, and preserves provenance', () => {
    // 1 px = 0.5 cm vertical; top y=0 -> 500 cm, bottom y=1000 -> 0 cm
    const homography = PlanarHomography.solveFromCorrespondences(
      [
        { x: 0, y: 0 },
        { x: 1000, y: 0 },
        { x: 1000, y: 1000 },
        { x: 0, y: 1000 },
      ],
      [
        { x: 0, y: 500 },
        { x: 500, y: 500 },
        { x: 500, y: 0 },
        { x: 0, y: 0 },
      ],
    );

    // 45 cm jump -> flight time ~ 0.606 s (~36 frames at 60 FPS)
    const frames = [];
    for (let i = 0; i < 50; i += 1) {
      const lms = blankPose(0.95);
      let liftCm = 0;
      if (i >= 10 && i <= 45) {
        const u = (i - 10) / 35;
        liftCm = 4 * 45 * u * (1 - u);
      }
      const heelYNorm = (500 - (20 + liftCm)) / 500; // floor at 20 cm
      const tipYNorm = (500 - (240 + liftCm)) / 500; // standing reach 220 cm -> apex 265 cm (delta 45 cm)
      lms[PoseLandmarkIndex.LEFT_HEEL] = { x: 0.45, y: heelYNorm, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_HEEL] = { x: 0.55, y: heelYNorm, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.LEFT_FOOT_INDEX] = { x: 0.43, y: heelYNorm, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX] = { x: 0.57, y: heelYNorm, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.LEFT_SHOULDER] = { x: 0.45, y: heelYNorm - 0.25, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_SHOULDER] = { x: 0.55, y: heelYNorm - 0.25, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.LEFT_HIP] = { x: 0.45, y: heelYNorm - 0.12, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_HIP] = { x: 0.55, y: heelYNorm - 0.12, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.LEFT_KNEE] = { x: 0.45, y: heelYNorm - 0.06, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_KNEE] = { x: 0.55, y: heelYNorm - 0.06, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.LEFT_ANKLE] = { x: 0.45, y: heelYNorm - 0.01, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_ANKLE] = { x: 0.55, y: heelYNorm - 0.01, z: 0, visibility: 0.95 };

      frames.push({
        timestampUs: Math.round(i * 16_666.667),
        imageWidth: 1000,
        imageHeight: 1000,
        landmarks: lms,
        refinedHandTip: {
          xNormalized: 0.55,
          yNormalized: tipYNorm,
          confidence: 0.93,
          side: 'RIGHT' as const,
        },
      });
    }

    const vj = new VerticalJumpCalculator().calculate(
      frames,
      makeCalibration('VERTICAL_PLANE'),
      homography,
      makeTiming(60, 50),
      Date.now(),
    );
    expect(vj.kind).toBe('VALID');
    if (vj.kind === 'VALID') {
      expect(vj.value).toBeCloseTo(45.0, 0);
      expect(vj.provenance.landmarkProvenance).toBe('MEDIAPIPE_HAND_LANDMARKER_TIP');
      expect(vj.detail.flightTimeEstimate.flightHeightCm).toBeGreaterThan(35);

      const bio = new BiomechanicsAnalyzer().analyze(
        frames,
        makeCalibration('VERTICAL_PLANE'),
        makeTiming(60, 50),
        Date.now(),
        homography,
        vj.detail.takeoffTimestampUs,
        vj.detail.landingTimestampUs,
      );
      expect(bio.kind).toBe('VALID');
    }
  });

  it('computes COUNTERMOVEMENT_FLIGHT_TIME height from airborne timestamps with hands down and rejects STANDING_REACH_DELTA without overhead reach', () => {
    const homography = PlanarHomography.solveFromCorrespondences(
      [
        { x: 0, y: 0 },
        { x: 1000, y: 0 },
        { x: 1000, y: 1000 },
        { x: 0, y: 1000 },
      ],
      [
        { x: 0, y: 350 },
        { x: 350, y: 350 },
        { x: 350, y: 0 },
        { x: 0, y: 0 },
      ],
    );

    // 789 ms flight time at 30 FPS (~23.7 frames -> frames 15..38 airborne) => ~76.34 cm
    const frames = [];
    for (let i = 0; i < 60; i += 1) {
      const lms = blankPose(0.95);
      let liftCm = 0;
      if (i >= 15 && i <= 38) {
        const u = (i - 14) / 25;
        liftCm = 4 * 76 * u * (1 - u);
      }
      const heelYNorm = (350 - (20 + liftCm)) / 350;
      lms[PoseLandmarkIndex.LEFT_HEEL] = { x: 0.45, y: heelYNorm, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_HEEL] = { x: 0.55, y: heelYNorm, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.LEFT_FOOT_INDEX] = { x: 0.43, y: heelYNorm, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX] = { x: 0.57, y: heelYNorm, z: 0, visibility: 0.95 };
      // Hands kept down at hips (y > shoulder y)
      lms[PoseLandmarkIndex.LEFT_SHOULDER] = { x: 0.45, y: heelYNorm - 0.35, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_SHOULDER] = { x: 0.55, y: heelYNorm - 0.35, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_WRIST] = { x: 0.55, y: heelYNorm - 0.2, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_INDEX] = { x: 0.55, y: heelYNorm - 0.18, z: 0, visibility: 0.95 };

      frames.push({
        timestampUs: Math.round(i * 33_333.333),
        imageWidth: 1000,
        imageHeight: 1000,
        landmarks: lms,
      });
    }

    const reachRes = new VerticalJumpCalculator().calculate(
      frames,
      makeCalibration('VERTICAL_PLANE'),
      homography,
      makeTiming(30, 60),
      Date.now(),
      'STANDING_REACH_DELTA',
    );
    expect(reachRes.kind).toBe('INVALID_ATTEMPT');

    const cmjRes = new VerticalJumpCalculator().calculate(
      frames,
      makeCalibration('VERTICAL_PLANE'),
      homography,
      makeTiming(30, 60),
      Date.now(),
      'COUNTERMOVEMENT_FLIGHT_TIME',
    );
    expect(cmjRes.kind).toBe('VALID');
    if (cmjRes.kind === 'VALID') {
      expect(cmjRes.detail.protocolUsed).toBe('COUNTERMOVEMENT_FLIGHT_TIME');
      expect(cmjRes.detail.flightTimeEstimate.flightTimeSec).toBeGreaterThan(0.75);
      expect(cmjRes.detail.flightTimeEstimate.flightTimeSec).toBeLessThan(0.84);
      expect(cmjRes.value).toBeGreaterThan(70);
      expect(cmjRes.value).toBeLessThan(86);
    }
  });

  it('ignores resting opposite-hand tip detections below an overhead pose arm and tracks pre-launch stance shifts', () => {
    const homography = PlanarHomography.solveFromCorrespondences(
      [
        { x: 0, y: 0 },
        { x: 1000, y: 0 },
        { x: 1000, y: 1000 },
        { x: 0, y: 1000 },
      ],
      [
        { x: 0, y: 500 },
        { x: 500, y: 500 },
        { x: 500, y: 0 },
        { x: 0, y: 0 },
      ],
    );

    const frames = [];
    for (let i = 0; i < 65; i += 1) {
      const lms = blankPose(0.95);
      // Subject steps back +5.5 cm during initial standing before settling at frame 12..24, then jumps ~0.45s (25 cm).
      const stanceBaseCm = i < 12 ? 20 : 25.5;
      let liftCm = 0;
      if (i >= 25 && i <= 51) {
        const u = (i - 24) / 28;
        liftCm = 4 * 25 * u * (1 - u);
      }
      const floorCm = stanceBaseCm + liftCm;
      const footYNorm = (500 - floorCm) / 500;
      const shoulderYNorm = (500 - (floorCm + 140)) / 500;
      const rightReachYNorm = (500 - (floorCm + 210)) / 500; // overhead right arm holding target
      const leftHipHandYNorm = (500 - (floorCm + 75)) / 500; // resting left arm down at hip

      lms[PoseLandmarkIndex.LEFT_HEEL] = { x: 0.45, y: footYNorm, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_HEEL] = { x: 0.55, y: footYNorm, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.LEFT_FOOT_INDEX] = { x: 0.43, y: footYNorm, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX] = { x: 0.57, y: footYNorm, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.LEFT_SHOULDER] = { x: 0.45, y: shoulderYNorm, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_SHOULDER] = { x: 0.55, y: shoulderYNorm, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.LEFT_WRIST] = { x: 0.44, y: leftHipHandYNorm, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.LEFT_INDEX] = { x: 0.44, y: leftHipHandYNorm, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_INDEX] = { x: 0.56, y: rightReachYNorm, z: 0, visibility: 0.95 };

      frames.push({
        timestampUs: Math.round(i * 16_666.667),
        imageWidth: 1000,
        imageHeight: 1000,
        landmarks: lms,
        // HandLandmarker detects ONLY the hanging resting LEFT hand at hip level
        refinedHandTip: {
          xNormalized: 0.44,
          yNormalized: leftHipHandYNorm,
          confidence: 0.98,
          side: 'LEFT' as const,
        },
      });
    }

    const res = new VerticalJumpCalculator().calculate(
      frames,
      makeCalibration('VERTICAL_PLANE'),
      homography,
      makeTiming(60, 65),
      Date.now(),
      'STANDING_REACH_DELTA',
    );
    expect(res.kind).toBe('VALID');
    if (res.kind === 'VALID') {
      expect(res.value).toBeCloseTo(25.0, 0);
      expect(res.provenance.landmarkProvenance).toBe('MEDIAPIPE_POSE_INDEX_FALLBACK');
      expect(res.detail.dominantSide).toBe('RIGHT');
      expect(res.detail.flightTimeEstimate.flightHeightCm).toBeCloseTo(24.8, 0);
    }
  });
});

describe('BroadJumpCalculator (SAI Khelo India Default) & SprintTimerCalculator', () => {
  it('measures rear-heel distance under SAI_KHELO_INDIA_HEEL and invalidates backward hand touch', () => {
    // Ground plane: image px (0..1000) -> X = 0.4*px - 40 cm (px=100 is takeoff line 0 cm; px=600 is 200 cm)
    const groundH = PlanarHomography.solveFromCorrespondences(
      [
        { x: 100, y: 400 },
        { x: 900, y: 400 },
        { x: 900, y: 800 },
        { x: 100, y: 800 },
      ],
      [
        { x: 0, y: 0 },
        { x: 320, y: 0 },
        { x: 320, y: 160 },
        { x: 0, y: 160 },
      ],
    );

    const buildAttempt = (includeBackwardHandTouch: boolean) => {
      const samples = [];
      for (let i = 0; i < 30; i += 1) {
        const lms = blankPose(0.95);
        let heelX = 80; // -8 cm behind line
        let toeX = 95; // -2 cm behind line
        let footY = 0.7;
        if (i >= 8 && i <= 15) {
          const u = (i - 8) / 7;
          heelX = 80 + u * 470; // lands at px=550 -> (550*0.4 - 40) = 180 cm
          toeX = heelX + 20;
          footY = 0.7 - 0.08 * Math.sin(u * Math.PI);
        } else if (i > 15) {
          heelX = 550; // 180 cm
          toeX = 570;
          footY = 0.7;
        }

        lms[PoseLandmarkIndex.LEFT_HEEL] = { x: heelX / 1000, y: footY, z: 0, visibility: 0.95 };
        lms[PoseLandmarkIndex.RIGHT_HEEL] = { x: heelX / 1000, y: footY, z: 0, visibility: 0.95 };
        lms[PoseLandmarkIndex.LEFT_FOOT_INDEX] = { x: toeX / 1000, y: footY, z: 0, visibility: 0.95 };
        lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX] = { x: toeX / 1000, y: footY, z: 0, visibility: 0.95 };

        if (includeBackwardHandTouch && i === 18) {
          lms[PoseLandmarkIndex.LEFT_WRIST] = { x: 0.42, y: 0.7, z: 0, visibility: 0.95 };
        }

        samples.push({
          timestampUs: Math.round(i * 33_333),
          imageWidth: 1000,
          imageHeight: 1000,
          landmarks: lms,
        });
      }
      return samples;
    };

    const cleanRes = new BroadJumpCalculator().calculate(
      buildAttempt(false),
      makeCalibration('GROUND_PLANE'),
      groundH,
      makeTiming(30, 30),
      Date.now(),
    );
    expect(cleanRes.kind).toBe('VALID');
    if (cleanRes.kind === 'VALID') {
      expect(cleanRes.value).toBeCloseTo(180, 0);
      expect(cleanRes.detail.protocolUsed).toBe('SAI_KHELO_INDIA_HEEL');
    }

    const foulRes = new BroadJumpCalculator().calculate(
      buildAttempt(true),
      makeCalibration('GROUND_PLANE'),
      groundH,
      makeTiming(30, 30),
      Date.now(),
      'SAI_KHELO_INDIA_HEEL',
    );
    expect(foulRes.kind).toBe('INVALID_ATTEMPT');
    if (foulRes.kind === 'INVALID_ATTEMPT') {
      expect(foulRes.requiresExaminerReview).toBe(true);
    }
  });

  it('interpolates sprint line crossing and computes split time & speed', () => {
    // Ground plane: X = px * 1.2 - 120 cm (px=100 is 0 cm, px=933.33 is 1000 cm = 10 m)
    const groundH = PlanarHomography.solveFromCorrespondences(
      [
        { x: 100, y: 400 },
        { x: 933.3333, y: 400 },
        { x: 933.3333, y: 800 },
        { x: 100, y: 800 },
      ],
      [
        { x: 0, y: 0 },
        { x: 1000, y: 0 },
        { x: 1000, y: 400 },
        { x: 0, y: 400 },
      ],
    );

    // 10m in 2.0s (5 m/s) across 21 frames @ 100ms
    const samples = Array.from({ length: 25 }, (_, i) => {
      const lms = blankPose(0.95);
      const xCm = -50 + i * 50; // frame 1 -> 0 cm, frame 21 -> 1000 cm => 2.00 s
      const px = (xCm + 120) / 1.2 / 1000;
      lms[PoseLandmarkIndex.LEFT_HIP] = { x: px, y: 0.5, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_HIP] = { x: px, y: 0.5, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.LEFT_SHOULDER] = { x: px, y: 0.35, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_SHOULDER] = { x: px, y: 0.35, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.LEFT_ANKLE] = { x: px, y: 0.7, z: 0, visibility: 0.95 };
      lms[PoseLandmarkIndex.RIGHT_ANKLE] = { x: px, y: 0.7, z: 0, visibility: 0.95 };
      return {
        timestampUs: i * 100_000,
        imageWidth: 1000,
        imageHeight: 1000,
        landmarks: lms,
      };
    });

    const sprint = new SprintTimerCalculator().calculate(
      samples,
      10,
      makeCalibration('GROUND_PLANE'),
      groundH,
      makeTiming(10, 25),
      Date.now(),
    );
    expect(sprint.kind).toBe('VALID');
    if (sprint.kind === 'VALID') {
      expect(sprint.value).toBeCloseTo(2.0, 2);
      expect(sprint.detail.averageSpeedMps).toBeCloseTo(5.0, 2);
    }
  });
});
