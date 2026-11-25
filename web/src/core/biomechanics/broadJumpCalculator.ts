import type {
  BroadJumpProtocol,
  CalibrationQuality,
  FrameTimingMetadata,
  LandmarkPoint3D,
  MeasurementOutcome,
  Point2D,
} from '../model/types';
import { CalibrationQualityGate, PlanarHomography } from './planarHomography';
import { PoseLandmarkIndex, isLandmarkVisible, median, toPixelPoint } from './poseLandmarkIndex';

export type LandingContactKind =
  | 'HEEL'
  | 'CANDIDATE_SHOE_SOLE_REAR'
  | 'HAND_TOUCH'
  | 'HIP_OR_BODY_FALL'
  | 'BACKWARD_STEP';

export type LandingStabilityStatus =
  | 'STABLE'
  | 'HAND_OR_BODY_TOUCH'
  | 'BACKWARD_STEP_OR_FALL';

/**
 * Candidate ground-contact points derived from MediaPipe person foreground segmentation mask.
 * Note: MediaPipe segmentation identifies the person foreground silhouette (not dedicated shoe segmentation),
 * so mask points are treated strictly as candidate locations requiring heel-proximity & examiner validation.
 */
export interface MaskGroundContactCandidates {
  rearShoeSolePointNormalized?: Point2D | null;
  rearBodyOrHandGroundPointNormalized?: Point2D | null;
  maskConfidence?: number;
}

export interface BroadJumpFrameSample {
  timestampUs: number;
  imageWidth: number;
  imageHeight: number;
  landmarks: LandmarkPoint3D[];
  maskContacts?: MaskGroundContactCandidates | null;
}

export interface BroadJumpMeasurement {
  distanceCm: number;
  heelDistanceCm: number;
  startingFootXCm: number;
  initialLandingXCm: number;
  nearestContactXCm: number;
  landingContactKind: LandingContactKind;
  stabilityStatus: LandingStabilityStatus;
  protocolUsed: BroadJumpProtocol;
  takeoffTimestampUs: number;
  landingTimestampUs: number;
}

export class BroadJumpCalculator {
  private readonly minLandmarkVisibility = 0.6;
  private readonly minStationaryFrames = 5;
  private readonly takeoffLineToleranceCm = 1.0;
  private readonly takeoffProgressThresholdCm = 10.0;
  private readonly airborneFootLiftNormRatio = 0.025;
  private readonly handGroundTouchNormTolerance = 0.035;
  private readonly hipGroundFallNormTolerance = 0.06;
  private readonly backwardStepToleranceCm = 3.0;
  private readonly postLandingObserveMs = 1000;
  private readonly algorithmVersion = 'sbj-c3-web-v1.1';

