package com.athletiq.core.biomechanics

import com.athletiq.core.model.CalibrationMethod
import com.athletiq.core.model.CalibrationPlaneType
import com.athletiq.core.model.CalibrationQuality
import com.athletiq.core.model.TestType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class PlanarHomographyTest {
    @Test
    fun `recovers metric plane coordinates accurately under perspective warp`() {
        // Known ground-truth projective matrix (plane cm -> image px)
        val truePlaneToImage = PlanarHomography(
            doubleArrayOf(
                4.2, 0.35, 180.0,
                -0.15, 3.8, 120.0,
                0.0004, 0.0009, 1.0,
            ),
        )
        val planePts = listOf(
            Point2D(0.0, 0.0),
            Point2D(200.0, 0.0),
            Point2D(200.0, 120.0),
            Point2D(0.0, 120.0),
            Point2D(100.0, 60.0),
        )
        val imgPts = planePts.map { truePlaneToImage.imageToPlane(it) }

        val solved = PlanarHomography.solveFromCorrespondences(imgPts, planePts)
        val rmsErr = solved.computeRmsReprojectionErrorPx(imgPts, planePts)
        assertTrue(rmsErr < 1e-6, "Expected near-zero reprojection error, got $rmsErr")

        // Independently verify an unseen test point (155.5 cm, 42.0 cm)
        val unseenPlane = Point2D(155.5, 42.0)
        val unseenImg = truePlaneToImage.imageToPlane(unseenPlane)
        val recovered = solved.imageToPlane(unseenImg)
        assertTrue(abs(recovered.x - unseenPlane.x) < 1e-4)
        assertTrue(abs(recovered.y - unseenPlane.y) < 1e-4)
    }

    @Test
    fun `rejects collinear or insufficient calibration points`() {
        val collinear = listOf(
            Point2D(10.0, 10.0),
            Point2D(20.0, 20.0),
            Point2D(30.0, 30.0),
            Point2D(40.0, 40.0),
        )
        val plane = listOf(
            Point2D(0.0, 0.0),
            Point2D(50.0, 0.0),
            Point2D(50.0, 50.0),
            Point2D(0.0, 50.0),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PlanarHomography.solveFromCorrespondences(collinear, plane)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanarHomography.solveFromCorrespondences(collinear.take(3), plane.take(3))
        }
    }

    @Test
    fun `quality gate strictly separates vertical plane and ground plane`() {
        val groundCal = CalibrationQuality(
            calibrationId = "cal-ground-1",
            planeType = CalibrationPlaneType.GROUND_PLANE,
            method = CalibrationMethod.OPENCV_CHARUCO,
            reprojectionErrorPx = 0.85,
            calibratedAtEpochMs = 1_700_000_000_000L,
        )
        val rejectedHeight = CalibrationQualityGate.validate(TestType.HEIGHT, groundCal)
        assertTrue(rejectedHeight is CalibrationGateResult.Rejected)
        assertEquals(
            CalibrationPlaneType.VERTICAL_PLANE,
            (rejectedHeight as CalibrationGateResult.Rejected).requiredPlane,
        )

        val validBroadJump = CalibrationQualityGate.validate(TestType.STANDING_BROAD_JUMP, groundCal)
        assertTrue(validBroadJump is CalibrationGateResult.Valid)

        val noisyVerticalCal = CalibrationQuality(
            calibrationId = "cal-vert-noisy",
            planeType = CalibrationPlaneType.VERTICAL_PLANE,
            method = CalibrationMethod.OPENCV_ARUCO,
            reprojectionErrorPx = 3.5,
            calibratedAtEpochMs = 1_700_000_000_000L,
        )
        assertTrue(CalibrationQualityGate.validate(TestType.VERTICAL_JUMP, noisyVerticalCal) is CalibrationGateResult.Rejected)
    }
}
