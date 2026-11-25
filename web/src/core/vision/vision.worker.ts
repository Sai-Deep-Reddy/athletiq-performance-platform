import { FilesetResolver, HandLandmarker, PoseLandmarker } from '@mediapipe/tasks-vision';
import type { LandmarkPoint3D } from '../model/types';
import type { RefinedHeadCrown } from '../biomechanics/heightCalculator';
import type { MaskGroundContactCandidates } from '../biomechanics/broadJumpCalculator';
import type { ReachHandSide, RefinedFingertipPoint } from '../biomechanics/verticalJumpCalculator';
import { HandLandmarkIndex, PoseLandmarkIndex } from '../biomechanics/poseLandmarkIndex';
import {
  extractCandidateGroundContactsFromMask,
  refineHeadCrownFromMask,
} from './boundaryExtractors';

export interface VisionInitMessage {
  type: 'INIT';
  wasmBasePath: string;
  poseModelPath: string;
  handModelPath: string;
  enableHandLandmarker: boolean;
  delegate?: 'GPU' | 'CPU';
}

export interface VisionDetectMessage {
  type: 'DETECT_FRAME';
  id: number;
  frame: ImageBitmap;
  timestampUs: number;
  extractHeadCrown?: boolean;
  extractGroundContacts?: boolean;
  extractHandTip?: boolean;
}

export interface VisionResetMessage {
  type: 'RESET';
}

export type VisionWorkerRequest = VisionInitMessage | VisionDetectMessage | VisionResetMessage;

export interface VisionDetectResponse {
  type: 'DETECT_RESULT';
  id: number;
  timestampUs: number;
  width: number;
  height: number;
  posesDetectedCount: number;
  landmarks: LandmarkPoint3D[] | null;
  headCrown: RefinedHeadCrown | null;
  maskContacts: MaskGroundContactCandidates | null;
  refinedHandTip: RefinedFingertipPoint | null;
}

export type VisionWorkerResponse =
  | { type: 'READY'; delegate: string }
  | { type: 'RESET_OK' }
  | { type: 'ERROR'; id?: number; message: string }
  | VisionDetectResponse;

let poseLandmarker: PoseLandmarker | null = null;
let handLandmarker: HandLandmarker | null = null;
let scratchCanvas: OffscreenCanvas | null = null;
let scratchCtx: OffscreenCanvasRenderingContext2D | null = null;
let lastPoseLocalMs = -1;
let lastHandLocalMs = -1;
let poseGraphOffsetMs = 0;
let handGraphOffsetMs = 0;
let lastPoseGraphMs = 0;
let lastHandGraphMs = 0;

function ensureScratchCanvas(width: number, height: number): OffscreenCanvasRenderingContext2D {
  if (!scratchCanvas) {
    scratchCanvas = new OffscreenCanvas(width, height);
    scratchCtx = scratchCanvas.getContext('2d', { willReadFrequently: true });
  } else if (scratchCanvas.width !== width || scratchCanvas.height !== height) {
    scratchCanvas.width = width;
    scratchCanvas.height = height;
  }
  if (!scratchCtx) {
    throw new Error('OffscreenCanvas 2D context unavailable inside MediaPipe worker.');
  }
  return scratchCtx;
}

async function ensureEsmModuleFactory(wasmLoaderPath: string): Promise<void> {
  const scope = globalThis as { ModuleFactory?: unknown };
  if (typeof scope.ModuleFactory === 'function') {
    return;
  }
  const mod = (await import(/* @vite-ignore */ wasmLoaderPath)) as { default?: unknown };
  if (typeof mod.default === 'function') {
    scope.ModuleFactory = mod.default;
  }
}

