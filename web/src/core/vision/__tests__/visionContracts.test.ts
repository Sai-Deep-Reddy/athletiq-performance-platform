import { describe, expect, it } from 'vitest';
import { verifyVisionModelManifest } from '../modelVerifier';
import { extractCandidateGroundContactsFromMask, refineHeadCrownFromMask } from '../boundaryExtractors';
import { PoseLandmarkIndex } from '../../biomechanics/poseLandmarkIndex';

describe('Shared model manifest & boundary extractors', () => {
  it('rejects missing model_manifest.json explicitly', async () => {
    await expect(
      verifyVisionModelManifest({
        fetchImpl: async () => new Response('Not found', { status: 404 }),
      }),
    ).rejects.toThrow('Shared model manifest missing');
  });

  it('refines cranial vertex from person mask and treats ground mask contacts strictly as candidates', () => {
    const w = 100;
    const h = 100;
    const rgba = new Uint8ClampedArray(w * h * 4).fill(255);
    const mask = new Float32Array(w * h).fill(0);

    // Draw person mask rows 18..90, cols 42..58 + dark hair boundary at row 18
    for (let y = 18; y <= 90; y += 1) {
      for (let x = 42; x <= 58; x += 1) {
        mask[y * w + x] = 0.9;
        const p = (y * w + x) * 4;
        rgba[p] = 20;
        rgba[p + 1] = 20;
        rgba[p + 2] = 20;
      }
    }

    const lms = Array.from({ length: PoseLandmarkIndex.LANDMARK_COUNT }, () => ({
      x: 0.5,
      y: 0.5,
      z: 0,
      visibility: 0.9,
    }));
    lms[PoseLandmarkIndex.NOSE].y = 0.28;
    lms[PoseLandmarkIndex.LEFT_EYE].y = 0.26;
    lms[PoseLandmarkIndex.RIGHT_EYE].y = 0.26;
    lms[PoseLandmarkIndex.LEFT_EAR] = { x: 0.45, y: 0.27, z: 0, visibility: 0.9 };
    lms[PoseLandmarkIndex.RIGHT_EAR] = { x: 0.55, y: 0.27, z: 0, visibility: 0.9 };
    lms[PoseLandmarkIndex.LEFT_HEEL] = { x: 0.46, y: 0.89, z: 0, visibility: 0.9 };
    lms[PoseLandmarkIndex.RIGHT_HEEL] = { x: 0.54, y: 0.89, z: 0, visibility: 0.9 };

    const crown = refineHeadCrownFromMask(rgba, mask, w, h, w, h, lms);
    expect(crown).not.toBeNull();
    expect(crown!.yNormalized).toBeCloseTo(0.18, 2);

    const contacts = extractCandidateGroundContactsFromMask(mask, w, h, lms);
    expect(contacts).not.toBeNull();
    expect(contacts!.rearShoeSolePointNormalized).not.toBeNull();
  });
});
