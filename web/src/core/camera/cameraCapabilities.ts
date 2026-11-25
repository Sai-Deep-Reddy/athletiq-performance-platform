export type CameraAvailabilityStatus =
  | 'READY'
  | 'INSECURE_CONTEXT'
  | 'API_UNSUPPORTED'
  | 'PERMISSION_DENIED'
  | 'NO_CAMERA_DEVICE'
  | 'ERROR';

export interface VideoInputDeviceInfo {
  deviceId: string;
  label: string;
  groupId: string;
}

export interface BrowserCameraCapabilities {
  status: CameraAvailabilityStatus;
  isSecureContext: boolean;
  hasGetUserMedia: boolean;
  hasMediaRecorder: boolean;
  hasRequestVideoFrameCallback: boolean;
  hasWebCodecs: boolean;
  hasOpfs: boolean;
  devices: VideoInputDeviceInfo[];
  supportedRecorderMimeType: string | null;
  errorMessage: string | null;
}

const PREFERRED_RECORDER_MIME_TYPES = [
  'video/mp4;codecs=avc1.42E01E',
  'video/mp4',
  'video/webm;codecs=vp9',
  'video/webm;codecs=vp8',
  'video/webm',
];

export function selectSupportedRecorderMimeType(): string | null {
  if (typeof MediaRecorder === 'undefined' || typeof MediaRecorder.isTypeSupported !== 'function') {
    return null;
  }
  for (const mime of PREFERRED_RECORDER_MIME_TYPES) {
    if (MediaRecorder.isTypeSupported(mime)) {
      return mime;
    }
  }
  return null;
}

export async function probeBrowserCameraCapabilities(): Promise<BrowserCameraCapabilities> {
  const isSecureContext =
    typeof window !== 'undefined' ? Boolean(window.isSecureContext) : false;
  const hasGetUserMedia =
    typeof navigator !== 'undefined' &&
    Boolean(navigator.mediaDevices && typeof navigator.mediaDevices.getUserMedia === 'function');
  const hasMediaRecorder = typeof MediaRecorder !== 'undefined';
  const hasRequestVideoFrameCallback =
    typeof HTMLVideoElement !== 'undefined' &&
    'requestVideoFrameCallback' in HTMLVideoElement.prototype;
  const hasWebCodecs =
    typeof VideoDecoder !== 'undefined' &&
    typeof EncodedVideoChunk !== 'undefined' &&
    typeof VideoFrame !== 'undefined';
  const hasOpfs =
    typeof navigator !== 'undefined' &&
    Boolean(navigator.storage && typeof navigator.storage.getDirectory === 'function');
  const supportedRecorderMimeType = selectSupportedRecorderMimeType();

  if (!isSecureContext) {
    return {
      status: 'INSECURE_CONTEXT',
      isSecureContext,
      hasGetUserMedia,
      hasMediaRecorder,
      hasRequestVideoFrameCallback,
      hasWebCodecs,
      hasOpfs,
      devices: [],
      supportedRecorderMimeType,
      errorMessage:
        'Camera capture requires HTTPS (or localhost during development). Secure context is inactive.',
    };
  }

  if (!hasGetUserMedia) {
    return {
      status: 'API_UNSUPPORTED',
      isSecureContext,
      hasGetUserMedia,
      hasMediaRecorder,
      hasRequestVideoFrameCallback,
      hasWebCodecs,
      hasOpfs,
      devices: [],
      supportedRecorderMimeType,
      errorMessage: 'Browser MediaDevices.getUserMedia API is unavailable.',
    };
  }

  try {
    const allDevices = await navigator.mediaDevices.enumerateDevices();
    const videoInputs: VideoInputDeviceInfo[] = allDevices
      .filter((d) => d.kind === 'videoinput')
      .map((d, idx) => ({
        deviceId: d.deviceId,
        label: d.label || `Camera ${idx + 1}`,
        groupId: d.groupId,
      }));

    if (videoInputs.length === 0) {
      return {
        status: 'NO_CAMERA_DEVICE',
        isSecureContext,
        hasGetUserMedia,
        hasMediaRecorder,
        hasRequestVideoFrameCallback,
        hasWebCodecs,
        hasOpfs,
        devices: [],
        supportedRecorderMimeType,
        errorMessage: 'No camera device detected on this machine or mobile browser.',
      };
    }

    return {
      status: 'READY',
      isSecureContext,
      hasGetUserMedia,
      hasMediaRecorder,
      hasRequestVideoFrameCallback,
      hasWebCodecs,
      hasOpfs,
      devices: videoInputs,
      supportedRecorderMimeType,
      errorMessage: null,
    };
  } catch (err) {
    return {
      status: 'ERROR',
      isSecureContext,
      hasGetUserMedia,
      hasMediaRecorder,
      hasRequestVideoFrameCallback,
      hasWebCodecs,
      hasOpfs,
      devices: [],
      supportedRecorderMimeType,
      errorMessage: err instanceof Error ? err.message : 'Failed to inspect camera devices',
    };
  }
}

export type CameraFacingMode = 'environment' | 'user';

export async function openCameraStream(options: {
  deviceId?: string;
  facingMode?: CameraFacingMode;
  idealWidth?: number;
  idealHeight?: number;
  idealFps?: number;
}): Promise<MediaStream> {
  if (typeof window === 'undefined' || !window.isSecureContext) {
    throw new Error('Camera capture requires HTTPS or localhost secure context.');
  }
  if (!navigator.mediaDevices?.getUserMedia) {
    throw new Error('Browser getUserMedia API is not supported.');
  }

  const videoConstraints: MediaTrackConstraints = {
    width: { ideal: options.idealWidth ?? 1280 },
    height: { ideal: options.idealHeight ?? 720 },
    frameRate: { ideal: options.idealFps ?? 60 },
  };
  if (options.deviceId) {
    videoConstraints.deviceId = { exact: options.deviceId };
  } else {
    videoConstraints.facingMode = { ideal: options.facingMode ?? 'environment' };
  }

  return navigator.mediaDevices.getUserMedia({
    audio: false,
    video: videoConstraints,
  });
}

export function stopCameraStream(stream: MediaStream | null): void {
  if (!stream) return;
  for (const track of stream.getTracks()) {
    track.stop();
  }
}
