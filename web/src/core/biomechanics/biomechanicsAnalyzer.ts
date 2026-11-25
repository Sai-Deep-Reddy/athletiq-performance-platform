import type {
  CalibrationQuality,
  FrameTimingMetadata,
  LandmarkPoint3D,
  MeasurementOutcome,
  Point2D,
} from '../model/types';
import type { PlanarHomography } from './planarHomography';
import {
  PoseLandmarkIndex,
  computeJointAngleDeg,
  isLandmarkVisible,
  toPixelPoint,
} from './poseLandmarkIndex';

export interface BiomechanicsFrameSample {
  timestampUs: number;
  imageWidth: number;
  imageHeight: number;
  landmarks: LandmarkPoint3D[];
}

export interface FrameKinematics {
  timestampUs: number;
  leftKneeDeg: number | null;
  rightKneeDeg: number | null;
  leftHipDeg: number | null;
  rightHipDeg: number | null;
  trunkLeanDeg: number | null;
  leftShoulderElevationDeg: number | null;
  rightShoulderElevationDeg: number | null;
  leftAnkleDeg: number | null;
  rightAnkleDeg: number | null;
  comProxyNorm: Point2D | null;
  comProxyPlaneCm: Point2D | null;
  lateralBalanceOffsetNorm: number | null;
}

export type PerformanceIndicatorCode =
  | 'BALANCED_BILATERAL_KNEE_FLEXION'
  | 'ASYMMETRIC_KNEE_FLEXION_OBSERVED'
  | 'FULL_ARM_SWING_OBSERVED'
  | 'LIMITED_ARM_SWING_OBSERVED'
  | 'CONTROLLED_LANDING_FLEXION'
  | 'STIFF_LANDING_POSTURE_OBSERVED'
  | 'BALANCED_TRUNK_ALIGNMENT'
  | 'PRONOUNCED_TRUNK_LEAN_OBSERVED';

export interface PerformanceIndicator {
  code: PerformanceIndicatorCode;
  summary: StringOrText;
  observedValue: number;
  unitLabel: string;
}
type StringOrText = string;

export interface JumpKeyFrames {
  initialStanceIndex: number;
  deepestCountermovementIndex: number | null;
  takeoffIndex: number | null;
  apexIndex: number | null;
  landingIndex: number | null;
}

export interface BiomechanicsReport {
  frameKinematics: FrameKinematics[];
  keyFrames: JumpKeyFrames;
  minKneeFlexionDeg: number | null;
  maxTrunkLeanDeg: number | null;
  maxShoulderElevationDeg: number | null;
  bilateralKneeAsymmetryDeg: number | null;
  indicators: PerformanceIndicator[];
}

export class BiomechanicsAnalyzer {
  private readonly minVisibility = 0.55;
  private readonly asymmetryWarningDeg = 12.0;
  private readonly stiffLandingKneeDeg = 155.0;
  private readonly pronouncedTrunkLeanDeg = 35.0;
  private readonly fullArmSwingDeg = 110.0;
  private readonly algorithmVersion = 'bio-c4-web-v1.0';

