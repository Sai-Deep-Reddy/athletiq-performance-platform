package com.athletiq.core.biomechanics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class RefinedHeadCrown(
    val crownXNormalized: Double,
    val crownYNormalized: Double,
    val boundaryConfidence: Double,
    val maskVsEdgeDiscrepancyPx: Double,
    val possibleHairOrHeadwear: Boolean,
) {
    init {
        require(crownXNormalized.isFinite() && crownXNormalized in 0.0..1.0)
        require(crownYNormalized.isFinite() && crownYNormalized in 0.0..1.0)
        require(boundaryConfidence.isFinite() && boundaryConfidence in 0.0..1.0)
        require(maskVsEdgeDiscrepancyPx.isFinite() && maskVsEdgeDiscrepancyPx >= 0.0)
    }
}

/**
 * Refines the top-of-head boundary within a face/head ROI by combining:
 * 1. MediaPipe foreground segmentation mask support (filtering stray 1-2px noise), and
 * 2. Original-image vertical luminance gradient refinement near the mask upper edge.
 *
 * Explicitly flags soft/extended boundaries (hair volume or headwear) rather than
 * claiming the topmost foreground pixel is always the anatomical skull vertex.
 */
object HeadBoundaryDetector {
    /**
     * @param imageWidth Full image width in pixels.
     * @param imageHeight Full image height in pixels.
     * @param landmarks 33 normalized Pose landmarks.
     * @param maskWidth Segmentation mask width.
     * @param maskHeight Segmentation mask height.
     * @param maskConfidence Row-major foreground confidence [0f..1f] of size maskWidth * maskHeight.
     * @param grayscalePatch Optional row-major luminance [0.0..255.0] of the full image or matching mask dims.
     *                       When null, gradient refinement is skipped and uncertainty is flagged.
     */
    fun detectInHeadRegion(
        imageWidth: Int,
        imageHeight: Int,
        landmarks: List<LandmarkPoint3D>,
        maskWidth: Int,
        maskHeight: Int,
        maskConfidence: FloatArray,
        grayscalePatch: FloatArray? = null,
        grayscaleWidth: Int = maskWidth,
        grayscaleHeight: Int = maskHeight,
        maskThreshold: Float = 0.5f,
    ): RefinedHeadCrown? {
        require(landmarks.size == PoseLandmarkIndex.LANDMARK_COUNT) {
            "Expected ${PoseLandmarkIndex.LANDMARK_COUNT} pose landmarks"
        }
        if (imageWidth <= 0 || imageHeight <= 0 || maskWidth <= 0 || maskHeight <= 0) return null
        if (maskConfidence.size != maskWidth * maskHeight) return null

        val nose = landmarks[PoseLandmarkIndex.NOSE]
        val leftEye = landmarks[PoseLandmarkIndex.LEFT_EYE]
        val rightEye = landmarks[PoseLandmarkIndex.RIGHT_EYE]
        val leftEar = landmarks[PoseLandmarkIndex.LEFT_EAR]
        val rightEar = landmarks[PoseLandmarkIndex.RIGHT_EAR]

        if (min(leftEye.visibility, rightEye.visibility) < 0.4 && nose.visibility < 0.4) {
            return null
        }

        val earSpanNorm = max(abs(leftEar.x - rightEar.x), abs(leftEye.x - rightEye.x) * 2.0)
            .coerceIn(0.03, 0.35)
        val centerXNorm = ((leftEye.x + rightEye.x) * 0.5).takeIf {
            leftEye.visibility >= 0.4 && rightEye.visibility >= 0.4
        } ?: nose.x
        val eyeLineYNorm = ((leftEye.y + rightEye.y) * 0.5).takeIf {
            leftEye.visibility >= 0.4 && rightEye.visibility >= 0.4
        } ?: nose.y

        // Search strictly inside the head ROI above the eye line, avoiding background clutter.
        val roiLeft = ((centerXNorm - earSpanNorm * 0.9) * maskWidth).toInt().coerceIn(0, maskWidth - 1)
        val roiRight = ((centerXNorm + earSpanNorm * 0.9) * maskWidth).toInt().coerceIn(roiLeft, maskWidth - 1)
        val roiBottom = (eyeLineYNorm * maskHeight).toInt().coerceIn(0, maskHeight - 1)
        val roiTop = ((eyeLineYNorm - earSpanNorm * 2.2) * maskHeight).toInt().coerceIn(0, roiBottom)

        val roiWidth = (roiRight - roiLeft + 1).coerceAtLeast(1)
        val minSupportCols = max(3, (roiWidth * 0.18).toInt())

        var maskCrownRow = -1
        var crownColCenter = ((roiLeft + roiRight) * 0.5)
        var maxRowSpanAboveEyes = 0

        for (y in roiTop..roiBottom) {
            var count = 0
            var sumX = 0.0
            val rowOffset = y * maskWidth
            for (x in roiLeft..roiRight) {
                if (maskConfidence[rowOffset + x] >= maskThreshold) {
                    count++
                    sumX += x
                }
            }
            if (count >= minSupportCols) {
                if (maskCrownRow == -1) {
                    maskCrownRow = y
                    crownColCenter = sumX / count
                }
                maxRowSpanAboveEyes = max(maxRowSpanAboveEyes, count)
            }
        }

        if (maskCrownRow == -1) return null

        // Check vertical luminance transition in a +/- 4 mask-row band around maskCrownRow.
        var refinedRow = maskCrownRow.toDouble()
        var discrepancyPx = 0.0
        var edgeStrength = 0.5

        if (grayscalePatch != null &&
            grayscaleWidth > 0 &&
            grayscaleHeight > 0 &&
            grayscalePatch.size == grayscaleWidth * grayscaleHeight
        ) {
            val scaleX = grayscaleWidth.toDouble() / maskWidth
            val scaleY = grayscaleHeight.toDouble() / maskHeight
            val centerXGray = (crownColCenter * scaleX).toInt().coerceIn(1, grayscaleWidth - 2)
            val halfBandX = max(2, (earSpanNorm * grayscaleWidth * 0.2).toInt())
            val gxStart = (centerXGray - halfBandX).coerceIn(0, grayscaleWidth - 1)
            val gxEnd = (centerXGray + halfBandX).coerceIn(gxStart, grayscaleWidth - 1)

            val maskCrownGrayY = (maskCrownRow * scaleY).toInt().coerceIn(1, grayscaleHeight - 2)
            val searchTop = (maskCrownGrayY - max(4, (0.04 * grayscaleHeight).toInt())).coerceIn(1, grayscaleHeight - 2)
            val searchBot = (maskCrownGrayY + max(4, (0.04 * grayscaleHeight).toInt())).coerceIn(searchTop, grayscaleHeight - 2)

            var bestGrad = -1.0
            var bestGrayY = maskCrownGrayY
            for (gy in searchTop..searchBot) {
                var rowGradSum = 0.0
                for (gx in gxStart..gxEnd) {
                    val above = grayscalePatch[(gy - 1) * grayscaleWidth + gx]
                    val below = grayscalePatch[(gy + 1) * grayscaleWidth + gx]
                    rowGradSum += abs(below - above)
                }
                val avgGrad = rowGradSum / (gxEnd - gxStart + 1)
                if (avgGrad > bestGrad) {
                    bestGrad = avgGrad
                    bestGrayY = gy
                }
            }

            edgeStrength = (bestGrad / 50.0).coerceIn(0.15, 1.0)
            val refinedYInImage = bestGrayY.toDouble() / grayscaleHeight
            val maskYInImage = maskCrownRow.toDouble() / maskHeight
            discrepancyPx = abs(refinedYInImage - maskYInImage) * imageHeight

            // Only adopt gradient peak if edge is distinct; otherwise keep denoised mask boundary.
            refinedRow = if (bestGrad >= 12.0) {
                (bestGrayY.toDouble() / grayscaleHeight) * maskHeight
            } else {
                maskCrownRow.toDouble()
            }
        }

        val normalizedCrownY = (refinedRow / maskHeight).coerceIn(0.0, 1.0)
        val normalizedCrownX = (crownColCenter / maskWidth).coerceIn(0.0, 1.0)

        // Heuristic indicator: unusually tall crown-above-eye ratio or weak/discrepant edge often indicates hair/headwear.
        val crownToEyeNorm = (eyeLineYNorm - normalizedCrownY).coerceAtLeast(0.0)
        val crownRatio = crownToEyeNorm / earSpanNorm
        val possibleHairOrHeadwear = crownRatio > 1.25 || discrepancyPx > (imageHeight * 0.015) || grayscalePatch == null

        val boundaryConfidence = (
            0.6 * edgeStrength +
                0.4 * (1.0 - (discrepancyPx / (imageHeight * 0.03)).coerceIn(0.0, 1.0))
            ).coerceIn(0.0, 1.0)

        return RefinedHeadCrown(
            crownXNormalized = normalizedCrownX,
            crownYNormalized = normalizedCrownY,
            boundaryConfidence = boundaryConfidence,
            maskVsEdgeDiscrepancyPx = discrepancyPx,
            possibleHairOrHeadwear = possibleHairOrHeadwear,
        )
    }
}
