package com.athletiq.core.biomechanics

import com.athletiq.core.model.CalibrationQuality
import com.athletiq.core.model.ConfidenceReport
import com.athletiq.core.model.FrameTimingMetadata
import com.athletiq.core.model.LandmarkProvenance
import com.athletiq.core.model.MeasurementOutcome
import com.athletiq.core.model.MeasurementProvenance
import com.athletiq.core.model.MeasurementUnit
import com.athletiq.core.model.TestType
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class HeightFrameSample(
    val timestampNs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val landmarks: List<LandmarkPoint3D>,
    val headCrown: RefinedHeadCrown?,
) {
    init {
        require(landmarks.size == PoseLandmarkIndex.LANDMARK_COUNT) {
            "HeightFrameSample requires ${PoseLandmarkIndex.LANDMARK_COUNT} pose landmarks"
        }
        require(imageWidth > 0 && imageHeight > 0) { "Image dimensions must be > 0" }
    }
}

data class HeightMeasurement(
    val heightCm: Double,
    val medianCrownPlaneYCm: Double,
    val medianFloorPlaneYCm: Double,
    val interQuartileRangeCm: Double,
    val validFrameCount: Int,
    val possibleHairOrHeadwear: Boolean,
)

/**
 * Pure Kotlin Stage C1 human height engine.
 *
 * Enforces:
 * - Active VERTICAL_PLANE calibration (never ground-plane homography).
 * - Unclipped body framing and high landmark visibility.
 * - Upright posture (knee extension + bilateral level heels/feet).
 * - Stationary posture stability over a minimum temporal window.
 * - Head-region refined crown minus bilateral heel floor contact on the vertical plane.
 */
