import type { LandmarkPoint3D, Point2D } from '../model/types';
import type { RefinedHeadCrown } from '../biomechanics/heightCalculator';
import type { MaskGroundContactCandidates } from '../biomechanics/broadJumpCalculator';
import { PoseLandmarkIndex } from '../biomechanics/poseLandmarkIndex';

/**
 * Refine the cranial vertex above eye/ear landmarks using MediaPipe person foreground mask
 * plus original image luminance gradient, flagging possible hair volume or headwear.
 * Note: MediaPipe segmentation provides person foreground silhouette, not dedicated hair/hat segmentation.
 */
export function refineHeadCrownFromMask(
  rgbaPixels: Uint8ClampedArray,
  maskValues: Float32Array | null,
  maskWidth: number,
  maskHeight: number,
  imageWidth: number,
  imageHeight: number,
  landmarks: LandmarkPoint3D[],
): RefinedHeadCrown | null {
  if (landmarks.length !== PoseLandmarkIndex.LANDMARK_COUNT) {
    return null;
  }
  const nose = landmarks[PoseLandmarkIndex.NOSE];
  const lEar = landmarks[PoseLandmarkIndex.LEFT_EAR];
  const rEar = landmarks[PoseLandmarkIndex.RIGHT_EAR];
  const lEye = landmarks[PoseLandmarkIndex.LEFT_EYE];
  const rEye = landmarks[PoseLandmarkIndex.RIGHT_EYE];

  const eyeEarY = Math.min(lEye.y, rEye.y, lEar.y, rEar.y, nose.y);
  const headWidthNorm = Math.max(0.06, Math.abs(lEar.x - rEar.x) * 1.4);
  const centerXNorm = (lEar.x + rEar.x + nose.x) / 3;

  if (!maskValues || maskWidth <= 0 || maskHeight <= 0) {
    // Without foreground mask, cannot verify cranial vertex above eyes reliably.
    return null;
  }

  const xStart = Math.max(0, Math.floor((centerXNorm - headWidthNorm * 0.5) * maskWidth));
  const xEnd = Math.min(maskWidth - 1, Math.ceil((centerXNorm + headWidthNorm * 0.5) * maskWidth));
  const yEnd = Math.max(0, Math.floor(eyeEarY * maskHeight));
  const yMinSearch = Math.max(0, Math.floor((eyeEarY - headWidthNorm * 1.8) * maskHeight));

  let topMaskY = -1;
  let topMaskXSum = 0;
  let topMaskCount = 0;

  for (let y = yMinSearch; y <= yEnd; y += 1) {
    let rowHits = 0;
    let rowXSum = 0;
    for (let x = xStart; x <= xEnd; x += 1) {
      const val = maskValues[y * maskWidth + x];
      if (val >= 0.55) {
        rowHits += 1;
        rowXSum += x;
      }
    }
    if (rowHits >= Math.max(2, Math.floor((xEnd - xStart + 1) * 0.12))) {
      topMaskY = y;
      topMaskXSum = rowXSum;
      topMaskCount = rowHits;
      break;
    }
  }

  if (topMaskY < 0 || topMaskCount === 0) {
    return null;
  }

  const imgX = Math.round((topMaskXSum / topMaskCount / maskWidth) * imageWidth);
  const imgY = Math.round((topMaskY / maskHeight) * imageHeight);

  // Check vertical luminance contrast around the mask boundary to gauge edge sharpness
  let maxGrad = 0;
  for (let dy = -3; dy <= 3; dy += 1) {
    const y0 = Math.max(0, Math.min(imageHeight - 2, imgY + dy));
    const idx0 = (y0 * imageWidth + Math.max(0, Math.min(imageWidth - 1, imgX))) * 4;
    const idx1 = ((y0 + 1) * imageWidth + Math.max(0, Math.min(imageWidth - 1, imgX))) * 4;
    const lum0 = 0.299 * rgbaPixels[idx0] + 0.587 * rgbaPixels[idx0 + 1] + 0.114 * rgbaPixels[idx0 + 2];
    const lum1 = 0.299 * rgbaPixels[idx1] + 0.587 * rgbaPixels[idx1 + 1] + 0.114 * rgbaPixels[idx1 + 2];
    maxGrad = Math.max(maxGrad, Math.abs(lum1 - lum0));
  }

  const crownYNorm = topMaskY / maskHeight;
  const earToCrownRatio = (eyeEarY - crownYNorm) / Math.max(0.02, headWidthNorm);
  const possibleHairOrHeadwear = earToCrownRatio > 0.95;
  const boundaryConfidence = Math.max(0.35, Math.min(0.95, 0.45 + maxGrad / 120));

  return {
    xNormalized: topMaskXSum / topMaskCount / maskWidth,
    yNormalized: crownYNorm,
    boundaryConfidence,
    possibleHairOrHeadwear,
  };
}