async function initEngines(msg: VisionInitMessage): Promise<void> {
  poseLandmarker?.close();
  handLandmarker?.close();
  poseLandmarker = null;
  handLandmarker = null;
  lastPoseLocalMs = -1;
  lastHandLocalMs = -1;
  poseGraphOffsetMs = 0;
  handGraphOffsetMs = 0;
  lastPoseGraphMs = 0;
  lastHandGraphMs = 0;

  // Use ESM wasm module loader (useModule=true) for Vite ES-module Web Workers.
  const vision = await FilesetResolver.forVisionTasks(msg.wasmBasePath, true);
  const delegate = msg.delegate ?? 'GPU';

  try {
    await ensureEsmModuleFactory(vision.wasmLoaderPath);
    poseLandmarker = await PoseLandmarker.createFromOptions(vision, {
      baseOptions: { modelAssetPath: msg.poseModelPath, delegate },
      runningMode: 'VIDEO',
      numPoses: 2,
      minPoseDetectionConfidence: 0.5,
      minPosePresenceConfidence: 0.5,
      minTrackingConfidence: 0.5,
      outputSegmentationMasks: true,
    });
  } catch {
    await ensureEsmModuleFactory(vision.wasmLoaderPath);
    poseLandmarker = await PoseLandmarker.createFromOptions(vision, {
      baseOptions: { modelAssetPath: msg.poseModelPath, delegate: 'CPU' },
      runningMode: 'VIDEO',
      numPoses: 2,
      outputSegmentationMasks: true,
    });
  }

  if (msg.enableHandLandmarker) {
    try {
      await ensureEsmModuleFactory(vision.wasmLoaderPath);
      handLandmarker = await HandLandmarker.createFromOptions(vision, {
        baseOptions: { modelAssetPath: msg.handModelPath, delegate },
        runningMode: 'VIDEO',
        numHands: 2,
        minHandDetectionConfidence: 0.5,
        minHandPresenceConfidence: 0.5,
        minTrackingConfidence: 0.5,
      });
    } catch {
      await ensureEsmModuleFactory(vision.wasmLoaderPath);
      handLandmarker = await HandLandmarker.createFromOptions(vision, {
        baseOptions: { modelAssetPath: msg.handModelPath, delegate: 'CPU' },
        runningMode: 'VIDEO',
        numHands: 2,
      });
    }
  }

  self.postMessage({ type: 'READY', delegate } satisfies VisionWorkerResponse);
}