  calculate(
    samples: BroadJumpFrameSample[],
    calibration: CalibrationQuality | null | undefined,
    groundHomography: PlanarHomography | null | undefined,
    timing: FrameTimingMetadata | null | undefined,
    recordedAtEpochMs: number,
    protocol: BroadJumpProtocol = 'SAI_KHELO_INDIA_HEEL',
  ): MeasurementOutcome<BroadJumpMeasurement> {
    const calCheck = CalibrationQualityGate.validate('STANDING_BROAD_JUMP', calibration);
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
    if (!timing || samples.length < this.minStationaryFrames + 6) {
      return {
        kind: 'INSUFFICIENT_DATA',
        reason: `Insufficient frames or timing metadata for standing broad jump (got ${samples.length})`,
      };
    }

    for (let i = 1; i < samples.length; i += 1) {
      if (samples[i].timestampUs <= samples[i - 1].timestampUs) {
        return {
          kind: 'INVALID_ATTEMPT',
          reasons: ['Non-monotonic frame timestamps detected during broad jump'],
          requiresExaminerReview: true,
        };
      }
    }

    const initialFloorYs: number[] = [];
    for (const sample of samples.slice(0, this.minStationaryFrames * 2)) {
      const lms = sample.landmarks;
      if (lms.length !== PoseLandmarkIndex.LANDMARK_COUNT) continue;
      for (const idx of [
        PoseLandmarkIndex.LEFT_HEEL,
        PoseLandmarkIndex.RIGHT_HEEL,
        PoseLandmarkIndex.LEFT_FOOT_INDEX,
        PoseLandmarkIndex.RIGHT_FOOT_INDEX,
      ]) {
        if (isLandmarkVisible(lms[idx], this.minLandmarkVisibility)) {
          initialFloorYs.push(lms[idx].y);
        }
      }
    }
    if (initialFloorYs.length < 4) {
      return {
        kind: 'INSUFFICIENT_DATA',
        reason: 'Foot landmarks were not reliably visible before take-off',
      };
    }
    const baselineFloorYNorm = median(initialFloorYs);

    interface FrameProj {
      timestampUs: number;
      rearHeelXCm: number;
      maxFootFrontXCm: number;
      candidateHeelOrSoleXCm: number;
      soleKind: LandingContactKind;
      handContactXCm: number | null;
      hipFallContactXCm: number | null;
      footYNorm: number;
      avgFootVis: number;
      heelOccluded: boolean;
    }

    const projections: FrameProj[] = [];
    for (const sample of samples) {
      const lms = sample.landmarks;
      if (lms.length !== PoseLandmarkIndex.LANDMARK_COUNT) continue;

      const lHeel = lms[PoseLandmarkIndex.LEFT_HEEL];
      const rHeel = lms[PoseLandmarkIndex.RIGHT_HEEL];
      const lToe = lms[PoseLandmarkIndex.LEFT_FOOT_INDEX];
      const rToe = lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX];
      const heelsVisible =
        isLandmarkVisible(lHeel, this.minLandmarkVisibility) &&
        isLandmarkVisible(rHeel, this.minLandmarkVisibility);
      const toesVisible =
        isLandmarkVisible(lToe, this.minLandmarkVisibility) &&
        isLandmarkVisible(rToe, this.minLandmarkVisibility);

      if (!heelsVisible && !toesVisible) continue;

      const lHeelX = groundHomography.imageToPlane(
        toPixelPoint(lHeel, sample.imageWidth, sample.imageHeight),
      ).x;
      const rHeelX = groundHomography.imageToPlane(
        toPixelPoint(rHeel, sample.imageWidth, sample.imageHeight),
      ).x;
      const lToeX = groundHomography.imageToPlane(
        toPixelPoint(lToe, sample.imageWidth, sample.imageHeight),
      ).x;
      const rToeX = groundHomography.imageToPlane(
        toPixelPoint(rToe, sample.imageWidth, sample.imageHeight),
      ).x;

      const rearHeelXCm = Math.min(lHeelX, rHeelX);
      const maxFootFrontXCm = Math.max(lToeX, rToeX);

      let candidateHeelOrSoleXCm = rearHeelXCm;
      let soleKind: LandingContactKind = 'HEEL';
      const candidateRearSole = sample.maskContacts?.rearShoeSolePointNormalized;
      if (candidateRearSole) {
        const soleX = groundHomography.imageToPlane({
          x: candidateRearSole.x * sample.imageWidth,
          y: candidateRearSole.y * sample.imageHeight,
        }).x;
        // Treat person-mask boundary strictly as a candidate within plausible shoe distance of heel (<= 15 cm behind heel).
        if (soleX < candidateHeelOrSoleXCm && rearHeelXCm - soleX <= 15.0) {
          candidateHeelOrSoleXCm = soleX;
          soleKind = 'CANDIDATE_SHOE_SOLE_REAR';
        }
      }

      const handXs: number[] = [];
      for (const idx of [
        PoseLandmarkIndex.LEFT_WRIST,
        PoseLandmarkIndex.RIGHT_WRIST,
        PoseLandmarkIndex.LEFT_INDEX,
        PoseLandmarkIndex.RIGHT_INDEX,
      ]) {
        const lm = lms[idx];
        if (
          isLandmarkVisible(lm, this.minLandmarkVisibility) &&
          Math.abs(lm.y - baselineFloorYNorm) <= this.handGroundTouchNormTolerance
        ) {
          handXs.push(
            groundHomography.imageToPlane(toPixelPoint(lm, sample.imageWidth, sample.imageHeight)).x,
          );
        }
      }
      if (sample.maskContacts?.rearBodyOrHandGroundPointNormalized) {
        const pt = sample.maskContacts.rearBodyOrHandGroundPointNormalized;
        const mX = groundHomography.imageToPlane({
          x: pt.x * sample.imageWidth,
          y: pt.y * sample.imageHeight,
        }).x;
        if (mX < rearHeelXCm - 5.0) {
          handXs.push(mX);
        }
      }

      const hipXs: number[] = [];
      for (const idx of [PoseLandmarkIndex.LEFT_HIP, PoseLandmarkIndex.RIGHT_HIP]) {
        const lm = lms[idx];
        if (
          isLandmarkVisible(lm, this.minLandmarkVisibility) &&
          Math.abs(lm.y - baselineFloorYNorm) <= this.hipGroundFallNormTolerance
        ) {
          hipXs.push(
            groundHomography.imageToPlane(toPixelPoint(lm, sample.imageWidth, sample.imageHeight)).x,
          );
        }
      }

      const footYNorm = (lHeel.y + rHeel.y + lToe.y + rToe.y) / 4;
      const avgFootVis = (lHeel.visibility + rHeel.visibility + lToe.visibility + rToe.visibility) / 4;
      projections.push({
        timestampUs: sample.timestampUs,
        rearHeelXCm,
        maxFootFrontXCm,
        candidateHeelOrSoleXCm,
        soleKind,
        handContactXCm: handXs.length > 0 ? Math.min(...handXs) : null,
        hipFallContactXCm: hipXs.length > 0 ? Math.min(...hipXs) : null,
        footYNorm,
        avgFootVis,
        heelOccluded: !heelsVisible,
      });
    }

