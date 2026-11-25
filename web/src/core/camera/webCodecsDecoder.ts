import { ALL_FORMATS, BlobSource, Input, VideoSampleSink } from 'mediabunny';
import type { AthletiqApiClient } from '../api/client';
import type { FrameTimingMetadata } from '../model/types';
import { MonotonicFrameTimingTracker } from './liveGuidanceStreamer';

export interface DecodedVideoFrameItem {
  bitmap: ImageBitmap;
  timestampUs: number;
  width: number;
  height: number;
}

class StreamConsumerError extends Error {
  readonly causeError: unknown;
  constructor(causeError: unknown) {
    super(causeError instanceof Error ? causeError.message : 'Frame consumer error');
    this.name = 'StreamConsumerError';
    this.causeError = causeError;
  }
}

export interface VideoDecodeResult {
  frames: DecodedVideoFrameItem[];
  decodedCount: number;
  timing: FrameTimingMetadata;
  decoderUsed: 'WEBCODECS_MEDIABUNNY' | 'SERVER_FFMPEG_FALLBACK';
  warnings: string[];
}

export async function decodeVideoWithWebCodecs(
  blob: Blob,
  options: {
    apiFallback?: AthletiqApiClient;
    maxFrames?: number;
    onDecodedFrame?: (item: DecodedVideoFrameItem, index: number) => Promise<void> | void;
    onProgress?: (decodedCount: number) => void;
    signal?: AbortSignal;
  } = {},
): Promise<VideoDecodeResult> {
  const maxFrames = options.maxFrames ?? 1200;
  const warnings: string[] = [];
  const hasWebCodecs =
    typeof VideoDecoder !== 'undefined' && typeof VideoDecoder.isConfigSupported === 'function';

  if (hasWebCodecs) {
    const input = new Input({
      formats: ALL_FORMATS,
      source: new BlobSource(blob),
    });

    try {
      const videoTrack = await input.getPrimaryVideoTrack();
      if (!videoTrack) {
        throw new Error('No video track found in media container.');
      }

      const decoderConfig = await videoTrack.getDecoderConfig();
      if (decoderConfig) {
        const support = await VideoDecoder.isConfigSupported(decoderConfig);
        if (support.supported) {
          const sink = new VideoSampleSink(videoTrack);
          const tracker = new MonotonicFrameTimingTracker('WEBCODECS_DECODED');
          const frames: DecodedVideoFrameItem[] = [];
          let decodedCount = 0;

          for await (const sample of sink.samples()) {
            if (options.signal?.aborted) {
              sample.close();
              for (const f of frames) f.bitmap.close();
              throw new DOMException('Video decoding aborted', 'AbortError');
            }
            if (decodedCount >= maxFrames) {
              sample.close();
              warnings.push(`Reached max decode limit (${maxFrames} frames); remaining frames skipped.`);
              break;
            }

            const timestampUs = Math.max(0, Math.round(sample.microsecondTimestamp));
            const videoFrame = sample.toVideoFrame();
            let bitmap: ImageBitmap | null = null;
            try {
              bitmap = await createImageBitmap(videoFrame);
              const accepted = tracker.record(timestampUs);
              if (accepted) {
                const item: DecodedVideoFrameItem = {
                  bitmap,
                  timestampUs,
                  width: sample.displayWidth,
                  height: sample.displayHeight,
                };
                if (options.onDecodedFrame) {
                  // Stream frame-by-frame to avoid buffering hundreds of uncompressed bitmaps in RAM.
                  bitmap = null;
                  try {
                    await options.onDecodedFrame(item, decodedCount);
                  } catch (streamErr) {
                    try {
                      item.bitmap.close();
                    } catch {
                      // Bitmap may already have been transferred to worker.
                    }
                    throw new StreamConsumerError(streamErr);
                  }
                } else {
                  frames.push(item);
                  bitmap = null;
                }
                decodedCount += 1;
                options.onProgress?.(decodedCount);
              }
            } finally {
              if (bitmap) {
                bitmap.close();
              }
              videoFrame.close();
              sample.close();
            }
          }

          const timing = tracker.summarize();
          if (!timing || decodedCount < 2) {
            for (const f of frames) f.bitmap.close();
            throw new Error('Decoded fewer than 2 valid frames with monotonic timestamps.');
          }
          return {
            frames,
            decodedCount,
            timing,
            decoderUsed: 'WEBCODECS_MEDIABUNNY',
            warnings,
          };
        }
        warnings.push(
          `WebCodecs codec '${decoderConfig.codec}' is unsupported in this browser; attempting server FFmpeg fallback.`,
        );
      } else {
        warnings.push('Video track did not expose a WebCodecs decoder configuration; attempting fallback.');
      }
    } catch (err) {
      if (err instanceof StreamConsumerError) {
        throw err.causeError;
      }
      if (err instanceof DOMException && err.name === 'AbortError') {
        throw err;
      }
      if (!options.apiFallback) {
        throw err;
      }
      warnings.push(
        err instanceof Error
          ? `WebCodecs decode unavailable (${err.message}); falling back to server extraction.`
          : 'WebCodecs decode failed; falling back to server extraction.',
      );
    } finally {
      input.dispose();
    }
  } else {
    warnings.push('WebCodecs API is unavailable in this browser; attempting server FFmpeg fallback.');
  }

  if (!options.apiFallback) {
    throw new Error('WebCodecs is unsupported in this browser and no server fallback client was supplied.');
  }

  const formData = new FormData();
  formData.append('file', blob, 'attempt_video.bin');
  formData.append('max_frames', String(Math.min(maxFrames, 600)));
  const fallback = await options.apiFallback.extractVideoFramesFallback(formData);

  const tracker = new MonotonicFrameTimingTracker('FFMPEG_FALLBACK');
  const frames: DecodedVideoFrameItem[] = [];
  let decodedCount = 0;
  for (let i = 0; i < fallback.frames_base64.length; i += 1) {
    const binStr = atob(fallback.frames_base64[i]);
    const bytes = new Uint8Array(binStr.length);
    for (let j = 0; j < binStr.length; j += 1) {
      bytes[j] = binStr.charCodeAt(j);
    }
    const imgBlob = new Blob([bytes], { type: 'image/jpeg' });
    const bitmap = await createImageBitmap(imgBlob);
    const tsUs = fallback.timestamps_us[i];
    if (tracker.record(tsUs)) {
      const item: DecodedVideoFrameItem = {
        bitmap,
        timestampUs: tsUs,
        width: bitmap.width,
        height: bitmap.height,
      };
      if (options.onDecodedFrame) {
        try {
          await options.onDecodedFrame(item, decodedCount);
        } catch (streamErr) {
          try {
            item.bitmap.close();
          } catch {
            // Bitmap may already have been transferred to worker.
          }
          throw streamErr;
        }
      } else {
        frames.push(item);
      }
      decodedCount += 1;
      options.onProgress?.(decodedCount);
    } else {
      bitmap.close();
    }
  }
  const timing = tracker.summarize();
  if (!timing) {
    for (const f of frames) f.bitmap.close();
    throw new Error('Server FFmpeg fallback returned insufficient monotonic frames.');
  }
  return {
    frames,
    decodedCount,
    timing,
    decoderUsed: 'SERVER_FFMPEG_FALLBACK',
    warnings,
  };
}
