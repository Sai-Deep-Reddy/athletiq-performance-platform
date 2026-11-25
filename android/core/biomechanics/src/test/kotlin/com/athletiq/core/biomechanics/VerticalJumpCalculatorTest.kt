package com.athletiq.core.biomechanics

import com.athletiq.core.model.CalibrationMethod
import com.athletiq.core.model.CalibrationPlaneType
import com.athletiq.core.model.CalibrationQuality
import com.athletiq.core.model.FrameTimingMetadata
import com.athletiq.core.model.LandmarkProvenance
import com.athletiq.core.model.MeasurementOutcome
import com.athletiq.core.model.MeasurementUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class VerticalJumpCalculatorTest {
    private val imgWidth = 1080
    private val imgHeight = 1920

    private val planeToImage = PlanarHomography(
        doubleArrayOf(
            4.5, 0.1, 220.0,
            0.0, -6.0, 1780.0,
            0.0, 0.00015, 1.0,
        ),
    )

    private val verticalHomography: PlanarHomography by lazy {
        val planePts = listOf(
            Point2D(0.0, 0.0),
            Point2D(140.0, 0.0),
            Point2D(140.0, 290.0),
            Point2D(0.0, 290.0),
        )
        val imgPts = planePts.map { planeToImage.imageToPlane(it) }
        PlanarHomography.solveFromCorrespondences(imgPts, planePts)
    }

    private val validVerticalCal = CalibrationQuality(
        calibrationId = "vert-cal-vj",
        planeType = CalibrationPlaneType.VERTICAL_PLANE,
        method = CalibrationMethod.OPENCV_CHARUCO,
        reprojectionErrorPx = 0.35,
        calibratedAtEpochMs = 1_700_000_000_000L,
    )

    private val timing120Fps = FrameTimingMetadata(
        fpsActual = 120.0,
        medianFrameDtMs = 8.333,
        maxFrameDtMs = 8.4,
        droppedFrames = 0,
        isVariableFrameRate = false,
    )

    private fun buildJumpSequence(
        fps: Double = 120.0,
        standingReachCm: Double = 220.0,
        takeoffVelocityMps: Double = 3.0, // true apex = 3^2 / (2*9.80665) = 0.45887m = 45.89cm
        useHandLandmarkerTip: Boolean = true,
        hideIndexForWristFallback: Boolean = false,
        extraAirborneTimeSec: Double = 0.0, // simulates leg tuck altering flight time
    ): List<VerticalJumpFrameSample> {
        val g = VerticalJumpCalculator.STANDARD_GRAVITY_MPS2
        val dtSec = 1.0 / fps
        val dtNs = (dtSec * 1_000_000_000.0).toLong()
        val samples = mutableListOf<VerticalJumpFrameSample>()
        var tNs = 0L

        fun planeToNorm(xCm: Double, yCm: Double, vis: Double = 0.96): LandmarkPoint3D {
            val px = planeToImage.imageToPlane(Point2D(xCm, yCm))
            return LandmarkPoint3D(px.x / imgWidth, px.y / imgHeight, 0.0, vis)
        }

        fun makeSample(
            timestampNs: Long,
            bodyLiftCm: Double,
            armRaised: Boolean,
        ): VerticalJumpFrameSample {
            val lms = MutableList(PoseLandmarkIndex.LANDMARK_COUNT) { planeToNorm(60.0, 90.0 + bodyLiftCm) }
            lms[PoseLandmarkIndex.NOSE] = planeToNorm(60.0, 155.0 + bodyLiftCm)
            lms[PoseLandmarkIndex.LEFT_SHOULDER] = planeToNorm(50.0, 140.0 + bodyLiftCm)
            lms[PoseLandmarkIndex.RIGHT_SHOULDER] = planeToNorm(70.0, 140.0 + bodyLiftCm)
            lms[PoseLandmarkIndex.LEFT_HIP] = planeToNorm(52.0, 92.0 + bodyLiftCm)
            lms[PoseLandmarkIndex.RIGHT_HIP] = planeToNorm(68.0, 92.0 + bodyLiftCm)
            lms[PoseLandmarkIndex.LEFT_KNEE] = planeToNorm(52.0, 48.0 + bodyLiftCm)
            lms[PoseLandmarkIndex.RIGHT_KNEE] = planeToNorm(68.0, 48.0 + bodyLiftCm)
            lms[PoseLandmarkIndex.LEFT_ANKLE] = planeToNorm(52.0, 8.0 + bodyLiftCm)
            lms[PoseLandmarkIndex.RIGHT_ANKLE] = planeToNorm(68.0, 8.0 + bodyLiftCm)
            lms[PoseLandmarkIndex.LEFT_HEEL] = planeToNorm(52.0, bodyLiftCm)
            lms[PoseLandmarkIndex.RIGHT_HEEL] = planeToNorm(68.0, bodyLiftCm)
            lms[PoseLandmarkIndex.LEFT_FOOT_INDEX] = planeToNorm(52.0, bodyLiftCm)
            lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX] = planeToNorm(68.0, bodyLiftCm)

            val handY = if (armRaised) standingReachCm + bodyLiftCm else 100.0 + bodyLiftCm
            lms[PoseLandmarkIndex.RIGHT_ELBOW] = planeToNorm(70.0, (140.0 + handY) * 0.5)
            lms[PoseLandmarkIndex.RIGHT_WRIST] = planeToNorm(70.0, handY - 12.0)
            lms[PoseLandmarkIndex.RIGHT_INDEX] = planeToNorm(
                70.0,
                handY,
                vis = if (hideIndexForWristFallback) 0.1 else 0.95,
            )
            lms[PoseLandmarkIndex.LEFT_INDEX] = planeToNorm(50.0, 95.0, vis = if (hideIndexForWristFallback) 0.1 else 0.95)
            lms[PoseLandmarkIndex.LEFT_WRIST] = planeToNorm(50.0, 95.0)

            val tip = if (useHandLandmarkerTip) {
                val tipNorm = planeToNorm(70.0, handY)
                RefinedFingertipPoint(tipNorm.x, tipNorm.y, 0.96, ReachHandSide.RIGHT)
            } else {
                null
            }
            return VerticalJumpFrameSample(timestampNs, imgWidth, imgHeight, lms, tip)
        }

        // Standing reach phase (0.5s)
        val standingCount = (0.5 * fps).toInt().coerceAtLeast(10)
        repeat(standingCount) {
            samples.add(makeSample(tNs, bodyLiftCm = 0.0, armRaised = true))
            tNs += dtNs
        }

        // Parabolic airborne phase
        val totalFlightSec = (2.0 * takeoffVelocityMps / g) + extraAirborneTimeSec
        val airCount = (totalFlightSec / dtSec).toInt()
        for (i in 1..airCount) {
            val tau = i * dtSec
            val trueFlightTime = 2.0 * takeoffVelocityMps / g
            val scaledTau = tau * (trueFlightTime / totalFlightSec)
            val liftCm = maxOf(0.0, (takeoffVelocityMps * scaledTau - 0.5 * g * scaledTau * scaledTau) * 100.0)
            samples.add(makeSample(tNs, bodyLiftCm = liftCm, armRaised = true))
            tNs += dtNs
        }

        // Landing phase (0.3s)
        val landCount = (0.3 * fps).toInt().coerceAtLeast(6)
        repeat(landCount) {
            samples.add(makeSample(tNs, bodyLiftCm = 0.0, armRaised = true))
            tNs += dtNs
        }
        return samples
    }

    @Test
    fun `computes reach jump height and independent flight time accurately at 120fps`() {
        val calc = VerticalJumpCalculator()
        val samples = buildJumpSequence(fps = 120.0, takeoffVelocityMps = 3.0)
        val outcome = calc.calculate(samples, validVerticalCal, verticalHomography, timing120Fps, 1_700_000_000_000L)

        assertTrue(outcome is MeasurementOutcome.Success)
        val success = outcome as MeasurementOutcome.Success
        val expectedCm = (3.0 * 3.0 / (2.0 * VerticalJumpCalculator.STANDARD_GRAVITY_MPS2)) * 100.0

        assertEquals(MeasurementUnit.CENTIMETERS, success.unit)
        assertEquals(LandmarkProvenance.MEDIAPIPE_HAND_LANDMARKER_TIP, success.provenance.landmarkProvenance)
        assertTrue(abs(success.value.jumpHeightCm - expectedCm) < 1.0, "Got ${success.value.jumpHeightCm}")
        assertTrue(abs(success.value.flightTimeEstimate.flightHeightCm - expectedCm) < 2.0)
        assertFalse(success.requiresExaminerReview)
    }

    @Test
    fun `flags explicit limitation and review when wrist fallback is used`() {
        val calc = VerticalJumpCalculator()
        val samples = buildJumpSequence(
            fps = 60.0,
            useHandLandmarkerTip = false,
            hideIndexForWristFallback = true,
        )
        val timing60 = FrameTimingMetadata(60.0, 16.67, 16.7, 0, false)
        val outcome = calc.calculate(samples, validVerticalCal, verticalHomography, timing60, 1_700_000_000_000L)

        assertTrue(outcome is MeasurementOutcome.Success)
        val success = outcome as MeasurementOutcome.Success
        assertEquals(LandmarkProvenance.MEDIAPIPE_POSE_WRIST_FALLBACK, success.provenance.landmarkProvenance)
        assertTrue(success.requiresExaminerReview)
        assertTrue(success.provenance.limitations.any { it.contains("wrist fallback", ignoreCase = true) })
        assertTrue(success.uncertainty >= 3.0)
    }

    @Test
    fun `flags discrepancy for examiner review when flight time diverges from reach delta`() {
        val calc = VerticalJumpCalculator()
        // Extra airborne duration simulates leg tucking on landing while apex reach stays unchanged
        val samples = buildJumpSequence(fps = 120.0, takeoffVelocityMps = 3.0, extraAirborneTimeSec = 0.12)
        val outcome = calc.calculate(samples, validVerticalCal, verticalHomography, timing120Fps, 1_700_000_000_000L)

        assertTrue(outcome is MeasurementOutcome.Success)
        val success = outcome as MeasurementOutcome.Success
        assertTrue(success.value.reachVsFlightDiscrepancyCm > 8.0)
        assertTrue(success.requiresExaminerReview)
    }

    @Test
    fun `rejects ground plane calibration for vertical jump`() {
        val calc = VerticalJumpCalculator()
        val groundCal = validVerticalCal.copy(planeType = CalibrationPlaneType.GROUND_PLANE)
        val samples = buildJumpSequence()
        val outcome = calc.calculate(samples, groundCal, verticalHomography, timing120Fps, 1_700_000_000_000L)
        assertTrue(outcome is MeasurementOutcome.CalibrationRequired)
    }

    @Test
    fun `computes countermovement flight time protocol when arms stay lowered`() {
        val calc = VerticalJumpCalculator()
        val samplesWithLoweredArms = buildJumpSequence(fps = 60.0, takeoffVelocityMps = 3.0, useHandLandmarkerTip = false)
            .map { s ->
                // Simulate countermovement jump with arms at sides (wrists below shoulders)
                val updated = s.landmarks.toMutableList()
                updated[PoseLandmarkIndex.RIGHT_WRIST] = updated[PoseLandmarkIndex.RIGHT_HIP]
                updated[PoseLandmarkIndex.RIGHT_INDEX] = updated[PoseLandmarkIndex.RIGHT_HIP]
                s.copy(landmarks = updated)
            }
        val timing60 = FrameTimingMetadata(60.0, 16.67, 16.7, 0, false)
        val outcome = calc.calculate(
            samplesWithLoweredArms,
            validVerticalCal,
            verticalHomography,
            timing60,
            1_700_000_000_000L,
            protocol = VerticalJumpProtocol.COUNTERMOVEMENT_FLIGHT_TIME,
        )
        assertTrue(outcome is MeasurementOutcome.Success)
        val success = outcome as MeasurementOutcome.Success
        val expectedCm = (3.0 * 3.0 / (2.0 * VerticalJumpCalculator.STANDARD_GRAVITY_MPS2)) * 100.0
        assertEquals(LandmarkProvenance.MEDIAPIPE_POSE_HEEL_AND_MASK, success.provenance.landmarkProvenance)
        assertTrue(abs(success.value.jumpHeightCm - expectedCm) < 2.5, "Got ${success.value.jumpHeightCm}")
        assertFalse(success.requiresExaminerReview)
    }
}
