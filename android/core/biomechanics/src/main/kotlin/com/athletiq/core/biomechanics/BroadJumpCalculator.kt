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
import kotlin.math.max
import kotlin.math.min

enum class BroadJumpProtocol {
    /** Standard protocol: nearest contact on landing plane by any body part (heels, shoe sole, hands, buttocks). */
    NEAREST_BODY_CONTACT,

    /** Foot-only protocol: nearest heel or shoe-sole contact; backward hand/body contacts still trigger examiner review. */
    NEAREST_FOOT_OR_SHOE_CONTACT,
}

enum class LandingContactKind {
    HEEL,
    SHOE_SOLE_REAR,
    HAND_TOUCH,
    HIP_OR_BODY_FALL,
    BACKWARD_STEP,
}

enum class LandingStabilityStatus {
    STABLE,
    HAND_OR_BODY_TOUCH,
    BACKWARD_STEP_OR_FALL,
}

/**
 * Optional mask-derived floor-contact candidates in normalized image coordinates [0..1]
 * extracted by :core:vision [GroundContactExtractor].
 */
data class MaskGroundContactCandidates(
    val rearShoeSolePointNormalized: Point2D? = null,
    val rearBodyOrHandGroundPointNormalized: Point2D? = null,
)

data class BroadJumpFrameSample(
    val timestampNs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val landmarks: List<LandmarkPoint3D>,
    val maskContacts: MaskGroundContactCandidates? = null,
) {
    init {
        require(landmarks.size == PoseLandmarkIndex.LANDMARK_COUNT) {
            "BroadJumpFrameSample requires ${PoseLandmarkIndex.LANDMARK_COUNT} pose landmarks"
        }
        require(imageWidth > 0 && imageHeight > 0)
    }
}

data class BroadJumpMeasurement(
    val distanceCm: Double,
    val startingFootXCm: Double,
    val initialLandingXCm: Double,
    val nearestContactXCm: Double,
    val landingContactKind: LandingContactKind,
    val stabilityStatus: LandingStabilityStatus,
    val protocolUsed: BroadJumpProtocol,
    val takeoffTimestampNs: Long,
    val landingTimestampNs: Long,
)

/**
 * Pure Kotlin Stage C3 standing broad jump engine.
 *
 * Uses a calibrated GROUND_PLANE homography (where plane X = 0 cm is the take-off line
 * and +X is the jump direction). Detects pre-jump foot faults, take-off, landing across
 * heels, shoe-sole rear boundaries, hands, and hip/backward body contacts, and monitors
 * post-landing stability for backward steps or falls.
 */
