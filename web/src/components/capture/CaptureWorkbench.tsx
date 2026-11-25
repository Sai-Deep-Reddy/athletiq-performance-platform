import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Activity,
  AlertTriangle,
  ArrowRight,
  Camera,
  CheckCircle2,
  Clock,
  Eye,
  EyeOff,
  FlipHorizontal,
  Play,
  RefreshCw,
  Ruler,
  SlidersHorizontal,
  Square,
  Upload,
  Video,
  Zap,
} from 'lucide-react';
import { PoseTelemetryCanvas } from '../telemetry/PoseTelemetryCanvas';
import type { useAthletiqCore } from '../../core/domain/useAthletiqCore';
import type { OverlaySample } from '../../core/domain/useCapturePipeline';
import type { BroadJumpProtocol, TestType, VerticalJumpProtocol } from '../../core/model/types';

const TEST_LABELS: Record<TestType, string> = {
  HEIGHT: 'Height',
  VERTICAL_JUMP: 'Vertical Jump',
  STANDING_BROAD_JUMP: 'Broad Jump',
  SPRINT: 'Sprint',
};

const PHASE_LABELS: Record<string, string> = {
  IDLE: 'Ready',
  CAMERA_LIVE: 'Live CV',
  RECORDING: 'Recording',
  DECODING_WEBCODECS: 'Decoding…',
  ANALYZING_WORKER: 'Analyzing…',
  EVALUATED: 'Evaluated',
  ERROR: 'Error',
};

function findClosestOverlayFrame(frames: OverlaySample[], videoTimeSec: number): OverlaySample | null {
  if (frames.length === 0) return null;
  const baseUs = frames[0].timestampUs;
  const targetUs = baseUs + videoTimeSec * 1_000_000;
  let lo = 0;
  let hi = frames.length - 1;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (frames[mid].timestampUs < targetUs) {
      lo = mid + 1;
    } else {
      hi = mid;
    }
  }
  if (lo > 0) {
    const prevDiff = Math.abs(frames[lo - 1].timestampUs - targetUs);
    const currDiff = Math.abs(frames[lo].timestampUs - targetUs);
    if (prevDiff < currDiff) return frames[lo - 1];
  }
  return frames[lo];
}

function PhaseBadge({ phase }: { phase: string }) {
  const isActive = phase === 'CAMERA_LIVE' || phase === 'RECORDING';
  const isProcessing = phase === 'DECODING_WEBCODECS' || phase === 'ANALYZING_WORKER';
  const isResult = phase === 'EVALUATED';
  const isError = phase === 'ERROR';
  return (
    <span
      className={[
        'inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-bold uppercase tracking-wide tnum',
        isActive ? 'bg-red-500/20 text-red-500' : '',
        isProcessing ? 'bg-cyan-500/20 text-cyan-500' : '',
        isResult ? 'bg-emerald-500/15 text-tertiary' : '',
        isError ? 'bg-error-container text-error' : '',
        !isActive && !isProcessing && !isResult && !isError
          ? 'bg-surface-container text-on-surface-variant'
          : '',
      ]
        .filter(Boolean)
        .join(' ')}
    >
      {isActive && <span className="h-1.5 w-1.5 rounded-full bg-red-500 animate-pulse" />}
      {isProcessing && <span className="h-1.5 w-1.5 rounded-full bg-cyan-500 animate-pulse" />}
      {PHASE_LABELS[phase] ?? phase}
    </span>
  );
}

