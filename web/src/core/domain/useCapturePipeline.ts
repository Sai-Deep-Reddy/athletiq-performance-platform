import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { AthletiqApiClient } from '../api/client';
import type { CalibrationProfileResponse } from '../api/schemas';
import { AttemptMediaRecorder } from '../camera/attemptRecorder';
import {
  type BrowserCameraCapabilities,
  type CameraFacingMode,
  openCameraStream,
  probeBrowserCameraCapabilities,
  stopCameraStream,
} from '../camera/cameraCapabilities';
import { startLiveGuidanceLoop } from '../camera/liveGuidanceStreamer';
import { decodeVideoWithWebCodecs } from '../camera/webCodecsDecoder';
import { BiomechanicsAnalyzer, type BiomechanicsReport } from '../biomechanics/biomechanicsAnalyzer';
import { BroadJumpCalculator, type BroadJumpMeasurement } from '../biomechanics/broadJumpCalculator';
import { HeightCalculator, type HeightMeasurement } from '../biomechanics/heightCalculator';
import { LandmarkSmoother3D } from '../biomechanics/oneEuroFilter';
import {
  type CameraIntrinsicsModel,
  CalibrationQualityGate,
  PlanarHomography,
} from '../biomechanics/planarHomography';
import { PoseLandmarkIndex } from '../biomechanics/poseLandmarkIndex';
import { SprintTimerCalculator, type SprintMeasurement } from '../biomechanics/sprintTimerCalculator';
import {
  VerticalJumpCalculator,
  type VerticalJumpMeasurement,
} from '../biomechanics/verticalJumpCalculator';
import type {
  BroadJumpProtocol,
  CalibrationQuality,
  FrameTimingMetadata,
  LandmarkPoint3D,
  MeasurementOutcome,
  TestType,
  VerticalJumpProtocol,
} from '../model/types';
import { saveVideoClipToOpfs } from '../storage/opfsVideoStore';
import { MediaPipeWorkerClient } from '../vision/visionClient';
import type { VisionDetectResponse } from '../vision/vision.worker';

export type CapturePhase =
  | 'IDLE'
  | 'CAMERA_LIVE'
  | 'RECORDING'
  | 'DECODING_WEBCODECS'
  | 'ANALYZING_WORKER'
  | 'EVALUATED'
  | 'ERROR';

export type AnyAttemptOutcome =
  | MeasurementOutcome<HeightMeasurement>
  | MeasurementOutcome<VerticalJumpMeasurement>
  | MeasurementOutcome<BroadJumpMeasurement>
  | MeasurementOutcome<SprintMeasurement>;

export interface OverlaySample {
  timestampUs: number;
  width: number;
  height: number;
  landmarks: LandmarkPoint3D[];
  headCrown: VisionDetectResponse['headCrown'];
  maskContacts: VisionDetectResponse['maskContacts'];
  refinedHandTip: VisionDetectResponse['refinedHandTip'];
}

export interface EvaluatedCaptureResult {
  clientUuid: string;
  testType: TestType;
  outcome: AnyAttemptOutcome;
  biomechanics: MeasurementOutcome<BiomechanicsReport> | null;
  timing: FrameTimingMetadata;
  decoderUsed: string;
  opfsVideoFilename: string | null;
  warnings: string[];
  calibrationProfileId: string;
  rawMetricsJson: Record<string, unknown>;
  videoObjectUrl: string | null;
  overlayFrames: OverlaySample[];
}

export interface CapturePipelineState {
  phase: CapturePhase;
  capabilities: BrowserCameraCapabilities | null;
  facingMode: CameraFacingMode;
  selectedDeviceId: string | undefined;
  liveLandmarks: LandmarkPoint3D[];
  liveHandTip: VisionDetectResponse['refinedHandTip'];
  liveHeadCrown: VisionDetectResponse['headCrown'];
  liveFrameWidth: number;
  liveFrameHeight: number;
  multiPersonWarning: string | null;
  liveGuidanceFps: number | null;
  progressMessage: string | null;
  error: string | null;
  lastEvaluation: EvaluatedCaptureResult | null;
}

