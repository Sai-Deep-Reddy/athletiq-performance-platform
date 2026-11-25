package com.athletiq.core.biomechanics

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

data class Point2D(val x: Double, val y: Double) {
    init {
        require(x.isFinite() && y.isFinite()) { "Point2D coordinates must be finite" }
    }
}

/**
 * 3x3 projective homography mapping 2D image pixels (u, v) to 2D physical plane coordinates (cm)
 * and inverse-projecting plane coordinates back to image pixels for reprojection verification.
 */
class PlanarHomography(
    val imageToPlaneMatrix: DoubleArray,
) {
    init {
        require(imageToPlaneMatrix.size == 9) { "Homography matrix must have exactly 9 elements" }
        require(imageToPlaneMatrix.all { it.isFinite() }) { "Homography matrix values must be finite" }
    }

    fun imageToPlane(imagePoint: Point2D): Point2D = applyProjective(imageToPlaneMatrix, imagePoint)

    fun planeToImage(planePoint: Point2D): Point2D {
        val inv = invert3x3(imageToPlaneMatrix)
        return applyProjective(inv, planePoint)
    }

    /**
     * Compute RMS reprojection error in image pixels across ground-truth correspondences.
     */
    fun computeRmsReprojectionErrorPx(
        imagePoints: List<Point2D>,
        planePointsCm: List<Point2D>,
    ): Double {
        require(imagePoints.size == planePointsCm.size && imagePoints.isNotEmpty()) {
            "Point lists must be non-empty and of equal length"
        }
        val inv = invert3x3(imageToPlaneMatrix)
        var sumSq = 0.0
        for (i in imagePoints.indices) {
            val reprojected = applyProjective(inv, planePointsCm[i])
            val err = hypot(reprojected.x - imagePoints[i].x, reprojected.y - imagePoints[i].y)
            sumSq += err * err
        }
        return sqrt(sumSq / imagePoints.size)
    }

    companion object {
        private const val DETERMINANT_EPSILON = 1e-12

        /**
         * Solve normalized 4+ point Direct Linear Transform (DLT) homography from image pixels
         * to physical plane coordinates (cm). Throws [IllegalArgumentException] on degenerate/collinear inputs.
         */
        fun solveFromCorrespondences(
            imagePoints: List<Point2D>,
            planePointsCm: List<Point2D>,
        ): PlanarHomography {
            require(imagePoints.size >= 4) { "At least 4 point correspondences are required" }
            require(imagePoints.size == planePointsCm.size) { "Correspondence lists must have equal size" }

            val (normImg, tImg) = normalizePoints(imagePoints)
            val (normPlane, tPlane) = normalizePoints(planePointsCm)

            // Solve A^T A h = min-eigenvector via normalized 8x8 linear system fixing h33 = 1,
            // or full 8-equation normal system when >= 4 non-degenerate correspondences exist.
            val n = normImg.size
            val ata = Array(8) { DoubleArray(8) }
            val atb = DoubleArray(8)

            for (i in 0 until n) {
                val x = normImg[i].x
                val y = normImg[i].y
                val u = normPlane[i].x
                val v = normPlane[i].y

                val row1 = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y)
                val row2 = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y)
                accumulateNormalEquation(ata, atb, row1, u)
                accumulateNormalEquation(ata, atb, row2, v)
            }

            val hVec = solveLinearSystemGaussian(ata, atb)
            val hNorm = doubleArrayOf(
                hVec[0], hVec[1], hVec[2],
                hVec[3], hVec[4], hVec[5],
                hVec[6], hVec[7], 1.0,
            )

            // Denormalize: H = T_plane^-1 * H_norm * T_img
            val tPlaneInv = invert3x3(tPlane)
            val hDenorm = multiply3x3(multiply3x3(tPlaneInv, hNorm), tImg)
            val scale = if (abs(hDenorm[8]) > DETERMINANT_EPSILON) hDenorm[8] else 1.0
            val normalizedH = DoubleArray(9) { idx -> hDenorm[idx] / scale }
            // Verify invertible
            invert3x3(normalizedH)
            return PlanarHomography(normalizedH)
        }

        private fun applyProjective(m: DoubleArray, p: Point2D): Point2D {
            val w = m[6] * p.x + m[7] * p.y + m[8]
            require(abs(w) > DETERMINANT_EPSILON) { "Point lies on or near vanishing line (w=$w)" }
            val xOut = (m[0] * p.x + m[1] * p.y + m[2]) / w
            val yOut = (m[3] * p.x + m[4] * p.y + m[5]) / w
            return Point2D(xOut, yOut)
        }

        private fun normalizePoints(points: List<Point2D>): Pair<List<Point2D>, DoubleArray> {
            val meanX = points.sumOf { it.x } / points.size
            val meanY = points.sumOf { it.y } / points.size
            val meanDist = points.sumOf { hypot(it.x - meanX, it.y - meanY) } / points.size
            require(meanDist > DETERMINANT_EPSILON) { "Points are coincident / degenerate" }
            val scale = sqrt(2.0) / meanDist
            val t = doubleArrayOf(
                scale, 0.0, -scale * meanX,
                0.0, scale, -scale * meanY,
                0.0, 0.0, 1.0,
            )
            val normalized = points.map { Point2D((it.x - meanX) * scale, (it.y - meanY) * scale) }
            return normalized to t
        }

        private fun accumulateNormalEquation(
            ata: Array<DoubleArray>,
            atb: DoubleArray,
            row: DoubleArray,
            rhs: Double,
        ) {
            for (r in 0 until 8) {
                atb[r] += row[r] * rhs
                for (c in 0 until 8) {
                    ata[r][c] += row[r] * row[c]
                }
            }
        }

        private fun solveLinearSystemGaussian(aInput: Array<DoubleArray>, bInput: DoubleArray): DoubleArray {
            val n = 8
            val a = Array(n) { r -> aInput[r].copyOf() }
            val b = bInput.copyOf()

            for (col in 0 until n) {
                var pivotRow = col
                var maxVal = abs(a[col][col])
                for (row in col + 1 until n) {
                    val candidate = abs(a[row][col])
                    if (candidate > maxVal) {
                        maxVal = candidate
                        pivotRow = row
                    }
                }
                require(maxVal > DETERMINANT_EPSILON) {
                    "Degenerate or collinear calibration points: singular homography matrix"
                }
                if (pivotRow != col) {
                    val tmpRow = a[col]
                    a[col] = a[pivotRow]
                    a[pivotRow] = tmpRow
                    val tmpB = b[col]
                    b[col] = b[pivotRow]
                    b[pivotRow] = tmpB
                }
                val pivot = a[col][col]
                for (row in col + 1 until n) {
                    val factor = a[row][col] / pivot
                    for (k in col until n) {
                        a[row][k] -= factor * a[col][k]
                    }
                    b[row] -= factor * b[col]
                }
            }

            val solution = DoubleArray(n)
            for (row in n - 1 downTo 0) {
                var sum = b[row]
                for (col in row + 1 until n) {
                    sum -= a[row][col] * solution[col]
                }
                solution[row] = sum / a[row][row]
            }
            return solution
        }

        private fun multiply3x3(a: DoubleArray, b: DoubleArray): DoubleArray {
            val out = DoubleArray(9)
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    out[r * 3 + c] =
                        a[r * 3] * b[c] +
                        a[r * 3 + 1] * b[3 + c] +
                        a[r * 3 + 2] * b[6 + c]
                }
            }
            return out
        }

        private fun invert3x3(m: DoubleArray): DoubleArray {
            val a00 = m[0]; val a01 = m[1]; val a02 = m[2]
            val a10 = m[3]; val a11 = m[4]; val a12 = m[5]
            val a20 = m[6]; val a21 = m[7]; val a22 = m[8]

            val c00 = a11 * a22 - a12 * a21
            val c01 = -(a10 * a22 - a12 * a20)
            val c02 = a10 * a21 - a11 * a20
            val det = a00 * c00 + a01 * c01 + a02 * c02
            require(abs(det) > DETERMINANT_EPSILON) { "Homography matrix is singular" }

            val invDet = 1.0 / det
            return doubleArrayOf(
                c00 * invDet,
                (-(a01 * a22 - a02 * a21)) * invDet,
                (a01 * a12 - a02 * a11) * invDet,
                c01 * invDet,
                (a00 * a22 - a02 * a20) * invDet,
                (-(a00 * a12 - a02 * a10)) * invDet,
                c02 * invDet,
                (-(a00 * a21 - a01 * a20)) * invDet,
                (a00 * a11 - a01 * a10) * invDet,
            )
        }
    }
}
