package com.athletiq.core.biomechanics

import com.athletiq.core.model.ConfidenceReport
import com.athletiq.core.model.FrameTimingMetadata
import com.athletiq.core.model.LandmarkProvenance
import com.athletiq.core.model.MeasurementOutcome
import com.athletiq.core.model.MeasurementProvenance
import com.athletiq.core.model.MeasurementUnit
import com.athletiq.core.model.CalibrationQuality
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class BiomechanicsFrameSample(
    val timestampNs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val landmarks: List<LandmarkPoint3D>,
) {
    init {
        require(landmarks.size == PoseLandmarkIndex.LANDMARK_COUNT) {
            "BiomechanicsFrameSample requires ${PoseLandmarkIndex.LANDMARK_COUNT} pose landmarks"
        }
        require(imageWidth > 0 && imageHeight > 0) { "Image dimensions must be > 0" }
    }
}

data class FrameKinematics(
    val timestampNs: Long,
    val leftKneeDeg: Double?,
    val rightKneeDeg: Double?,
    val leftHipDeg: Double?,
    val rightHipDeg: Double?,
    val trunkLeanDeg: Double?,
    val leftShoulderElevationDeg: Double?,
    val rightShoulderElevationDeg: Double?,
    val leftAnkleDeg: Double?,
    val rightAnkleDeg: Double?,
    val comProxyNorm: Point2D?,
    val comProxyPlaneCm: Point2D?,
    val lateralBalanceOffsetNorm: Double?,
)

enum class PerformanceIndicatorCode {
    BALANCED_BILATERAL_KNEE_FLEXION,
    ASYMMETRIC_KNEE_FLEXION_OBSERVED,
    FULL_ARM_SWING_OBSERVED,
    LIMITED_ARM_SWING_OBSERVED,
    CONTROLLED_LANDING_FLEXION,
    STIFF_LANDING_POSTURE_OBSERVED,
    BALANCED_TRUNK_ALIGNMENT,
    PRONOUNCED_TRUNK_LEAN_OBSERVED,
}

/**
 * Neutral biomechanical performance observation (never a clinical/medical diagnosis).
 */
data class PerformanceIndicator(
    val code: PerformanceIndicatorCode,
    val summary: String,
    val observedValue: Double,
    val unitLabel: String,
)

data class JumpKeyFrames(
    val initialStanceIndex: Int,
    val deepestCountermovementIndex: Int?,
    val takeoffIndex: Int?,
    val apexIndex: Int?,
    val landingIndex: Int?,
)

data class BiomechanicsReport(
    val frameKinematics: List<FrameKinematics>,
    val keyFrames: JumpKeyFrames,
    val minKneeFlexionDeg: Double?,
    val maxTrunkLeanDeg: Double?,
    val maxShoulderElevationDeg: Double?,
    val bilateralKneeAsymmetryDeg: Double?,
    val indicators: List<PerformanceIndicator>,
)

/**
 * Pure Kotlin Stage C4 biomechanical kinematics, jump trajectory, and key-frame analyzer.
 *
 * Returns null for any joint angle whose underlying landmarks fall below [minVisibility]
 * rather than fabricating defaults, and frames all findings strictly as objective
 * performance indicators (never medical diagnoses).
 */
