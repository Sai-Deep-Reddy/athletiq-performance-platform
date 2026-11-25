package com.athletiq.core.biomechanics

import com.athletiq.core.model.CalibrationMethod
import com.athletiq.core.model.CalibrationPlaneType
import com.athletiq.core.model.CalibrationQuality
import com.athletiq.core.model.FrameTimingMetadata
import com.athletiq.core.model.MeasurementOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class BiomechanicsAnalyzerTest {
    private val validCal = CalibrationQuality(
        calibrationId = "cal-bio-1",
        planeType = CalibrationPlaneType.VERTICAL_PLANE,
        method = CalibrationMethod.OPENCV_CHARUCO,
        reprojectionErrorPx = 0.5,
        calibratedAtEpochMs = 1_700_000_000_000L,
    )

    private val timing60 = FrameTimingMetadata(
        fpsActual = 60.0,
        medianFrameDtMs = 16.67,
        maxFrameDtMs = 16.7,
        droppedFrames = 0,
        isVariableFrameRate = false,
    )

    private fun makePose(
        hipY: Double = 0.55,
        kneeBendOffsetX: Double = 0.0,
        kneeY: Double = 0.72,
        shoulderXOffset: Double = 0.0,
        shoulderY: Double = 0.35,
        elbowRaisedY: Double = 0.45,
        occludeLeftKnee: Boolean = false,
    ): MutableList<LandmarkPoint3D> {
        val lms = MutableList(PoseLandmarkIndex.LANDMARK_COUNT) {
            LandmarkPoint3D(0.5, 0.5, 0.0, 0.95)
        }
        lms[PoseLandmarkIndex.LEFT_SHOULDER] = LandmarkPoint3D(0.45 + shoulderXOffset, shoulderY, 0.0, 0.95)
        lms[PoseLandmarkIndex.RIGHT_SHOULDER] = LandmarkPoint3D(0.55 + shoulderXOffset, shoulderY, 0.0, 0.95)
        lms[PoseLandmarkIndex.LEFT_ELBOW] = LandmarkPoint3D(0.45 + shoulderXOffset, elbowRaisedY, 0.0, 0.95)
        lms[PoseLandmarkIndex.RIGHT_ELBOW] = LandmarkPoint3D(0.55 + shoulderXOffset, elbowRaisedY, 0.0, 0.95)
        lms[PoseLandmarkIndex.LEFT_HIP] = LandmarkPoint3D(0.45, hipY, 0.0, 0.95)
        lms[PoseLandmarkIndex.RIGHT_HIP] = LandmarkPoint3D(0.55, hipY, 0.0, 0.95)
        lms[PoseLandmarkIndex.LEFT_KNEE] = LandmarkPoint3D(
            0.45 + kneeBendOffsetX,
            kneeY,
            0.0,
            if (occludeLeftKnee) 0.1 else 0.95,
        )
        lms[PoseLandmarkIndex.RIGHT_KNEE] = LandmarkPoint3D(0.55, kneeY, 0.0, 0.95)
        lms[PoseLandmarkIndex.LEFT_ANKLE] = LandmarkPoint3D(0.45, 0.88, 0.0, 0.95)
        lms[PoseLandmarkIndex.RIGHT_ANKLE] = LandmarkPoint3D(0.55, 0.88, 0.0, 0.95)
        lms[PoseLandmarkIndex.LEFT_FOOT_INDEX] = LandmarkPoint3D(0.49, 0.90, 0.0, 0.95)
        lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX] = LandmarkPoint3D(0.59, 0.90, 0.0, 0.95)
        return lms
    }

    @Test
    fun `computes exact 90 degree knee flexion and null for occluded joint`() {
        val analyzer = BiomechanicsAnalyzer()
        // In pixel coordinates on 1000x1000 frame: hip(450, 500), knee(600, 500), ankle(600, 650) -> exact 90 deg!
        val lms = makePose(occludeLeftKnee = true)
        lms[PoseLandmarkIndex.RIGHT_HIP] = LandmarkPoint3D(0.45, 0.50, 0.0, 0.95)
        lms[PoseLandmarkIndex.RIGHT_KNEE] = LandmarkPoint3D(0.60, 0.50, 0.0, 0.95)
        lms[PoseLandmarkIndex.RIGHT_ANKLE] = LandmarkPoint3D(0.60, 0.65, 0.0, 0.95)

        val samples = (0 until 6).map { i ->
            BiomechanicsFrameSample(i * 16_666_667L, 1000, 1000, lms)
        }
        val outcome = analyzer.analyze(samples, validCal, timing60, 1_700_000_000_000L)
        assertTrue(outcome is MeasurementOutcome.Success)
        val report = (outcome as MeasurementOutcome.Success).value
        assertNull(report.frameKinematics[0].leftKneeDeg)
        assertNotNull(report.frameKinematics[0].rightKneeDeg)
        assertTrue(abs(report.frameKinematics[0].rightKneeDeg!! - 90.0) < 0.1)
    }

    @Test
    fun `extracts countermovement takeoff apex landing keyframes and neutral indicators`() {
        val analyzer = BiomechanicsAnalyzer()
        val dtNs = 16_666_667L
        val samples = mutableListOf<BiomechanicsFrameSample>()

        // 0..2: upright stance (hipY = 0.55)
        for (i in 0..2) {
            samples.add(BiomechanicsFrameSample(i * dtNs, 1000, 1000, makePose(hipY = 0.55)))
        }
        // 3..4: countermovement squat (hipY drops to 0.66 at index 4)
        samples.add(BiomechanicsFrameSample(3 * dtNs, 1000, 1000, makePose(hipY = 0.61, kneeBendOffsetX = 0.06)))
        samples.add(BiomechanicsFrameSample(4 * dtNs, 1000, 1000, makePose(hipY = 0.66, kneeBendOffsetX = 0.10)))
        // 5: takeoff (hipY = 0.55, arms swung overhead elbowY = 0.22)
        samples.add(BiomechanicsFrameSample(5 * dtNs, 1000, 1000, makePose(hipY = 0.55, elbowRaisedY = 0.22)))
        // 6..8: flight with apex at index 7 (hipY = 0.32)
        samples.add(BiomechanicsFrameSample(6 * dtNs, 1000, 1000, makePose(hipY = 0.40, elbowRaisedY = 0.15)))
        samples.add(BiomechanicsFrameSample(7 * dtNs, 1000, 1000, makePose(hipY = 0.32, elbowRaisedY = 0.10)))
        samples.add(BiomechanicsFrameSample(8 * dtNs, 1000, 1000, makePose(hipY = 0.42, elbowRaisedY = 0.16)))
        // 9..11: landing (hipY = 0.58)
        for (i in 9..11) {
            samples.add(BiomechanicsFrameSample(i * dtNs, 1000, 1000, makePose(hipY = 0.58, kneeBendOffsetX = 0.04)))
        }

        val outcome = analyzer.analyze(
            samples = samples,
            calibration = validCal,
            timing = timing60,
            recordedAtEpochMs = 1_700_000_000_000L,
            takeoffTimestampNs = 5 * dtNs,
            landingTimestampNs = 9 * dtNs,
        )

        assertTrue(outcome is MeasurementOutcome.Success)
        val success = outcome as MeasurementOutcome.Success
        val report = success.value
        assertEquals(4, report.keyFrames.deepestCountermovementIndex)
        assertEquals(5, report.keyFrames.takeoffIndex)
        assertEquals(7, report.keyFrames.apexIndex)
        assertEquals(9, report.keyFrames.landingIndex)
        assertTrue(
            report.indicators.any { it.code == PerformanceIndicatorCode.FULL_ARM_SWING_OBSERVED },
        )
        assertTrue(
            success.provenance.limitations.any { it.contains("not medical diagnoses", ignoreCase = true) },
        )
    }
}
