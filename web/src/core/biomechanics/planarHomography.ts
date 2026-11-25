import type {
  CalibrationPlaneType,
  CalibrationQuality,
  Point2D,
  TestType,
} from '../model/types';

const DETERMINANT_EPSILON = 1e-12;

export interface CameraIntrinsicsModel {
  cameraMatrix3x3: number[];
  distCoeffs: number[];
}

/**
 * Remove Brown-Conrady radial & tangential lens distortion from a pixel point
 * using iterative fixed-point undistortion when an active INTRINSICS profile is supplied.
 */
export function undistortPixelPoint(
  pixel: Point2D,
  intrinsics: CameraIntrinsicsModel,
): Point2D {
  if (intrinsics.cameraMatrix3x3.length !== 9 || intrinsics.distCoeffs.length < 4) {
    throw new Error('Invalid camera intrinsics dimensions');
  }
  const [fx, , cx, , fy, cy] = intrinsics.cameraMatrix3x3;
  if (Math.abs(fx) < DETERMINANT_EPSILON || Math.abs(fy) < DETERMINANT_EPSILON) {
    throw new Error('Focal length must be non-zero');
  }
  const k1 = intrinsics.distCoeffs[0] ?? 0;
  const k2 = intrinsics.distCoeffs[1] ?? 0;
  const p1 = intrinsics.distCoeffs[2] ?? 0;
  const p2 = intrinsics.distCoeffs[3] ?? 0;
  const k3 = intrinsics.distCoeffs[4] ?? 0;

  const xd = (pixel.x - cx) / fx;
  const yd = (pixel.y - cy) / fy;
  let x = xd;
  let y = yd;

  for (let iter = 0; iter < 5; iter += 1) {
    const r2 = x * x + y * y;
    const radial = 1 + k1 * r2 + k2 * r2 * r2 + k3 * r2 * r2 * r2;
    const dx = 2 * p1 * x * y + p2 * (r2 + 2 * x * x);
    const dy = p1 * (r2 + 2 * y * y) + 2 * p2 * x * y;
    x = (xd - dx) / radial;
    y = (yd - dy) / radial;
  }

  return {
    x: x * fx + cx,
    y: y * fy + cy,
  };
}

function applyProjective(matrix: number[], point: Point2D): Point2D {
  const w = matrix[6] * point.x + matrix[7] * point.y + matrix[8];
  if (Math.abs(w) < DETERMINANT_EPSILON) {
    throw new Error('Projective transformation denominator is near zero');
  }
  return {
    x: (matrix[0] * point.x + matrix[1] * point.y + matrix[2]) / w,
    y: (matrix[3] * point.x + matrix[4] * point.y + matrix[5]) / w,
  };
}

function invert3x3(m: number[]): number[] {
  const a = m[0], b = m[1], c = m[2];
  const d = m[3], e = m[4], f = m[5];
  const g = m[6], h = m[7], i = m[8];

  const c00 = e * i - f * h;
  const c01 = -(d * i - f * g);
  const c02 = d * h - e * g;
  const c10 = -(b * i - c * h);
  const c11 = a * i - c * g;
  const c12 = -(a * h - b * g);
  const c20 = b * f - c * e;
  const c21 = -(a * f - c * d);
  const c22 = a * e - b * d;

  const det = a * c00 + b * c01 + c * c02;
  if (Math.abs(det) < DETERMINANT_EPSILON) {
    throw new Error('Homography matrix is singular');
  }
  return [
    c00 / det, c10 / det, c20 / det,
    c01 / det, c11 / det, c21 / det,
    c02 / det, c12 / det, c22 / det,
  ];
}

function multiply3x3(a: number[], b: number[]): number[] {
  const out = new Array<number>(9).fill(0);
  for (let row = 0; row < 3; row += 1) {
    for (let col = 0; col < 3; col += 1) {
      out[row * 3 + col] =
        a[row * 3 + 0] * b[0 * 3 + col] +
        a[row * 3 + 1] * b[1 * 3 + col] +
        a[row * 3 + 2] * b[2 * 3 + col];
    }
  }
  return out;
}

