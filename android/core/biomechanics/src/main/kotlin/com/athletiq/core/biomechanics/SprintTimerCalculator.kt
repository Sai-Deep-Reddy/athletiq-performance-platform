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

data class SprintFrameSample(
    val timestampNs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val landmarks: List<LandmarkPoint3D>,
) {
    init {
        require(landmarks.size == PoseLandmarkIndex.LANDMARK_COUNT) {
            "SprintFrameSample requires ${PoseLandmarkIndex.LANDMARK_COUNT} pose landmarks"
        }
        require(imageWidth > 0 && imageHeight > 0)
    }
}

data class SprintMeasurement(
    val splitTimeSec: Double,
    val distanceMeters: Double,
    val averageSpeedMps: Double,
    val startTimestampNs: Long,
    val finishTimestampNs: Long,
)

/**
 * Pure Kotlin Stage D (Phase 10) sprint timing calculator.
 *
 * Uses a calibrated GROUND_PLANE homography where plane X = 0 cm is the start line
 * and X = targetDistanceMeters * 100 cm is the finish line. Tracks torso midpoint
 * (shoulders/hips) with sub-frame linear interpolation and propagates camera timing
 * quantization error explicitly.
 */
class SprintTimerCalculator(
    private val minLandmarkVisibility: Double = 0.55,
    private val preStartViolationToleranceCm: Double = 5.0,
    private val algorithmVersion: String = "sprint-d-v1.0",
) {
    fun calculate(
        samples: List<SprintFrameSample>,
        targetDistanceMeters: Double,
        calibration: CalibrationQuality?,
        groundHomography: PlanarHomography?,
        timing: FrameTimingMetadata?,
        recordedAtEpochMs: Long,
    ): MeasurementOutcome<SprintMeasurement> {
        require(targetDistanceMeters.isFinite() && targetDistanceMeters > 0.0) {
            "targetDistanceMeters must be > 0"
        }

        val calCheck = CalibrationQualityGate.validate(TestType.SPRINT, calibration)
        if (calCheck is CalibrationGateResult.Rejected) {
            return MeasurementOutcome.CalibrationRequired(calCheck.requiredPlane, calCheck.reason)
        }
        val validCalibration = (calCheck as CalibrationGateResult.Valid).calibration
        if (groundHomography == null) {
            return MeasurementOutcome.CalibrationRequired(
                requiredPlane = CalibrationQualityGate.requiredPlaneFor(TestType.SPRINT),
                reason = "Ground plane homography transform is missing",
            )
        }
        if (timing == null || samples.size < 4) {
            return MeasurementOutcome.InsufficientData("Insufficient frames or timing metadata for sprint timing")
        }

        for (i in 1 until samples.size) {
            if (samples[i].timestampNs <= samples[i - 1].timestampNs) {
                return MeasurementOutcome.InvalidAttempt(
                    reasons = listOf("Non-monotonic frame timestamps detected during sprint capture"),
                    requiresExaminerReview = true,
                )
            }
        }

        val finishLineCm = targetDistanceMeters * 100.0
        val torsoTrack = mutableListOf<Pair<Long, Double>>()
        val visibilities = mutableListOf<Double>()

        for (sample in samples) {
            val lms = sample.landmarks
            val lHip = lms[PoseLandmarkIndex.LEFT_HIP]
            val rHip = lms[PoseLandmarkIndex.RIGHT_HIP]
            val lSh = lms[PoseLandmarkIndex.LEFT_SHOULDER]
            val rSh = lms[PoseLandmarkIndex.RIGHT_SHOULDER]

            val torsoPts = listOf(lHip, rHip, lSh, rSh).filter { it.visibility >= minLandmarkVisibility }
            if (torsoPts.size < 2) continue

            val avgXNorm = torsoPts.map { it.x }.average()
            // Project torso ground-reference X using ankle/foot height or torso projection on calibrated lane.
            val feet = listOf(
                lms[PoseLandmarkIndex.LEFT_FOOT_INDEX],
                lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX],
                lms[PoseLandmarkIndex.LEFT_ANKLE],
                lms[PoseLandmarkIndex.RIGHT_ANKLE],
            ).filter { it.visibility >= minLandmarkVisibility }

            val groundYNorm = if (feet.isNotEmpty()) {
                feet.map { it.y }.average()
            } else {
                torsoPts.map { it.y }.average()
            }

            val planePt = groundHomography.imageToPlane(
                Point2D(avgXNorm * sample.imageWidth, groundYNorm * sample.imageHeight),
            )
            torsoTrack.add(sample.timestampNs to planePt.x)
            visibilities.add(torsoPts.map { it.visibility }.average())
        }

        if (torsoTrack.size < 4) {
            return MeasurementOutcome.LowConfidence(
                reason = "Insufficient visible torso/foot frames across sprint lane",
                observedScore = torsoTrack.size.toDouble() / samples.size,
                minimumRequired = 0.5,
            )
        }

        if (torsoTrack.first().second > preStartViolationToleranceCm) {
            return MeasurementOutcome.InvalidAttempt(
                reasons = listOf("Athlete torso started beyond the start line (${"%.1f".format(torsoTrack.first().second)} cm)"),
                requiresExaminerReview = true,
            )
        }

        val startTimestampNs = interpolateLineCrossingNs(torsoTrack, targetXCm = 0.0)
            ?: return MeasurementOutcome.InvalidAttempt(
                reasons = listOf("Start line crossing (0.0 cm) was not observed"),
                requiresExaminerReview = true,
            )

        val finishTimestampNs = interpolateLineCrossingNs(torsoTrack, targetXCm = finishLineCm)
            ?: return MeasurementOutcome.InvalidAttempt(
                reasons = listOf("Finish line crossing (${"%.1f".format(finishLineCm)} cm) was not observed"),
                requiresExaminerReview = true,
            )

        if (finishTimestampNs <= startTimestampNs) {
            return MeasurementOutcome.InvalidAttempt(
                reasons = listOf("Finish crossing timestamp preceded start crossing"),
                requiresExaminerReview = true,
            )
        }

        val splitTimeSec = (finishTimestampNs - startTimestampNs) / 1_000_000_000.0
        val avgSpeedMps = targetDistanceMeters / splitTimeSec
        if (avgSpeedMps > 13.5 || avgSpeedMps <= 0.1) {
            return MeasurementOutcome.InvalidAttempt(
                reasons = listOf("Computed sprint speed (${"%.2f".format(avgSpeedMps)} m/s) outside plausible human bounds"),
                requiresExaminerReview = true,
            )
        }

        val timingUncertaintySec = timing.maxFrameDtMs / 1000.0
        val limitations = mutableListOf<String>()
        if (timing.isVariableFrameRate || timing.droppedFrames > 0) {
            limitations.add("Camera recorded variable FPS / ${timing.droppedFrames} dropped frames (max dt ${"%.1f".format(timing.maxFrameDtMs)} ms)")
        }

        val avgVis = visibilities.average()
        val confidenceScore = (
            avgVis * (1.0 - (validCalibration.reprojectionErrorPx / 5.0).coerceIn(0.0, 0.3))
            ).coerceIn(0.0, 1.0)

        return MeasurementOutcome.Success(
            value = SprintMeasurement(
                splitTimeSec = splitTimeSec,
                distanceMeters = targetDistanceMeters,
                averageSpeedMps = avgSpeedMps,
                startTimestampNs = startTimestampNs,
                finishTimestampNs = finishTimestampNs,
            ),
            unit = MeasurementUnit.SECONDS,
            uncertainty = timingUncertaintySec,
            confidence = ConfidenceReport(score = confidenceScore, notes = limitations),
            provenance = MeasurementProvenance(
                calibration = validCalibration,
                timing = timing,
                landmarkProvenance = LandmarkProvenance.MEDIAPIPE_POSE_HEEL_AND_MASK,
                algorithmVersion = algorithmVersion,
                recordedAtEpochMs = recordedAtEpochMs,
                limitations = limitations,
            ),
            requiresExaminerReview = timing.droppedFrames > 0 || abs(timingUncertaintySec) > 0.04,
        )
    }

    private fun interpolateLineCrossingNs(
        track: List<Pair<Long, Double>>,
        targetXCm: Double,
    ): Long? {
        for (i in 1 until track.size) {
            val (t0, x0) = track[i - 1]
            val (t1, x1) = track[i]
            if (x0 <= targetXCm && x1 >= targetXCm) {
                val dx = x1 - x0
                if (dx <= 1e-9) return t1
                val fraction = ((targetXCm - x0) / dx).coerceIn(0.0, 1.0)
                return t0 + ((t1 - t0) * fraction).toLong()
            }
        }
        return null
    }
}
