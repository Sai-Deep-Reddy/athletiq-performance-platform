import type {
  CalibrationQuality,
  FrameTimingMetadata,
  LandmarkPoint3D,
  MeasurementOutcome,
} from '../model/types';
import { CalibrationQualityGate, PlanarHomography } from './planarHomography';
import {
  PoseLandmarkIndex,
  computeJointAngleDeg,
  interquartileRange,
  isLandmarkVisible,
  median,
  toPixelPoint,
} from './poseLandmarkIndex';

export interface RefinedHeadCrown {
  xNormalized: number;
  yNormalized: number;
  boundaryConfidence: number;
  possibleHairOrHeadwear: boolean;
}

export interface HeightFrameSample {
  timestampUs: number;
  imageWidth: number;
  imageHeight: number;
  landmarks: LandmarkPoint3D[];
  headCrown: RefinedHeadCrown | null;
}

export interface HeightMeasurement {
  heightCm: number;
  medianCrownPlaneYCm: number;
  medianFloorPlaneYCm: number;
  interQuartileRangeCm: number;
  validFrameCount: number;
  possibleHairOrHeadwear: boolean;
}

export class HeightCalculator {
  private readonly minLandmarkVisibility = 0.65;
  private readonly frameEdgeMarginNorm = 0.02;
  private readonly minKneeAngleDeg = 165.0;
  private readonly maxHeelHeightDiffCm = 2.0;
  private readonly maxFootPitchDiffCm = 3.5;
  private readonly maxCrownVelocityCmPerSec = 4.0;
  private readonly minValidFrames = 10;
  private readonly minStableDurationMs = 800;
  private readonly algorithmVersion = 'height-c1-web-v1.0';