class HeightCalculator(
    private val minLandmarkVisibility: Double = 0.65,
    private val frameEdgeMarginNorm: Double = 0.02,
    private val minKneeAngleDeg: Double = 165.0,
    private val maxHeelHeightDiffCm: Double = 2.0,
    private val maxFootPitchDiffCm: Double = 3.5,
    private val maxCrownVelocityCmPerSec: Double = 4.0,
    private val minValidFrames: Int = 10,
    private val minStableDurationMs: Long = 800L,
    private val algorithmVersion: String = "height-c1-v1.0",
) {
    fun calculate(
        samples: List<HeightFrameSample>,
        calibration: CalibrationQuality?,
        verticalHomography: PlanarHomography?,
        timing: FrameTimingMetadata?,
        recordedAtEpochMs: Long,
    ): MeasurementOutcome<HeightMeasurement> {
        val calCheck = CalibrationQualityGate.validate(TestType.HEIGHT, calibration)
        if (calCheck is CalibrationGateResult.Rejected) {
            return MeasurementOutcome.CalibrationRequired(calCheck.requiredPlane, calCheck.reason)
        }
        val validCalibration = (calCheck as CalibrationGateResult.Valid).calibration
        if (verticalHomography == null) {
            return MeasurementOutcome.CalibrationRequired(
                requiredPlane = CalibrationQualityGate.requiredPlaneFor(TestType.HEIGHT),
                reason = "Vertical plane homography transform is missing",
            )
        }
        if (timing == null || samples.size < minValidFrames) {
            return MeasurementOutcome.InsufficientData(
                "At least $minValidFrames pose frames and timing metadata are required (got ${samples.size})",
            )
        }

        val rejectionReasons = linkedSetOf<String>()
        val perFrameHeightsCm = mutableListOf<Double>()
        val perFrameCrownsCm = mutableListOf<Double>()
        val perFrameFloorsCm = mutableListOf<Double>()
        val perFrameTimestampsNs = mutableListOf<Long>()
        val boundaryConfidences = mutableListOf<Double>()
        var anyHairOrHeadwearFlag = false

        for (sample in samples) {
            val crown = sample.headCrown
            if (crown == null) {
                rejectionReasons.add("Head crown boundary could not be isolated")
                continue
            }

            val lm = sample.landmarks
            val requiredIndices = intArrayOf(
                PoseLandmarkIndex.LEFT_SHOULDER,
                PoseLandmarkIndex.RIGHT_SHOULDER,
                PoseLandmarkIndex.LEFT_HIP,
                PoseLandmarkIndex.RIGHT_HIP,
                PoseLandmarkIndex.LEFT_KNEE,
                PoseLandmarkIndex.RIGHT_KNEE,
                PoseLandmarkIndex.LEFT_ANKLE,
                PoseLandmarkIndex.RIGHT_ANKLE,
                PoseLandmarkIndex.LEFT_HEEL,
                PoseLandmarkIndex.RIGHT_HEEL,
                PoseLandmarkIndex.LEFT_FOOT_INDEX,
                PoseLandmarkIndex.RIGHT_FOOT_INDEX,
            )

            val lowVis = requiredIndices.any { lm[it].visibility < minLandmarkVisibility }
            if (lowVis) {
                rejectionReasons.add("Required standing landmarks below visibility threshold $minLandmarkVisibility")
                continue
            }

            val clipped = crown.crownYNormalized < frameEdgeMarginNorm ||
                requiredIndices.any {
                    val p = lm[it]
                    p.x < frameEdgeMarginNorm || p.x > (1.0 - frameEdgeMarginNorm) ||
                        p.y < frameEdgeMarginNorm || p.y > (1.0 - frameEdgeMarginNorm)
                }
            if (clipped) {
                rejectionReasons.add("Subject head or feet too close to frame border")
                continue
            }

            val leftKneeDeg = jointAngleDeg(
                lm[PoseLandmarkIndex.LEFT_HIP],
                lm[PoseLandmarkIndex.LEFT_KNEE],
                lm[PoseLandmarkIndex.LEFT_ANKLE],
            )
            val rightKneeDeg = jointAngleDeg(
                lm[PoseLandmarkIndex.RIGHT_HIP],
                lm[PoseLandmarkIndex.RIGHT_KNEE],
                lm[PoseLandmarkIndex.RIGHT_ANKLE],
            )
            if (min(leftKneeDeg, rightKneeDeg) < minKneeAngleDeg) {
                rejectionReasons.add("Knees not fully extended for upright standing posture")
                continue
            }

            val crownPlane = verticalHomography.imageToPlane(
                Point2D(crown.crownXNormalized * sample.imageWidth, crown.crownYNormalized * sample.imageHeight),
            )
            val leftHeelPlane = verticalHomography.imageToPlane(
                Point2D(lm[PoseLandmarkIndex.LEFT_HEEL].x * sample.imageWidth, lm[PoseLandmarkIndex.LEFT_HEEL].y * sample.imageHeight),
            )
            val rightHeelPlane = verticalHomography.imageToPlane(
                Point2D(lm[PoseLandmarkIndex.RIGHT_HEEL].x * sample.imageWidth, lm[PoseLandmarkIndex.RIGHT_HEEL].y * sample.imageHeight),
            )
            val leftToePlane = verticalHomography.imageToPlane(
                Point2D(lm[PoseLandmarkIndex.LEFT_FOOT_INDEX].x * sample.imageWidth, lm[PoseLandmarkIndex.LEFT_FOOT_INDEX].y * sample.imageHeight),
            )
            val rightToePlane = verticalHomography.imageToPlane(
                Point2D(lm[PoseLandmarkIndex.RIGHT_FOOT_INDEX].x * sample.imageWidth, lm[PoseLandmarkIndex.RIGHT_FOOT_INDEX].y * sample.imageHeight),
            )

            // Check bilateral floor contact: heels level & feet flat (not tiptoeing/mid-step).
            if (abs(leftHeelPlane.y - rightHeelPlane.y) > maxHeelHeightDiffCm) {
                rejectionReasons.add("Heels are not level on the floor")
                continue
            }
            val leftFootPitch = abs(leftHeelPlane.y - leftToePlane.y)
            val rightFootPitch = abs(rightHeelPlane.y - rightToePlane.y)
            if (max(leftFootPitch, rightFootPitch) > maxFootPitchDiffCm) {
                rejectionReasons.add("Feet are not flat on the floor (possible tiptoe or step)")
                continue
            }

            val floorYCm = (leftHeelPlane.y + rightHeelPlane.y) * 0.5
            val heightCm = abs(crownPlane.y - floorYCm)
            if (heightCm !in 50.0..250.0) {
                rejectionReasons.add("Computed physical height ($heightCm cm) outside plausible bounds")
                continue
            }

            perFrameHeightsCm.add(heightCm)
            perFrameCrownsCm.add(crownPlane.y)
            perFrameFloorsCm.add(floorYCm)
            perFrameTimestampsNs.add(sample.timestampNs)
            boundaryConfidences.add(crown.boundaryConfidence)
            if (crown.possibleHairOrHeadwear) {
                anyHairOrHeadwearFlag = true
            }
        }

        if (perFrameHeightsCm.size < minValidFrames) {
            return if (rejectionReasons.isNotEmpty()) {
                MeasurementOutcome.InvalidAttempt(rejectionReasons.toList(), requiresExaminerReview = true)
            } else {
                MeasurementOutcome.InsufficientData("Insufficient valid standing frames")
            }
        }

        val durationMs = (perFrameTimestampsNs.last() - perFrameTimestampsNs.first()) / 1_000_000L
        if (durationMs < minStableDurationMs) {
            return MeasurementOutcome.InsufficientData(
                "Stable standing window (${durationMs}ms) shorter than required ${minStableDurationMs}ms",
            )
        }

        // Verify stillness across the accepted window.
        var maxVelocityCmPerSec = 0.0
        for (i in 1 until perFrameCrownsCm.size) {
            val dtSec = (perFrameTimestampsNs[i] - perFrameTimestampsNs[i - 1]) / 1_000_000_000.0
            if (dtSec > 0.0) {
                val vel = abs(perFrameCrownsCm[i] - perFrameCrownsCm[i - 1]) / dtSec
                maxVelocityCmPerSec = max(maxVelocityCmPerSec, vel)
            }
        }
        if (maxVelocityCmPerSec > maxCrownVelocityCmPerSec) {
            return MeasurementOutcome.InvalidAttempt(
                reasons = listOf(
                    "Subject moved during height capture (peak vertical speed ${"%.1f".format(maxVelocityCmPerSec)} cm/s)",
                ),
                requiresExaminerReview = true,
            )
        }

        val medianHeightCm = median(perFrameHeightsCm)
        val iqrCm = interquartileRange(perFrameHeightsCm)
        val medianCrownY = median(perFrameCrownsCm)
        val medianFloorY = median(perFrameFloorsCm)

        val avgBoundaryConf = boundaryConfidences.average()
        if (avgBoundaryConf < 0.45) {
            return MeasurementOutcome.LowConfidence(
                reason = "Head boundary confidence too low for reliable height measurement",
                observedScore = avgBoundaryConf,
                minimumRequired = 0.45,
            )
        }

        val limitations = mutableListOf<String>()
        if (anyHairOrHeadwearFlag) {
            limitations.add("Top boundary may reflect hair volume or headwear rather than anatomical skull vertex")
        }
        if (timing.droppedFrames > 0) {
            limitations.add("${timing.droppedFrames} camera frames dropped during standing capture")
        }

        // Conservative uncertainty budget combining calibration residual, frame dispersion, and headwear uncertainty.
        val hairMarginCm = if (anyHairOrHeadwearFlag) 1.5 else 0.5
        val uncertaintyCm = (validCalibration.reprojectionErrorPx * 0.4) + (iqrCm * 0.5) + hairMarginCm
        val confidenceScore = (
            avgBoundaryConf *
                (1.0 - (iqrCm / 5.0).coerceIn(0.0, 0.4)) *
                (1.0 - (validCalibration.reprojectionErrorPx / 5.0).coerceIn(0.0, 0.3))
            ).coerceIn(0.0, 1.0)

        return MeasurementOutcome.Success(
            value = HeightMeasurement(
                heightCm = medianHeightCm,
                medianCrownPlaneYCm = medianCrownY,
                medianFloorPlaneYCm = medianFloorY,
                interQuartileRangeCm = iqrCm,
                validFrameCount = perFrameHeightsCm.size,
                possibleHairOrHeadwear = anyHairOrHeadwearFlag,
            ),
            unit = MeasurementUnit.CENTIMETERS,
            uncertainty = uncertaintyCm,
            confidence = ConfidenceReport(
                score = confidenceScore,
                notes = limitations,
            ),
            provenance = MeasurementProvenance(
                calibration = validCalibration,
                timing = timing,
                landmarkProvenance = LandmarkProvenance.MEDIAPIPE_POSE_SEGMENTATION_REFINED,
                algorithmVersion = algorithmVersion,
                recordedAtEpochMs = recordedAtEpochMs,
                limitations = limitations,
            ),
            requiresExaminerReview = anyHairOrHeadwearFlag || iqrCm > 1.5,
        )
    }

    private fun jointAngleDeg(a: LandmarkPoint3D, b: LandmarkPoint3D, c: LandmarkPoint3D): Double {
        val bax = a.x - b.x
        val bay = a.y - b.y
        val bcx = c.x - b.x
        val bcy = c.y - b.y
        val magBa = hypot(bax, bay)
        val magBc = hypot(bcx, bcy)
        if (magBa <= 1e-9 || magBc <= 1e-9) return 0.0
        val cosTheta = ((bax * bcx + bay * bcy) / (magBa * magBc)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosTheta))
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun interquartileRange(values: List<Double>): Double {
        if (values.size < 4) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        val lower = sorted.subList(0, mid)
        val upper = if (sorted.size % 2 == 0) sorted.subList(mid, sorted.size) else sorted.subList(mid + 1, sorted.size)
        return median(upper) - median(lower)
    }
}
