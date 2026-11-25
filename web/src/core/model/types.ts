export type UserRole = 'ADMIN' | 'INSTRUCTOR' | 'COACH';

export type TestType = 'HEIGHT' | 'VERTICAL_JUMP' | 'STANDING_BROAD_JUMP' | 'SPRINT';

export type CalibrationPlaneType = 'INTRINSICS' | 'VERTICAL_PLANE' | 'GROUND_PLANE';

export type AttemptValidityStatus = 'VALID' | 'FLAGGED' | 'INVALID';

export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export type MeasurementUnit = 'CENTIMETERS' | 'SECONDS' | 'METERS_PER_SECOND' | 'DEGREES';

export type BroadJumpProtocol =
  | 'SAI_KHELO_INDIA_HEEL'
  | 'NEAREST_BODY_CONTACT'
  | 'NEAREST_FOOT_OR_SHOE_CONTACT';

export type VerticalJumpProtocol =
  | 'STANDING_REACH_DELTA'
  | 'COUNTERMOVEMENT_FLIGHT_TIME';

export type LandmarkProvenance =
  | 'MEDIAPIPE_HAND_LANDMARKER_TIP'
  | 'MEDIAPIPE_POSE_INDEX_FALLBACK'
  | 'MEDIAPIPE_POSE_WRIST_FALLBACK'
  | 'MEDIAPIPE_POSE_HEAD_CROWN_REFINED'
  | 'MEDIAPIPE_POSE_HEEL_AND_MASK_CANDIDATE'
  | 'MEDIAPIPE_POSE_HEEL_ONLY';

export interface Point2D {
  x: number;
  y: number;
}

export interface LandmarkPoint3D {
  x: number;
  y: number;
  z: number;
  visibility: number;
}

export interface FrameTimingMetadata {
  fpsActual: number;
  frameDtMs: number;
  droppedFrames: number;
  isVariableFrameRate: boolean;
  frameCount: number;
  durationMs: number;
  sourceKind: 'WEBCODECS_DECODED' | 'LIVE_GUIDANCE_RVFC' | 'FFMPEG_FALLBACK' | 'SYNTHETIC_TEST';
  firstTimestampUs: number;
  lastTimestampUs: number;
}

export interface CalibrationQuality {
  calibrationProfileId: string;
  planeType: CalibrationPlaneType;
  method: string;
  reprojectionErrorPx: number;
  isActive: boolean;
  usedIntrinsics: boolean;
  spatialCoverageRatio?: number;
  independentDistanceVerified?: boolean;
}

export interface MeasurementProvenance {
  landmarkProvenance: LandmarkProvenance;
  algorithmVersion: string;
  calibrationProfileId: string;
  timing: FrameTimingMetadata;
  recordedAtEpochMs: number;
  warnings: string[];
}

export type MeasurementOutcome<T> =
  | {
      kind: 'VALID';
      value: number;
      unit: MeasurementUnit;
      uncertainty: number;
      confidenceScore: number;
      validityStatus: AttemptValidityStatus;
      requiresExaminerReview: boolean;
      reviewReasons: string[];
      provenance: MeasurementProvenance;
      detail: T;
    }
  | {
      kind: 'CALIBRATION_REQUIRED';
      requiredPlane: CalibrationPlaneType;
      reason: string;
    }
  | {
      kind: 'LOW_CONFIDENCE';
      reason: string;
      confidenceScore: number;
    }
  | {
      kind: 'INSUFFICIENT_DATA';
      reason: string;
    }
  | {
      kind: 'INVALID_ATTEMPT';
      reasons: string[];
      requiresExaminerReview: boolean;
      detail?: T;
    };
