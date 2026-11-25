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

enum class ReachHandSide {
    LEFT,
    RIGHT,
}

enum class VerticalJumpProtocol {
    /** Standard reach-delta protocol: requires stationary overhead reach before take-off and measures reach delta. */
    STANDING_REACH_DELTA,

    /** Countermovement flight-time protocol: measures vertical leap from airborne foot take-off to touchdown (h = g * t^2 / 8). */
    COUNTERMOVEMENT_FLIGHT_TIME,
}

data class RefinedFingertipPoint(
    val xNormalized: Double,
    val yNormalized: Double,
    val confidence: Double,
    val side: ReachHandSide,
) {
    init {
        require(xNormalized.isFinite() && xNormalized in 0.0..1.0)
        require(yNormalized.isFinite() && yNormalized in 0.0..1.0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class VerticalJumpFrameSample(
    val timestampNs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val landmarks: List<LandmarkPoint3D>,
    val refinedHandTip: RefinedFingertipPoint? = null,
) {
    init {
        require(landmarks.size == PoseLandmarkIndex.LANDMARK_COUNT) {
            "VerticalJumpFrameSample requires ${PoseLandmarkIndex.LANDMARK_COUNT} pose landmarks"
        }
        require(imageWidth > 0 && imageHeight > 0)
    }
}

data class FlightTimeEstimate(
    val flightTimeSec: Double,
    val flightHeightCm: Double,
    val quantizationUncertaintyCm: Double,
    val takeoffTimestampNs: Long,
    val landingTimestampNs: Long,
)

data class VerticalJumpMeasurement(
    val jumpHeightCm: Double,
    val standingReachCm: Double,
    val maxJumpReachCm: Double,
    val jumpDurationSec: Double,
    val takeoffTimestampNs: Long,
    val landingTimestampNs: Long,
    val apexTimestampNs: Long,
    val dominantSide: ReachHandSide,
    val flightTimeEstimate: FlightTimeEstimate,
    val reachVsFlightDiscrepancyCm: Double,
)

/**
 * Pure Kotlin Stage C2 vertical jump engine.
 *
 * Prioritizes standing-reach vs. maximum-jump-reach delta on a calibrated VERTICAL_PLANE,
 * preferring HandLandmarker fingertip detections and explicitly reporting lower-precision
 * Pose index/wrist fallbacks. Flight-time height is computed independently as a cross-check.
 */
class VerticalJumpCalculator(
    private val minLandmarkVisibility: Double = 0.60,
    private val frameEdgeMarginNorm: Double = 0.015,
    private val minStandingReachFrames: Int = 5,
    private val minKneeExtensionDeg: Double = 160.0,
    private val minElbowExtensionDeg: Double = 145.0,
    private val maxBaselineFootFloorDiffCm: Double = 2.5,
    private val takeoffClearanceCm: Double = 2.5,
    private val landingContactCm: Double = 2.5,
    private val maxReachVsFlightDiscrepancyCm: Double = 8.0,
    private val algorithmVersion: String = "vjump-c2-v1.0",
) {
    companion object {
        const val STANDARD_GRAVITY_MPS2 = 9.80665
    }

    private data class ResolvedReach(
        val pointPx: Point2D,
        val provenance: LandmarkProvenance,
        val side: ReachHandSide,
        val confidence: Double,
    )

    private data class ProjectedFrame(
        val timestampNs: Long,
        val reachCm: Double,
        val minFootHeightCm: Double,
        val minKneeDeg: Double,
        val elbowDeg: Double,
        val isArmRaisedOverhead: Boolean,
        val provenance: LandmarkProvenance,
        val side: ReachHandSide,
        val confidence: Double,
    )

    fun calculate(
        samples: List<VerticalJumpFrameSample>,
        calibration: CalibrationQuality?,
        verticalHomography: PlanarHomography?,
        timing: FrameTimingMetadata?,
        recordedAtEpochMs: Long,
        protocol: VerticalJumpProtocol = VerticalJumpProtocol.STANDING_REACH_DELTA,
    ): MeasurementOutcome<VerticalJumpMeasurement> {
        val calCheck = CalibrationQualityGate.validate(TestType.VERTICAL_JUMP, calibration)
        if (calCheck is CalibrationGateResult.Rejected) {
            return MeasurementOutcome.CalibrationRequired(calCheck.requiredPlane, calCheck.reason)
        }
        val validCalibration = (calCheck as CalibrationGateResult.Valid).calibration
        if (verticalHomography == null) {
            return MeasurementOutcome.CalibrationRequired(
                requiredPlane = CalibrationQualityGate.requiredPlaneFor(TestType.VERTICAL_JUMP),
                reason = "Vertical plane homography transform is missing",
            )
        }
        if (timing == null || samples.size < minStandingReachFrames + 5) {
            return MeasurementOutcome.InsufficientData(
                "Insufficient frames or timing metadata for vertical jump analysis (got ${samples.size})",
            )
        }

        // Verify strictly increasing timestamps.
        for (i in 1 until samples.size) {
            if (samples[i].timestampNs <= samples[i - 1].timestampNs) {
                return MeasurementOutcome.InvalidAttempt(
                    reasons = listOf("Non-monotonic frame timestamps detected during jump capture"),
                    requiresExaminerReview = true,
                )
            }
        }

        // 1. Establish floor datum from initial grounded feet across earliest valid frames.
        val floorCandidateHeights = mutableListOf<Double>()
        for (sample in samples.take(minStandingReachFrames * 3)) {
            val lms = sample.landmarks
            val lh = lms[PoseLandmarkIndex.LEFT_HEEL]
            val rh = lms[PoseLandmarkIndex.RIGHT_HEEL]
            val lt = lms[PoseLandmarkIndex.LEFT_FOOT_INDEX]
            val rt = lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX]
            if (minOf(lh.visibility, rh.visibility, lt.visibility, rt.visibility) < minLandmarkVisibility) {
                continue
            }
            val lhPlane = verticalHomography.imageToPlane(Point2D(lh.x * sample.imageWidth, lh.y * sample.imageHeight))
            val rhPlane = verticalHomography.imageToPlane(Point2D(rh.x * sample.imageWidth, rh.y * sample.imageHeight))
            val ltPlane = verticalHomography.imageToPlane(Point2D(lt.x * sample.imageWidth, lt.y * sample.imageHeight))
            val rtPlane = verticalHomography.imageToPlane(Point2D(rt.x * sample.imageWidth, rt.y * sample.imageHeight))
            floorCandidateHeights.add(minOf(lhPlane.y, rhPlane.y, ltPlane.y, rtPlane.y))
        }

        if (floorCandidateHeights.size < minStandingReachFrames) {
            return MeasurementOutcome.LowConfidence(
                reason = "Could not detect grounded feet reliably before jump",
                observedScore = floorCandidateHeights.size.toDouble() / minStandingReachFrames,
                minimumRequired = 1.0,
            )
        }
        val floorDatumYCm = median(floorCandidateHeights)

        // 2. Project all frames to vertical-plane reach & foot clearance coordinates.
        val projected = mutableListOf<ProjectedFrame>()
        var clippedFramesCount = 0

        for (sample in samples) {
            val lms = sample.landmarks
            val resolvedReach = resolveReachPoint(sample)
                ?: if (protocol == VerticalJumpProtocol.COUNTERMOVEMENT_FLIGHT_TIME) {
                    val nose = lms[PoseLandmarkIndex.NOSE]
                    if (nose.visibility >= minLandmarkVisibility) {
                        ResolvedReach(
                            pointPx = Point2D(nose.x * sample.imageWidth, nose.y * sample.imageHeight),
                            provenance = LandmarkProvenance.MEDIAPIPE_POSE_HEEL_AND_MASK,
                            side = ReachHandSide.RIGHT,
                            confidence = nose.visibility,
                        )
                    } else null
                } else null
            if (resolvedReach == null) continue

            // Check frame clipping for reach point and feet.
            val reachNormX = resolvedReach.pointPx.x / sample.imageWidth
            val reachNormY = resolvedReach.pointPx.y / sample.imageHeight
            val feet = listOf(
                lms[PoseLandmarkIndex.LEFT_HEEL],
                lms[PoseLandmarkIndex.RIGHT_HEEL],
                lms[PoseLandmarkIndex.LEFT_FOOT_INDEX],
                lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX],
            )
            if (feet.any { it.visibility < minLandmarkVisibility }) continue

            val isClipped = reachNormY < frameEdgeMarginNorm ||
                reachNormX < frameEdgeMarginNorm ||
                reachNormX > (1.0 - frameEdgeMarginNorm) ||
                feet.any { it.y > (1.0 - frameEdgeMarginNorm) }
            if (isClipped) {
                clippedFramesCount++
                continue
            }

            val reachPlaneY = verticalHomography.imageToPlane(resolvedReach.pointPx).y
            val footHeightsAboveFloor = feet.map { ft ->
                val py = verticalHomography.imageToPlane(Point2D(ft.x * sample.imageWidth, ft.y * sample.imageHeight)).y
                py - floorDatumYCm
            }
            val minFootHeightCm = footHeightsAboveFloor.min()

            val leftKneeDeg = jointAngleDeg(
                lms[PoseLandmarkIndex.LEFT_HIP],
                lms[PoseLandmarkIndex.LEFT_KNEE],
                lms[PoseLandmarkIndex.LEFT_ANKLE],
            )
            val rightKneeDeg = jointAngleDeg(
                lms[PoseLandmarkIndex.RIGHT_HIP],
                lms[PoseLandmarkIndex.RIGHT_KNEE],
                lms[PoseLandmarkIndex.RIGHT_ANKLE],
            )

            val shoulder = lms[
                if (resolvedReach.side == ReachHandSide.LEFT) PoseLandmarkIndex.LEFT_SHOULDER else PoseLandmarkIndex.RIGHT_SHOULDER,
            ]
            val elbow = lms[
                if (resolvedReach.side == ReachHandSide.LEFT) PoseLandmarkIndex.LEFT_ELBOW else PoseLandmarkIndex.RIGHT_ELBOW,
            ]
            val wrist = lms[
                if (resolvedReach.side == ReachHandSide.LEFT) PoseLandmarkIndex.LEFT_WRIST else PoseLandmarkIndex.RIGHT_WRIST,
            ]
            val nose = lms[PoseLandmarkIndex.NOSE]

            val elbowDeg = jointAngleDeg(shoulder, elbow, wrist)
            val isArmOverhead = wrist.y < shoulder.y && wrist.y < nose.y

            projected.add(
                ProjectedFrame(
                    timestampNs = sample.timestampNs,
                    reachCm = reachPlaneY - floorDatumYCm,
                    minFootHeightCm = minFootHeightCm,
                    minKneeDeg = min(leftKneeDeg, rightKneeDeg),
                    elbowDeg = elbowDeg,
                    isArmRaisedOverhead = isArmOverhead,
                    provenance = resolvedReach.provenance,
                    side = resolvedReach.side,
                    confidence = resolvedReach.confidence,
                ),
            )
        }

        if (clippedFramesCount > 0 && projected.size < minStandingReachFrames + 3) {
            return MeasurementOutcome.InvalidAttempt(
                reasons = listOf("Reaching hand or feet left camera frame during attempt"),
                requiresExaminerReview = true,
            )
        }
        if (projected.size < minStandingReachFrames + 5) {
            return MeasurementOutcome.InsufficientData("Too few valid frames after quality screening")
        }

        // 3. Identify airborne take-off and landing transitions using noise-immune clearance thresholds,
        // then refine boundaries down to ground contact (0.5 cm) to avoid systematic threshold bias on flight time.
        val groundContactRefineCm = 0.5
        val rawTakeoffIdx = projected.indexOfFirst { it.minFootHeightCm > takeoffClearanceCm }
        if (rawTakeoffIdx <= 0) {
            return MeasurementOutcome.InvalidAttempt(
                reasons = listOf("No valid take-off detected from grounded standing baseline"),
                requiresExaminerReview = true,
            )
        }
        var takeoffIdx = rawTakeoffIdx
        while (takeoffIdx > 1 &&
            projected[takeoffIdx - 1].minFootHeightCm > groundContactRefineCm &&
            projected[takeoffIdx - 1].minFootHeightCm < projected[takeoffIdx].minFootHeightCm
        ) {
            takeoffIdx--
        }

        // Baseline standing frames strictly before take-off.
        val standingReachFrames = if (protocol == VerticalJumpProtocol.STANDING_REACH_DELTA) {
            projected.subList(0, takeoffIdx).filter { frame ->
                abs(frame.minFootHeightCm) <= maxBaselineFootFloorDiffCm &&
                    frame.minKneeDeg >= minKneeExtensionDeg &&
                    frame.isArmRaisedOverhead &&
                    frame.elbowDeg >= minElbowExtensionDeg
            }
        } else {
            projected.subList(0, takeoffIdx).filter { frame ->
                abs(frame.minFootHeightCm) <= maxBaselineFootFloorDiffCm
            }
        }

        if (standingReachFrames.size < minStandingReachFrames) {
            val msg = if (protocol == VerticalJumpProtocol.STANDING_REACH_DELTA) {
                "Standing reach baseline not established before jump (found ${standingReachFrames.size} upright overhead-reach frames; need $minStandingReachFrames)"
            } else {
                "Grounded stance baseline not established before jump (found ${standingReachFrames.size} frames; need $minStandingReachFrames)"
            }
            return MeasurementOutcome.InvalidAttempt(
                reasons = listOf(msg),
                requiresExaminerReview = true,
            )
        }

        val standingReachCm = median(standingReachFrames.map { it.reachCm })

        // Find landing frame after take-off, refining down to floor touchdown.
        val landingRelIdx = projected.subList(rawTakeoffIdx, projected.size)
            .indexOfFirst { it.minFootHeightCm <= landingContactCm }
        if (landingRelIdx <= 0) {
            return MeasurementOutcome.InvalidAttempt(
                reasons = listOf("Jump landing was not captured inside recording"),
                requiresExaminerReview = true,
            )
        }
        var landingIdx = rawTakeoffIdx + landingRelIdx
        while (landingIdx + 1 < projected.size &&
            projected[landingIdx].minFootHeightCm > groundContactRefineCm &&
            projected[landingIdx + 1].minFootHeightCm < projected[landingIdx].minFootHeightCm
        ) {
            landingIdx++
        }
        val airborneFrames = projected.subList(takeoffIdx, landingIdx)
        if (airborneFrames.isEmpty()) {
            return MeasurementOutcome.InvalidAttempt(
                reasons = listOf("Airborne phase too short to measure"),
                requiresExaminerReview = true,
            )
        }

        // Midpoint boundary estimation between last grounded/first airborne and last airborne/first landed frames.
        val takeoffTimestampNs = (projected[takeoffIdx - 1].timestampNs + projected[takeoffIdx].timestampNs) / 2L
        val landingTimestampNs = (projected[landingIdx - 1].timestampNs + projected[landingIdx].timestampNs) / 2L
        val flightTimeSec = (landingTimestampNs - takeoffTimestampNs) / 1_000_000_000.0
        val flightHeightCm = (STANDARD_GRAVITY_MPS2 * flightTimeSec * flightTimeSec / 8.0) * 100.0
        val quantizationUncertaintyCm =
            0.25 * STANDARD_GRAVITY_MPS2 * flightTimeSec * (timing.maxFrameDtMs / 1000.0) * 100.0

        val apexFrame = airborneFrames.maxBy { it.reachCm }
        val (effectiveStandingReachCm, effectiveMaxReachCm, jumpHeightCm, discrepancyCm) = if (
            protocol == VerticalJumpProtocol.STANDING_REACH_DELTA
        ) {
            val delta = apexFrame.reachCm - standingReachCm
            listOf(standingReachCm, apexFrame.reachCm, delta, abs(delta - flightHeightCm))
        } else {
            listOf(0.0, flightHeightCm, flightHeightCm, 0.0)
        }

        if (jumpHeightCm <= 0.0 || jumpHeightCm > 150.0) {
            return MeasurementOutcome.InvalidAttempt(
                reasons = listOf("Calculated vertical jump (${"%.1f".format(jumpHeightCm)} cm) is outside plausible bounds"),
                requiresExaminerReview = true,
            )
        }

        val flightEstimate = FlightTimeEstimate(
            flightTimeSec = flightTimeSec,
            flightHeightCm = flightHeightCm,
            quantizationUncertaintyCm = quantizationUncertaintyCm,
            takeoffTimestampNs = takeoffTimestampNs,
            landingTimestampNs = landingTimestampNs,
        )

        // Determine explicit provenance across baseline & apex (strictly never hide fallbacks).
        val worstProvenance = if (protocol == VerticalJumpProtocol.COUNTERMOVEMENT_FLIGHT_TIME) {
            LandmarkProvenance.MEDIAPIPE_POSE_HEEL_AND_MASK
        } else {
            val keyframeProvenances = standingReachFrames.map { it.provenance } + apexFrame.provenance
            when {
                keyframeProvenances.any { it == LandmarkProvenance.MEDIAPIPE_POSE_WRIST_FALLBACK } ->
                    LandmarkProvenance.MEDIAPIPE_POSE_WRIST_FALLBACK
                keyframeProvenances.any { it == LandmarkProvenance.MEDIAPIPE_POSE_INDEX_FALLBACK } ->
                    LandmarkProvenance.MEDIAPIPE_POSE_INDEX_FALLBACK
                else -> LandmarkProvenance.MEDIAPIPE_HAND_LANDMARKER_TIP
            }
        }

        val limitations = mutableListOf<String>()
        var requiresReview = false

        if (protocol == VerticalJumpProtocol.COUNTERMOVEMENT_FLIGHT_TIME) {
            limitations.add("Flight-time CMJ protocol used (no overhead reach delta); assumes symmetric take-off and landing posture")
        } else {
            when (worstProvenance) {
                LandmarkProvenance.MEDIAPIPE_POSE_WRIST_FALLBACK -> {
                    limitations.add("Pose wrist fallback used instead of fingertip landmark; reach precision reduced")
                    requiresReview = true
                }
                LandmarkProvenance.MEDIAPIPE_POSE_INDEX_FALLBACK -> {
                    limitations.add("Pose index landmark fallback used instead of 21-point HandLandmarker tip")
                }
                else -> Unit
            }
            if (discrepancyCm > maxReachVsFlightDiscrepancyCm) {
                limitations.add(
                    "Reach jump (${"%.1f".format(jumpHeightCm)} cm) differs from flight-time estimate (${"%.1f".format(flightHeightCm)} cm) by ${"%.1f".format(discrepancyCm)} cm",
                )
                requiresReview = true
            }
        }
        if (timing.droppedFrames > 0 || timing.isVariableFrameRate) {
            limitations.add("Camera recorded variable FPS / ${timing.droppedFrames} dropped frames (max dt ${"%.1f".format(timing.maxFrameDtMs)} ms)")
        }

        val provenancePenaltyCm = when (worstProvenance) {
            LandmarkProvenance.MEDIAPIPE_HAND_LANDMARKER_TIP -> 0.5
            LandmarkProvenance.MEDIAPIPE_POSE_INDEX_FALLBACK -> 1.5
            LandmarkProvenance.MEDIAPIPE_POSE_HEEL_AND_MASK -> 1.5
            else -> 3.0
        }
        val uncertaintyCm = (validCalibration.reprojectionErrorPx * 0.4) +
            provenancePenaltyCm +
            min(quantizationUncertaintyCm, 5.0)

        val avgConf = (standingReachFrames.map { it.confidence } + apexFrame.confidence).average()
        val confidenceScore = (
            avgConf *
                (if (worstProvenance == LandmarkProvenance.MEDIAPIPE_POSE_WRIST_FALLBACK) 0.75 else 1.0) *
                (1.0 - (validCalibration.reprojectionErrorPx / 5.0).coerceIn(0.0, 0.3))
            ).coerceIn(0.0, 1.0)

        return MeasurementOutcome.Success(
            value = VerticalJumpMeasurement(
                jumpHeightCm = jumpHeightCm,
                standingReachCm = effectiveStandingReachCm,
                maxJumpReachCm = effectiveMaxReachCm,
                jumpDurationSec = flightTimeSec,
                takeoffTimestampNs = takeoffTimestampNs,
                landingTimestampNs = landingTimestampNs,
                apexTimestampNs = apexFrame.timestampNs,
                dominantSide = apexFrame.side,
                flightTimeEstimate = flightEstimate,
                reachVsFlightDiscrepancyCm = discrepancyCm,
            ),
            unit = MeasurementUnit.CENTIMETERS,
            uncertainty = uncertaintyCm,
            confidence = ConfidenceReport(score = confidenceScore, notes = limitations),
            provenance = MeasurementProvenance(
                calibration = validCalibration,
                timing = timing,
                landmarkProvenance = worstProvenance,
                algorithmVersion = algorithmVersion,
                recordedAtEpochMs = recordedAtEpochMs,
                limitations = limitations,
            ),
            requiresExaminerReview = requiresReview,
        )
    }

    private fun resolveReachPoint(sample: VerticalJumpFrameSample): ResolvedReach? {
        val tip = sample.refinedHandTip
        if (tip != null && tip.confidence >= minLandmarkVisibility) {
            return ResolvedReach(
                pointPx = Point2D(tip.xNormalized * sample.imageWidth, tip.yNormalized * sample.imageHeight),
                provenance = LandmarkProvenance.MEDIAPIPE_HAND_LANDMARKER_TIP,
                side = tip.side,
                confidence = tip.confidence,
            )
        }

        val lms = sample.landmarks
        val leftIdx = lms[PoseLandmarkIndex.LEFT_INDEX]
        val rightIdx = lms[PoseLandmarkIndex.RIGHT_INDEX]
        val leftIdxOk = leftIdx.visibility >= minLandmarkVisibility
        val rightIdxOk = rightIdx.visibility >= minLandmarkVisibility
        if (leftIdxOk || rightIdxOk) {
            val useLeft = when {
                leftIdxOk && rightIdxOk -> leftIdx.y <= rightIdx.y
                else -> leftIdxOk
            }
            val chosen = if (useLeft) leftIdx else rightIdx
            return ResolvedReach(
                pointPx = Point2D(chosen.x * sample.imageWidth, chosen.y * sample.imageHeight),
                provenance = LandmarkProvenance.MEDIAPIPE_POSE_INDEX_FALLBACK,
                side = if (useLeft) ReachHandSide.LEFT else ReachHandSide.RIGHT,
                confidence = chosen.visibility,
            )
        }

        val leftWrist = lms[PoseLandmarkIndex.LEFT_WRIST]
        val rightWrist = lms[PoseLandmarkIndex.RIGHT_WRIST]
        val leftWristOk = leftWrist.visibility >= minLandmarkVisibility
        val rightWristOk = rightWrist.visibility >= minLandmarkVisibility
        if (leftWristOk || rightWristOk) {
            val useLeft = when {
                leftWristOk && rightWristOk -> leftWrist.y <= rightWrist.y
                else -> leftWristOk
            }
            val chosen = if (useLeft) leftWrist else rightWrist
            return ResolvedReach(
                pointPx = Point2D(chosen.x * sample.imageWidth, chosen.y * sample.imageHeight),
                provenance = LandmarkProvenance.MEDIAPIPE_POSE_WRIST_FALLBACK,
                side = if (useLeft) ReachHandSide.LEFT else ReachHandSide.RIGHT,
                confidence = chosen.visibility,
            )
        }

        return null
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
}