  calculate(
    samples: HeightFrameSample[],
    calibration: CalibrationQuality | null | undefined,
    verticalHomography: PlanarHomography | null | undefined,
    timing: FrameTimingMetadata | null | undefined,
    recordedAtEpochMs: number,
  ): MeasurementOutcome<HeightMeasurement> {
    const calCheck = CalibrationQualityGate.validate('HEIGHT', calibration);
    if (calCheck.kind === 'REJECTED') {
      return {
        kind: 'CALIBRATION_REQUIRED',
        requiredPlane: calCheck.requiredPlane,
        reason: calCheck.reason,
      };
    }
    if (!verticalHomography) {
      return {
        kind: 'CALIBRATION_REQUIRED',
        requiredPlane: 'VERTICAL_PLANE',
        reason: 'Vertical plane homography transform is missing',
      };
    }
    if (!timing || samples.length < this.minValidFrames) {
      return {
        kind: 'INSUFFICIENT_DATA',
        reason: `At least ${this.minValidFrames} pose frames and timing metadata are required (got ${samples.length})`,
      };
    }

    for (let i = 1; i < samples.length; i += 1) {
      if (samples[i].timestampUs <= samples[i - 1].timestampUs) {
        return {
          kind: 'INVALID_ATTEMPT',
          reasons: ['Non-monotonic frame timestamps detected during height capture'],
          requiresExaminerReview: true,
        };
      }
    }

    const rejectionReasons = new Set<string>();
    const perFrameHeightsCm: number[] = [];
    const perFrameCrownsCm: number[] = [];
    const perFrameFloorsCm: number[] = [];
    const perFrameTimestampsUs: number[] = [];
    const boundaryConfidences: number[] = [];
    let anyHairOrHeadwear = false;

    for (const sample of samples) {
      if (sample.landmarks.length !== PoseLandmarkIndex.LANDMARK_COUNT) {
        rejectionReasons.add('Incomplete pose landmark vector');
        continue;
      }
      const crown = sample.headCrown;
      if (!crown) {
        rejectionReasons.add('Head crown boundary could not be isolated');
        continue;
      }

      const lms = sample.landmarks;
      const reqIndices = [
        PoseLandmarkIndex.LEFT_HIP,
        PoseLandmarkIndex.RIGHT_HIP,
        PoseLandmarkIndex.LEFT_KNEE,
        PoseLandmarkIndex.RIGHT_KNEE,
        PoseLandmarkIndex.LEFT_ANKLE,
        PoseLandmarkIndex.RIGHT_ANKLE,
        PoseLandmarkIndex.LEFT_HEEL,
        PoseLandmarkIndex.RIGHT_HEEL,
        PoseLandmarkIndex.LEFT_FOOT_INDEX,
        PoseLandmarkIndex.RIGHT_FOOT_INDEX,
      ];
      if (!reqIndices.every((idx) => isLandmarkVisible(lms[idx], this.minLandmarkVisibility, this.frameEdgeMarginNorm))) {
        rejectionReasons.add('Required lower-body landmarks clipped or low visibility');
        continue;
      }
      if (
        crown.yNormalized < this.frameEdgeMarginNorm ||
        crown.yNormalized > 1 - this.frameEdgeMarginNorm ||
        crown.boundaryConfidence < 0.5
      ) {
        rejectionReasons.add('Head crown clipped near frame edge or low boundary confidence');
        continue;
      }

      const lKneeDeg = computeJointAngleDeg(
        toPixelPoint(lms[PoseLandmarkIndex.LEFT_HIP], sample.imageWidth, sample.imageHeight),
        toPixelPoint(lms[PoseLandmarkIndex.LEFT_KNEE], sample.imageWidth, sample.imageHeight),
        toPixelPoint(lms[PoseLandmarkIndex.LEFT_ANKLE], sample.imageWidth, sample.imageHeight),
      );
      const rKneeDeg = computeJointAngleDeg(
        toPixelPoint(lms[PoseLandmarkIndex.RIGHT_HIP], sample.imageWidth, sample.imageHeight),
        toPixelPoint(lms[PoseLandmarkIndex.RIGHT_KNEE], sample.imageWidth, sample.imageHeight),
        toPixelPoint(lms[PoseLandmarkIndex.RIGHT_ANKLE], sample.imageWidth, sample.imageHeight),
      );
      if (
        lKneeDeg === null ||
        rKneeDeg === null ||
        lKneeDeg < this.minKneeAngleDeg ||
        rKneeDeg < this.minKneeAngleDeg
      ) {
        rejectionReasons.add('Subject knees are bent; upright standing posture required');
        continue;
      }

      const crownPlane = verticalHomography.imageToPlane({
        x: crown.xNormalized * sample.imageWidth,
        y: crown.yNormalized * sample.imageHeight,
      });
      const lHeelPlane = verticalHomography.imageToPlane(
        toPixelPoint(lms[PoseLandmarkIndex.LEFT_HEEL], sample.imageWidth, sample.imageHeight),
      );
      const rHeelPlane = verticalHomography.imageToPlane(
        toPixelPoint(lms[PoseLandmarkIndex.RIGHT_HEEL], sample.imageWidth, sample.imageHeight),
      );
      const lToePlane = verticalHomography.imageToPlane(
        toPixelPoint(lms[PoseLandmarkIndex.LEFT_FOOT_INDEX], sample.imageWidth, sample.imageHeight),
      );
      const rToePlane = verticalHomography.imageToPlane(
        toPixelPoint(lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX], sample.imageWidth, sample.imageHeight),
      );

      if (Math.abs(lHeelPlane.y - rHeelPlane.y) > this.maxHeelHeightDiffCm) {
        rejectionReasons.add('Heels are not bilaterally level on floor datum');
        continue;
      }
      if (
        Math.abs(lHeelPlane.y - lToePlane.y) > this.maxFootPitchDiffCm ||
        Math.abs(rHeelPlane.y - rToePlane.y) > this.maxFootPitchDiffCm
      ) {
        rejectionReasons.add('Feet are not flat on floor');
        continue;
      }

      const floorPlaneY = (lHeelPlane.y + rHeelPlane.y) / 2;
      const heightCm = Math.abs(crownPlane.y - floorPlaneY);
      if (!Number.isFinite(heightCm) || heightCm < 50 || heightCm > 260) {
        rejectionReasons.add('Measured height outside physical plausibility bounds (50–260 cm)');
        continue;
      }

      perFrameHeightsCm.push(heightCm);
      perFrameCrownsCm.push(crownPlane.y);
      perFrameFloorsCm.push(floorPlaneY);
      perFrameTimestampsUs.push(sample.timestampUs);
      boundaryConfidences.push(crown.boundaryConfidence);
      if (crown.possibleHairOrHeadwear) {
        anyHairOrHeadwear = true;
      }
    }

