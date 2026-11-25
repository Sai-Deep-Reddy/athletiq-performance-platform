import type {
  CalibrationQuality,
  FrameTimingMetadata,
  LandmarkPoint3D,
  LandmarkProvenance,
  MeasurementOutcome,
  VerticalJumpProtocol,
} from '../model/types';
import { CalibrationQualityGate, PlanarHomography } from './planarHomography';
import {
  PoseLandmarkIndex,
  isLandmarkVisible,
  median,
  toPixelPoint,
} from './poseLandmarkIndex';

export type ReachHandSide = 'LEFT' | 'RIGHT';

export interface RefinedFingertipPoint {
  xNormalized: number;
  yNormalized: number;
  confidence: number;
  side: ReachHandSide;
}

export interface VerticalJumpFrameSample {
  timestampUs: number;
  imageWidth: number;
  imageHeight: number;
  landmarks: LandmarkPoint3D[];
  refinedHandTip?: RefinedFingertipPoint | null;
}

export interface FlightTimeEstimate {
  flightTimeSec: number;
  flightHeightCm: number;
  quantizationUncertaintyCm: number;
  takeoffTimestampUs: number;
  landingTimestampUs: number;
}

export interface VerticalJumpMeasurement {
  jumpHeightCm: number;
  standingReachCm: number;
  maxJumpReachCm: number;
  jumpDurationSec: number;
  takeoffTimestampUs: number;
  landingTimestampUs: number;
  apexTimestampUs: number;
  dominantSide: ReachHandSide;
  protocolUsed: VerticalJumpProtocol;
  flightTimeEstimate: FlightTimeEstimate;
  reachVsFlightDiscrepancyCm: number;
}

const STANDARD_GRAVITY_MPS2 = 9.80665;

export class VerticalJumpCalculator {
  private readonly minLandmarkVisibility = 0.6;
  private readonly minStandingReachFrames = 5;
  private readonly takeoffClearanceCm = 2.5;
  private readonly landingContactCm = 2.5;
  private readonly groundContactRefineCm = 0.5;
  private readonly maxReachVsFlightDiscrepancyCm = 10.0;
  private readonly algorithmVersion = 'vjump-c2-web-v1.1';

