package com.athletiq.core.domain

import com.athletiq.core.model.CalibrationMethod
import com.athletiq.core.model.CalibrationPlaneType
import com.athletiq.core.model.CalibrationQuality
import com.athletiq.core.model.MeasurementUnit
import com.athletiq.core.model.ScoringStandardSpec
import com.athletiq.core.model.TestType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DomainStageDTest {
    @Test
    fun `scoring evaluator returns NoStandardConfigured when no rubric matches`() {
        val outcome = ScoringEvaluator.evaluate(
            value = 45.0,
            testType = TestType.VERTICAL_JUMP,
            ageYears = 15,
            genderCategory = "FEMALE",
            discipline = null,
            rubrics = emptyList(),
        )
        assertTrue(outcome is ScoringEvaluationOutcome.NoStandardConfigured)
    }

    @Test
    fun `scoring evaluator matches highest specificity rubric including lower_is_better sprint`() {
        val genericRubric = ConfiguredScoringRubric(
            spec = ScoringStandardSpec(
                id = "std-generic",
                institutionId = "inst-1",
                name = "Generic Sprint",
                testType = TestType.SPRINT,
                ageMin = 12,
                ageMax = 18,
                genderCategory = null,
                unit = MeasurementUnit.SECONDS,
                sourceCitation = "Board Standard",
            ),
            direction = "lower_is_better",
            bands = listOf(
                ScoringBand("Fast", minValue = 0.0, maxValue = 4.5, points = 90.0, qualified = true),
                ScoringBand("Standard", minValue = 4.51, maxValue = 6.0, points = 60.0, qualified = false),
            ),
        )
        val femaleSpecific = ConfiguredScoringRubric(
            spec = genericRubric.spec.copy(id = "std-female", name = "Girls Sprint", genderCategory = "FEMALE"),
            direction = "lower_is_better",
            bands = listOf(
                ScoringBand("Elite", minValue = 0.0, maxValue = 4.8, points = 100.0, qualified = true),
            ),
        )

        val res = ScoringEvaluator.evaluate(
            value = 4.65,
            testType = TestType.SPRINT,
            ageYears = 15,
            genderCategory = "FEMALE",
            discipline = null,
            rubrics = listOf(genericRubric, femaleSpecific),
        )
        assertTrue(res is ScoringEvaluationOutcome.Evaluated)
        val evaluated = res as ScoringEvaluationOutcome.Evaluated
        assertEquals("std-female", evaluated.standardId)
        assertEquals("Elite", evaluated.categoryLabel)
        assertEquals(true, evaluated.qualified)
    }

    @Test
    fun `session coordinators enforce calibration plane gate before capture`() {
        val groundCal = CalibrationQuality(
            calibrationId = "cal-g",
            planeType = CalibrationPlaneType.GROUND_PLANE,
            method = CalibrationMethod.OPENCV_CHARUCO,
            reprojectionErrorPx = 0.5,
            calibratedAtEpochMs = 1_700_000_000_000L,
        )
        val vjCoord = VerticalJumpSessionCoordinator()
        vjCoord.prepare(groundCal)
        assertTrue(vjCoord.uiState.value is AssessmentUiState.CalibrationRequired)

        val sbjCoord = BroadJumpSessionCoordinator()
        sbjCoord.prepare(groundCal)
        assertTrue(sbjCoord.uiState.value is AssessmentUiState.Ready)
    }
}
