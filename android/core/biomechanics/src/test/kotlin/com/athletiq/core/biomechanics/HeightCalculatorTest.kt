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

class HeightCalculatorTest {
    private val imgWidth = 1080
    private val imgHeight = 1920

    // Known plane-to-image transform for vertical wall plane (x cm, y cm upward from floor=0) -> image pixels
    private val planeToImage = PlanarHomography(
        doubleArrayOf(
            5.0, 0.15, 240.0,
            0.0, -7.5, 1750.0,
            0.0001, 0.0002, 1.0,
        ),
    )

    private val verticalHomography: PlanarHomography by lazy {
        val planePts = listOf(
            Point2D(0.0, 0.0),
            Point2D(120.0, 0.0),
            Point2D(120.0, 200.0),
            Point2D(0.0, 200.0),
        )
        val imgPts = planePts.map { planeToImage.imageToPlane(it) }
        PlanarHomography.solveFromCorrespondences(imgPts, planePts)
    }

    private val validVerticalCal = CalibrationQuality(
        calibrationId = "vert-cal-1",
        planeType = CalibrationPlaneType.VERTICAL_PLANE,
        method = CalibrationMethod.OPENCV_CHARUCO,
        reprojectionErrorPx = 0.4,
        calibratedAtEpochMs = 1_700_000_000_000L,
    )

    private val steadyTiming = FrameTimingMetadata(
        fpsActual = 30.0,
        medianFrameDtMs = 33.33,
        maxFrameDtMs = 33.4,
        droppedFrames = 0,
        isVariableFrameRate = false,
    )

    private fun buildStandingSample(
        timestampNs: Long,
        heightCm: Double,
        kneeBendOffsetNorm: Double = 0.0,
        leftHeelLiftCm: Double = 0.0,
        possibleHairOrHeadwear: Boolean = false,
    ): HeightFrameSample {
        fun planeToNorm(xCm: Double, yCm: Double): LandmarkPoint3D {
            val px = planeToImage.imageToPlane(Point2D(xCm, yCm))
            return LandmarkPoint3D(
                x = px.x / imgWidth,
                y = px.y / imgHeight,
                z = 0.0,
                visibility = 0.95,
            )
        }

        val lms = MutableList(PoseLandmarkIndex.LANDMARK_COUNT) { planeToNorm(50.0, heightCm * 0.5) }
        lms[PoseLandmarkIndex.NOSE] = planeToNorm(50.0, heightCm - 12.0)
        lms[PoseLandmarkIndex.LEFT_SHOULDER] = planeToNorm(40.0, heightCm - 28.0)
        lms[PoseLandmarkIndex.RIGHT_SHOULDER] = planeToNorm(60.0, heightCm - 28.0)
        lms[PoseLandmarkIndex.LEFT_HIP] = planeToNorm(43.0, heightCm * 0.53)
        lms[PoseLandmarkIndex.RIGHT_HIP] = planeToNorm(57.0, heightCm * 0.53)
        val leftKnee = planeToNorm(43.0, heightCm * 0.28)
        val rightKnee = planeToNorm(57.0, heightCm * 0.28)
        lms[PoseLandmarkIndex.LEFT_KNEE] = leftKnee.copy(x = leftKnee.x + kneeBendOffsetNorm)
        lms[PoseLandmarkIndex.RIGHT_KNEE] = rightKnee.copy(x = rightKnee.x + kneeBendOffsetNorm)
        lms[PoseLandmarkIndex.LEFT_ANKLE] = planeToNorm(43.0, 7.0 + leftHeelLiftCm)
        lms[PoseLandmarkIndex.RIGHT_ANKLE] = planeToNorm(57.0, 7.0)
        lms[PoseLandmarkIndex.LEFT_HEEL] = planeToNorm(43.0, leftHeelLiftCm)
        lms[PoseLandmarkIndex.RIGHT_HEEL] = planeToNorm(57.0, 0.0)
        lms[PoseLandmarkIndex.LEFT_FOOT_INDEX] = planeToNorm(43.0, 0.0)
        lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX] = planeToNorm(57.0, 0.0)