    if (projections.length < this.minStationaryFrames + 4) {
      return {
        kind: 'INSUFFICIENT_DATA',
        reason: 'Too few valid ground-plane frames for standing broad jump',
      };
    }

    const startSlice = projections.slice(0, this.minStationaryFrames);
    const maxStartToeX = Math.max(...startSlice.map((p) => p.maxFootFrontXCm));
    const startingFootXCm = median(startSlice.map((p) => p.rearHeelXCm));
    if (maxStartToeX > this.takeoffLineToleranceCm) {
      return {
        kind: 'INVALID_ATTEMPT',
        reasons: [
          `Take-off foot fault: toe crossed baseline (${maxStartToeX.toFixed(1)} cm > 0 cm)`,
        ],
        requiresExaminerReview: true,
      };
    }

    let takeoffIdx = -1;
    let landingIdx = -1;
    for (let i = this.minStationaryFrames; i < projections.length; i += 1) {
      const p = projections[i];
      const airborne = baselineFloorYNorm - p.footYNorm >= this.airborneFootLiftNormRatio;
      const movedForward = p.rearHeelXCm - startingFootXCm >= this.takeoffProgressThresholdCm;
      if (takeoffIdx === -1 && (airborne || movedForward)) {
        takeoffIdx = i;
      } else if (
        takeoffIdx !== -1 &&
        i > takeoffIdx &&
        baselineFloorYNorm - p.footYNorm <= this.airborneFootLiftNormRatio * 0.45 &&
        p.rearHeelXCm > startingFootXCm + this.takeoffProgressThresholdCm
      ) {
        landingIdx = i;
        break;
      }
    }

    if (takeoffIdx === -1 || landingIdx === -1) {
      return {
        kind: 'INSUFFICIENT_DATA',
        reason: 'Could not detect clear broad-jump take-off and landing phases',
      };
    }

    const landingFrame = projections[landingIdx];
    const heelDistanceCm = landingFrame.rearHeelXCm;
    const postWindowEndUs = landingFrame.timestampUs + this.postLandingObserveMs * 1000;
    const postFrames = projections.filter(
      (p, idx) => idx >= landingIdx && p.timestampUs <= postWindowEndUs,
    );

    let nearestContactXCm = landingFrame.rearHeelXCm;
    let contactKind: LandingContactKind = 'HEEL';
    let stabilityStatus: LandingStabilityStatus = 'STABLE';
    const invalidFaultReasons: string[] = [];
    const reviewReasons: string[] = [];

    if (landingFrame.heelOccluded) {
      reviewReasons.push('Heel landmark visibility was reduced at landing; verify rear contact frame.');
    }

    if (protocol !== 'SAI_KHELO_INDIA_HEEL' && landingFrame.candidateHeelOrSoleXCm < nearestContactXCm) {
      nearestContactXCm = landingFrame.candidateHeelOrSoleXCm;
      contactKind = landingFrame.soleKind;
      if (landingFrame.soleKind === 'CANDIDATE_SHOE_SOLE_REAR') {
        reviewReasons.push(
          'Candidate shoe-sole boundary derived from person segmentation mask; examiner confirmation recommended.',
        );
      }
    }