  calculate(
    samples: VerticalJumpFrameSample[],
    calibration: CalibrationQuality | null | undefined,
    verticalHomography: PlanarHomography | null | undefined,
    timing: FrameTimingMetadata | null | undefined,
    recordedAtEpochMs: number,
    protocol: VerticalJumpProtocol = 'STANDING_REACH_DELTA',
  ): MeasurementOutcome<VerticalJumpMeasurement> {
    const calCheck = CalibrationQualityGate.validate('VERTICAL_JUMP', calibration);
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
    if (!timing || samples.length < this.minStandingReachFrames + 6) {
      return {
        kind: 'INSUFFICIENT_DATA',
        reason: `At least ${this.minStandingReachFrames + 6} frames and timing metadata required`,
      };
    }

    for (let i = 1; i < samples.length; i += 1) {
      if (samples[i].timestampUs <= samples[i - 1].timestampUs) {
        return {
          kind: 'INVALID_ATTEMPT',
          reasons: ['Non-monotonic frame timestamps detected during vertical jump'],
          requiresExaminerReview: true,
        };
      }
    }

    // Determine vertical sign convention (+Y up vs +Y down in calibration target plane)
    const pTop = verticalHomography.imageToPlane({ x: samples[0].imageWidth / 2, y: 50 });
    const pBot = verticalHomography.imageToPlane({
      x: samples[0].imageWidth / 2,
      y: samples[0].imageHeight - 50,
    });
    const ySign = pTop.y >= pBot.y ? 1 : -1;

    interface TrackedFrame {
      timestampUs: number;
      reachHeightCm: number;
      footHeightCm: number;
      isArmOverhead: boolean;
      provenance: LandmarkProvenance;
      side: ReachHandSide;
      confidence: number;
    }

    const tracked: TrackedFrame[] = [];
    for (const sample of samples) {
      const lms = sample.landmarks;
      if (lms.length !== PoseLandmarkIndex.LANDMARK_COUNT) continue;
      const footLandmarks = [
        lms[PoseLandmarkIndex.LEFT_HEEL],
        lms[PoseLandmarkIndex.RIGHT_HEEL],
        lms[PoseLandmarkIndex.LEFT_FOOT_INDEX],
        lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX],
      ].filter((lm) => isLandmarkVisible(lm, this.minLandmarkVisibility));
      if (footLandmarks.length < 2) {
        continue;
      }

      // Use the lowest ground-contact point across visible heels & toes so plantarflexion before toe-off isn't miscounted as flight.
      const footHeightsCm = footLandmarks.map(
        (ft) => verticalHomography.imageToPlane(toPixelPoint(ft, sample.imageWidth, sample.imageHeight)).y * ySign,
      );
      const footHeightCm = Math.min(...footHeightsCm);

      let poseReachPtNorm: { x: number; y: number } | null = null;
      let poseProvenance: LandmarkProvenance = 'MEDIAPIPE_POSE_WRIST_FALLBACK';
      let poseSide: ReachHandSide = 'RIGHT';
      let poseConf = 0.65;

      const lIdx = lms[PoseLandmarkIndex.LEFT_INDEX];
      const rIdx = lms[PoseLandmarkIndex.RIGHT_INDEX];
      const lWri = lms[PoseLandmarkIndex.LEFT_WRIST];
      const rWri = lms[PoseLandmarkIndex.RIGHT_WRIST];

      const lIdxVis = isLandmarkVisible(lIdx, this.minLandmarkVisibility);
      const rIdxVis = isLandmarkVisible(rIdx, this.minLandmarkVisibility);
      if (lIdxVis || rIdxVis) {
        const useLeft = lIdxVis && (!rIdxVis || lIdx.y < rIdx.y);
        const lm = useLeft ? lIdx : rIdx;
        poseReachPtNorm = { x: lm.x, y: lm.y };
        poseProvenance = 'MEDIAPIPE_POSE_INDEX_FALLBACK';
        poseSide = useLeft ? 'LEFT' : 'RIGHT';
        poseConf = lm.visibility;
      } else {
        const lWriVis = isLandmarkVisible(lWri, this.minLandmarkVisibility);
        const rWriVis = isLandmarkVisible(rWri, this.minLandmarkVisibility);
        if (lWriVis || rWriVis) {
          const useLeft = lWriVis && (!rWriVis || lWri.y < rWri.y);
          const lm = useLeft ? lWri : rWri;
          poseReachPtNorm = { x: lm.x, y: lm.y };
          poseProvenance = 'MEDIAPIPE_POSE_WRIST_FALLBACK';
          poseSide = useLeft ? 'LEFT' : 'RIGHT';
          poseConf = lm.visibility;
        }
      }

      let reachPtNorm = poseReachPtNorm;
      let provenance: LandmarkProvenance = poseProvenance;
      let side: ReachHandSide = poseSide;
      let conf = poseConf;

      // Only accept HandLandmarker tip refinement when it is at least near the highest Pose hand
      // (a resting lower hand must never shadow an overhead Pose arm).
      if (
        sample.refinedHandTip &&
        sample.refinedHandTip.confidence >= 0.55 &&
        (poseReachPtNorm === null ||
          sample.refinedHandTip.yNormalized <= poseReachPtNorm.y + 0.05)
      ) {
        reachPtNorm = {
          x: sample.refinedHandTip.xNormalized,
          y: sample.refinedHandTip.yNormalized,
        };
        provenance = 'MEDIAPIPE_HAND_LANDMARKER_TIP';
        side = sample.refinedHandTip.side;
        conf = sample.refinedHandTip.confidence;
      }

      if (!reachPtNorm && protocol === 'COUNTERMOVEMENT_FLIGHT_TIME') {
        const lSh = lms[PoseLandmarkIndex.LEFT_SHOULDER];
        const rSh = lms[PoseLandmarkIndex.RIGHT_SHOULDER];
        if (isLandmarkVisible(lSh, 0.3) || isLandmarkVisible(rSh, 0.3)) {
          const refSh = lSh.visibility >= rSh.visibility ? lSh : rSh;
          reachPtNorm = { x: refSh.x, y: refSh.y };
        } else {
          reachPtNorm = { x: footLandmarks[0].x, y: footLandmarks[0].y };
        }
        provenance = 'MEDIAPIPE_POSE_HEEL_ONLY';
        conf = footLandmarks.reduce((s, f) => s + f.visibility, 0) / footLandmarks.length;
      }

      if (!reachPtNorm) continue;
      const reachPlane = verticalHomography.imageToPlane({
        x: reachPtNorm.x * sample.imageWidth,
        y: reachPtNorm.y * sample.imageHeight,
      });

      const sideShoulder =
        side === 'LEFT' ? lms[PoseLandmarkIndex.LEFT_SHOULDER] : lms[PoseLandmarkIndex.RIGHT_SHOULDER];
      const isArmOverhead =
        !isLandmarkVisible(sideShoulder, 0.4) || reachPtNorm.y < sideShoulder.y;

      tracked.push({
        timestampUs: sample.timestampUs,
        reachHeightCm: reachPlane.y * ySign,
        footHeightCm,
        isArmOverhead,
        provenance,
        side,
        confidence: conf,
      });
    }