export function CaptureWorkbench({
  core,
  initialTestType = 'VERTICAL_JUMP',
}: {
  core: ReturnType<typeof useAthletiqCore>;
  initialTestType?: TestType;
}) {
  const videoRef = useRef<HTMLVideoElement | null>(null);

  const [testType, setTestType] = useState<TestType>(initialTestType);
  const [verticalJumpProtocol, setVerticalJumpProtocol] =
    useState<VerticalJumpProtocol>('STANDING_REACH_DELTA');
  const [broadJumpProtocol, setBroadJumpProtocol] =
    useState<BroadJumpProtocol>('SAI_KHELO_INDIA_HEEL');
  const [sprintMeters, setSprintMeters] = useState('10');
  const [selectedExamId, setSelectedExamId] = useState('');
  const [selectedStudentId, setSelectedStudentId] = useState('');
  const [syncNotice, setSyncNotice] = useState<string | null>(null);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [facingMode, setFacingMode] = useState<'environment' | 'user'>('environment');

  const [showSkeleton, setShowSkeleton] = useState(true);
  const [showAngles, setShowAngles] = useState(true);
  const [showGuides, setShowGuides] = useState(true);
  const [mirrorX, setMirrorX] = useState(false);

  const [playbackSample, setPlaybackSample] = useState<OverlaySample | null>(null);

  const { state } = core.capture;
  const ev = state.lastEvaluation;
  const isLive = state.phase === 'CAMERA_LIVE' || state.phase === 'RECORDING';
  const isProcessing =
    state.phase === 'DECODING_WEBCODECS' || state.phase === 'ANALYZING_WORKER';
  const hasPlaybackVideo =
    !isLive && !isProcessing && state.phase === 'EVALUATED' && Boolean(ev?.videoObjectUrl);

  useEffect(() => {
    setTestType(initialTestType);
  }, [initialTestType]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    if (hasPlaybackVideo && ev?.videoObjectUrl) {
      video.srcObject = null;
      video.src = ev.videoObjectUrl;
      video.load();
      setPlaybackSample(ev.overlayFrames[0] ?? null);
    } else if (!isLive) {
      video.removeAttribute('src');
      setPlaybackSample(null);
    }
  }, [ev?.overlayFrames, ev?.videoObjectUrl, hasPlaybackVideo, isLive]);

  const syncPlaybackFrame = useCallback(() => {
    const video = videoRef.current;
    if (!video || !ev?.overlayFrames.length) return;
    const sample = findClosestOverlayFrame(ev.overlayFrames, video.currentTime);
    setPlaybackSample(sample);
  }, [ev?.overlayFrames]);

  useEffect(() => {
    if (!hasPlaybackVideo) return;
    const video = videoRef.current;
    if (!video) return;

    let rafId = 0;
    let active = true;

    const tick = () => {
      if (!active) return;
      syncPlaybackFrame();
      if (!video.paused && !video.ended) {
        rafId = requestAnimationFrame(tick);
      }
    };

    const onPlay = () => {
      rafId = requestAnimationFrame(tick);
    };
    const onSeekOrTime = () => {
      syncPlaybackFrame();
    };

    video.addEventListener('play', onPlay);
    video.addEventListener('seeked', onSeekOrTime);
    video.addEventListener('timeupdate', onSeekOrTime);
    return () => {
      active = false;
      cancelAnimationFrame(rafId);
      video.removeEventListener('play', onPlay);
      video.removeEventListener('seeked', onSeekOrTime);
      video.removeEventListener('timeupdate', onSeekOrTime);
    };
  }, [hasPlaybackVideo, syncPlaybackFrame]);

  const handleStartCamera = async (nextFacing = facingMode) => {
    const video = videoRef.current;
    if (!video) return;
    video.removeAttribute('src');
    setSyncNotice(null);
    setSyncError(null);
    await core.capture.startLiveCamera(video, { facingMode: nextFacing });
  };

  const handleFlipCamera = async () => {
    const next = facingMode === 'environment' ? 'user' : 'environment';
    setFacingMode(next);
    setMirrorX(next === 'user');
    if (isLive) {
      await handleStartCamera(next);
    }
  };

  const handleUploadFile = async (file: File | undefined) => {
    if (!file) return;
    setSyncNotice(null);
    setSyncError(null);
    await core.capture.analyzeVideoBlob({
      blob: file,
      testType,
      calibrations: core.workspace.calibrations,
      broadJumpProtocol,
      verticalJumpProtocol,
      sprintDistanceMeters: Math.max(1, Number(sprintMeters) || 10),
      saveToOpfs: true,
    });
  };

  const handleQueueSync = async () => {
    setSyncNotice(null);
    setSyncError(null);
    if (!state.lastEvaluation) return;
    if (!core.auth.isGuest && (!selectedExamId || !selectedStudentId)) {
      setSyncError('Select both an examination and an athlete before queuing.');
      return;
    }
    const res = await core.queueOrSyncEvaluation({
      evaluation: state.lastEvaluation,
      examinationId: selectedExamId || 'guest-local-session',
      studentId: selectedStudentId || 'guest-athlete',
    });
    if (res.syncSummary?.deferredReason) {
      setSyncNotice(`Saved locally to IndexedDB: ${res.syncSummary.deferredReason}`);
    } else {
      setSyncNotice(
        `Synced: ${res.syncSummary?.syncedCount ?? 0} new, ${res.syncSummary?.replayedCount ?? 0} replayed.`,
      );
      await core.refreshWorkspace();
    }
  };

  const caps = state.capabilities;

  const activeLandmarks =
    isLive || isProcessing ? state.liveLandmarks : (playbackSample?.landmarks ?? []);
  const activeHandTip =
    isLive || isProcessing ? state.liveHandTip : (playbackSample?.refinedHandTip ?? null);
  const activeHeadCrown =
    isLive || isProcessing ? state.liveHeadCrown : (playbackSample?.headCrown ?? null);
  const activeSourceW =
    isLive || isProcessing ? state.liveFrameWidth || undefined : playbackSample?.width || undefined;
  const activeSourceH =
    isLive || isProcessing
      ? state.liveFrameHeight || undefined
      : playbackSample?.height || undefined;

  const overlayOptions = useMemo(
    () => ({ showSkeleton, showAngles, showGuides, mirrorX, testType }),
    [showSkeleton, showAngles, showGuides, mirrorX, testType],
  );

  const selectCls =
    'mt-1 min-h-[44px] w-full rounded-xl border border-outline-variant bg-surface-container-low px-3 py-2 text-xs font-medium text-on-surface outline-none transition focus:border-primary focus:ring-2 focus:ring-primary/20';
  const inputCls =
    'mt-1 min-h-[44px] w-full rounded-xl border border-outline-variant bg-surface-container-low px-3 py-2 text-xs font-medium text-on-surface outline-none transition focus:border-primary focus:ring-2 focus:ring-primary/20 tnum';

  return (
    <div className="space-y-4">
      <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2.5">
            <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-surface-container text-primary">
              <Zap className="h-4 w-4" />
            </div>
            <div>
              <h2 className="text-sm font-bold text-on-surface sm:text-base">
                AI Capture &amp; CV Telemetry Workbench
              </h2>
              <p className="text-[11px] text-on-surface-variant">
                Live MediaPipe skeleton overlay + WebCodecs frame-accurate biomechanics
              </p>
            </div>
          </div>
          <PhaseBadge phase={state.phase} />
        </div>

        <div className="-mx-1 overflow-x-auto px-1 pb-0.5">
          <div className="flex min-w-max gap-1.5">
            {(['HEIGHT', 'VERTICAL_JUMP', 'STANDING_BROAD_JUMP', 'SPRINT'] as TestType[]).map(
              (tt) => (
                <button
                  key={tt}
                  type="button"
                  onClick={() => setTestType(tt)}
                  className={[
                    'min-h-[40px] rounded-xl px-3.5 py-2 text-xs font-bold tracking-wide transition-all active:scale-[0.98]',
                    testType === tt
                      ? 'bg-primary-container text-white shadow-sm'
                      : 'border border-outline-variant bg-surface-container-low text-on-surface-variant hover:text-on-surface',
                  ].join(' ')}
                >
                  {TEST_LABELS[tt]}
                </button>
              ),
            )}
          </div>
        </div>
      </div>

      {state.error && (
        <div className="flex items-start gap-2 rounded-xl border border-error/30 bg-error-container/50 p-3 text-xs text-error">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{state.error}</span>
        </div>
      )}
      {state.multiPersonWarning && (
        <div className="flex items-start gap-2 rounded-xl border border-amber-500/30 bg-amber-500/15 p-3 text-xs text-amber-800 dark:text-amber-300">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{state.multiPersonWarning}</span>
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-[1fr_380px]">
        <div className="flex flex-col gap-3">
          <div className="overflow-hidden rounded-2xl border border-slate-800 bg-telemetry-dark text-white shadow-md">
            <div className="flex items-center justify-between border-b border-white/10 px-3.5 py-2 text-[11px]">
              <div className="flex items-center gap-2">
                <span
                  className={`h-2 w-2 rounded-full ${
                    state.phase === 'RECORDING'
                      ? 'bg-red-500 animate-ping'
                      : isLive
                        ? 'bg-cyan-400 animate-pulse'
                        : 'bg-slate-600'
                  }`}
                />
                <span className="font-semibold tracking-wide text-slate-200">
                  {state.phase === 'RECORDING'
                    ? 'RECORDING STREAM'
                    : isLive
                      ? 'CV TRACKING ACTIVE'
                      : hasPlaybackVideo
                        ? 'POST-CAPTURE CV REPLAY'
                        : 'OPTICAL VIEWPORT STANDBY'}
                </span>
              </div>
              <div className="flex items-center gap-2 tnum text-slate-400">
                {activeLandmarks.length > 0 && (
                  <span className="rounded bg-cyan-500/15 px-1.5 py-0.5 text-[10px] font-bold text-cyan-300">
                    33 PTS LOCKED
                  </span>
                )}
                {state.liveGuidanceFps !== null && (
                  <span>{state.liveGuidanceFps.toFixed(1)} FPS</span>
                )}
              </div>
            </div>

            <div className="relative aspect-[4/3] w-full bg-black sm:aspect-video">
              <video
                ref={videoRef}
                playsInline
                muted={!hasPlaybackVideo}
                controls={hasPlaybackVideo}
                className={`h-full w-full object-contain ${
                  mirrorX && isLive ? '-scale-x-100' : ''
                }`}
              />
              <PoseTelemetryCanvas
                videoEl={videoRef.current}
                landmarks={activeLandmarks}
                handTip={activeHandTip}
                headCrown={activeHeadCrown}
                sourceWidth={activeSourceW}
                sourceHeight={activeSourceH}
                options={overlayOptions}
              />

              {state.phase === 'IDLE' && (
                <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center p-4 text-center">
                  <div className="flex h-14 w-14 items-center justify-center rounded-2xl border border-white/10 bg-white/5 text-cyan-400">
                    <Camera className="h-7 w-7" />
                  </div>
                  <p className="mt-3 text-sm font-semibold text-slate-200">
                    Start Camera or Upload Attempt Clip
                  </p>
                  <p className="mt-1 max-w-xs text-xs text-slate-400">
                    Real-time MediaPipe pose skeleton, joint angles, CoM reticle, and reach markers render directly over the video stream.
                  </p>
                </div>
              )}

              {isProcessing && (
                <div className="absolute inset-0 flex flex-col items-center justify-center bg-slate-950/75 px-4 text-center backdrop-blur-xs">
                  <div className="animate-hud-scan absolute inset-x-0 top-1/2 h-0.5 bg-cyan-400/80 shadow-[0_0_16px_#00f2fe]" />
                  <Activity className="h-8 w-8 animate-pulse text-cyan-400" />
                  <p className="mt-3 text-sm font-bold text-cyan-300">
                    {PHASE_LABELS[state.phase]}
                  </p>
                  {state.progressMessage && (
                    <p className="mt-1 max-w-sm text-xs text-slate-300">{state.progressMessage}</p>
                  )}
                </div>
              )}
            </div>

            <div className="flex flex-wrap items-center gap-1.5 border-t border-white/10 bg-telemetry-elevated/70 p-2.5 sm:px-3.5">
              <span className="mr-1 hidden items-center gap-1 text-[10px] font-bold uppercase tracking-wider text-slate-400 sm:inline-flex">
                <SlidersHorizontal className="h-3 w-3" /> CV Layers
              </span>
              {[
                { key: 'skeleton', label: 'Skeleton', val: showSkeleton, set: setShowSkeleton, Icon: Eye },
                { key: 'angles', label: 'Angles', val: showAngles, set: setShowAngles, Icon: Ruler },
                { key: 'guides', label: 'Guides', val: showGuides, set: setShowGuides, Icon: Activity },
                { key: 'mirror', label: 'Mirror', val: mirrorX, set: setMirrorX, Icon: FlipHorizontal },
              ].map(({ key, label, val, set, Icon }) => (
                <button
                  key={key}
                  type="button"
                  onClick={() => set((v) => !v)}
                  className={[
                    'inline-flex min-h-[36px] items-center gap-1.5 rounded-lg px-2.5 py-1 text-xs font-semibold transition-all',
                    val
                      ? 'bg-cyan-500/20 text-cyan-300 ring-1 ring-cyan-500/40'
                      : 'bg-slate-800/70 text-slate-400 ring-1 ring-white/10 hover:text-slate-200',
                  ].join(' ')}
                >
                  {val ? <Icon className="h-3.5 w-3.5" /> : <EyeOff className="h-3.5 w-3.5" />}
                  {label}
                </button>
              ))}

              {hasPlaybackVideo && (
                <button
                  type="button"
                  onClick={() => {
                    const v = videoRef.current;
                    if (v) {
                      v.currentTime = 0;
                      void v.play();
                    }
                  }}
                  className="ml-auto inline-flex min-h-[36px] items-center gap-1 rounded-lg bg-emerald-500/20 px-2.5 py-1 text-xs font-semibold text-emerald-300 ring-1 ring-emerald-500/40"
                >
                  <Play className="h-3.5 w-3.5" /> Replay CV
                </button>
              )}
            </div>

            <div className="grid grid-cols-2 gap-2 border-t border-white/10 p-3 sm:flex sm:flex-wrap">
              {(state.phase === 'IDLE' ||
                state.phase === 'ERROR' ||
                state.phase === 'EVALUATED') && (
                <button
                  type="button"
                  onClick={() => void handleStartCamera()}
                  className="col-span-2 inline-flex min-h-[50px] flex-1 items-center justify-center gap-2 rounded-xl bg-primary-container px-4 py-2.5 text-sm font-bold text-white shadow-sm transition-all hover:brightness-110 active:scale-[0.98]"
                >
                  <Camera className="h-5 w-5" />
                  Start Camera
                </button>
              )}

              {state.phase === 'CAMERA_LIVE' && (
                <button
                  type="button"
                  onClick={() => core.capture.startRecordingAttempt()}
                  className="col-span-2 inline-flex min-h-[50px] flex-1 items-center justify-center gap-2 rounded-xl bg-red-600 px-4 py-2.5 text-sm font-bold text-white shadow-sm transition-all hover:bg-red-500 active:scale-[0.98]"
                >
                  <Video className="h-5 w-5" />
                  Record Attempt
                </button>
              )}

              {state.phase === 'RECORDING' && (
                <button
                  type="button"
                  onClick={() =>
                    void core.capture.stopRecordingAndAnalyze({
                      testType,
                      calibrations: core.workspace.calibrations,
                      broadJumpProtocol,
                      verticalJumpProtocol,
                      sprintDistanceMeters: Math.max(1, Number(sprintMeters) || 10),
                    })
                  }
                  className="col-span-2 inline-flex min-h-[50px] flex-1 items-center justify-center gap-2 rounded-xl bg-red-600 px-4 py-2.5 text-sm font-bold text-white animate-pulse active:scale-[0.98]"
                >
                  <Square className="h-5 w-5" />
                  Stop &amp; Analyze
                </button>
              )}

              {isLive && (
                <button
                  type="button"
                  onClick={() => core.capture.stopLiveCamera()}
                  className="inline-flex min-h-[46px] items-center justify-center gap-1.5 rounded-xl bg-slate-700 px-3 py-2 text-xs font-bold text-white active:scale-[0.98]"
                >
                  Stop Camera
                </button>
              )}

              <button
                type="button"
                onClick={() => void handleFlipCamera()}
                className="inline-flex min-h-[46px] items-center justify-center gap-1.5 rounded-xl border border-white/15 bg-white/5 px-3 py-2 text-xs font-semibold text-slate-200 active:scale-[0.98]"
              >
                <FlipHorizontal className="h-4 w-4" />
                {facingMode === 'environment' ? 'Rear Cam' : 'Front Cam'}
              </button>

              <label className="inline-flex min-h-[46px] cursor-pointer items-center justify-center gap-1.5 rounded-xl border border-white/15 bg-white/5 px-3 py-2 text-xs font-semibold text-slate-200 hover:bg-white/10 active:scale-[0.98]">
                <Upload className="h-4 w-4" />
                Upload Video
                <input
                  type="file"
                  accept="video/mp4,video/webm,video/*"
                  className="hidden"
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    e.target.value = '';
                    void handleUploadFile(file);
                  }}
                />
              </label>

              <button
                type="button"
                onClick={() => void core.capture.probeCapabilities()}
                className="inline-flex min-h-[46px] items-center justify-center gap-1.5 rounded-xl border border-white/15 px-3 py-2 text-xs text-slate-300 active:scale-[0.98]"
              >
                <RefreshCw className="h-3.5 w-3.5" />
                Re-probe
              </button>
            </div>
          </div>

          <div className="flex flex-wrap gap-2 text-[11px]">
            {[
              {
                label: `Camera: ${caps?.status ?? 'PROBING'}`,
                ok: caps?.status === 'READY',
              },
              {
                label: `Decoder: ${caps?.hasWebCodecs ? 'WebCodecs' : 'Server FFmpeg'}`,
                ok: Boolean(caps?.hasWebCodecs),
              },
              {
                label: `OPFS: ${caps?.hasOpfs ? 'Ready' : 'Unavailable'}`,
                ok: Boolean(caps?.hasOpfs),
              },
            ].map(({ label, ok }) => (
              <span
                key={label}
                className={`rounded-lg border px-2.5 py-1 font-semibold tnum ${
                  ok
                    ? 'border-tertiary/25 bg-emerald-500/10 text-tertiary'
                    : 'border-outline-variant bg-surface-container-low text-on-surface-variant'
                }`}
              >
                {label}
              </span>
            ))}
          </div>
        </div>

        <div className="flex flex-col gap-4">
          <div className="rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-3">
            <h3 className="text-[11px] font-bold uppercase tracking-wider text-on-surface-variant">
              Protocol &amp; Target Cohort
            </h3>

            {testType === 'VERTICAL_JUMP' && (
              <label className="block text-xs text-on-surface-variant">
                Vertical Jump Protocol
                <select
                  value={verticalJumpProtocol}
                  onChange={(e) => setVerticalJumpProtocol(e.target.value as VerticalJumpProtocol)}
                  className={selectCls}
                >
                  <option value="STANDING_REACH_DELTA">Standing Reach Delta</option>
                  <option value="COUNTERMOVEMENT_FLIGHT_TIME">CMJ Flight Time (g·t²/8)</option>
                </select>
              </label>
            )}

            {testType === 'STANDING_BROAD_JUMP' && (
              <label className="block text-xs text-on-surface-variant">
                Broad Jump Protocol
                <select
                  value={broadJumpProtocol}
                  onChange={(e) => setBroadJumpProtocol(e.target.value as BroadJumpProtocol)}
                  className={selectCls}
                >
                  <option value="SAI_KHELO_INDIA_HEEL">SAI Khelo India (Heel / Foul)</option>
                  <option value="NEAREST_BODY_CONTACT">Nearest Body Contact</option>
                  <option value="NEAREST_FOOT_OR_SHOE_CONTACT">Nearest Foot/Shoe Candidate</option>
                </select>
              </label>
            )}

            {testType === 'SPRINT' && (
              <label className="block text-xs text-on-surface-variant">
                Sprint Distance (meters)
                <input
                  value={sprintMeters}
                  onChange={(e) => setSprintMeters(e.target.value)}
                  type="number"
                  min="1"
                  max="100"
                  className={inputCls}
                />
              </label>
            )}

            {!core.auth.isGuest ? (
              <>
                <label className="block text-xs text-on-surface-variant">
                  Examination
                  <select
                    value={selectedExamId}
                    onChange={(e) => setSelectedExamId(e.target.value)}
                    className={selectCls}
                  >
                    <option value="">— Select examination —</option>
                    {core.workspace.examinations.map((ex) => (
                      <option key={ex.id} value={ex.id}>
                        {ex.title} ({ex.status})
                      </option>
                    ))}
                  </select>
                </label>

                <label className="block text-xs text-on-surface-variant">
                  Athlete
                  <select
                    value={selectedStudentId}
                    onChange={(e) => setSelectedStudentId(e.target.value)}
                    className={selectCls}
                  >
                    <option value="">— Select athlete —</option>
                    {core.workspace.students.map((st) => (
                      <option key={st.id} value={st.id}>
                        {st.institution_student_code} — {st.full_name}
                      </option>
                    ))}
                  </select>
                </label>
              </>
            ) : (
              <div className="rounded-xl border border-outline-variant bg-surface-container-low p-3 text-xs text-on-surface-variant">
                Guest Mode active — evaluations save locally to IndexedDB without requiring an institutional cohort.
              </div>
            )}
          </div>

          <div className="flex-1 rounded-2xl border border-outline-variant bg-surface-container-lowest p-4 shadow-xs space-y-3">
            <h3 className="text-[11px] font-bold uppercase tracking-wider text-on-surface-variant">
              Biomechanics Output &amp; Provenance
            </h3>

            {!ev ? (
              <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-outline-variant bg-surface-container-low/50 px-4 py-8 text-center">
                <Zap className="h-8 w-8 text-outline" />
                <p className="mt-2 text-xs font-semibold text-on-surface">No capture evaluated yet</p>
                <p className="mt-1 text-xs text-on-surface-variant">
                  Record from camera or upload MP4/WebM footage to inspect real WebCodecs &amp; MediaPipe kinematics.
                </p>
              </div>
            ) : (
              <div className="space-y-3">
                {ev.outcome.kind === 'VALID' ? (
                  <>
                    <div className="rounded-xl border border-tertiary/30 bg-emerald-500/10 p-4">
                      <div className="flex items-center justify-between gap-2 text-xs font-bold text-tertiary">
                        <span className="inline-flex items-center gap-1.5">
                          <CheckCircle2 className="h-4 w-4" />
                          {ev.testType} • {ev.outcome.validityStatus}
                        </span>
                        <span className="tnum">
                          {(ev.outcome.confidenceScore * 100).toFixed(0)}% conf
                        </span>
                      </div>
                      <p className="mt-2 text-3xl font-bold text-on-surface sm:text-4xl tnum">
                        {ev.outcome.value.toFixed(2)}{' '}
                        <span className="text-base font-semibold text-on-surface-variant">
                          {ev.outcome.unit}
                        </span>
                      </p>
                      <p className="mt-1 text-xs text-on-surface-variant tnum">
                        Uncertainty: ±{ev.outcome.uncertainty.toFixed(2)} {ev.outcome.unit}
                      </p>
                    </div>

                    {ev.testType === 'VERTICAL_JUMP' &&
                      'flightTimeEstimate' in ev.outcome.detail && (
                        <div className="grid grid-cols-2 gap-2 text-xs tnum">
                          <div className="rounded-xl border border-outline-variant bg-surface-container-low p-3">
                            <div className="flex items-center gap-1 text-[11px] text-on-surface-variant">
                              <Clock className="h-3 w-3" /> Flight Time
                            </div>
                            <p className="mt-0.5 text-lg font-bold text-primary">
                              {(ev.outcome.detail.flightTimeEstimate.flightTimeSec * 1000).toFixed(0)}{' '}
                              ms
                            </p>
                          </div>
                          <div className="rounded-xl border border-outline-variant bg-surface-container-low p-3">
                            <div className="text-[11px] text-on-surface-variant">Flight Height</div>
                            <p className="mt-0.5 text-lg font-bold text-tertiary">
                              {ev.outcome.detail.flightTimeEstimate.flightHeightCm.toFixed(1)} cm
                            </p>
                          </div>
                        </div>
                      )}

                    <p className="text-[11px] text-on-surface-variant">
                      {ev.outcome.provenance.algorithmVersion} •{' '}
                      {ev.outcome.provenance.landmarkProvenance}
                    </p>
                  </>
                ) : ev.outcome.kind === 'INVALID_ATTEMPT' ? (
                  <div className="rounded-xl border border-error/30 bg-error-container/50 p-3 text-xs text-error">
                    <div className="mb-1.5 flex items-center gap-1.5 font-bold">
                      <AlertTriangle className="h-4 w-4" />
                      INVALID ATTEMPT — {ev.testType}
                    </div>
                    <ul className="list-disc space-y-1 pl-4">
                      {ev.outcome.reasons.map((r, i) => (
                        <li key={i}>{r}</li>
                      ))}
                    </ul>
                  </div>
                ) : (
                  <div className="rounded-xl border border-amber-500/30 bg-amber-500/15 p-3 text-xs text-amber-800 dark:text-amber-300">
                    {ev.outcome.reason}
                  </div>
                )}

                <div className="rounded-xl border border-outline-variant bg-surface-container-low p-3 text-[11px] text-on-surface-variant tnum">
                  {ev.decoderUsed} • {ev.timing.frameCount} frames •{' '}
                  {ev.timing.fpsActual.toFixed(2)} fps • dt {ev.timing.frameDtMs.toFixed(2)} ms
                  {ev.opfsVideoFilename && (
                    <p className="mt-1 font-semibold text-tertiary">
                      OPFS clip saved: {ev.opfsVideoFilename}
                    </p>
                  )}
                </div>

                {(ev.outcome.kind === 'VALID' || ev.outcome.kind === 'INVALID_ATTEMPT') && (
                  <button
                    type="button"
                    onClick={() => void handleQueueSync()}
                    disabled={!core.auth.isGuest && (!selectedExamId || !selectedStudentId)}
                    className="inline-flex min-h-[48px] w-full items-center justify-center gap-2 rounded-xl bg-primary-container px-4 py-2.5 text-xs font-bold text-white shadow-sm transition-all hover:brightness-110 disabled:opacity-40 active:scale-[0.99]"
                  >
                    <ArrowRight className="h-4 w-4" />
                    {core.auth.isGuest
                      ? 'Save Attempt Locally (IndexedDB)'
                      : 'Queue & Sync to Examination'}
                  </button>
                )}

                {syncNotice && (
                  <p className="rounded-lg bg-emerald-500/15 px-3 py-2 text-xs text-tertiary">
                    {syncNotice}
                  </p>
                )}
                {syncError && (
                  <p className="rounded-lg bg-error-container/50 px-3 py-2 text-xs text-error">
                    {syncError}
                  </p>
                )}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
