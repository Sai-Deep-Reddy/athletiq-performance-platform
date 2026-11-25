package com.athletiq.core.biomechanics

import com.athletiq.core.model.CalibrationMethod
import com.athletiq.core.model.CalibrationPlaneType
import com.athletiq.core.model.CalibrationQuality
import com.athletiq.core.model.FrameTimingMetadata
import com.athletiq.core.model.MeasurementOutcome
import com.athletiq.core.model.MeasurementUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class SprintTimerCalculatorTest {
    private val imgWidth = 1920
    private val imgHeight = 1080

    private val groundToImage = PlanarHomography(
        doubleArrayOf(
            1.4, 0.05, 160.0,
            0.0, 3.0, 760.0,
            0.00005, 0.0001, 1.0,
        ),
    )

    private val groundHomography: PlanarHomography by lazy {
        val planePts = listOf(
            Point2D(-100.0, -50.0),
            Point2D(1100.0, -50.0),
            Point2D(1100.0, 50.0),
            Point2D(-100.0, 50.0),
        )
        val imgPts = planePts.map { groundToImage.imageToPlane(it) }
        PlanarHomography.solveFromCorrespondences(imgPts, planePts)
    }

    private val validGroundCal = CalibrationQuality(
        calibrationId = "sprint-cal-1",
        planeType = CalibrationPlaneType.GROUND_PLANE,
        method = CalibrationMethod.OPENCV_CHARUCO,
        reprojectionErrorPx = 0.4,
        calibratedAtEpochMs = 1_700_000_000_000L,
    )

    private val timing60 = FrameTimingMetadata(60.0, 16.67, 16.7, 0, false)

    @Test
    fun `measures interpolated 10m sprint time and average speed accurately`() {
        val calc = SprintTimerCalculator()
        // Constant 5.0 m/s = 500 cm/s -> 10m (1000 cm) takes exactly 2.000 s
        val dtSec = 1.0 / 60.0
        val dtNs = 16_666_667L
        val samples = (0..160).map { idx ->
            val tSec = idx * dtSec
            val xCm = -50.0 + 500.0 * tSec
            val groundPx = groundToImage.imageToPlane(Point2D(xCm, 0.0))
            val lms = MutableList(PoseLandmarkIndex.LANDMARK_COUNT) {
                LandmarkPoint3D(groundPx.x / imgWidth, groundPx.y / imgHeight, 0.0, 0.95)
            }
            SprintFrameSample(idx * dtNs, imgWidth, imgHeight, lms)
        }

        val outcome = calc.calculate(
            samples = samples,
            targetDistanceMeters = 10.0,
            calibration = validGroundCal,
            groundHomography = groundHomography,
            timing = timing60,
            recordedAtEpochMs = 1_700_000_000_000L,
        )

        assertTrue(outcome is MeasurementOutcome.Success)
        val success = outcome as MeasurementOutcome.Success
        assertEquals(MeasurementUnit.SECONDS, success.unit)
        assertTrue(abs(success.value.splitTimeSec - 2.0) < 0.02, "Got ${success.value.splitTimeSec}")
        assertTrue(abs(success.value.averageSpeedMps - 5.0) < 0.05, "Got ${success.value.averageSpeedMps}")
    }

    @Test
    fun `rejects vertical calibration and missing finish crossing`() {
        val calc = SprintTimerCalculator()
        val vertCal = validGroundCal.copy(planeType = CalibrationPlaneType.VERTICAL_PLANE)
        val px = groundToImage.imageToPlane(Point2D(-10.0, 0.0))
        val lms = List(PoseLandmarkIndex.LANDMARK_COUNT) {
            LandmarkPoint3D(px.x / imgWidth, px.y / imgHeight, 0.0, 0.95)
        }
        val samples = (0..5).map { SprintFrameSample(it * 16_666_667L, imgWidth, imgHeight, lms) }

        assertTrue(
            calc.calculate(samples, 10.0, vertCal, groundHomography, timing60, 1_700_000_000_000L)
                is MeasurementOutcome.CalibrationRequired,
        )
        assertTrue(
            calc.calculate(samples, 10.0, validGroundCal, groundHomography, timing60, 1_700_000_000_000L)
                is MeasurementOutcome.InvalidAttempt,
        )
    }
}