function normalizePoints(points: Point2D[]): { normalized: Point2D[]; transform: number[] } {
  const n = points.length;
  const meanX = points.reduce((s, p) => s + p.x, 0) / n;
  const meanY = points.reduce((s, p) => s + p.y, 0) / n;
  const meanDist =
    points.reduce((s, p) => s + Math.hypot(p.x - meanX, p.y - meanY), 0) / n;
  if (meanDist < DETERMINANT_EPSILON) {
    throw new Error('Degenerate coincident point set');
  }
  const scale = Math.SQRT2 / meanDist;
  const transform = [scale, 0, -scale * meanX, 0, scale, -scale * meanY, 0, 0, 1];
  const normalized = points.map((p) => ({
    x: scale * (p.x - meanX),
    y: scale * (p.y - meanY),
  }));
  return { normalized, transform };
}

function solveLinearSystemGaussian(aInput: number[][], bInput: number[]): number[] {
  const n = bInput.length;
  const a = aInput.map((row) => [...row]);
  const b = [...bInput];

  for (let col = 0; col < n; col += 1) {
    let pivotRow = col;
    let maxVal = Math.abs(a[col][col]);
    for (let r = col + 1; r < n; r += 1) {
      if (Math.abs(a[r][col]) > maxVal) {
        maxVal = Math.abs(a[r][col]);
        pivotRow = r;
      }
    }
    if (maxVal < DETERMINANT_EPSILON) {
      throw new Error('Collinear or degenerate calibration correspondences');
    }
    [a[col], a[pivotRow]] = [a[pivotRow], a[col]];
    [b[col], b[pivotRow]] = [b[pivotRow], b[col]];

    const pivot = a[col][col];
    for (let j = col; j < n; j += 1) a[col][j] /= pivot;
    b[col] /= pivot;

    for (let r = 0; r < n; r += 1) {
      if (r === col) continue;
      const factor = a[r][col];
      for (let j = col; j < n; j += 1) {
        a[r][j] -= factor * a[col][j];
      }
      b[r] -= factor * b[col];
    }
  }
  return b;
}

export class PlanarHomography {
  readonly imageToPlaneMatrix: number[];
  readonly intrinsics?: CameraIntrinsicsModel;

  constructor(imageToPlaneMatrix: number[], intrinsics?: CameraIntrinsicsModel) {
    if (imageToPlaneMatrix.length !== 9 || !imageToPlaneMatrix.every(Number.isFinite)) {
      throw new Error('Homography matrix must contain 9 finite numbers');
    }
    invert3x3(imageToPlaneMatrix);
    this.imageToPlaneMatrix = [...imageToPlaneMatrix];
    this.intrinsics = intrinsics;
  }

  imageToPlane(imagePoint: Point2D): Point2D {
    const corrected = this.intrinsics
      ? undistortPixelPoint(imagePoint, this.intrinsics)
      : imagePoint;
    return applyProjective(this.imageToPlaneMatrix, corrected);
  }

  planeToImage(planePoint: Point2D): Point2D {
    const inv = invert3x3(this.imageToPlaneMatrix);
    return applyProjective(inv, planePoint);
  }

  computeRmsReprojectionErrorPx(imagePoints: Point2D[], planePointsCm: Point2D[]): number {
    if (imagePoints.length === 0 || imagePoints.length !== planePointsCm.length) {
      throw new Error('Point lists must be non-empty and equal length');
    }
    const inv = invert3x3(this.imageToPlaneMatrix);
    let sumSq = 0;
    for (let i = 0; i < imagePoints.length; i += 1) {
      const targetPixel = this.intrinsics
        ? undistortPixelPoint(imagePoints[i], this.intrinsics)
        : imagePoints[i];
      const reproj = applyProjective(inv, planePointsCm[i]);
      const err = Math.hypot(reproj.x - targetPixel.x, reproj.y - targetPixel.y);
      sumSq += err * err;
    }
    return Math.sqrt(sumSq / imagePoints.length);
  }