class BroadJumpCalculator(
    private val minLandmarkVisibility: Double = 0.60,
    private val frameEdgeMarginNorm: Double = 0.015,
    private val minStationaryFrames: Int = 5,
    private val takeoffLineToleranceCm: Double = 1.0,
    private val takeoffProgressThresholdCm: Double = 10.0,
    private val airborneFootLiftNormRatio: Double = 0.025,
    private val handGroundTouchNormTolerance: Double = 0.035,
    private val hipGroundFallNormTolerance: Double = 0.06,
    private val backwardStepToleranceCm: Double = 3.0,
    private val postLandingObserveMs: Long = 1000L,
    private val algorithmVersion: String = "sbj-c3-v1.0",
) {
    private data class FrameProjection(
        val timestampNs: Long,
        val leftHeelXCm: Double,
        val rightHeelXCm: Double,
        val maxFootFrontXCm: Double,
        val minHeelOrSoleXCm: Double,
        val soleKind: LandingContactKind,
        val handContactXCm: Double?,
        val hipFallContactXCm: Double?,
        val footGroundBaselineYNorm: Double,
        val expectedFloorYNorm: Double,
        val avgFootVisibility: Double,
    )

    fun calculate(
        samples: List<BroadJumpFrameSample>,
        calibration: CalibrationQuality?,
        groundHomography: PlanarHomography?,
        timing: FrameTimingMetadata?,
        recordedAtEpochMs: Long,
        protocol: BroadJumpProtocol = BroadJumpProtocol.NEAREST_BODY_CONTACT,
    ): MeasurementOutcome<BroadJumpMeasurement> {
        val calCheck = CalibrationQualityGate.validate(TestType.STANDING_BROAD_JUMP, calibration)
        if (calCheck is CalibrationGateResult.Rejected) {
            return MeasurementOutcome.CalibrationRequired(calCheck.requiredPlane, calCheck.reason)
        }
        val validCalibration = (calCheck as CalibrationGateResult.Valid).calibration
        if (groundHomography == null) {
            return MeasurementOutcome.CalibrationRequired(
                requiredPlane = CalibrationQualityGate.requiredPlaneFor(TestType.STANDING_BROAD_JUMP),
                reason = "Ground plane homography transform is missing",
            )
        }
        if (timing == null || samples.size < minStationaryFrames + 6) {
            return MeasurementOutcome.InsufficientData(
                "Insufficient frames or timing metadata for standing broad jump (got ${samples.size})",
            )
        }

        for (i in 1 until samples.size) {
            if (samples[i].timestampNs <= samples[i - 1].timestampNs) {
                return MeasurementOutcome.InvalidAttempt(
                    reasons = listOf("Non-monotonic frame timestamps detected during broad jump"),
                    requiresExaminerReview = true,
                )
            }
        }

        // 1. Estimate lateral foot lane bounds & floor baseline before jumping.
        val initialFeetYNorm = mutableListOf<Double>()
        val startMinLaneYVals = mutableListOf<Double>()
        val startMaxLaneYVals = mutableListOf<Double>()
        for (sample in samples.take(minStationaryFrames * 2)) {
            val lms = sample.landmarks
            val feet = listOf(
                lms[PoseLandmarkIndex.LEFT_HEEL],
                lms[PoseLandmarkIndex.RIGHT_HEEL],
                lms[PoseLandmarkIndex.LEFT_FOOT_INDEX],
                lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX],
            )
            if (feet.all { it.visibility >= minLandmarkVisibility }) {
                initialFeetYNorm.add(feet.maxOf { it.y })
                val laneYs = feet.map { ft ->
                    groundHomography.imageToPlane(
                        Point2D(ft.x * sample.imageWidth, ft.y * sample.imageHeight),
                    ).y
                }
                startMinLaneYVals.add(laneYs.min())
                startMaxLaneYVals.add(laneYs.max())
            }
        }
        if (initialFeetYNorm.size < minStationaryFrames) {
            return MeasurementOutcome.LowConfidence(
                reason = "Could not detect grounded starting feet reliably",
                observedScore = initialFeetYNorm.size.toDouble() / minStationaryFrames,
                minimumRequired = 1.0,
            )
        }
        val startMinLaneYCm = median(startMinLaneYVals)
        val startMaxLaneYCm = median(startMaxLaneYVals)

        fun expectedFloorYRangeNorm(planeXCm: Double, imageHeight: Int): Pair<Double, Double>? = try {
            val y1 = groundHomography.planeToImage(Point2D(planeXCm, startMinLaneYCm)).y / imageHeight
            val y2 = groundHomography.planeToImage(Point2D(planeXCm, startMaxLaneYCm)).y / imageHeight
            min(y1, y2) to max(y1, y2)
        } catch (_: IllegalArgumentException) {
            null
        }

        // 2. Project all valid frames onto ground-plane homography.
        val projected = mutableListOf<FrameProjection>()
        for (sample in samples) {
            val lms = sample.landmarks
            val lh = lms[PoseLandmarkIndex.LEFT_HEEL]
            val rh = lms[PoseLandmarkIndex.RIGHT_HEEL]
            val lt = lms[PoseLandmarkIndex.LEFT_FOOT_INDEX]
            val rt = lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX]
            val lWrist = lms[PoseLandmarkIndex.LEFT_WRIST]
            val rWrist = lms[PoseLandmarkIndex.RIGHT_WRIST]
            val lIndex = lms[PoseLandmarkIndex.LEFT_INDEX]
            val rIndex = lms[PoseLandmarkIndex.RIGHT_INDEX]
            val lHip = lms[PoseLandmarkIndex.LEFT_HIP]
            val rHip = lms[PoseLandmarkIndex.RIGHT_HIP]

            val requiredFeet = listOf(lh, rh, lt, rt)
            if (requiredFeet.any { it.visibility < minLandmarkVisibility }) continue
            val clipped = requiredFeet.any {
                it.x < frameEdgeMarginNorm || it.x > (1.0 - frameEdgeMarginNorm) ||
                    it.y < frameEdgeMarginNorm || it.y > (1.0 - frameEdgeMarginNorm)
            }
            if (clipped) continue

            val lhX = groundHomography.imageToPlane(Point2D(lh.x * sample.imageWidth, lh.y * sample.imageHeight)).x
            val rhX = groundHomography.imageToPlane(Point2D(rh.x * sample.imageWidth, rh.y * sample.imageHeight)).x
            val ltX = groundHomography.imageToPlane(Point2D(lt.x * sample.imageWidth, lt.y * sample.imageHeight)).x
            val rtX = groundHomography.imageToPlane(Point2D(rt.x * sample.imageWidth, rt.y * sample.imageHeight)).x

            val minHeelX = min(lhX, rhX)
            val maxFrontX = maxOf(lhX, rhX, ltX, rtX)
            val expectedFootFloorYNorm = expectedFloorYRangeNorm(minHeelX, sample.imageHeight)?.second
                ?: median(initialFeetYNorm)

            var minHeelOrSoleX = minHeelX
            var soleKind = LandingContactKind.HEEL
            val soleNorm = sample.maskContacts?.rearShoeSolePointNormalized
            if (soleNorm != null) {
                val soleX = groundHomography.imageToPlane(
                    Point2D(soleNorm.x * sample.imageWidth, soleNorm.y * sample.imageHeight),
                ).x
                if (soleX < minHeelOrSoleX) {
                    minHeelOrSoleX = soleX
                    soleKind = LandingContactKind.SHOE_SOLE_REAR
                }
            }

            // Detect hands touching ground (when hand landmarks reach local floor plane Y).
            val handTouchX = listOf(lWrist, rWrist, lIndex, rIndex)
                .filter { it.visibility >= minLandmarkVisibility }
                .mapNotNull { hand ->
                    try {
                        val handX = groundHomography.imageToPlane(
                            Point2D(hand.x * sample.imageWidth, hand.y * sample.imageHeight),
                        ).x
                        val localFloorMinY = expectedFloorYRangeNorm(handX, sample.imageHeight)?.first
                            ?: expectedFootFloorYNorm
                        if (hand.y >= localFloorMinY - handGroundTouchNormTolerance) handX else null
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
                .minOrNull()

            // Detect hip/buttocks/backward mask contact near local floor level.
            val hipFallX = listOf(lHip, rHip)
                .filter { it.visibility >= minLandmarkVisibility }
                .mapNotNull { hip ->
                    try {
                        val hipX = groundHomography.imageToPlane(
                            Point2D(hip.x * sample.imageWidth, hip.y * sample.imageHeight),
                        ).x
                        val localFloorMinY = expectedFloorYRangeNorm(hipX, sample.imageHeight)?.first
                            ?: expectedFootFloorYNorm
                        if (hip.y >= localFloorMinY - hipGroundFallNormTolerance) hipX else null
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
                .minOrNull()
            val maskRearBodyX = sample.maskContacts?.rearBodyOrHandGroundPointNormalized?.let { pt ->
                groundHomography.imageToPlane(Point2D(pt.x * sample.imageWidth, pt.y * sample.imageHeight)).x
            }
            val combinedBodyFallX = listOfNotNull(hipFallX, maskRearBodyX).minOrNull()

            projected.add(
                FrameProjection(
                    timestampNs = sample.timestampNs,
                    leftHeelXCm = lhX,
                    rightHeelXCm = rhX,
                    maxFootFrontXCm = maxFrontX,
                    minHeelOrSoleXCm = minHeelOrSoleX,
                    soleKind = soleKind,
                    handContactXCm = handTouchX,
                    hipFallContactXCm = combinedBodyFallX,
                    footGroundBaselineYNorm = requiredFeet.maxOf { it.y },
                    expectedFloorYNorm = expectedFootFloorYNorm,
                    avgFootVisibility = requiredFeet.map { it.visibility }.average(),
                ),
            )
        }

        if (projected.size < minStationaryFrames + 6) {
            return MeasurementOutcome.InsufficientData("Too few unclipped valid frames for broad jump")
        }

        // 3. Detect take-off (feet advance forward & lift above local floor baseline).
        val takeoffIdx = projected.indexOfFirst { frame ->
            val lifted = (frame.expectedFloorYNorm - frame.footGroundBaselineYNorm) >= airborneFootLiftNormRatio
            val movedForward = frame.minHeelOrSoleXCm > takeoffProgressThresholdCm
            lifted || movedForward
        }
        if (takeoffIdx < minStationaryFrames) {
            return MeasurementOutcome.InvalidAttempt(
                reasons = listOf("Stationary starting stance before take-off was too short ($takeoffIdx frames)"),
                requiresExaminerReview = true,
            )
        }

        val startFrames = projected.subList(0, takeoffIdx)
        val footFaultFrames = startFrames.filter { it.maxFootFrontXCm > takeoffLineToleranceCm }
        if (footFaultFrames.isNotEmpty()) {
            val maxOverCm = footFaultFrames.maxOf { it.maxFootFrontXCm }
            return MeasurementOutcome.InvalidAttempt(
                reasons = listOf("Foot fault: toes crossed take-off line by ${"%.1f".format(maxOverCm)} cm before jump"),
                requiresExaminerReview = true,
            )
        }

        val startingFootXCm = median(startFrames.map { it.maxFootFrontXCm })

        // 4. Detect landing frame after take-off (forward progress established and feet return near local floor Y).
        val landingRelIdx = projected.subList(takeoffIdx, projected.size).indexOfFirst { frame ->
            val forwardEnough = frame.minHeelOrSoleXCm > takeoffProgressThresholdCm
            val nearFloor = (frame.expectedFloorYNorm - frame.footGroundBaselineYNorm) < (airborneFootLiftNormRatio * 0.6)
            forwardEnough && nearFloor
        }
        if (landingRelIdx <= 0) {
            return MeasurementOutcome.InvalidAttempt(
                reasons = listOf("Landing position was not captured inside calibrated landing zone"),
                requiresExaminerReview = true,
            )
        }
        val landingIdx = takeoffIdx + landingRelIdx
        val initialLandingFrame = projected[landingIdx]
        val initialLandingXCm = initialLandingFrame.minHeelOrSoleXCm

        // 5. Inspect post-landing stability window (1.0s or end of clip) for hand/body touches & backward steps.
        val postLandingFrames = projected.subList(landingIdx, projected.size).filter { frame ->
            (frame.timestampNs - initialLandingFrame.timestampNs) <= postLandingObserveMs * 1_000_000L
        }

        var nearestFootXCm = initialLandingXCm
        var footContactKind = initialLandingFrame.soleKind
        var backwardStepDetected = false

        for (frame in postLandingFrames) {
            if (frame.minHeelOrSoleXCm < initialLandingXCm - backwardStepToleranceCm) {
                backwardStepDetected = true
                if (frame.minHeelOrSoleXCm < nearestFootXCm) {
                    nearestFootXCm = frame.minHeelOrSoleXCm
                    footContactKind = LandingContactKind.BACKWARD_STEP
                }
            } else if (frame.minHeelOrSoleXCm < nearestFootXCm) {
                nearestFootXCm = frame.minHeelOrSoleXCm
                footContactKind = frame.soleKind
            }
        }

        val minHandTouchXCm = postLandingFrames.mapNotNull { it.handContactXCm }.minOrNull()
        val minHipFallXCm = postLandingFrames.mapNotNull { it.hipFallContactXCm }.minOrNull()

        var finalDistanceXCm = nearestFootXCm
        var finalContactKind = footContactKind
        var requiresReview = false
        val limitations = mutableListOf<String>()

        if (minHipFallXCm != null && minHipFallXCm < finalDistanceXCm) {
            requiresReview = true
            limitations.add("Backward hip/body floor contact detected at ${"%.1f".format(minHipFallXCm)} cm")
            if (protocol == BroadJumpProtocol.NEAREST_BODY_CONTACT) {
                finalDistanceXCm = minHipFallXCm
                finalContactKind = LandingContactKind.HIP_OR_BODY_FALL
            }
        }

        if (minHandTouchXCm != null && minHandTouchXCm < finalDistanceXCm) {
            requiresReview = true
            limitations.add("Hand ground touch detected behind landing feet at ${"%.1f".format(minHandTouchXCm)} cm")
            if (protocol == BroadJumpProtocol.NEAREST_BODY_CONTACT) {
                finalDistanceXCm = minHandTouchXCm
                finalContactKind = LandingContactKind.HAND_TOUCH
            }
        }

        if (backwardStepDetected) {
            requiresReview = true
            limitations.add("Post-landing backward step/rebound detected beyond ${backwardStepToleranceCm} cm tolerance")
        }

        val stabilityStatus = when {
            backwardStepDetected || minHipFallXCm != null -> LandingStabilityStatus.BACKWARD_STEP_OR_FALL
            minHandTouchXCm != null -> LandingStabilityStatus.HAND_OR_BODY_TOUCH
            else -> LandingStabilityStatus.STABLE
        }

        if (finalDistanceXCm <= 0.0 || finalDistanceXCm > 450.0) {
            return MeasurementOutcome.InvalidAttempt(
                reasons = listOf("Computed broad jump distance (${"%.1f".format(finalDistanceXCm)} cm) outside plausible bounds"),
                requiresExaminerReview = true,
            )
        }

        if (timing.droppedFrames > 0 || timing.isVariableFrameRate) {
            limitations.add("Variable FPS / ${timing.droppedFrames} dropped frames recorded")
        }

        val avgVis = postLandingFrames.map { it.avgFootVisibility }.average()
        val uncertaintyCm = (validCalibration.reprojectionErrorPx * 0.6) +
            (if (requiresReview) 2.5 else 1.0) +
            (if (timing.droppedFrames > 0) 1.0 else 0.0)
        val confidenceScore = (
            avgVis *
                (if (requiresReview) 0.80 else 1.0) *
                (1.0 - (validCalibration.reprojectionErrorPx / 5.0).coerceIn(0.0, 0.3))
            ).coerceIn(0.0, 1.0)

        return MeasurementOutcome.Success(
            value = BroadJumpMeasurement(
                distanceCm = finalDistanceXCm,
                startingFootXCm = startingFootXCm,
                initialLandingXCm = initialLandingXCm,
                nearestContactXCm = finalDistanceXCm,
                landingContactKind = finalContactKind,
                stabilityStatus = stabilityStatus,
                protocolUsed = protocol,
                takeoffTimestampNs = projected[takeoffIdx].timestampNs,
                landingTimestampNs = initialLandingFrame.timestampNs,
            ),
            unit = MeasurementUnit.CENTIMETERS,
            uncertainty = uncertaintyCm,
            confidence = ConfidenceReport(score = confidenceScore, notes = limitations),
            provenance = MeasurementProvenance(
                calibration = validCalibration,
                timing = timing,
                landmarkProvenance = LandmarkProvenance.MEDIAPIPE_POSE_HEEL_AND_MASK,
                algorithmVersion = algorithmVersion,
                recordedAtEpochMs = recordedAtEpochMs,
                limitations = limitations,
            ),
            requiresExaminerReview = requiresReview,
        )
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
