import { verifyVisionModelManifest } from './modelVerifier';
import type {
  VisionDetectResponse,
  VisionWorkerRequest,
  VisionWorkerResponse,
} from './vision.worker';

export interface DetectFrameOptions {
  extractHeadCrown?: boolean;
  extractGroundContacts?: boolean;
  extractHandTip?: boolean;
}

export class MediaPipeWorkerClient {
  private worker: Worker | null = null;
  private nextRequestId = 1;
  private pending = new Map<
    number,
    { resolve: (res: VisionDetectResponse) => void; reject: (err: Error) => void }
  >();
  private readyPromise: Promise<void> | null = null;

  async initialize(options?: {
    enableHandLandmarker?: boolean;
    verifyChecksums?: boolean;
    delegate?: 'GPU' | 'CPU';
  }): Promise<void> {
    if (this.readyPromise) {
      return this.readyPromise;
    }

    this.readyPromise = (async () => {
      const assets = await verifyVisionModelManifest({
        verifyChecksums: options?.verifyChecksums ?? false,
      });

      const worker = new Worker(new URL('./vision.worker.ts', import.meta.url), {
        type: 'module',
      });
      this.worker = worker;

      await new Promise<void>((resolve, reject) => {
        worker.onmessage = (event: MessageEvent<VisionWorkerResponse>) => {
          const msg = event.data;
          if (msg.type === 'READY') {
            resolve();
            return;
          }
          if (msg.type === 'DETECT_RESULT') {
            const waiter = this.pending.get(msg.id);
            if (waiter) {
              this.pending.delete(msg.id);
              waiter.resolve(msg);
            }
            return;
          }
          if (msg.type === 'ERROR') {
            if (msg.id !== undefined) {
              const waiter = this.pending.get(msg.id);
              if (waiter) {
                this.pending.delete(msg.id);
                waiter.reject(new Error(msg.message));
              }
            } else {
              reject(new Error(msg.message));
            }
          }
        };
        worker.onerror = (err) => {
          reject(new Error(err.message || 'MediaPipe Web Worker crashed'));
        };

        const initMsg: VisionWorkerRequest = {
          type: 'INIT',
          wasmBasePath: assets.wasmBasePath,
          poseModelPath: assets.poseModelPath,
          handModelPath: assets.handModelPath,
          enableHandLandmarker: options?.enableHandLandmarker ?? true,
          delegate: options?.delegate ?? 'GPU',
        };
        worker.postMessage(initMsg);
      });
    })();

    return this.readyPromise;
  }

  async detectFrame(
    bitmap: ImageBitmap,
    timestampUs: number,
    options: DetectFrameOptions = {},
  ): Promise<VisionDetectResponse> {
    if (!this.worker) {
      bitmap.close();
      throw new Error('MediaPipeWorkerClient is not initialized.');
    }
    const id = this.nextRequestId++;
    return new Promise<VisionDetectResponse>((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      const msg: VisionWorkerRequest = {
        type: 'DETECT_FRAME',
        id,
        frame: bitmap,
        timestampUs,
        extractHeadCrown: options.extractHeadCrown,
        extractGroundContacts: options.extractGroundContacts,
        extractHandTip: options.extractHandTip,
      };
      this.worker!.postMessage(msg, [bitmap]);
    });
  }

  resetTracking(): void {
    if (!this.worker) return;
    this.worker.postMessage({ type: 'RESET' } satisfies VisionWorkerRequest);
  }

  dispose(): void {
    for (const [, waiter] of this.pending) {
      waiter.reject(new Error('MediaPipeWorkerClient disposed'));
    }
    this.pending.clear();
    this.worker?.terminate();
    this.worker = null;
    this.readyPromise = null;
  }
}