    if (tracked.length < this.minStandingReachFrames + 4) {
      return {
        kind: 'INSUFFICIENT_DATA',
        reason: 'Insufficient visible hand/foot frames for vertical jump analysis',
      };
    }

    // Anchor on the primary airborne apex so brief squat/occlusion dips do not trigger premature take-off.
    let apexFootIdx = this.minStandingReachFrames;
    for (let i = this.minStandingReachFrames + 1; i < tracked.length; i += 1) {
      if (tracked[i].footHeightCm > tracked[apexFootIdx].footHeightCm) {
        apexFootIdx = i;
      }
    }

    const preApexHeights = tracked
      .slice(0, apexFootIdx)
      .map((f) => f.footHeightCm)
      .sort((a, b) => a - b);
    const coarsePreFloorCm = preApexHeights[Math.floor(preApexHeights.length * 0.25)];
    const coarsePeakCm = tracked[apexFootIdx].footHeightCm - coarsePreFloorCm;
    if (coarsePeakCm < this.takeoffClearanceCm) {
      return {
        kind: 'INVALID_ATTEMPT',
        reasons: ['No valid take-off detected from grounded standing baseline'],
        requiresExaminerReview: true,
      };
    }

    // Locate the local pre-launch grounded floor just before take-off so slight stance adjustments
    // earlier in a multi-second clip do not corrupt the take-off or overhead baseline gate.
    const groundedBandUpperCm = coarsePreFloorCm + Math.max(10.0, coarsePeakCm * 0.25);
    let bandEntryIdx = -1;
    for (let i = apexFootIdx - 1; i >= 0; i -= 1) {
      if (tracked[i].footHeightCm <= groundedBandUpperCm) {
        bandEntryIdx = i;
        break;
      }
    }
    if (bandEntryIdx < this.minStandingReachFrames - 1) {
      return {
        kind: 'INVALID_ATTEMPT',
        reasons: ['No valid take-off detected from grounded standing baseline'],
        requiresExaminerReview: true,
      };
    }

    const troughStart = Math.max(0, bandEntryIdx - 10);
    let localTroughIdx = bandEntryIdx;
    for (let i = bandEntryIdx - 1; i >= troughStart; i -= 1) {
      if (tracked[i].footHeightCm < tracked[localTroughIdx].footHeightCm - this.groundContactRefineCm) {
        localTroughIdx = i;
      }
    }
    const localFloorWindow = tracked
      .slice(Math.max(0, localTroughIdx - 4), bandEntryIdx + 1)
      .filter((f) => f.footHeightCm <= tracked[localTroughIdx].footHeightCm + this.takeoffClearanceCm);
    const floorDatumCm =
      localFloorWindow.length > 0
        ? median(localFloorWindow.map((f) => f.footHeightCm))
        : tracked[localTroughIdx].footHeightCm;

    const peakFootLiftCm = tracked[apexFootIdx].footHeightCm - floorDatumCm;
    if (peakFootLiftCm < this.takeoffClearanceCm) {
      return {
        kind: 'INVALID_ATTEMPT',
        reasons: ['No valid take-off detected from grounded standing baseline'],
        requiresExaminerReview: true,
      };
    }