  analyze(
    samples: BiomechanicsFrameSample[],
    calibration: CalibrationQuality,
    timing: FrameTimingMetadata,
    recordedAtEpochMs: number,
    planarHomography: PlanarHomography | null = null,
    takeoffTimestampUs: number | null = null,
    landingTimestampUs: number | null = null,
  ): MeasurementOutcome<BiomechanicsReport> {
    if (samples.length < 5) {
      return {
        kind: 'INSUFFICIENT_DATA',
        reason: `At least 5 pose frames required for biomechanics trajectory analysis (got ${samples.length})`,
      };
    }
    for (let i = 1; i < samples.length; i += 1) {
      if (samples[i].timestampUs <= samples[i - 1].timestampUs) {
        return {
          kind: 'INVALID_ATTEMPT',
          reasons: ['Non-monotonic frame timestamps in biomechanics sequence'],
          requiresExaminerReview: true,
        };
      }
    }

    const kinematics: FrameKinematics[] = [];
    const visScores: number[] = [];

    for (const sample of samples) {
      const lms = sample.landmarks;
      if (lms.length !== PoseLandmarkIndex.LANDMARK_COUNT) continue;

      const angleIfVisible = (i1: number, i2: number, i3: number): number | null => {
        if (
          !isLandmarkVisible(lms[i1], this.minVisibility) ||
          !isLandmarkVisible(lms[i2], this.minVisibility) ||
          !isLandmarkVisible(lms[i3], this.minVisibility)
        ) {
          return null;
        }
        return computeJointAngleDeg(
          toPixelPoint(lms[i1], sample.imageWidth, sample.imageHeight),
          toPixelPoint(lms[i2], sample.imageWidth, sample.imageHeight),
          toPixelPoint(lms[i3], sample.imageWidth, sample.imageHeight),
        );
      };

      const leftKneeDeg = angleIfVisible(
        PoseLandmarkIndex.LEFT_HIP,
        PoseLandmarkIndex.LEFT_KNEE,
        PoseLandmarkIndex.LEFT_ANKLE,
      );
      const rightKneeDeg = angleIfVisible(
        PoseLandmarkIndex.RIGHT_HIP,
        PoseLandmarkIndex.RIGHT_KNEE,
        PoseLandmarkIndex.RIGHT_ANKLE,
      );
      const leftHipDeg = angleIfVisible(
        PoseLandmarkIndex.LEFT_SHOULDER,
        PoseLandmarkIndex.LEFT_HIP,
        PoseLandmarkIndex.LEFT_KNEE,
      );
      const rightHipDeg = angleIfVisible(
        PoseLandmarkIndex.RIGHT_SHOULDER,
        PoseLandmarkIndex.RIGHT_HIP,
        PoseLandmarkIndex.RIGHT_KNEE,
      );
      const leftShoulderElevationDeg = angleIfVisible(
        PoseLandmarkIndex.LEFT_HIP,
        PoseLandmarkIndex.LEFT_SHOULDER,
        PoseLandmarkIndex.LEFT_ELBOW,
      );
      const rightShoulderElevationDeg = angleIfVisible(
        PoseLandmarkIndex.RIGHT_HIP,
        PoseLandmarkIndex.RIGHT_SHOULDER,
        PoseLandmarkIndex.RIGHT_ELBOW,
      );
      const leftAnkleDeg = angleIfVisible(
        PoseLandmarkIndex.LEFT_KNEE,
        PoseLandmarkIndex.LEFT_ANKLE,
        PoseLandmarkIndex.LEFT_FOOT_INDEX,
      );
      const rightAnkleDeg = angleIfVisible(
        PoseLandmarkIndex.RIGHT_KNEE,
        PoseLandmarkIndex.RIGHT_ANKLE,
        PoseLandmarkIndex.RIGHT_FOOT_INDEX,
      );

      let trunkLeanDeg: number | null = null;
      let comProxyNorm: Point2D | null = null;
      let comProxyPlaneCm: Point2D | null = null;
      let lateralBalanceOffsetNorm: number | null = null;

      const lSh = lms[PoseLandmarkIndex.LEFT_SHOULDER];
      const rSh = lms[PoseLandmarkIndex.RIGHT_SHOULDER];
      const lHip = lms[PoseLandmarkIndex.LEFT_HIP];
      const rHip = lms[PoseLandmarkIndex.RIGHT_HIP];

      if (
        isLandmarkVisible(lSh, this.minVisibility) &&
        isLandmarkVisible(rSh, this.minVisibility) &&
        isLandmarkVisible(lHip, this.minVisibility) &&
        isLandmarkVisible(rHip, this.minVisibility)
      ) {
        const shMidX = ((lSh.x + rSh.x) / 2) * sample.imageWidth;
        const shMidY = ((lSh.y + rSh.y) / 2) * sample.imageHeight;
        const hipMidX = ((lHip.x + rHip.x) / 2) * sample.imageWidth;
        const hipMidY = ((lHip.y + rHip.y) / 2) * sample.imageHeight;
        const dx = shMidX - hipMidX;
        const dy = hipMidY - shMidY;
        trunkLeanDeg = (Math.atan2(Math.abs(dx), Math.max(1e-6, dy)) * 180) / Math.PI;

        comProxyNorm = {
          x: (lSh.x + rSh.x + lHip.x + rHip.x) / 4,
          y: (lSh.y + rSh.y + lHip.y + rHip.y) / 4,
        };
        if (planarHomography) {
          comProxyPlaneCm = planarHomography.imageToPlane(
            toPixelPoint(comProxyNorm, sample.imageWidth, sample.imageHeight),
          );
        }

        const lAnk = lms[PoseLandmarkIndex.LEFT_ANKLE];
        const rAnk = lms[PoseLandmarkIndex.RIGHT_ANKLE];
        if (isLandmarkVisible(lAnk, this.minVisibility) && isLandmarkVisible(rAnk, this.minVisibility)) {
          lateralBalanceOffsetNorm = Math.abs(comProxyNorm.x - (lAnk.x + rAnk.x) / 2);
        }
      }

      visScores.push((lSh.visibility + rSh.visibility + lHip.visibility + rHip.visibility) / 4);
      kinematics.push({
        timestampUs: sample.timestampUs,
        leftKneeDeg,
        rightKneeDeg,
        leftHipDeg,
        rightHipDeg,
        trunkLeanDeg,
        leftShoulderElevationDeg,
        rightShoulderElevationDeg,
        leftAnkleDeg,
        rightAnkleDeg,
        comProxyNorm,
        comProxyPlaneCm,
        lateralBalanceOffsetNorm,
      });
    }

    if (kinematics.length < 5) {
      return {
        kind: 'INSUFFICIENT_DATA',
        reason: 'Insufficient visible kinematic frames',
      };
    }

    const kneeAngles = kinematics.flatMap((k) =>
      [k.leftKneeDeg, k.rightKneeDeg].filter((v): v is number => v !== null),
    );
    const trunkAngles = kinematics
      .map((k) => k.trunkLeanDeg)
      .filter((v): v is number => v !== null);
    const shoulderAngles = kinematics.flatMap((k) =>
      [k.leftShoulderElevationDeg, k.rightShoulderElevationDeg].filter(
        (v): v is number => v !== null,
      ),
    );
    const pairedDiffs = kinematics
      .filter((k) => k.leftKneeDeg !== null && k.rightKneeDeg !== null)
      .map((k) => Math.abs((k.leftKneeDeg ?? 0) - (k.rightKneeDeg ?? 0)));

    const minKneeFlexionDeg = kneeAngles.length > 0 ? Math.min(...kneeAngles) : null;
    const maxTrunkLeanDeg = trunkAngles.length > 0 ? Math.max(...trunkAngles) : null;
    const maxShoulderElevationDeg =
      shoulderAngles.length > 0 ? Math.max(...shoulderAngles) : null;
    const bilateralKneeAsymmetryDeg =
      pairedDiffs.length > 0
        ? pairedDiffs.reduce((a, b) => a + b, 0) / pairedDiffs.length
        : null;

    const takeoffIdx =
      takeoffTimestampUs !== null
        ? kinematics.findIndex((k) => k.timestampUs >= takeoffTimestampUs)
        : -1;
    const landingIdx =
      landingTimestampUs !== null
        ? kinematics.findIndex((k) => k.timestampUs >= landingTimestampUs)
        : -1;

    let deepestIdx: number | null = null;
    if (takeoffIdx > 0) {
      let minKnee = Infinity;
      for (let i = 0; i <= takeoffIdx; i += 1) {
        const avgKnee =
          kinematics[i].leftKneeDeg !== null && kinematics[i].rightKneeDeg !== null
            ? ((kinematics[i].leftKneeDeg ?? 0) + (kinematics[i].rightKneeDeg ?? 0)) / 2
            : kinematics[i].leftKneeDeg ?? kinematics[i].rightKneeDeg;
        if (avgKnee !== null && avgKnee < minKnee) {
          minKnee = avgKnee;
          deepestIdx = i;
        }
      }
    }

    let apexIdx: number | null = null;
    if (takeoffIdx >= 0 && landingIdx > takeoffIdx) {
      let minY = Infinity;
      for (let i = takeoffIdx; i <= landingIdx; i += 1) {
        const y = kinematics[i].comProxyNorm?.y;
        if (y !== undefined && y < minY) {
          minY = y;
          apexIdx = i;
        }
      }
    }

    const indicators: PerformanceIndicator[] = [];
    if (bilateralKneeAsymmetryDeg !== null) {
      indicators.push({
        code:
          bilateralKneeAsymmetryDeg >= this.asymmetryWarningDeg
            ? 'ASYMMETRIC_KNEE_FLEXION_OBSERVED'
            : 'BALANCED_BILATERAL_KNEE_FLEXION',
        summary:
          bilateralKneeAsymmetryDeg >= this.asymmetryWarningDeg
            ? 'Observed left/right knee flexion difference during movement.'
            : 'Bilateral knee flexion remained symmetric within tolerance.',
        observedValue: bilateralKneeAsymmetryDeg,
        unitLabel: 'deg',
      });
    }
    if (maxShoulderElevationDeg !== null) {
      indicators.push({
        code:
          maxShoulderElevationDeg >= this.fullArmSwingDeg
            ? 'FULL_ARM_SWING_OBSERVED'
            : 'LIMITED_ARM_SWING_OBSERVED',
        summary:
          maxShoulderElevationDeg >= this.fullArmSwingDeg
            ? 'Full overhead shoulder elevation observed during propulsion.'
            : 'Limited upward arm swing observed during propulsion.',
        observedValue: maxShoulderElevationDeg,
        unitLabel: 'deg',
      });
    }
    if (maxTrunkLeanDeg !== null) {
      indicators.push({
        code:
          maxTrunkLeanDeg >= this.pronouncedTrunkLeanDeg
            ? 'PRONOUNCED_TRUNK_LEAN_OBSERVED'
            : 'BALANCED_TRUNK_ALIGNMENT',
        summary:
          maxTrunkLeanDeg >= this.pronouncedTrunkLeanDeg
            ? 'Pronounced forward trunk lean observed relative to vertical.'
            : 'Trunk alignment remained balanced relative to vertical.',
        observedValue: maxTrunkLeanDeg,
        unitLabel: 'deg',
      });
    }
    if (landingIdx >= 0 && landingIdx < kinematics.length) {
      const postLandingKnees = kinematics
        .slice(landingIdx, Math.min(kinematics.length, landingIdx + 8))
        .flatMap((k) => [k.leftKneeDeg, k.rightKneeDeg].filter((v): v is number => v !== null));
      if (postLandingKnees.length > 0) {
        const minLandingKnee = Math.min(...postLandingKnees);
        indicators.push({
          code:
            minLandingKnee >= this.stiffLandingKneeDeg
              ? 'STIFF_LANDING_POSTURE_OBSERVED'
              : 'CONTROLLED_LANDING_FLEXION',
          summary:
            minLandingKnee >= this.stiffLandingKneeDeg
              ? 'Limited knee flexion observed immediately following touchdown.'
              : 'Controlled knee flexion observed on landing impact.',
          observedValue: minLandingKnee,
          unitLabel: 'deg',
        });
      }
    }

    const meanVis = visScores.reduce((a, b) => a + b, 0) / visScores.length;
    return {
      kind: 'VALID',
      value: meanVis,
      unit: 'DEGREES',
      uncertainty: 2.5,
      confidenceScore: Math.max(0.1, Math.min(0.99, meanVis)),
      validityStatus: 'VALID',
      requiresExaminerReview: false,
      reviewReasons: [],
      provenance: {
        landmarkProvenance: 'MEDIAPIPE_POSE_HEEL_ONLY',
        algorithmVersion: this.algorithmVersion,
        calibrationProfileId: calibration.calibrationProfileId,
        timing,
        recordedAtEpochMs,
        warnings: [
          'Biomechanical indicators are objective performance observations, not clinical or medical diagnoses.',
        ],
      },
      detail: {
        frameKinematics: kinematics,
        keyFrames: {
          initialStanceIndex: 0,
          deepestCountermovementIndex: deepestIdx,
          takeoffIndex: takeoffIdx >= 0 ? takeoffIdx : null,
          apexIndex: apexIdx,
          landingIndex: landingIdx >= 0 ? landingIdx : null,
        },
        minKneeFlexionDeg,
        maxTrunkLeanDeg,
        maxShoulderElevationDeg,
        bilateralKneeAsymmetryDeg,
        indicators,
      },
    };
  }
}