/**
 * Extract candidate ground-contact points from MediaPipe person foreground mask near the feet/rear body.
 * Explicitly returns candidate points only (not dedicated shoe segmentation).
 */
export function extractCandidateGroundContactsFromMask(
  maskValues: Float32Array | null,
  maskWidth: number,
  maskHeight: number,
  landmarks: LandmarkPoint3D[],
): MaskGroundContactCandidates | null {
  if (!maskValues || maskWidth <= 0 || maskHeight <= 0 || landmarks.length !== PoseLandmarkIndex.LANDMARK_COUNT) {
    return null;
  }

  const lHeel = landmarks[PoseLandmarkIndex.LEFT_HEEL];
  const rHeel = landmarks[PoseLandmarkIndex.RIGHT_HEEL];
  const lToe = landmarks[PoseLandmarkIndex.LEFT_FOOT_INDEX];
  const rToe = landmarks[PoseLandmarkIndex.RIGHT_FOOT_INDEX];

  const footY = Math.max(lHeel.y, rHeel.y, lToe.y, rToe.y);
  const minHeelX = Math.min(lHeel.x, rHeel.x);
  const bandTop = Math.max(0, Math.floor((footY - 0.04) * maskHeight));
  const bandBottom = Math.min(maskHeight - 1, Math.ceil((footY + 0.03) * maskHeight));
  const searchLeftX = Math.max(0, Math.floor((minHeelX - 0.08) * maskWidth));
  const searchRightX = Math.min(maskWidth - 1, Math.ceil((Math.max(lToe.x, rToe.x) + 0.03) * maskWidth));

  let rearShoeCandidate: Point2D | null = null;
  for (let x = searchLeftX; x <= searchRightX; x += 1) {
    for (let y = bandTop; y <= bandBottom; y += 1) {
      if (maskValues[y * maskWidth + x] >= 0.6) {
        rearShoeCandidate = { x: x / maskWidth, y: y / maskHeight };
        break;
      }
    }
    if (rearShoeCandidate) break;
  }

  let rearBodyCandidate: Point2D | null = null;
  const bodyLeftX = Math.max(0, Math.floor((minHeelX - 0.25) * maskWidth));
  const bodyRightX = Math.max(0, Math.floor((minHeelX - 0.03) * maskWidth));
  for (let x = bodyLeftX; x <= bodyRightX; x += 1) {
    for (let y = bandTop; y <= bandBottom; y += 1) {
      if (maskValues[y * maskWidth + x] >= 0.65) {
        rearBodyCandidate = { x: x / maskWidth, y: y / maskHeight };
        break;
      }
    }
    if (rearBodyCandidate) break;
  }

  if (!rearShoeCandidate && !rearBodyCandidate) {
    return null;
  }
  return {
    rearShoeSolePointNormalized: rearShoeCandidate,
    rearBodyOrHandGroundPointNormalized: rearBodyCandidate,
  };
}