function buildCalibrationQuality(
  profile: CalibrationProfileResponse | undefined,
  intrinsics: CalibrationProfileResponse | undefined,
): { quality: CalibrationQuality | null; homography: PlanarHomography | null } {
  if (!profile || !profile.is_active) {
    return { quality: null, homography: null };
  }
  const hRaw = profile.matrix_json['homography_3x3'];
  if (!Array.isArray(hRaw) || hRaw.length !== 9 || !hRaw.every((v) => typeof v === 'number')) {
    return { quality: null, homography: null };
  }
  let intrinsicsModel: CameraIntrinsicsModel | undefined;
  if (intrinsics && intrinsics.is_active) {
    const kRaw = intrinsics.matrix_json['camera_matrix_3x3'];
    const dRaw = intrinsics.matrix_json['dist_coeffs'];
    if (
      Array.isArray(kRaw) &&
      kRaw.length === 9 &&
      kRaw.every((v) => typeof v === 'number') &&
      Array.isArray(dRaw) &&
      dRaw.length >= 4 &&
      dRaw.every((v) => typeof v === 'number')
    ) {
      intrinsicsModel = { cameraMatrix3x3: kRaw, distCoeffs: dRaw };
    }
  }
  const homography = new PlanarHomography(hRaw, intrinsicsModel);
  const spec = profile.reference_target_spec_json;
  const quality: CalibrationQuality = {
    calibrationProfileId: profile.id,
    planeType: profile.plane_type,
    method: profile.method,
    reprojectionErrorPx: profile.reprojection_error_px,
    isActive: profile.is_active,
    usedIntrinsics: Boolean(intrinsicsModel),
    independentDistanceVerified: Boolean(spec['independent_distance_verified']),
  };
  return { quality, homography };
}

