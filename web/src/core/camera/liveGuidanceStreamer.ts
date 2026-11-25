import type { FrameTimingMetadata } from '../model/types';

export interface LiveFrameTick {
  timestampUs: number;
  presentedFrames: number;
  width: number;
  height: number;
}

export class MonotonicFrameTimingTracker {
  private timestampsUs: number[] = [];
  private droppedFrames = 0;
  private lastPresentedFrames: number | null = null;
  private readonly sourceKind: FrameTimingMetadata['sourceKind'];

  constructor(sourceKind: FrameTimingMetadata['sourceKind']) {
    this.sourceKind = sourceKind;
  }

  record(timestampUs: number, presentedFrames?: number): boolean {
    if (!Number.isFinite(timestampUs) || timestampUs < 0) {
      return false;
    }
    if (
      this.timestampsUs.length > 0 &&
      timestampUs <= this.timestampsUs[this.timestampsUs.length - 1]
    ) {
      // Drop non-monotonic duplicate frames cleanly instead of fabricating synthetic timestamps.
      this.droppedFrames += 1;
      return false;
    }

    if (
      presentedFrames !== undefined &&
      this.lastPresentedFrames !== null &&
      presentedFrames > this.lastPresentedFrames + 1
    ) {
      this.droppedFrames += presentedFrames - this.lastPresentedFrames - 1;
    }
    if (presentedFrames !== undefined) {
      this.lastPresentedFrames = presentedFrames;
    }

    this.timestampsUs.push(timestampUs);
    return true;
  }

  markDropped(count = 1): void {
    this.droppedFrames += Math.max(0, count);
  }

  summarize(): FrameTimingMetadata | null {
    const n = this.timestampsUs.length;
    if (n < 2) {
      return null;
    }

    const firstUs = this.timestampsUs[0];
    const lastUs = this.timestampsUs[n - 1];
    const durationUs = lastUs - firstUs;
    if (durationUs <= 0) {
      return null;
    }

    const deltasMs: number[] = [];
    for (let i = 1; i < n; i += 1) {
      deltasMs.push((this.timestampsUs[i] - this.timestampsUs[i - 1]) / 1000);
    }
    const meanDtMs = deltasMs.reduce((a, b) => a + b, 0) / deltasMs.length;
    const fpsActual = 1000 / meanDtMs;

    // Detect variable frame rate when any interval deviates > 35% from mean dt or frames dropped.
    const maxDeviation = deltasMs.reduce(
      (acc, dt) => Math.max(acc, Math.abs(dt - meanDtMs) / meanDtMs),
      0,
    );
    const isVariableFrameRate = maxDeviation > 0.35 || this.droppedFrames > 0;

    return {
      fpsActual,
      frameDtMs: meanDtMs,
      droppedFrames: this.droppedFrames,
      isVariableFrameRate,
      frameCount: n,
      durationMs: durationUs / 1000,
      sourceKind: this.sourceKind,
      firstTimestampUs: firstUs,
      lastTimestampUs: lastUs,
    };
  }
}

/**
 * Live camera preview guidance streamer using requestVideoFrameCallback.
 * Strictly for framing & live skeleton guidance (display-refresh capped);
 * official assessment analysis runs post-recording via WebCodecs on the recorded MediaRecorder blob.
 */
export function startLiveGuidanceLoop(
  video: HTMLVideoElement,
  onFrame: (tick: LiveFrameTick) => void,
): () => void {
  let cancelled = false;
  let rvfcHandle: number | null = null;
  let rafHandle: number | null = null;
  const tracker = new MonotonicFrameTimingTracker('LIVE_GUIDANCE_RVFC');

  const rvfc = (
    video as HTMLVideoElement & {
      requestVideoFrameCallback?: (
        cb: (now: number, metadata: VideoFrameCallbackMetadata) => void,
      ) => number;
      cancelVideoFrameCallback?: (id: number) => void;
    }
  ).requestVideoFrameCallback;

  if (typeof rvfc === 'function') {
    const step = (_now: number, metadata: VideoFrameCallbackMetadata) => {
      if (cancelled) return;
      const tsUs = Math.round(metadata.mediaTime * 1_000_000);
      if (tracker.record(tsUs, metadata.presentedFrames) && video.videoWidth > 0) {
        onFrame({
          timestampUs: tsUs,
          presentedFrames: metadata.presentedFrames,
          width: video.videoWidth,
          height: video.videoHeight,
        });
      }
      rvfcHandle = rvfc.call(video, step);
    };
    rvfcHandle = rvfc.call(video, step);
  } else {
    let lastVideoTime = -1;
    let frameIndex = 0;
    const stepRaf = () => {
      if (cancelled) return;
      if (video.readyState >= 2 && video.currentTime > lastVideoTime && video.videoWidth > 0) {
        lastVideoTime = video.currentTime;
        frameIndex += 1;
        const tsUs = Math.round(video.currentTime * 1_000_000);
        if (tracker.record(tsUs, frameIndex)) {
          onFrame({
            timestampUs: tsUs,
            presentedFrames: frameIndex,
            width: video.videoWidth,
            height: video.videoHeight,
          });
        }
      }
      rafHandle = requestAnimationFrame(stepRaf);
    };
    rafHandle = requestAnimationFrame(stepRaf);
  }

  return () => {
    cancelled = true;
    const cancelRvfc = (
      video as HTMLVideoElement & { cancelVideoFrameCallback?: (id: number) => void }
    ).cancelVideoFrameCallback;
    if (rvfcHandle !== null && typeof cancelRvfc === 'function') {
      cancelRvfc.call(video, rvfcHandle);
    }
    if (rafHandle !== null) {
      cancelAnimationFrame(rafHandle);
    }
  };
}