    // Walk backward from apex to locate the true grounded departure before the primary jump.
    const preFloorToleranceCm = Math.max(
      this.takeoffClearanceCm,
      Math.min(5.0, peakFootLiftCm * 0.1),
    );
    let groundedBeforeApex = -1;
    for (let i = apexFootIdx - 1; i >= 0; i -= 1) {
      if (tracked[i].footHeightCm - floorDatumCm <= preFloorToleranceCm) {
        groundedBeforeApex = i;
        break;
      }
    }
    if (groundedBeforeApex < this.minStandingReachFrames - 1 || groundedBeforeApex + 1 >= apexFootIdx) {
      return {
        kind: 'INVALID_ATTEMPT',
        reasons: ['No valid take-off detected from grounded standing baseline'],
        requiresExaminerReview: true,
      };
    }

    const takeoffIdx = groundedBeforeApex + 1;

    const preTakeoff = tracked.slice(0, takeoffIdx);
    const baselineSlice =
      protocol === 'STANDING_REACH_DELTA'
        ? preTakeoff.filter(
            (f) => Math.abs(f.footHeightCm - floorDatumCm) <= preFloorToleranceCm && f.isArmOverhead,
          )
        : preTakeoff.filter((f) => Math.abs(f.footHeightCm - floorDatumCm) <= preFloorToleranceCm);

    if (baselineSlice.length < this.minStandingReachFrames) {
      return {
        kind: 'INVALID_ATTEMPT',
        reasons: [
          protocol === 'STANDING_REACH_DELTA'
            ? `Overhead standing reach baseline not established before jump (found ${baselineSlice.length} frames; need ${this.minStandingReachFrames})`
            : `Grounded baseline not established before jump (found ${baselineSlice.length} frames; need ${this.minStandingReachFrames})`,
        ],
        requiresExaminerReview: true,
      };
    }

    // Estimate post-landing floor datum from settled post-apex frames (tolerates slight depth shift on tilted cameras).
    const postApex = tracked.slice(apexFootIdx + 1);
    const settledToleranceCm = Math.max(
      this.landingContactCm,
      Math.min(10.0, peakFootLiftCm * 0.18),
    );
    const postGroundedCandidates = postApex.filter(
      (f) => f.footHeightCm - floorDatumCm <= settledToleranceCm,
    );
    if (postGroundedCandidates.length < 2) {
      return {
        kind: 'INVALID_ATTEMPT',
        reasons: ['Jump landing was not captured inside recording'],
        requiresExaminerReview: true,
      };
    }
    const postFloorDatumCm = median(postGroundedCandidates.slice(0, 5).map((f) => f.footHeightCm));
    const effectiveLandingThresholdCm = Math.max(
      floorDatumCm + this.landingContactCm,
      postFloorDatumCm + this.landingContactCm,
    );

    // Stop at initial foot touchdown rather than walking forward into post-impact heel settlement.
    let landingIdx = -1;
    for (let i = apexFootIdx + 1; i < tracked.length; i += 1) {
      if (tracked[i].footHeightCm <= effectiveLandingThresholdCm) {
        landingIdx = i;
        break;
      }
    }
    if (landingIdx === -1 || landingIdx <= takeoffIdx) {
      return {
        kind: 'INVALID_ATTEMPT',
        reasons: ['Jump landing was not captured inside recording'],
        requiresExaminerReview: true,
      };
    }

    let apexFrame = tracked[takeoffIdx];
    for (let i = takeoffIdx; i <= landingIdx; i += 1) {
      if (tracked[i].reachHeightCm > apexFrame.reachHeightCm) {
        apexFrame = tracked[i];
      }
    }

    // Midpoint boundary timestamps remove half-frame systematic quantization bias.
    const takeoffUs = Math.round(
      (tracked[takeoffIdx - 1].timestampUs + tracked[takeoffIdx].timestampUs) / 2,
    );
    const landingUs = Math.round(
      (tracked[landingIdx - 1].timestampUs + tracked[landingIdx].timestampUs) / 2,
    );
    const flightTimeSec = (landingUs - takeoffUs) / 1_000_000;
    const flightHeightCm = ((STANDARD_GRAVITY_MPS2 * flightTimeSec * flightTimeSec) / 8) * 100;
    const dtSec = timing.frameDtMs / 1000;
    const flightQuantUncCm = ((STANDARD_GRAVITY_MPS2 * flightTimeSec * dtSec) / 4) * 100;