export function useCapturePipeline(api: AthletiqApiClient) {
  const [state, setState] = useState<CapturePipelineState>({
    phase: 'IDLE',
    capabilities: null,
    facingMode: 'environment',
    selectedDeviceId: undefined,
    liveLandmarks: [],
    liveHandTip: null,
    liveHeadCrown: null,
    liveFrameWidth: 0,
    liveFrameHeight: 0,
    multiPersonWarning: null,
    liveGuidanceFps: null,
    progressMessage: null,
    error: null,
    lastEvaluation: null,
  });

  const workerRef = useRef<MediaPipeWorkerClient | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const recorderRef = useRef<AttemptMediaRecorder>(new AttemptMediaRecorder());
  const stopGuidanceRef = useRef<(() => void) | null>(null);
  const liveInFlightRef = useRef(false);
  const livePausedRef = useRef(false);
  const livePromiseRef = useRef<Promise<void> | null>(null);
  const lastVideoUrlRef = useRef<string | null>(null);

  const probeCapabilities = useCallback(async () => {
    const caps = await probeBrowserCameraCapabilities();
    setState((prev) => ({
      ...prev,
      capabilities: caps,
      error: caps.errorMessage,
    }));
    return caps;
  }, []);

  useEffect(() => {
    void probeCapabilities();
    return () => {
      stopGuidanceRef.current?.();
      stopCameraStream(streamRef.current);
      workerRef.current?.dispose();
      if (lastVideoUrlRef.current) {
        URL.revokeObjectURL(lastVideoUrlRef.current);
        lastVideoUrlRef.current = null;
      }
    };
  }, [probeCapabilities]);

  const ensureWorker = useCallback(async (enableHandLandmarker: boolean) => {
    if (!workerRef.current) {
      workerRef.current = new MediaPipeWorkerClient();
    }
    await workerRef.current.initialize({
      enableHandLandmarker,
      verifyChecksums: true,
    });
    return workerRef.current;
  }, []);

  const startLiveCamera = useCallback(
    async (videoEl: HTMLVideoElement, options?: { facingMode?: CameraFacingMode; deviceId?: string }) => {
      stopGuidanceRef.current?.();
      stopCameraStream(streamRef.current);
      setState((prev) => ({ ...prev, error: null, progressMessage: 'Opening camera & MediaPipe worker...' }));

      try {
        const facing = options?.facingMode ?? state.facingMode;
        const stream = await openCameraStream({
          facingMode: facing,
          deviceId: options?.deviceId ?? state.selectedDeviceId,
        });
        streamRef.current = stream;
        videoEl.srcObject = stream;
        await videoEl.play();

        const worker = await ensureWorker(true);
        worker.resetTracking();

        let lastTickUs: number | null = null;
        livePausedRef.current = false;
        stopGuidanceRef.current = startLiveGuidanceLoop(videoEl, async (tick) => {
          if (livePausedRef.current || liveInFlightRef.current) return;
          liveInFlightRef.current = true;
          const run = (async () => {
            try {
              const bmp = await createImageBitmap(videoEl);
              const res = await worker.detectFrame(bmp, tick.timestampUs);
              if (livePausedRef.current) return;
              const fps =
                lastTickUs !== null && tick.timestampUs > lastTickUs
                  ? 1_000_000 / (tick.timestampUs - lastTickUs)
                  : null;
              lastTickUs = tick.timestampUs;

              setState((prev) => ({
                ...prev,
                liveLandmarks: res.landmarks ?? [],
                liveHandTip: res.refinedHandTip,
                liveHeadCrown: res.headCrown,
                liveFrameWidth: res.width,
                liveFrameHeight: res.height,
                liveGuidanceFps: fps,
                multiPersonWarning:
                  res.posesDetectedCount > 1
                    ? `Detected ${res.posesDetectedCount} people in frame; ensure only the tested athlete is visible.`
                    : null,
              }));
            } catch {
              // Live guidance frame skipped cleanly without crashing capture session.
            } finally {
              liveInFlightRef.current = false;
              livePromiseRef.current = null;
            }
          })();
          livePromiseRef.current = run;
          await run;
        });

        setState((prev) => ({
          ...prev,
          phase: 'CAMERA_LIVE',
          facingMode: facing,
          selectedDeviceId: options?.deviceId,
          progressMessage: 'Live guidance active (display-refresh capped). Record attempt for WebCodecs analysis.',
        }));
      } catch (err) {
        setState((prev) => ({
          ...prev,
          phase: 'ERROR',
          error: err instanceof Error ? err.message : 'Unable to start camera stream.',
          progressMessage: null,
        }));
      }
    },
    [ensureWorker, state.facingMode, state.selectedDeviceId],
  );

  const stopLiveCamera = useCallback(() => {
    stopGuidanceRef.current?.();
    stopGuidanceRef.current = null;
    stopCameraStream(streamRef.current);
    streamRef.current = null;
    setState((prev) => ({
      ...prev,
      phase: 'IDLE',
      liveLandmarks: [],
      liveHandTip: null,
      liveHeadCrown: null,
      liveFrameWidth: 0,
      liveFrameHeight: 0,
      liveGuidanceFps: null,
      progressMessage: null,
    }));
  }, []);

  const analyzeVideoBlob = useCallback(
    async (params: {
      blob: Blob;
      testType: TestType;
      calibrations: CalibrationProfileResponse[];
      broadJumpProtocol: BroadJumpProtocol;
      verticalJumpProtocol?: VerticalJumpProtocol;
      sprintDistanceMeters: number;
      saveToOpfs?: boolean;
    }): Promise<EvaluatedCaptureResult | null> => {
      const requiredPlane = CalibrationQualityGate.requiredPlaneFor(params.testType);
      const activePlane = params.calibrations.find(
        (c) => c.plane_type === requiredPlane && c.is_active,
      );
      const activeIntrinsics = params.calibrations.find(
        (c) => c.plane_type === 'INTRINSICS' && c.is_active,
      );
      const { quality, homography } = buildCalibrationQuality(activePlane, activeIntrinsics);
      if (!activePlane || !quality || !homography) {
        setState((prev) => ({
          ...prev,
          phase: 'ERROR',
          error: `Active ${requiredPlane} calibration required before evaluating ${params.testType}.`,
        }));
        return null;
      }

      const clientUuid =
        typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
          ? crypto.randomUUID()
          : `${Date.now()}-${Math.random().toString(16).slice(2)}`;

      let opfsFilename: string | null = null;
      if (params.saveToOpfs !== false) {
        try {
          opfsFilename = await saveVideoClipToOpfs(clientUuid, params.blob);
        } catch {
          opfsFilename = null;
        }
      }

      livePausedRef.current = true;
      setState((prev) => ({
        ...prev,
        phase: 'DECODING_WEBCODECS',
        error: null,
        progressMessage: 'Preparing MediaPipe Web Worker & streaming video frames...',
      }));

      try {
        if (livePromiseRef.current) {
          await livePromiseRef.current.catch(() => undefined);
        }
        const worker = await ensureWorker(params.testType === 'VERTICAL_JUMP');
        worker.resetTracking();

        const smoother = new LandmarkSmoother3D(PoseLandmarkIndex.LANDMARK_COUNT);
        const observations: Array<{
          res: VisionDetectResponse;
          smoothed: LandmarkPoint3D[];
        }> = [];
        const overlayFrames: OverlaySample[] = [];

        const decoded = await decodeVideoWithWebCodecs(params.blob, {
          apiFallback: api,
          onDecodedFrame: async (item, index) => {
            const res = await worker.detectFrame(item.bitmap, item.timestampUs, {
              extractHeadCrown: params.testType === 'HEIGHT',
              extractGroundContacts: params.testType === 'STANDING_BROAD_JUMP',
              extractHandTip: params.testType === 'VERTICAL_JUMP',
            });
            if (res.landmarks) {
              const smoothed = smoother.smooth(res.landmarks, res.timestampUs);
              observations.push({ res, smoothed });
              overlayFrames.push({
                timestampUs: res.timestampUs,
                width: res.width,
                height: res.height,
                landmarks: smoothed,
                headCrown: res.headCrown,
                maskContacts: res.maskContacts,
                refinedHandTip: res.refinedHandTip,
              });
              if (index % 3 === 0) {
                setState((prev) => ({
                  ...prev,
                  phase: 'ANALYZING_WORKER',
                  liveLandmarks: smoothed,
                  liveHandTip: res.refinedHandTip,
                  liveHeadCrown: res.headCrown,
                  liveFrameWidth: res.width,
                  liveFrameHeight: res.height,
                  progressMessage: `Streamed & analyzed ${index + 1} frames in MediaPipe Worker...`,
                }));
              }
            } else if (index % 10 === 0) {
              setState((prev) => ({
                ...prev,
                phase: 'ANALYZING_WORKER',
                progressMessage: `Streamed & analyzed ${index + 1} frames in MediaPipe Worker...`,
              }));
            }
          },
        });

        const recordedAtEpochMs = Date.now();
        let outcome: AnyAttemptOutcome;
        let takeoffUs: number | null = null;
        let landingUs: number | null = null;

        if (params.testType === 'HEIGHT') {
          outcome = new HeightCalculator().calculate(
            observations.map((o) => ({
              timestampUs: o.res.timestampUs,
              imageWidth: o.res.width,
              imageHeight: o.res.height,
              landmarks: o.smoothed,
              headCrown: o.res.headCrown,
            })),
            quality,
            homography,
            decoded.timing,
            recordedAtEpochMs,
          );
        } else if (params.testType === 'VERTICAL_JUMP') {
          const vjOutcome = new VerticalJumpCalculator().calculate(
            observations.map((o) => ({
              timestampUs: o.res.timestampUs,
              imageWidth: o.res.width,
              imageHeight: o.res.height,
              landmarks: o.smoothed,
              refinedHandTip: o.res.refinedHandTip,
            })),
            quality,
            homography,
            decoded.timing,
            recordedAtEpochMs,
            params.verticalJumpProtocol ?? 'STANDING_REACH_DELTA',
          );
          if (vjOutcome.kind === 'VALID') {
            takeoffUs = vjOutcome.detail.takeoffTimestampUs;
            landingUs = vjOutcome.detail.landingTimestampUs;
          }
          outcome = vjOutcome;
        } else if (params.testType === 'STANDING_BROAD_JUMP') {
          const bjOutcome = new BroadJumpCalculator().calculate(
            observations.map((o) => ({
              timestampUs: o.res.timestampUs,
              imageWidth: o.res.width,
              imageHeight: o.res.height,
              landmarks: o.smoothed,
              maskContacts: o.res.maskContacts,
            })),
            quality,
            homography,
            decoded.timing,
            recordedAtEpochMs,
            params.broadJumpProtocol,
          );
          if (bjOutcome.kind === 'VALID' || (bjOutcome.kind === 'INVALID_ATTEMPT' && bjOutcome.detail)) {
            takeoffUs = bjOutcome.detail?.takeoffTimestampUs ?? null;
            landingUs = bjOutcome.detail?.landingTimestampUs ?? null;
          }
          outcome = bjOutcome;
        } else {
          outcome = new SprintTimerCalculator().calculate(
            observations.map((o) => ({
              timestampUs: o.res.timestampUs,
              imageWidth: o.res.width,
              imageHeight: o.res.height,
              landmarks: o.smoothed,
            })),
            params.sprintDistanceMeters,
            quality,
            homography,
            decoded.timing,
            recordedAtEpochMs,
          );
        }

        const biomechanics =
          observations.length >= 5
            ? new BiomechanicsAnalyzer().analyze(
                observations.map((o) => ({
                  timestampUs: o.res.timestampUs,
                  imageWidth: o.res.width,
                  imageHeight: o.res.height,
                  landmarks: o.smoothed,
                })),
                quality,
                decoded.timing,
                recordedAtEpochMs,
                homography,
                takeoffUs,
                landingUs,
              )
            : null;

        const rawMetricsJson: Record<string, unknown> = {
          decoder_used: decoded.decoderUsed,
          timing: decoded.timing,
          outcome_kind: outcome.kind,
          detail: outcome.kind === 'VALID' || outcome.kind === 'INVALID_ATTEMPT' ? outcome.detail ?? null : null,
          biomechanics: biomechanics && biomechanics.kind === 'VALID' ? biomechanics.detail : null,
        };

        if (lastVideoUrlRef.current) {
          URL.revokeObjectURL(lastVideoUrlRef.current);
          lastVideoUrlRef.current = null;
        }
        const videoObjectUrl =
          typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function'
            ? URL.createObjectURL(params.blob)
            : null;
        if (videoObjectUrl) {
          lastVideoUrlRef.current = videoObjectUrl;
        }

        const evaluation: EvaluatedCaptureResult = {
          clientUuid,
          testType: params.testType,
          outcome,
          biomechanics,
          timing: decoded.timing,
          decoderUsed: decoded.decoderUsed,
          opfsVideoFilename: opfsFilename,
          warnings: decoded.warnings,
          calibrationProfileId: activePlane.id,
          rawMetricsJson,
          videoObjectUrl,
          overlayFrames,
        };

        setState((prev) => ({
          ...prev,
          phase: 'EVALUATED',
          progressMessage: null,
          lastEvaluation: evaluation,
        }));
        return evaluation;
      } catch (err) {
        setState((prev) => ({
          ...prev,
          phase: 'ERROR',
          error: err instanceof Error ? err.message : 'Video analysis failed.',
          progressMessage: null,
        }));
        return null;
      } finally {
        livePausedRef.current = false;
        if (streamRef.current && workerRef.current) {
          workerRef.current.resetTracking();
        }
      }
    },
    [api, ensureWorker],
  );

  const startRecordingAttempt = useCallback(() => {
    if (!streamRef.current) {
      setState((prev) => ({ ...prev, error: 'Start live camera before recording an attempt.' }));
      return;
    }
    try {
      recorderRef.current.start(streamRef.current!, 250);
      setState((prev) => ({
        ...prev,
        phase: 'RECORDING',
        error: null,
        progressMessage: 'Recording original webcam stream via MediaRecorder...',
      }));
    } catch (err) {
      setState((prev) => ({
        ...prev,
        error: err instanceof Error ? err.message : 'Failed to start MediaRecorder.',
      }));
    }
  }, []);

  const stopRecordingAndAnalyze = useCallback(
    async (params: {
      testType: TestType;
      calibrations: CalibrationProfileResponse[];
      broadJumpProtocol: BroadJumpProtocol;
      verticalJumpProtocol?: VerticalJumpProtocol;
      sprintDistanceMeters: number;
    }) => {
      try {
        const recorded = await recorderRef.current.stop();
        return await analyzeVideoBlob({
          blob: recorded.blob,
          testType: params.testType,
          calibrations: params.calibrations,
          broadJumpProtocol: params.broadJumpProtocol,
          verticalJumpProtocol: params.verticalJumpProtocol,
          sprintDistanceMeters: params.sprintDistanceMeters,
          saveToOpfs: true,
        });
      } catch (err) {
        setState((prev) => ({
          ...prev,
          phase: 'ERROR',
          error: err instanceof Error ? err.message : 'Failed to stop recording.',
          progressMessage: null,
        }));
        return null;
      }
    },
    [analyzeVideoBlob],
  );

  return useMemo(
    () => ({
      state,
      probeCapabilities,
      startLiveCamera,
      stopLiveCamera,
      startRecordingAttempt,
      stopRecordingAndAnalyze,
      analyzeVideoBlob,
    }),
    [
      state,
      probeCapabilities,
      startLiveCamera,
      stopLiveCamera,
      startRecordingAttempt,
      stopRecordingAndAnalyze,
      analyzeVideoBlob,
    ],
  );
}
