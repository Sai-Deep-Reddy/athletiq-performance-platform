import type {
  CalibrationQuality,
  FrameTimingMetadata,
  LandmarkPoint3D,
  MeasurementOutcome,
} from '../model/types';
import { CalibrationQualityGate, PlanarHomography } from './planarHomography';
import { PoseLandmarkIndex, toPixelPoint } from './poseLandmarkIndex';

export interface SprintFrameSample {
  timestampUs: number;
  imageWidth: number;
  imageHeight: number;
  landmarks: LandmarkPoint3D[];
}

export interface SprintMeasurement {
  splitTimeSec: number;
  distanceMeters: number;
  averageSpeedMps: number;
  startTimestampUs: number;
  finishTimestampUs: number;
}

export class SprintTimerCalculator {
  private readonly minLandmarkVisibility = 0.55;
  private readonly preStartToleranceCm = 5.0;
  private readonly algorithmVersion = 'sprint-d-web-v1.0';

  calculate(
    samples: SprintFrameSample[],
    targetDistanceMeters: number,
    calibration: CalibrationQuality | null | undefined,
    groundHomography: PlanarHomography | null | undefined,
    timing: FrameTimingMetadata | null | undefined,
    recordedAtEpochMs: number,
  ): MeasurementOutcome<SprintMeasurement> {
    if (!Number.isFinite(targetDistanceMeters) || targetDistanceMeters <= 0) {
      throw new Error('targetDistanceMeters must be > 0');
    }

    const calCheck = CalibrationQualityGate.validate('SPRINT', calibration);
    if (calCheck.kind === 'REJECTED') {
      return {
        kind: 'CALIBRATION_REQUIRED',
        requiredPlane: calCheck.requiredPlane,
        reason: calCheck.reason,
      };
    }
    if (!groundHomography) {
      return {
        kind: 'CALIBRATION_REQUIRED',
        requiredPlane: 'GROUND_PLANE',
        reason: 'Ground plane homography transform is missing',
      };
    }
    if (!timing || samples.length < 4) {
      return {
        kind: 'INSUFFICIENT_DATA',
        reason: 'Insufficient frames or timing metadata for sprint timing',
      };
    }

    for (let i = 1; i < samples.length; i += 1) {
      if (samples[i].timestampUs <= samples[i - 1].timestampUs) {
        return {
          kind: 'INVALID_ATTEMPT',
          reasons: ['Non-monotonic frame timestamps detected during sprint capture'],
          requiresExaminerReview: true,
        };
      }
    }

    const finishLineCm = targetDistanceMeters * 100;
    const track: Array<{ timestampUs: number; xCm: number }> = [];
    const visibilities: number[] = [];

    for (const sample of samples) {
      const lms = sample.landmarks;
      if (lms.length !== PoseLandmarkIndex.LANDMARK_COUNT) continue;
      const feet = [
        lms[PoseLandmarkIndex.LEFT_ANKLE],
        lms[PoseLandmarkIndex.RIGHT_ANKLE],
        lms[PoseLandmarkIndex.LEFT_HEEL],
        lms[PoseLandmarkIndex.RIGHT_HEEL],
      ].filter((lm) => lm.visibility >= this.minLandmarkVisibility);
      const torso = [
        lms[PoseLandmarkIndex.LEFT_HIP],
        lms[PoseLandmarkIndex.RIGHT_HIP],
        lms[PoseLandmarkIndex.LEFT_SHOULDER],
        lms[PoseLandmarkIndex.RIGHT_SHOULDER],
      ].filter((lm) => lm.visibility >= this.minLandmarkVisibility);

      if (torso.length < 2 || feet.length === 0) continue;

      const avgTorsoXNorm = torso.reduce((s, p) => s + p.x, 0) / torso.length;
      const groundYNorm = feet.reduce((s, p) => s + p.y, 0) / feet.length;
      const planePt = groundHomography.imageToPlane(
        toPixelPoint({ x: avgTorsoXNorm, y: groundYNorm }, sample.imageWidth, sample.imageHeight),
      );
      track.push({ timestampUs: sample.timestampUs, xCm: planePt.x });
      visibilities.push(torso.reduce((s, p) => s + p.visibility, 0) / torso.length);
    }

    if (track.length < 4) {
      return {
        kind: 'INSUFFICIENT_DATA',
        reason: 'Insufficient visible sprint frames',
      };
    }

    if (track[0].xCm > this.preStartToleranceCm) {
      return {
        kind: 'INVALID_ATTEMPT',
        reasons: [
          `Athlete started ahead of start line (${track[0].xCm.toFixed(1)} cm > 0 cm)`,
        ],
        requiresExaminerReview: true,
      };
    }

    const startUs = this.interpolateLineCrossingUs(track, 0);
    const finishUs = this.interpolateLineCrossingUs(track, finishLineCm);
    if (startUs === null || finishUs === null || finishUs <= startUs) {
      return {
        kind: 'INSUFFICIENT_DATA',
        reason: `Could not verify both start (0 m) and finish (${targetDistanceMeters} m) line crossings`,
      };
    }

    const splitTimeSec = (finishUs - startUs) / 1_000_000;
    const averageSpeedMps = targetDistanceMeters / splitTimeSec;
    const timingUncertaintySec = timing.frameDtMs / 1000;
    const meanConf = visibilities.reduce((a, b) => a + b, 0) / visibilities.length;

    return {
      kind: 'VALID',
      value: splitTimeSec,
      unit: 'SECONDS',
      uncertainty: timingUncertaintySec,
      confidenceScore: Math.max(0.1, Math.min(0.99, meanConf)),
      validityStatus: timing.isVariableFrameRate ? 'FLAGGED' : 'VALID',
      requiresExaminerReview: timing.isVariableFrameRate,
      reviewReasons: timing.isVariableFrameRate
        ? [`Variable frame rate or ${timing.droppedFrames} dropped frame(s) detected.`]
        : [],
      provenance: {
        landmarkProvenance: 'MEDIAPIPE_POSE_HEEL_ONLY',
        algorithmVersion: this.algorithmVersion,
        calibrationProfileId: calCheck.calibration.calibrationProfileId,
        timing,
        recordedAtEpochMs,
        warnings: calCheck.warnings,
      },
      detail: {
        splitTimeSec,
        distanceMeters: targetDistanceMeters,
        averageSpeedMps,
        startTimestampUs: startUs,
        finishTimestampUs: finishUs,
      },
    };
  }

  private interpolateLineCrossingUs(
    track: Array<{ timestampUs: number; xCm: number }>,
    targetXCm: number,
  ): number | null {
    for (let i = 1; i < track.length; i += 1) {
      const prev = track[i - 1];
      const curr = track[i];
      if (prev.xCm < targetXCm && curr.xCm >= targetXCm) {
        const span = curr.xCm - prev.xCm;
        const frac = span > 1e-6 ? (targetXCm - prev.xCm) / span : 0;
        return Math.round(prev.timestampUs + frac * (curr.timestampUs - prev.timestampUs));
      }
    }
    return null;
  }
}