    const standingReachCm =
      protocol === 'STANDING_REACH_DELTA'
        ? median(baselineSlice.map((f) => f.reachHeightCm)) - floorDatumCm
        : 0;
    const maxJumpReachCm =
      protocol === 'STANDING_REACH_DELTA' ? apexFrame.reachHeightCm - floorDatumCm : flightHeightCm;
    const jumpHeightCm =
      protocol === 'STANDING_REACH_DELTA' ? maxJumpReachCm - standingReachCm : flightHeightCm;

    if (!Number.isFinite(jumpHeightCm) || jumpHeightCm <= 1.0 || jumpHeightCm > 160) {
      return {
        kind: 'INVALID_ATTEMPT',
        reasons: [`Computed jump height (${jumpHeightCm.toFixed(1)} cm) is outside plausible bounds`],
        requiresExaminerReview: true,
      };
    }

    const discrepancyCm =
      protocol === 'STANDING_REACH_DELTA' ? Math.abs(jumpHeightCm - flightHeightCm) : 0;

    const dominantProvenance: LandmarkProvenance =
      protocol === 'COUNTERMOVEMENT_FLIGHT_TIME' ? 'MEDIAPIPE_POSE_HEEL_ONLY' : apexFrame.provenance;
    const reviewReasons: string[] = [];
    const warnings = [...calCheck.warnings];
    if (protocol === 'COUNTERMOVEMENT_FLIGHT_TIME') {
      warnings.push(
        'Countermovement flight-time protocol used (h = g * t^2 / 8); assumes symmetric take-off and landing posture.',
      );
    } else {
      if (dominantProvenance !== 'MEDIAPIPE_HAND_LANDMARKER_TIP') {
        warnings.push(
          `HandLandmarker ROI unavailable at apex; used ${dominantProvenance} with reduced precision.`,
        );
      }
      if (discrepancyCm > this.maxReachVsFlightDiscrepancyCm) {
        reviewReasons.push(
          `Reach-delta (${jumpHeightCm.toFixed(1)} cm) diverges from flight-time (${flightHeightCm.toFixed(1)} cm) by ${discrepancyCm.toFixed(1)} cm.`,
        );
      }
    }

    const uncertainty = Math.max(
      0.5,
      calCheck.calibration.reprojectionErrorPx * 0.4 +
        (protocol === 'COUNTERMOVEMENT_FLIGHT_TIME'
          ? Math.max(0.5, flightQuantUncCm)
          : dominantProvenance === 'MEDIAPIPE_HAND_LANDMARKER_TIP'
            ? 0.5
            : 1.5),
    );

    return {
      kind: 'VALID',
      value: jumpHeightCm,
      unit: 'CENTIMETERS',
      uncertainty,
      confidenceScore: Math.max(0.1, Math.min(0.99, apexFrame.confidence)),
      validityStatus: reviewReasons.length > 0 ? 'FLAGGED' : 'VALID',
      requiresExaminerReview: reviewReasons.length > 0,
      reviewReasons,
      provenance: {
        landmarkProvenance: dominantProvenance,
        algorithmVersion: this.algorithmVersion,
        calibrationProfileId: calCheck.calibration.calibrationProfileId,
        timing,
        recordedAtEpochMs,
        warnings,
      },
      detail: {
        jumpHeightCm,
        standingReachCm,
        maxJumpReachCm,
        jumpDurationSec: flightTimeSec,
        takeoffTimestampUs: takeoffUs,
        landingTimestampUs: landingUs,
        apexTimestampUs: apexFrame.timestampUs,
        dominantSide: apexFrame.side,
        protocolUsed: protocol,
        flightTimeEstimate: {
          flightTimeSec,
          flightHeightCm,
          quantizationUncertaintyCm: flightQuantUncCm,
          takeoffTimestampUs: takeoffUs,
          landingTimestampUs: landingUs,
        },
        reachVsFlightDiscrepancyCm: discrepancyCm,
      },
    };
  }
}