self.onmessage = async (event: MessageEvent<VisionWorkerRequest>) => {
  const payload = event.data;

  if (payload.type === 'INIT') {
    try {
      await initEngines(payload);
    } catch (err) {
      self.postMessage({
        type: 'ERROR',
        message: err instanceof Error ? err.message : 'Failed to initialize MediaPipe worker.',
      } satisfies VisionWorkerResponse);
    }
    return;
  }

  if (payload.type === 'RESET') {
    // Advance native MediaPipe CalculatorGraph clocks monotonically across sessions/clips.
    poseGraphOffsetMs = Math.ceil(lastPoseGraphMs) + 1000;
    handGraphOffsetMs = Math.ceil(lastHandGraphMs) + 1000;
    lastPoseLocalMs = -1;
    lastHandLocalMs = -1;
    self.postMessage({ type: 'RESET_OK' } satisfies VisionWorkerResponse);
    return;
  }

  if (payload.type === 'DETECT_FRAME') {
    const { frame, timestampUs, id } = payload;
    try {
      if (!poseLandmarker) {
        self.postMessage({
          type: 'ERROR',
          id,
          message: 'PoseLandmarker is not initialized.',
        } satisfies VisionWorkerResponse);
        return;
      }

      // MediaPipe detectForVideo expects strictly increasing milliseconds; preserve timestampUs untouched.
      const localTimestampMs = timestampUs / 1000;
      if (localTimestampMs <= lastPoseLocalMs) {
        self.postMessage({
          type: 'DETECT_RESULT',
          id,
          timestampUs,
          width: frame.width,
          height: frame.height,
          posesDetectedCount: 0,
          landmarks: null,
          headCrown: null,
          maskContacts: null,
          refinedHandTip: null,
        } satisfies VisionWorkerResponse);
        return;
      }
      lastPoseLocalMs = localTimestampMs;
      const poseGraphTimestampMs = poseGraphOffsetMs + localTimestampMs;
      lastPoseGraphMs = poseGraphTimestampMs;

      const poseRes = poseLandmarker.detectForVideo(frame, poseGraphTimestampMs);
      const posesDetectedCount = poseRes.landmarks?.length ?? 0;
      const primary = posesDetectedCount > 0 ? poseRes.landmarks[0] : null;
      const landmarks: LandmarkPoint3D[] | null = primary
        ? primary.map((lm) => ({
            x: lm.x,
            y: lm.y,
            z: lm.z,
            visibility: lm.visibility ?? 0.5,
          }))
        : null;

      let headCrown: RefinedHeadCrown | null = null;
      let maskContacts: MaskGroundContactCandidates | null = null;

      if (landmarks && (payload.extractHeadCrown || payload.extractGroundContacts)) {
        const mask = poseRes.segmentationMasks?.[0];
        const maskFloat = mask ? mask.getAsFloat32Array() : null;
        const maskW = mask ? mask.width : 0;
        const maskH = mask ? mask.height : 0;

        if (payload.extractHeadCrown && maskFloat) {
          const ctx = ensureScratchCanvas(frame.width, frame.height);
          ctx.drawImage(frame, 0, 0);
          const imgData = ctx.getImageData(0, 0, frame.width, frame.height);
          headCrown = refineHeadCrownFromMask(
            imgData.data,
            maskFloat,
            maskW,
            maskH,
            frame.width,
            frame.height,
            landmarks,
          );
        }
        if (payload.extractGroundContacts && maskFloat) {
          maskContacts = extractCandidateGroundContactsFromMask(
            maskFloat,
            maskW,
            maskH,
            landmarks,
          );
        }
        mask?.close();
      }

      let refinedHandTip: RefinedFingertipPoint | null = null;
      if (landmarks && payload.extractHandTip && handLandmarker && localTimestampMs > lastHandLocalMs) {
        lastHandLocalMs = localTimestampMs;
        const handGraphTimestampMs = handGraphOffsetMs + localTimestampMs;
        lastHandGraphMs = handGraphTimestampMs;
        const handRes = handLandmarker.detectForVideo(frame, handGraphTimestampMs);
        if (handRes.landmarks && handRes.landmarks.length > 0) {
          // Pick highest reaching hand tip (smallest normalized Y)
          let bestTipY = Infinity;
          for (let hIdx = 0; hIdx < handRes.landmarks.length; hIdx += 1) {
            const handLms = handRes.landmarks[hIdx];
            const tip = handLms[HandLandmarkIndex.INDEX_FINGER_TIP];
            if (!tip) continue;
            if (tip.y < bestTipY) {
              bestTipY = tip.y;
              const lWrist = landmarks[PoseLandmarkIndex.LEFT_WRIST];
              const rWrist = landmarks[PoseLandmarkIndex.RIGHT_WRIST];
              const dLeft = Math.hypot(tip.x - lWrist.x, tip.y - lWrist.y);
              const dRight = Math.hypot(tip.x - rWrist.x, tip.y - rWrist.y);
              const side: ReachHandSide = dLeft <= dRight ? 'LEFT' : 'RIGHT';
              const score = handRes.handednesses?.[hIdx]?.[0]?.score ?? 0.85;
              refinedHandTip = {
                xNormalized: Math.max(0, Math.min(1, tip.x)),
                yNormalized: Math.max(0, Math.min(1, tip.y)),
                confidence: score,
                side,
              };
            }
          }
        }
      }

      self.postMessage({
        type: 'DETECT_RESULT',
        id,
        timestampUs,
        width: frame.width,
        height: frame.height,
        posesDetectedCount,
        landmarks,
        headCrown,
        maskContacts,
        refinedHandTip,
      } satisfies VisionWorkerResponse);
    } catch (err) {
      self.postMessage({
        type: 'ERROR',
        id,
        message: err instanceof Error ? err.message : 'MediaPipe inference failure',
      } satisfies VisionWorkerResponse);
    } finally {
      frame.close();
    }
  }
};
