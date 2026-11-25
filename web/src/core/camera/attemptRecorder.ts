import { selectSupportedRecorderMimeType } from './cameraCapabilities';

export interface RecordedAttemptVideo {
  blob: Blob;
  mimeType: string;
  recordedDurationMs: number;
  startedAtEpochMs: number;
  stoppedAtEpochMs: number;
}

export class AttemptMediaRecorder {
  private recorder: MediaRecorder | null = null;
  private chunks: Blob[] = [];
  private startedAtEpochMs = 0;
  private startedPerfMs = 0;
  private mimeType = 'video/webm';

  start(stream: MediaStream, timesliceMs = 250): void {
    if (typeof MediaRecorder === 'undefined') {
      throw new Error('MediaRecorder API is unavailable in this browser.');
    }
    if (this.recorder && this.recorder.state !== 'inactive') {
      throw new Error('Recording is already in progress.');
    }

    const supportedMime = selectSupportedRecorderMimeType();
    const options: MediaRecorderOptions = {};
    if (supportedMime) {
      options.mimeType = supportedMime;
      this.mimeType = supportedMime;
    }

    this.chunks = [];
    this.startedAtEpochMs = Date.now();
    this.startedPerfMs = performance.now();

    const recorder = new MediaRecorder(stream, options);
    this.mimeType = recorder.mimeType || this.mimeType;
    recorder.ondataavailable = (event: BlobEvent) => {
      if (event.data && event.data.size > 0) {
        this.chunks.push(event.data);
      }
    };
    recorder.start(timesliceMs);
    this.recorder = recorder;
  }

  async stop(): Promise<RecordedAttemptVideo> {
    const recorder = this.recorder;
    if (!recorder || recorder.state === 'inactive') {
      throw new Error('No active MediaRecorder session to stop.');
    }

    return new Promise<RecordedAttemptVideo>((resolve, reject) => {
      recorder.onerror = () => {
        this.recorder = null;
        reject(new Error('MediaRecorder encountered an encoding failure.'));
      };
      recorder.onstop = () => {
        const stoppedAtEpochMs = Date.now();
        const recordedDurationMs = Math.max(0, performance.now() - this.startedPerfMs);
        const blob = new Blob(this.chunks, { type: this.mimeType });
        this.recorder = null;
        this.chunks = [];
        if (blob.size === 0) {
          reject(new Error('Recorded video buffer was empty.'));
          return;
        }
        resolve({
          blob,
          mimeType: blob.type || this.mimeType,
          recordedDurationMs,
          startedAtEpochMs: this.startedAtEpochMs,
          stoppedAtEpochMs,
        });
      };
      recorder.stop();
    });
  }

  isRecording(): boolean {
    return this.recorder !== null && this.recorder.state === 'recording';
  }
}