    if (perFrameHeightsCm.length < this.minValidFrames) {
      return {
        kind: 'INSUFFICIENT_DATA',
        reason:
          Array.from(rejectionReasons)[0] ??
          `Only ${perFrameHeightsCm.length} valid upright frames detected`,
      };
    }

    const durationMs =
      (perFrameTimestampsUs[perFrameTimestampsUs.length - 1] - perFrameTimestampsUs[0]) / 1000;
    if (durationMs < this.minStableDurationMs) {
      return {
        kind: 'INSUFFICIENT_DATA',
        reason: `Hold stationary upright posture for at least ${this.minStableDurationMs} ms`,
      };
    }

    const crownSpeeds: number[] = [];
    for (let i = 1; i < perFrameCrownsCm.length; i += 1) {
      const dtSec = (perFrameTimestampsUs[i] - perFrameTimestampsUs[i - 1]) / 1_000_000;
      if (dtSec > 0) {
        crownSpeeds.push(Math.abs(perFrameCrownsCm[i] - perFrameCrownsCm[i - 1]) / dtSec);
      }
    }
    if (crownSpeeds.length > 0 && median(crownSpeeds) > this.maxCrownVelocityCmPerSec) {
      return {
        kind: 'INVALID_ATTEMPT',
        reasons: ['Subject was moving during standing height capture'],
        requiresExaminerReview: true,
      };
    }

    const heightCm = median(perFrameHeightsCm);
    const iqrCm = interquartileRange(perFrameHeightsCm);
    const meanBoundaryConf =
      boundaryConfidences.reduce((a, b) => a + b, 0) / boundaryConfidences.length;
    const uncertainty = Math.max(
      0.3,
      iqrCm / 2 + calCheck.calibration.reprojectionErrorPx * 0.25 + (anyHairOrHeadwear ? 1.0 : 0),
    );

    const reviewReasons: string[] = [];
    if (anyHairOrHeadwear) {
      reviewReasons.push('Possible hair volume or headwear above cranial landmark; verify crown line.');
    }
    if (iqrCm > 2.5) {
      reviewReasons.push(`Frame-to-frame height spread (IQR ${iqrCm.toFixed(2)} cm) is elevated.`);
    }

    return {
      kind: 'VALID',
      value: heightCm,
      unit: 'CENTIMETERS',
      uncertainty,
      confidenceScore: Math.max(0.1, Math.min(0.99, meanBoundaryConf)),
      validityStatus: reviewReasons.length > 0 ? 'FLAGGED' : 'VALID',
      requiresExaminerReview: reviewReasons.length > 0,
      reviewReasons,
      provenance: {
        landmarkProvenance: 'MEDIAPIPE_POSE_HEAD_CROWN_REFINED',
        algorithmVersion: this.algorithmVersion,
        calibrationProfileId: calCheck.calibration.calibrationProfileId,
        timing,
        recordedAtEpochMs,
        warnings: calCheck.warnings,
      },
      detail: {
        heightCm,
        medianCrownPlaneYCm: median(perFrameCrownsCm),
        medianFloorPlaneYCm: median(perFrameFloorsCm),
        interQuartileRangeCm: iqrCm,
        validFrameCount: perFrameHeightsCm.length,
        possibleHairOrHeadwear: anyHairOrHeadwear,
      },
    };
  }
}