    for (const pf of postFrames) {
      if (pf.rearHeelXCm < landingFrame.rearHeelXCm - this.backwardStepToleranceCm) {
        stabilityStatus = 'BACKWARD_STEP_OR_FALL';
        if (protocol === 'SAI_KHELO_INDIA_HEEL') {
          invalidFaultReasons.push('Backward step or fall detected after landing (SAI Khelo India foul).');
        } else {
          nearestContactXCm = Math.min(nearestContactXCm, pf.rearHeelXCm);
          contactKind = 'BACKWARD_STEP';
          reviewReasons.push('Athlete stepped backward after landing.');
        }
      }
      if (pf.handContactXCm !== null && pf.handContactXCm < landingFrame.rearHeelXCm - 2.0) {
        stabilityStatus = 'HAND_OR_BODY_TOUCH';
        if (protocol === 'SAI_KHELO_INDIA_HEEL') {
          invalidFaultReasons.push('Backward hand touch detected behind heels (SAI Khelo India foul).');
        } else if (protocol === 'NEAREST_BODY_CONTACT' && pf.handContactXCm < nearestContactXCm) {
          nearestContactXCm = pf.handContactXCm;
          contactKind = 'HAND_TOUCH';
          reviewReasons.push('Hand contacted ground behind heels.');
        } else {
          reviewReasons.push('Hand contact detected behind heels; verify protocol compliance.');
        }
      }
      if (pf.hipFallContactXCm !== null && pf.hipFallContactXCm < landingFrame.rearHeelXCm - 2.0) {
        stabilityStatus = 'BACKWARD_STEP_OR_FALL';
        if (protocol === 'SAI_KHELO_INDIA_HEEL') {
          invalidFaultReasons.push('Hip or backward body fall detected after landing (SAI Khelo India foul).');
        } else if (protocol === 'NEAREST_BODY_CONTACT' && pf.hipFallContactXCm < nearestContactXCm) {
          nearestContactXCm = pf.hipFallContactXCm;
          contactKind = 'HIP_OR_BODY_FALL';
          reviewReasons.push('Hip or backward body fall detected behind heels.');
        } else {
          reviewReasons.push('Backward body fall detected behind heels.');
        }
      }
    }

    const detail: BroadJumpMeasurement = {
      distanceCm: protocol === 'SAI_KHELO_INDIA_HEEL' ? heelDistanceCm : nearestContactXCm,
      heelDistanceCm,
      startingFootXCm,
      initialLandingXCm: landingFrame.rearHeelXCm,
      nearestContactXCm,
      landingContactKind: contactKind,
      stabilityStatus,
      protocolUsed: protocol,
      takeoffTimestampUs: projections[takeoffIdx].timestampUs,
      landingTimestampUs: landingFrame.timestampUs,
    };

    if (invalidFaultReasons.length > 0) {
      return {
        kind: 'INVALID_ATTEMPT',
        reasons: invalidFaultReasons,
        requiresExaminerReview: true,
        detail,
      };
    }

    if (!Number.isFinite(detail.distanceCm) || detail.distanceCm <= 0 || detail.distanceCm > 500) {
      return {
        kind: 'INVALID_ATTEMPT',
        reasons: [`Broad jump distance (${detail.distanceCm.toFixed(1)} cm) outside plausible bounds`],
        requiresExaminerReview: true,
        detail,
      };
    }

    const provenance =
      contactKind === 'CANDIDATE_SHOE_SOLE_REAR'
        ? 'MEDIAPIPE_POSE_HEEL_AND_MASK_CANDIDATE'
        : 'MEDIAPIPE_POSE_HEEL_ONLY';

    return {
      kind: 'VALID',
      value: detail.distanceCm,
      unit: 'CENTIMETERS',
      uncertainty: Math.max(0.8, calCheck.calibration.reprojectionErrorPx * 0.5 + 0.8),
      confidenceScore: Math.max(0.1, Math.min(0.99, landingFrame.avgFootVis)),
      validityStatus: reviewReasons.length > 0 ? 'FLAGGED' : 'VALID',
      requiresExaminerReview: reviewReasons.length > 0,
      reviewReasons,
      provenance: {
        landmarkProvenance: provenance,
        algorithmVersion: this.algorithmVersion,
        calibrationProfileId: calCheck.calibration.calibrationProfileId,
        timing,
        recordedAtEpochMs,
        warnings: calCheck.warnings,
      },
      detail,
    };
  }
}
