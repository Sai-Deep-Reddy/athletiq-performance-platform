package com.athletiq.core.vision

import com.athletiq.core.biomechanics.MaskGroundContactCandidates
import com.athletiq.core.biomechanics.Point2D
import com.athletiq.core.biomechanics.PoseLandmarkIndex

/**
 * Extracts shoe-sole rear boundaries and potential rear body/hand floor-contact points
 * from a MediaPipe segmentation mask in the calibrated landing region.
 */
object GroundContactExtractor {
    /**
     * @param observation Pose frame observation with optional segmentation mask.
     * @param jumpDirectionPositiveX True when the athlete jumps left-to-right (+image X);
     *                               false when jumping right-to-left (-image X).
     */
    fun extract(
        observation: PoseFrameObservation,
        jumpDirectionPositiveX: Boolean = true,
        maskThreshold: Float = 0.5f,
    ): MaskGroundContactCandidates? {
        val maskW = observation.segmentationMaskWidth ?: return null
        val maskH = observation.segmentationMaskHeight ?: return null
        val mask = observation.segmentationMaskConfidence ?: return null
        if (mask.size != maskW * maskH || observation.landmarks.size != PoseLandmarkIndex.LANDMARK_COUNT) {
            return null
        }

        val lms = observation.landmarks
        val lh = lms[PoseLandmarkIndex.LEFT_HEEL]
        val rh = lms[PoseLandmarkIndex.RIGHT_HEEL]
        val lt = lms[PoseLandmarkIndex.LEFT_FOOT_INDEX]
        val rt = lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX]
        if (minOf(lh.visibility, rh.visibility, lt.visibility, rt.visibility) < 0.4) return null

        val lowestFootY = maxOf(lh.y, rh.y, lt.y, rt.y)
        val floorBandTop = ((lowestFootY - 0.04) * maskH).toInt().coerceIn(0, maskH - 1)
        val floorBandBot = ((lowestFootY + 0.03) * maskH).toInt().coerceIn(floorBandTop, maskH - 1)

        val minFootX = minOf(lh.x, rh.x, lt.x, rt.x)
        val maxFootX = maxOf(lh.x, rh.x, lt.x, rt.x)

        // Shoe-sole ROI around the foot bounding box (expanded slightly behind heels for sole thickness).
        val soleLeft = ((minFootX - 0.04) * maskW).toInt().coerceIn(0, maskW - 1)
        val soleRight = ((maxFootX + 0.04) * maskW).toInt().coerceIn(soleLeft, maskW - 1)

        var rearShoePoint: Point2D? = null
        var bestShoeScore = if (jumpDirectionPositiveX) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
        for (y in floorBandTop..floorBandBot) {
            val rowOffset = y * maskW
            for (x in soleLeft..soleRight) {
                if (mask[rowOffset + x] >= maskThreshold) {
                    val xNorm = x.toDouble() / maskW
                    val isMoreRear = if (jumpDirectionPositiveX) xNorm < bestShoeScore else xNorm > bestShoeScore
                    if (isMoreRear) {
                        bestShoeScore = xNorm
                        rearShoePoint = Point2D(xNorm, y.toDouble() / maskH)
                    }
                }
            }
        }

        // Scan floor band behind the feet for backward hand or hip/buttock ground contact.
        val rearBodySearchStart = if (jumpDirectionPositiveX) 0 else ((maxFootX + 0.02) * maskW).toInt().coerceIn(0, maskW - 1)
        val rearBodySearchEnd = if (jumpDirectionPositiveX) ((minFootX - 0.02) * maskW).toInt().coerceIn(0, maskW - 1) else maskW - 1

        var rearBodyPoint: Point2D? = null
        if (rearBodySearchEnd > rearBodySearchStart) {
            var bestBodyX = if (jumpDirectionPositiveX) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
            for (y in floorBandTop..floorBandBot) {
                val rowOffset = y * maskW
                var contiguous = 0
                for (x in rearBodySearchStart..rearBodySearchEnd) {
                    if (mask[rowOffset + x] >= maskThreshold) {
                        contiguous++
                        if (contiguous >= 3) {
                            val xNorm = (x - contiguous + 1).toDouble() / maskW
                            val isMoreRear = if (jumpDirectionPositiveX) {
                                xNorm < bestBodyX
                            } else {
                                (x.toDouble() / maskW) > bestBodyX
                            }
                            if (isMoreRear) {
                                bestBodyX = if (jumpDirectionPositiveX) xNorm else (x.toDouble() / maskW)
                                rearBodyPoint = Point2D(bestBodyX, y.toDouble() / maskH)
                            }
                        }
                    } else {
                        contiguous = 0
                    }
                }
            }
        }

        return MaskGroundContactCandidates(
            rearShoeSolePointNormalized = rearShoePoint,
            rearBodyOrHandGroundPointNormalized = rearBodyPoint,
        )
    }
}