class BiomechanicsAnalyzer(
    private val minVisibility: Double = 0.55,
    private val asymmetryWarningDeg: Double = 12.0,
    private val stiffLandingKneeDeg: Double = 155.0,
    private val pronouncedTrunkLeanDeg: Double = 35.0,
    private val fullArmSwingDeg: Double = 110.0,
    private val algorithmVersion: String = "bio-c4-v1.0",
) {
    fun analyze(
        samples: List<BiomechanicsFrameSample>,
        calibration: CalibrationQuality,
        timing: FrameTimingMetadata,
        recordedAtEpochMs: Long,
        planarHomography: PlanarHomography? = null,
        takeoffTimestampNs: Long? = null,
        landingTimestampNs: Long? = null,
    ): MeasurementOutcome<BiomechanicsReport> {
        if (samples.size < 5) {
            return MeasurementOutcome.InsufficientData(
                "At least 5 pose frames are required for biomechanical trajectory analysis (got ${samples.size})",
            )
        }
        for (i in 1 until samples.size) {
            if (samples[i].timestampNs <= samples[i - 1].timestampNs) {
                return MeasurementOutcome.InvalidAttempt(
                    reasons = listOf("Non-monotonic frame timestamps in biomechanics sequence"),
                    requiresExaminerReview = true,
                )
            }
        }

        val frameKinematics = samples.map { sample ->
            computeFrameKinematics(sample, planarHomography)
        }

        val validComFrames = frameKinematics.count { it.comProxyNorm != null }
        if (validComFrames < 5) {
            return MeasurementOutcome.LowConfidence(
                reason = "Insufficient visible hip/torso landmarks for biomechanical tracking",
                observedScore = validComFrames.toDouble() / samples.size,
                minimumRequired = 0.5,
            )
        }

        val takeoffIdx = takeoffTimestampNs?.let { target ->
            samples.indices.minByOrNull { abs(samples[it].timestampNs - target) }
        }
        val landingIdx = landingTimestampNs?.let { target ->
            samples.indices.minByOrNull { abs(samples[it].timestampNs - target) }
        }

        // Deepest countermovement = lowest COM (highest normalized image Y) prior to takeoff (or overall first half).
        val preTakeoffEnd = takeoffIdx ?: (samples.size / 2)
        val deepestCountermovementIdx = (0 until preTakeoffEnd.coerceAtLeast(1))
            .filter { frameKinematics[it].comProxyNorm != null }
            .maxByOrNull { frameKinematics[it].comProxyNorm!!.y }

        // Apex = highest COM (smallest normalized image Y) between takeoff and landing (or overall).
        val apexStart = takeoffIdx ?: 0
        val apexEnd = landingIdx ?: (samples.size - 1)
        val apexIdx = if (apexStart <= apexEnd) {
            (apexStart..apexEnd)
                .filter { frameKinematics[it].comProxyNorm != null }
                .minByOrNull { frameKinematics[it].comProxyNorm!!.y }
        } else {
            null
        }

        val keyFrames = JumpKeyFrames(
            initialStanceIndex = 0,
            deepestCountermovementIndex = deepestCountermovementIdx,
            takeoffIndex = takeoffIdx,
            apexIndex = apexIdx,
            landingIndex = landingIdx,
        )

        val kneeValues = frameKinematics.flatMap { listOfNotNull(it.leftKneeDeg, it.rightKneeDeg) }
        val minKneeDeg = kneeValues.minOrNull()
        val maxTrunkLean = frameKinematics.mapNotNull { it.trunkLeanDeg }.maxOrNull()
        val maxShoulderElev = frameKinematics
            .flatMap { listOfNotNull(it.leftShoulderElevationDeg, it.rightShoulderElevationDeg) }
            .maxOrNull()

        val bilateralDiffs = frameKinematics.mapNotNull { fk ->
            if (fk.leftKneeDeg != null && fk.rightKneeDeg != null) {
                abs(fk.leftKneeDeg - fk.rightKneeDeg)
            } else {
                null
            }
        }
        val maxKneeAsymmetry = bilateralDiffs.maxOrNull()

        val indicators = mutableListOf<PerformanceIndicator>()
        if (maxKneeAsymmetry != null) {
            if (maxKneeAsymmetry > asymmetryWarningDeg) {
                indicators.add(
                    PerformanceIndicator(
                        code = PerformanceIndicatorCode.ASYMMETRIC_KNEE_FLEXION_OBSERVED,
                        summary = "Peak left-right knee angle difference of ${"%.1f".format(maxKneeAsymmetry)} deg observed",
                        observedValue = maxKneeAsymmetry,
                        unitLabel = "deg",
                    ),
                )
            } else {
                indicators.add(
                    PerformanceIndicator(
                        code = PerformanceIndicatorCode.BALANCED_BILATERAL_KNEE_FLEXION,
                        summary = "Left-right knee flexion remained symmetric within ${"%.1f".format(maxKneeAsymmetry)} deg",
                        observedValue = maxKneeAsymmetry,
                        unitLabel = "deg",
                    ),
                )
            }
        }

        if (maxShoulderElev != null) {
            if (maxShoulderElev >= fullArmSwingDeg) {
                indicators.add(
                    PerformanceIndicator(
                        code = PerformanceIndicatorCode.FULL_ARM_SWING_OBSERVED,
                        summary = "Arm elevation reached ${"%.1f".format(maxShoulderElev)} deg",
                        observedValue = maxShoulderElev,
                        unitLabel = "deg",
                    ),
                )
            } else {
                indicators.add(
                    PerformanceIndicator(
                        code = PerformanceIndicatorCode.LIMITED_ARM_SWING_OBSERVED,
                        summary = "Peak shoulder elevation was ${"%.1f".format(maxShoulderElev)} deg",
                        observedValue = maxShoulderElev,
                        unitLabel = "deg",
                    ),
                )
            }
        }

        if (maxTrunkLean != null) {
            if (maxTrunkLean > pronouncedTrunkLeanDeg) {
                indicators.add(
                    PerformanceIndicator(
                        code = PerformanceIndicatorCode.PRONOUNCED_TRUNK_LEAN_OBSERVED,
                        summary = "Peak trunk lean from vertical reached ${"%.1f".format(maxTrunkLean)} deg",
                        observedValue = maxTrunkLean,
                        unitLabel = "deg",
                    ),
                )
            } else {
                indicators.add(
                    PerformanceIndicator(
                        code = PerformanceIndicatorCode.BALANCED_TRUNK_ALIGNMENT,
                        summary = "Peak trunk lean stayed within ${"%.1f".format(maxTrunkLean)} deg",
                        observedValue = maxTrunkLean,
                        unitLabel = "deg",
                    ),
                )
            }
        }

        if (landingIdx != null) {
            val postWindow = frameKinematics.subList(landingIdx, min(frameKinematics.size, landingIdx + 8))
            val landingMinKnee = postWindow.flatMap { listOfNotNull(it.leftKneeDeg, it.rightKneeDeg) }.minOrNull()
            if (landingMinKnee != null) {
                if (landingMinKnee > stiffLandingKneeDeg) {
                    indicators.add(
                        PerformanceIndicator(
                            code = PerformanceIndicatorCode.STIFF_LANDING_POSTURE_OBSERVED,
                            summary = "Minimal knee flexion after landing (${"%.1f".format(landingMinKnee)} deg)",
                            observedValue = landingMinKnee,
                            unitLabel = "deg",
                        ),
                    )
                } else {
                    indicators.add(
                        PerformanceIndicator(
                            code = PerformanceIndicatorCode.CONTROLLED_LANDING_FLEXION,
                            summary = "Knee flexion absorbed landing down to ${"%.1f".format(landingMinKnee)} deg",
                            observedValue = landingMinKnee,
                            unitLabel = "deg",
                        ),
                    )
                }
            }
        }

        val coverageRatio = validComFrames.toDouble() / samples.size
        val notes = mutableListOf(
            "Biomechanical outputs are performance indicators only, not medical diagnoses.",
        )
        if (timing.droppedFrames > 0) {
            notes.add("${timing.droppedFrames} frames dropped during movement capture")
        }

        return MeasurementOutcome.Success(
            value = BiomechanicsReport(
                frameKinematics = frameKinematics,
                keyFrames = keyFrames,
                minKneeFlexionDeg = minKneeDeg,
                maxTrunkLeanDeg = maxTrunkLean,
                maxShoulderElevationDeg = maxShoulderElev,
                bilateralKneeAsymmetryDeg = maxKneeAsymmetry,
                indicators = indicators,
            ),
            unit = MeasurementUnit.CENTIMETERS,
            uncertainty = (calibration.reprojectionErrorPx * 0.5).coerceAtLeast(0.0),
            confidence = ConfidenceReport(score = coverageRatio.coerceIn(0.0, 1.0), notes = notes),
            provenance = MeasurementProvenance(
                calibration = calibration,
                timing = timing,
                landmarkProvenance = LandmarkProvenance.MEDIAPIPE_POSE_SEGMENTATION_REFINED,
                algorithmVersion = algorithmVersion,
                recordedAtEpochMs = recordedAtEpochMs,
                limitations = notes,
            ),
            requiresExaminerReview = coverageRatio < 0.85,
        )
    }

    private fun computeFrameKinematics(
        sample: BiomechanicsFrameSample,
        planarHomography: PlanarHomography?,
    ): FrameKinematics {
        val lms = sample.landmarks

        val lKnee = angleIfVisible(
            lms[PoseLandmarkIndex.LEFT_HIP],
            lms[PoseLandmarkIndex.LEFT_KNEE],
            lms[PoseLandmarkIndex.LEFT_ANKLE],
            sample.imageWidth,
            sample.imageHeight,
        )
        val rKnee = angleIfVisible(
            lms[PoseLandmarkIndex.RIGHT_HIP],
            lms[PoseLandmarkIndex.RIGHT_KNEE],
            lms[PoseLandmarkIndex.RIGHT_ANKLE],
            sample.imageWidth,
            sample.imageHeight,
        )
        val lHip = angleIfVisible(
            lms[PoseLandmarkIndex.LEFT_SHOULDER],
            lms[PoseLandmarkIndex.LEFT_HIP],
            lms[PoseLandmarkIndex.LEFT_KNEE],
            sample.imageWidth,
            sample.imageHeight,
        )
        val rHip = angleIfVisible(
            lms[PoseLandmarkIndex.RIGHT_SHOULDER],
            lms[PoseLandmarkIndex.RIGHT_HIP],
            lms[PoseLandmarkIndex.RIGHT_KNEE],
            sample.imageWidth,
            sample.imageHeight,
        )
        val lShoulder = angleIfVisible(
            lms[PoseLandmarkIndex.LEFT_HIP],
            lms[PoseLandmarkIndex.LEFT_SHOULDER],
            lms[PoseLandmarkIndex.LEFT_ELBOW],
            sample.imageWidth,
            sample.imageHeight,
        )
        val rShoulder = angleIfVisible(
            lms[PoseLandmarkIndex.RIGHT_HIP],
            lms[PoseLandmarkIndex.RIGHT_SHOULDER],
            lms[PoseLandmarkIndex.RIGHT_ELBOW],
            sample.imageWidth,
            sample.imageHeight,
        )
        val lAnkle = angleIfVisible(
            lms[PoseLandmarkIndex.LEFT_KNEE],
            lms[PoseLandmarkIndex.LEFT_ANKLE],
            lms[PoseLandmarkIndex.LEFT_FOOT_INDEX],
            sample.imageWidth,
            sample.imageHeight,
        )
        val rAnkle = angleIfVisible(
            lms[PoseLandmarkIndex.RIGHT_KNEE],
            lms[PoseLandmarkIndex.RIGHT_ANKLE],
            lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX],
            sample.imageWidth,
            sample.imageHeight,
        )

        val lHipLm = lms[PoseLandmarkIndex.LEFT_HIP]
        val rHipLm = lms[PoseLandmarkIndex.RIGHT_HIP]
        val lShLm = lms[PoseLandmarkIndex.LEFT_SHOULDER]
        val rShLm = lms[PoseLandmarkIndex.RIGHT_SHOULDER]
        val lAnkLm = lms[PoseLandmarkIndex.LEFT_ANKLE]
        val rAnkLm = lms[PoseLandmarkIndex.RIGHT_ANKLE]

        val comProxyNorm = if (min(lHipLm.visibility, rHipLm.visibility) >= minVisibility) {
            Point2D((lHipLm.x + rHipLm.x) * 0.5, (lHipLm.y + rHipLm.y) * 0.5)
        } else {
            null
        }

        val comPlaneCm = if (comProxyNorm != null && planarHomography != null) {
            runCatching {
                planarHomography.imageToPlane(
                    Point2D(comProxyNorm.x * sample.imageWidth, comProxyNorm.y * sample.imageHeight),
                )
            }.getOrNull()
        } else {
            null
        }

        val trunkLeanDeg = if (comProxyNorm != null && min(lShLm.visibility, rShLm.visibility) >= minVisibility) {
            val shMidX = (lShLm.x + rShLm.x) * 0.5 * sample.imageWidth
            val shMidY = (lShLm.y + rShLm.y) * 0.5 * sample.imageHeight
            val hipMidX = comProxyNorm.x * sample.imageWidth
            val hipMidY = comProxyNorm.y * sample.imageHeight
            val dx = shMidX - hipMidX
            val dyUp = hipMidY - shMidY // image Y increases downward
            if (hypot(dx, dyUp) > 1e-6) {
                Math.toDegrees(atan2(abs(dx), max(dyUp, 1e-6)))
            } else {
                null
            }
        } else {
            null
        }

        val lateralBalance = if (comProxyNorm != null && min(lAnkLm.visibility, rAnkLm.visibility) >= minVisibility) {
            val ankleMidX = (lAnkLm.x + rAnkLm.x) * 0.5
            abs(comProxyNorm.x - ankleMidX)
        } else {
            null
        }

        return FrameKinematics(
            timestampNs = sample.timestampNs,
            leftKneeDeg = lKnee,
            rightKneeDeg = rKnee,
            leftHipDeg = lHip,
            rightHipDeg = rHip,
            trunkLeanDeg = trunkLeanDeg,
            leftShoulderElevationDeg = lShoulder,
            rightShoulderElevationDeg = rShoulder,
            leftAnkleDeg = lAnkle,
            rightAnkleDeg = rAnkle,
            comProxyNorm = comProxyNorm,
            comProxyPlaneCm = comPlaneCm,
            lateralBalanceOffsetNorm = lateralBalance,
        )
    }

    private fun angleIfVisible(
        a: LandmarkPoint3D,
        b: LandmarkPoint3D,
        c: LandmarkPoint3D,
        width: Int,
        height: Int,
    ): Double? {
        if (minOf(a.visibility, b.visibility, c.visibility) < minVisibility) return null
        val bax = (a.x - b.x) * width
        val bay = (a.y - b.y) * height
        val bcx = (c.x - b.x) * width
        val bcy = (c.y - b.y) * height
        val magBa = hypot(bax, bay)
        val magBc = hypot(bcx, bcy)
        if (magBa <= 1e-6 || magBc <= 1e-6) return null
        val cosTheta = ((bax * bcx + bay * bcy) / (magBa * magBc)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosTheta))
    }
}
