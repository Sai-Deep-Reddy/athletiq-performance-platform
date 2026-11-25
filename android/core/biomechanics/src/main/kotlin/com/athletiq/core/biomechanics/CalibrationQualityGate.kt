package com.athletiq.core.biomechanics

import com.athletiq.core.model.CalibrationPlaneType
import com.athletiq.core.model.CalibrationQuality
import com.athletiq.core.model.TestType

sealed interface CalibrationGateResult {
    data class Valid(val calibration: CalibrationQuality) : CalibrationGateResult
    data class Rejected(val requiredPlane: CalibrationPlaneType, val reason: String) : CalibrationGateResult
}

/**
 * Enforces strict separation between vertical-plane calibration (height, vertical jump)
 * and ground-plane calibration (standing broad jump, sprint), as well as reprojection error limits.
 */
object CalibrationQualityGate {
    const val DEFAULT_MAX_REPROJECTION_ERROR_PX = 2.0

    fun requiredPlaneFor(testType: TestType): CalibrationPlaneType = when (testType) {
        TestType.HEIGHT, TestType.VERTICAL_JUMP -> CalibrationPlaneType.VERTICAL_PLANE
        TestType.STANDING_BROAD_JUMP, TestType.SPRINT -> CalibrationPlaneType.GROUND_PLANE
    }

    fun validate(
        testType: TestType,
        calibration: CalibrationQuality?,
        maxAllowedReprojectionErrorPx: Double = DEFAULT_MAX_REPROJECTION_ERROR_PX,
    ): CalibrationGateResult {
        val expectedPlane = requiredPlaneFor(testType)
        if (calibration == null) {
            return CalibrationGateResult.Rejected(
                requiredPlane = expectedPlane,
                reason = "No active $expectedPlane calibration profile for $testType",
            )
        }
        if (calibration.planeType != expectedPlane) {
            return CalibrationGateResult.Rejected(
                requiredPlane = expectedPlane,
                reason = "Incompatible plane ${calibration.planeType} for $testType; $expectedPlane is required",
            )
        }
        if (calibration.reprojectionErrorPx > maxAllowedReprojectionErrorPx) {
            return CalibrationGateResult.Rejected(
                requiredPlane = expectedPlane,
                reason = "Calibration reprojection error ${calibration.reprojectionErrorPx}px exceeds limit ${maxAllowedReprojectionErrorPx}px",
            )
        }
        return CalibrationGateResult.Valid(calibration)
    }
}
