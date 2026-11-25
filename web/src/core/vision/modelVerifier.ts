import { z } from 'zod';

const ModelManifestEntrySchema = z.object({
  id: z.string(),
  filename: z.string(),
  task: z.string(),
  version: z.string(),
  source: z.string(),
  license: z.string(),
  size_bytes: z.number().int().positive(),
  sha256: z.string().length(64),
  intended_use: z.string(),
});

const ModelManifestSchema = z.object({
  manifest_version: z.number().int(),
  models: z.array(ModelManifestEntrySchema),
});

export type ModelManifestEntry = z.infer<typeof ModelManifestEntrySchema>;
export type ModelManifest = z.infer<typeof ModelManifestSchema>;

export interface VerifiedVisionAssets {
  manifest: ModelManifest;
  poseModelPath: string;
  handModelPath: string;
  wasmBasePath: string;
  verifiedSha256: boolean;
}

async function computeSha256Hex(buffer: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
    .toUpperCase();
}

function resolveBaseUrl(): string {
  const raw =
    (import.meta as unknown as { env?: { BASE_URL?: string } }).env?.BASE_URL ?? '/';
  return raw.endsWith('/') ? raw : `${raw}/`;
}

export async function verifyVisionModelManifest(options?: {
  verifyChecksums?: boolean;
  fetchImpl?: typeof fetch;
}): Promise<VerifiedVisionAssets> {
  const base = resolveBaseUrl();
  const fetchFn = options?.fetchImpl ?? ((input, init) => globalThis.fetch(input, init));
  const manifestUrl = `${base}models/model_manifest.json`;
  const manifestResp = await fetchFn(manifestUrl);
  if (!manifestResp.ok) {
    throw new Error(
      `Shared model manifest missing at ${manifestUrl} (HTTP ${manifestResp.status}).`,
    );
  }
  const parsed = ModelManifestSchema.safeParse(await manifestResp.json());
  if (!parsed.success) {
    throw new Error('Shared model_manifest.json failed schema validation.');
  }

  const poseEntry = parsed.data.models.find((m) => m.id === 'pose_landmarker_full');
  const handEntry = parsed.data.models.find((m) => m.id === 'hand_landmarker');
  if (!poseEntry || !handEntry) {
    throw new Error('Model manifest is missing pose_landmarker_full or hand_landmarker entries.');
  }

  const poseModelPath = `${base}models/${poseEntry.filename}`;
  const handModelPath = `${base}models/${handEntry.filename}`;
  let verifiedSha256 = false;

  if (options?.verifyChecksums && typeof crypto !== 'undefined' && crypto.subtle) {
    for (const [entry, path] of [
      [poseEntry, poseModelPath],
      [handEntry, handModelPath],
    ] as const) {
      const res = await fetchFn(path);
      if (!res.ok) {
        throw new Error(`Missing model binary at ${path} (HTTP ${res.status}).`);
      }
      const buf = await res.arrayBuffer();
      if (buf.byteLength !== entry.size_bytes) {
        throw new Error(
          `Model size mismatch for ${entry.filename}: expected ${entry.size_bytes}, got ${buf.byteLength}.`,
        );
      }
      const hash = await computeSha256Hex(buf);
      if (hash !== entry.sha256.toUpperCase()) {
        throw new Error(`SHA-256 checksum mismatch for ${entry.filename}.`);
      }
    }
    verifiedSha256 = true;
  }

  return {
    manifest: parsed.data,
    poseModelPath,
    handModelPath,
    wasmBasePath: `${base}mediapipe/wasm`,
    verifiedSha256,
  };
}