  static solveFromCorrespondences(
    imagePoints: Point2D[],
    planePointsCm: Point2D[],
    intrinsics?: CameraIntrinsicsModel,
  ): PlanarHomography {
    if (imagePoints.length < 4 || imagePoints.length !== planePointsCm.length) {
      throw new Error('At least 4 equal-length point correspondences are required');
    }
    const undistorted = intrinsics
      ? imagePoints.map((p) => undistortPixelPoint(p, intrinsics))
      : imagePoints;

    const { normalized: normImg, transform: tImg } = normalizePoints(undistorted);
    const { normalized: normPlane, transform: tPlane } = normalizePoints(planePointsCm);

    const ata = Array.from({ length: 8 }, () => new Array<number>(8).fill(0));
    const atb = new Array<number>(8).fill(0);

    for (let i = 0; i < normImg.length; i += 1) {
      const { x, y } = normImg[i];
      const { x: u, y: v } = normPlane[i];
      const row1 = [x, y, 1, 0, 0, 0, -u * x, -u * y];
      const row2 = [0, 0, 0, x, y, 1, -v * x, -v * y];

      for (const [row, rhs] of [
        [row1, u],
        [row2, v],
      ] as const) {
        for (let r = 0; r < 8; r += 1) {
          atb[r] += row[r] * rhs;
          for (let c = 0; c < 8; c += 1) {
            ata[r][c] += row[r] * row[c];
          }
        }
      }
    }

    const hVec = solveLinearSystemGaussian(ata, atb);
    const hNorm = [hVec[0], hVec[1], hVec[2], hVec[3], hVec[4], hVec[5], hVec[6], hVec[7], 1];
    const hDenorm = multiply3x3(multiply3x3(invert3x3(tPlane), hNorm), tImg);
    const scale = Math.abs(hDenorm[8]) > DETERMINANT_EPSILON ? hDenorm[8] : 1;
    const normalizedH = hDenorm.map((v) => v / scale);
    return new PlanarHomography(normalizedH, intrinsics);
  }
}

export const PROVISIONAL_MAX_REPROJECTION_ERROR_PX = 1.5;

export type CalibrationGateResult =
  | {
      kind: 'VALID';
      calibration: CalibrationQuality;
      warnings: string[];
    }
  | {
      kind: 'REJECTED';
      requiredPlane: CalibrationPlaneType;
      reason: string;
    };

export const CalibrationQualityGate = {
  requiredPlaneFor(testType: TestType): CalibrationPlaneType {
    return testType === 'HEIGHT' || testType === 'VERTICAL_JUMP'
      ? 'VERTICAL_PLANE'
      : 'GROUND_PLANE';
  },

  validate(
    testType: TestType,
    calibration: CalibrationQuality | null | undefined,
  ): CalibrationGateResult {
    const requiredPlane = CalibrationQualityGate.requiredPlaneFor(testType);
    if (!calibration) {
      return {
        kind: 'REJECTED',
        requiredPlane,
        reason: `No active ${requiredPlane} calibration profile is loaded`,
      };
    }
    if (!calibration.isActive) {
      return {
        kind: 'REJECTED',
        requiredPlane,
        reason: `Selected ${calibration.planeType} calibration profile is deactivated`,
      };
    }
    if (calibration.planeType !== requiredPlane) {
      return {
        kind: 'REJECTED',
        requiredPlane,
        reason: `${testType} strictly requires ${requiredPlane} calibration (got ${calibration.planeType})`,
      };
    }
    if (
      !Number.isFinite(calibration.reprojectionErrorPx) ||
      calibration.reprojectionErrorPx < 0 ||
      calibration.reprojectionErrorPx > PROVISIONAL_MAX_REPROJECTION_ERROR_PX
    ) {
      return {
        kind: 'REJECTED',
        requiredPlane,
        reason: `Calibration RMS reprojection error ${calibration.reprojectionErrorPx.toFixed(2)} px exceeds provisional ${PROVISIONAL_MAX_REPROJECTION_ERROR_PX.toFixed(2)} px limit`,
      };
    }

    const warnings: string[] = [];
    if (!calibration.usedIntrinsics) {
      warnings.push(
        'No active INTRINSICS lens distortion profile applied; peripheral measurements may contain radial distortion.',
      );
    }
    if (!calibration.independentDistanceVerified) {
      warnings.push(
        'Independent known-distance target validation has not been recorded for this calibration; treat provisional reprojection fit cautiously.',
      );
    }
    return { kind: 'VALID', calibration, warnings };
  },
};
