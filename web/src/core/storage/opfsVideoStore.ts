export interface StorageQuotaTelemetry {
  opfsSupported: boolean;
  isPersistent: boolean | null;
  usageBytes: number | null;
  quotaBytes: number | null;
  videoFileCount: number;
  videoTotalBytes: number;
}

export interface StoredVideoEntry {
  filename: string;
  sizeBytes: number;
  lastModifiedMs: number;
}

const VIDEO_DIR_NAME = 'athletiq_videos';

async function getVideoDirectory(): Promise<FileSystemDirectoryHandle | null> {
  if (
    typeof navigator === 'undefined' ||
    !navigator.storage ||
    typeof navigator.storage.getDirectory !== 'function'
  ) {
    return null;
  }
  const root = await navigator.storage.getDirectory();
  return root.getDirectoryHandle(VIDEO_DIR_NAME, { create: true });
}

export async function requestDurablePersistence(): Promise<boolean | null> {
  if (typeof navigator === 'undefined' || !navigator.storage) {
    return null;
  }
  if (typeof navigator.storage.persisted === 'function') {
    const already = await navigator.storage.persisted();
    if (already) return true;
  }
  if (typeof navigator.storage.persist === 'function') {
    return navigator.storage.persist();
  }
  return null;
}

export async function saveVideoClipToOpfs(
  clientUuid: string,
  blob: Blob,
): Promise<string | null> {
  const dir = await getVideoDirectory();
  if (!dir) {
    return null;
  }
  const safeName = `${clientUuid.replace(/[^A-Za-z0-9_-]/g, '')}.webm`;
  const handle = await dir.getFileHandle(safeName, { create: true });
  const writable = await handle.createWritable();
  try {
    await writable.write(blob);
  } finally {
    await writable.close();
  }
  return safeName;
}

export async function listOpfsVideos(): Promise<StoredVideoEntry[]> {
  const dir = await getVideoDirectory();
  if (!dir) {
    return [];
  }
  const entries: StoredVideoEntry[] = [];
  const iter = (dir as FileSystemDirectoryHandle & {
    values?: () => AsyncIterable<FileSystemHandle>;
  }).values;
  if (typeof iter !== 'function') {
    return [];
  }
  for await (const handle of iter.call(dir)) {
    if (handle.kind === 'file') {
      const file = await (handle as FileSystemFileHandle).getFile();
      entries.push({
        filename: handle.name,
        sizeBytes: file.size,
        lastModifiedMs: file.lastModified,
      });
    }
  }
  return entries.sort((a, b) => b.lastModifiedMs - a.lastModifiedMs);
}

export async function purgeExpiredOpfsVideos(retentionDays: number): Promise<number> {
  const dir = await getVideoDirectory();
  if (!dir) return 0;
  const cutoffMs = Date.now() - Math.max(1, retentionDays) * 86_400_000;
  const entries = await listOpfsVideos();
  let removed = 0;
  for (const item of entries) {
    if (item.lastModifiedMs < cutoffMs) {
      await dir.removeEntry(item.filename);
      removed += 1;
    }
  }
  return removed;
}

export async function deleteAllOpfsVideos(): Promise<number> {
  const dir = await getVideoDirectory();
  if (!dir) return 0;
  const entries = await listOpfsVideos();
  for (const item of entries) {
    await dir.removeEntry(item.filename);
  }
  return entries.length;
}

export async function inspectStorageQuota(): Promise<StorageQuotaTelemetry> {
  const opfsSupported =
    typeof navigator !== 'undefined' &&
    Boolean(navigator.storage && typeof navigator.storage.getDirectory === 'function');
  let isPersistent: boolean | null = null;
  let usageBytes: number | null = null;
  let quotaBytes: number | null = null;

  if (typeof navigator !== 'undefined' && navigator.storage) {
    if (typeof navigator.storage.persisted === 'function') {
      try {
        isPersistent = await navigator.storage.persisted();
      } catch {
        isPersistent = null;
      }
    }
    if (typeof navigator.storage.estimate === 'function') {
      try {
        const est = await navigator.storage.estimate();
        usageBytes = est.usage ?? null;
        quotaBytes = est.quota ?? null;
      } catch {
        usageBytes = null;
        quotaBytes = null;
      }
    }
  }

  let videoFileCount = 0;
  let videoTotalBytes = 0;
  if (opfsSupported) {
    try {
      const videos = await listOpfsVideos();
      videoFileCount = videos.length;
      videoTotalBytes = videos.reduce((sum, v) => sum + v.sizeBytes, 0);
    } catch {
      videoFileCount = 0;
      videoTotalBytes = 0;
    }
  }

  return {
    opfsSupported,
    isPersistent,
    usageBytes,
    quotaBytes,
    videoFileCount,
    videoTotalBytes,
  };
}
