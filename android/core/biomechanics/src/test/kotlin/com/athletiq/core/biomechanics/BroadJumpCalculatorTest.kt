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

class BroadJumpCalculatorTest {
    private val imgWidth = 1920
    private val imgHeight = 1080

    // Ground plane (xCm forward from takeoff line=0, yCm lateral) -> image px
    private val groundToImage = PlanarHomography(
        doubleArrayOf(
            4.5, 0.2, 220.0,
            0.0, 3.2, 780.0,
            0.00015, 0.0002, 1.0,
        ),
    )

    private val groundHomography: PlanarHomography by lazy {
        val planePts = listOf(
            Point2D(-40.0, -50.0),
            Point2D(300.0, -50.0),
            Point2D(300.0, 50.0),
            Point2D(-40.0, 50.0),
        )
        val imgPts = planePts.map { groundToImage.imageToPlane(it) }
        PlanarHomography.solveFromCorrespondences(imgPts, planePts)
    }

    private val validGroundCal = CalibrationQuality(
        calibrationId = "ground-cal-sbj",
        planeType = CalibrationPlaneType.GROUND_PLANE,
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

    private fun buildBroadJumpSequence(
        startToeXCm: Double = -2.0,
        landingHeelXCm: Double = 185.0,
        rearShoeSoleXCm: Double? = null,
        postLandingHandTouchXCm: Double? = null,
        postLandingStepBackXCm: Double? = null,
    ): List<BroadJumpFrameSample> {
        val dtNs = 16_666_667L
        var tNs = 0L
        val samples = mutableListOf<BroadJumpFrameSample>()

        fun groundToNorm(xCm: Double, yCm: Double = 0.0, liftNormY: Double = 0.0): LandmarkPoint3D {
            val px = groundToImage.imageToPlane(Point2D(xCm, yCm))
            return LandmarkPoint3D(
                x = px.x / imgWidth,
                y = (px.y / imgHeight) - liftNormY,
                z = 0.0,
                visibility = 0.95,
            )
        }

        fun makeFrame(
            heelX: Double,
            toeX: Double,
            liftNormY: Double = 0.0,
            handTouchX: Double? = null,
            soleX: Double? = null,
        ): BroadJumpFrameSample {
            val lms = MutableList(PoseLandmarkIndex.LANDMARK_COUNT) {
                groundToNorm(heelX, 0.0, liftNormY + 0.25)
            }
            lms[PoseLandmarkIndex.LEFT_HIP] = groundToNorm(heelX, -10.0, liftNormY + 0.22)
            lms[PoseLandmarkIndex.RIGHT_HIP] = groundToNorm(heelX, 10.0, liftNormY + 0.22)
            lms[PoseLandmarkIndex.LEFT_HEEL] = groundToNorm(heelX, -10.0, liftNormY)
            lms[PoseLandmarkIndex.RIGHT_HEEL] = groundToNorm(heelX, 10.0, liftNormY)
            lms[PoseLandmarkIndex.LEFT_FOOT_INDEX] = groundToNorm(toeX, -10.0, liftNormY)
            lms[PoseLandmarkIndex.RIGHT_FOOT_INDEX] = groundToNorm(toeX, 10.0, liftNormY)

            // Wrists normally elevated well above floor unless handTouchX is simulated
            if (handTouchX != null) {
                lms[PoseLandmarkIndex.LEFT_WRIST] = groundToNorm(handTouchX, -10.0, 0.0)
                lms[PoseLandmarkIndex.RIGHT_WRIST] = groundToNorm(heelX + 10.0, 10.0, 0.15)
            } else {
                lms[PoseLandmarkIndex.LEFT_WRIST] = groundToNorm(heelX + 10.0, -10.0, 0.20)
                lms[PoseLandmarkIndex.RIGHT_WRIST] = groundToNorm(heelX + 10.0, 10.0, 0.20)
            }
            lms[PoseLandmarkIndex.LEFT_INDEX] = lms[PoseLandmarkIndex.LEFT_WRIST]
            lms[PoseLandmarkIndex.RIGHT_INDEX] = lms[PoseLandmarkIndex.RIGHT_WRIST]

            val maskCandidates = if (soleX != null) {
                val p = groundToNorm(soleX, 0.0, liftNormY)
                MaskGroundContactCandidates(rearShoeSolePointNormalized = Point2D(p.x, p.y))
            } else {
                null
            }
            return BroadJumpFrameSample(tNs, imgWidth, imgHeight, lms, maskCandidates)
        }

        // Stationary start behind takeoff line (10 frames)
        repeat(10) {
            samples.add(makeFrame(heelX = startToeXCm - 24.0, toeX = startToeXCm))
            tNs += dtNs
        }
        // Airborne flight (15 frames)
        for (i in 1..15) {
            val frac = i / 15.0
            val x = (startToeXCm - 24.0) + (landingHeelXCm - (startToeXCm - 24.0)) * frac
            val lift = 0.10 * kotlin.math.sin(frac * kotlin.math.PI)
            samples.add(makeFrame(heelX = x, toeX = x + 22.0, liftNormY = lift.coerceAtLeast(0.03)))
            tNs += dtNs
        }
        // Initial landing & 20 post-landing frames
        for (i in 0 until 20) {
            val currentHeelX = if (postLandingStepBackXCm != null && i >= 6) postLandingStepBackXCm else landingHeelXCm
            val currentHandX = if (postLandingHandTouchXCm != null && i in 2..5) postLandingHandTouchXCm else null
            samples.add(
                makeFrame(
                    heelX = currentHeelX,
                    toeX = currentHeelX + 24.0,
                    handTouchX = currentHandX,
                    soleX = rearShoeSoleXCm,
                ),
            )
            tNs += dtNs
        }
        return samples
    }

    @Test
    fun `measures clean heel landing accurately and confirms stability`() {
        val calc = BroadJumpCalculator()
        val samples = buildBroadJumpSequence(landingHeelXCm = 185.0)
        val outcome = calc.calculate(samples, validGroundCal, groundHomography, timing60, 1_700_000_000_000L)

        assertTrue(outcome is MeasurementOutcome.Success)
        val success = outcome as MeasurementOutcome.Success
        assertEquals(MeasurementUnit.CENTIMETERS, success.unit)
        assertEquals(LandmarkProvenance.MEDIAPIPE_POSE_HEEL_AND_MASK, success.provenance.landmarkProvenance)
        assertEquals(LandingContactKind.HEEL, success.value.landingContactKind)
        assertEquals(LandingStabilityStatus.STABLE, success.value.stabilityStatus)
        assertTrue(abs(success.value.distanceCm - 185.0) < 0.5, "Got ${success.value.distanceCm}")
        assertFalse(success.requiresExaminerReview)
    }

    @Test
    fun `uses rear shoe sole boundary when behind heel landmark`() {
        val calc = BroadJumpCalculator()
        val samples = buildBroadJumpSequence(landingHeelXCm = 185.0, rearShoeSoleXCm = 181.5)
        val outcome = calc.calculate(samples, validGroundCal, groundHomography, timing60, 1_700_000_000_000L)

        assertTrue(outcome is MeasurementOutcome.Success)
        val success = outcome as MeasurementOutcome.Success
        assertEquals(LandingContactKind.SHOE_SOLE_REAR, success.value.landingContactKind)
        assertTrue(abs(success.value.distanceCm - 181.5) < 0.5)
    }

    @Test
    fun `detects backward hand touch and post landing backward step for review`() {
        val calc = BroadJumpCalculator()

        // Hand touch behind heels under NEAREST_BODY_CONTACT protocol
        val handSamples = buildBroadJumpSequence(landingHeelXCm = 190.0, postLandingHandTouchXCm = 152.0)
        val handOutcome = calc.calculate(handSamples, validGroundCal, groundHomography, timing60, 1_700_000_000_000L)
        assertTrue(handOutcome is MeasurementOutcome.Success)
        val handSuccess = handOutcome as MeasurementOutcome.Success
        assertEquals(LandingContactKind.HAND_TOUCH, handSuccess.value.landingContactKind)
        assertEquals(LandingStabilityStatus.HAND_OR_BODY_TOUCH, handSuccess.value.stabilityStatus)
        assertTrue(abs(handSuccess.value.distanceCm - 152.0) < 0.5)
        assertTrue(handSuccess.requiresExaminerReview)

        // Post-landing backward step
        val stepBackSamples = buildBroadJumpSequence(landingHeelXCm = 185.0, postLandingStepBackXCm = 166.0)
        val stepOutcome = calc.calculate(stepBackSamples, validGroundCal, groundHomography, timing60, 1_700_000_000_000L)
        assertTrue(stepOutcome is MeasurementOutcome.Success)
        val stepSuccess = stepOutcome as MeasurementOutcome.Success
        assertEquals(LandingContactKind.BACKWARD_STEP, stepSuccess.value.landingContactKind)
        assertEquals(LandingStabilityStatus.BACKWARD_STEP_OR_FALL, stepSuccess.value.stabilityStatus)
        assertTrue(abs(stepSuccess.value.distanceCm - 166.0) < 0.5)
        assertTrue(stepSuccess.requiresExaminerReview)
    }

    @Test
    fun `rejects pre jump foot fault and incompatible vertical plane calibration`() {
        val calc = BroadJumpCalculator()
        val faultSamples = buildBroadJumpSequence(startToeXCm = 5.5)
        val faultOutcome = calc.calculate(faultSamples, validGroundCal, groundHomography, timing60, 1_700_000_000_000L)
        assertTrue(faultOutcome is MeasurementOutcome.InvalidAttempt)

        val vertCal = validGroundCal.copy(planeType = CalibrationPlaneType.VERTICAL_PLANE)
        val calOutcome = calc.calculate(
            buildBroadJumpSequence(),
            vertCal,
            groundHomography,
            timing60,
            1_700_000_000_000L,
        )
        assertTrue(calOutcome is MeasurementOutcome.CalibrationRequired)
    }
}