        val crownPx = planeToImage.imageToPlane(Point2D(50.0, heightCm))
        val crown = RefinedHeadCrown(
            crownXNormalized = crownPx.x / imgWidth,
            crownYNormalized = crownPx.y / imgHeight,
            boundaryConfidence = 0.90,
            maskVsEdgeDiscrepancyPx = 1.5,
            possibleHairOrHeadwear = possibleHairOrHeadwear,
        )
        return HeightFrameSample(timestampNs, imgWidth, imgHeight, lms, crown)
    }

    @Test
    fun `measures calibrated standing height accurately across stable frames`() {
        val calc = HeightCalculator()
        val samples = (0 until 30).map { idx ->
            buildStandingSample(timestampNs = idx * 33_333_333L, heightCm = 172.5)
        }
        val outcome = calc.calculate(samples, validVerticalCal, verticalHomography, steadyTiming, 1_700_000_000_000L)
        assertTrue(outcome is MeasurementOutcome.Success)
        val success = outcome as MeasurementOutcome.Success
        assertEquals(MeasurementUnit.CENTIMETERS, success.unit)
        assertEquals(LandmarkProvenance.MEDIAPIPE_POSE_SEGMENTATION_REFINED, success.provenance.landmarkProvenance)
        assertTrue(abs(success.value.heightCm - 172.5) < 0.25, "Got ${success.value.heightCm}")
        assertFalse(success.requiresExaminerReview)
    }

    @Test
    fun `rejects ground plane calibration for height measurement`() {
        val calc = HeightCalculator()
        val groundCal = validVerticalCal.copy(planeType = CalibrationPlaneType.GROUND_PLANE)
        val samples = (0 until 30).map { idx -> buildStandingSample(idx * 33_333_333L, 170.0) }
        val outcome = calc.calculate(samples, groundCal, verticalHomography, steadyTiming, 1_700_000_000_000L)
        assertTrue(outcome is MeasurementOutcome.CalibrationRequired)
        assertEquals(
            CalibrationPlaneType.VERTICAL_PLANE,
            (outcome as MeasurementOutcome.CalibrationRequired).requiredPlane,
        )
    }

    @Test
    fun `rejects bent knees and unlevel heels as invalid attempt`() {
        val calc = HeightCalculator()
        val bentSamples = (0 until 30).map { idx ->
            buildStandingSample(idx * 33_333_333L, 170.0, kneeBendOffsetNorm = 0.12)
        }
        assertTrue(
            calc.calculate(bentSamples, validVerticalCal, verticalHomography, steadyTiming, 1_700_000_000_000L)
                is MeasurementOutcome.InvalidAttempt,
        )

        val liftedHeelSamples = (0 until 30).map { idx ->
            buildStandingSample(idx * 33_333_333L, 170.0, leftHeelLiftCm = 6.0)
        }
        assertTrue(
            calc.calculate(liftedHeelSamples, validVerticalCal, verticalHomography, steadyTiming, 1_700_000_000_000L)
                is MeasurementOutcome.InvalidAttempt,
        )
    }

    @Test
    fun `flags headwear or subject movement for examiner review`() {
        val calc = HeightCalculator()
        val headwearSamples = (0 until 30).map { idx ->
            buildStandingSample(idx * 33_333_333L, 175.0, possibleHairOrHeadwear = true)
        }
        val headwearOutcome = calc.calculate(
            headwearSamples,
            validVerticalCal,
            verticalHomography,
            steadyTiming,
            1_700_000_000_000L,
        )
        assertTrue(headwearOutcome is MeasurementOutcome.Success)
        assertTrue((headwearOutcome as MeasurementOutcome.Success).requiresExaminerReview)
        assertTrue(headwearOutcome.uncertainty >= 1.5)

        val movingSamples = (0 until 30).map { idx ->
            buildStandingSample(idx * 33_333_333L, 170.0 + idx * 0.6)
        }
        assertTrue(
            calc.calculate(movingSamples, validVerticalCal, verticalHomography, steadyTiming, 1_700_000_000_000L)
                is MeasurementOutcome.InvalidAttempt,
        )
    }
}
