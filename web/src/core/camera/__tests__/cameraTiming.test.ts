import { describe, expect, it } from 'vitest';
import { MonotonicFrameTimingTracker } from '../liveGuidanceStreamer';
import { probeBrowserCameraCapabilities } from '../cameraCapabilities';

describe('MonotonicFrameTimingTracker', () => {
  it('computes actual FPS, mean dt, and flags variable frame rate or dropped frames', () => {
    const tracker = new MonotonicFrameTimingTracker('WEBCODECS_DECODED');
    // 60 FPS -> 16,667 us step
    expect(tracker.record(0)).toBe(true);
    expect(tracker.record(16_667)).toBe(true);
    expect(tracker.record(33_334)).toBe(true);
    // Duplicate non-monotonic timestamp must be rejected and counted as dropped
    expect(tracker.record(33_334)).toBe(false);
    expect(tracker.record(50_000)).toBe(true);

    const summary = tracker.summarize();
    expect(summary).not.toBeNull();
    expect(summary!.frameCount).toBe(4);
    expect(summary!.droppedFrames).toBe(1);
    expect(summary!.isVariableFrameRate).toBe(true);
    expect(summary!.fpsActual).toBeCloseTo(60, 0);
    expect(summary!.firstTimestampUs).toBe(0);
    expect(summary!.lastTimestampUs).toBe(50_000);
  });

  it('reports steady 120 FPS cleanly when timestamps are uniform', () => {
    const tracker = new MonotonicFrameTimingTracker('WEBCODECS_DECODED');
    for (let i = 0; i < 12; i += 1) {
      expect(tracker.record(Math.round(i * 8333.333))).toBe(true);
    }
    const summary = tracker.summarize();
    expect(summary).not.toBeNull();
    expect(summary!.droppedFrames).toBe(0);
    expect(summary!.isVariableFrameRate).toBe(false);
    expect(summary!.fpsActual).toBeCloseTo(120, 0);
  });

  it('reports INSECURE_CONTEXT explicitly when window.isSecureContext is false', async () => {
    Object.defineProperty(window, 'isSecureContext', { value: false, configurable: true });
    const caps = await probeBrowserCameraCapabilities();
    expect(caps.status).toBe('INSECURE_CONTEXT');
    expect(caps.errorMessage).toContain('HTTPS');
  });
});
