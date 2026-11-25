package com.athletiq.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class UserRole {
    ADMIN,
    INSTRUCTOR,
    COACH,
}

@Serializable
enum class TestType {
    HEIGHT,
    VERTICAL_JUMP,
    STANDING_BROAD_JUMP,
    SPRINT,
}

@Serializable
enum class CalibrationPlaneType {
    INTRINSICS,
    VERTICAL_PLANE,
    GROUND_PLANE,
}

@Serializable
enum class CalibrationMethod {
    DEVICE_INTRINSICS,
    OPENCV_CHARUCO,
    OPENCV_ARUCO,
}

@Serializable
enum class LandmarkProvenance {
    MEDIAPIPE_POSE_SEGMENTATION_REFINED,
    MEDIAPIPE_HAND_LANDMARKER_TIP,
    MEDIAPIPE_POSE_INDEX_FALLBACK,
    MEDIAPIPE_POSE_WRIST_FALLBACK,
    MEDIAPIPE_POSE_HEEL_AND_MASK,
}

@Serializable
enum class AttemptValidityStatus {
    VALID,
    FLAGGED,
    INVALID,
}

@Serializable
enum class ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
}

@Serializable
enum class MeasurementUnit {
    CENTIMETERS,
    SECONDS,
    METERS_PER_SECOND,
}

@Serializable
data class CalibrationQuality(
    val calibrationId: String,
    val planeType: CalibrationPlaneType,
    val method: CalibrationMethod,
    val reprojectionErrorPx: Double,
    val calibratedAtEpochMs: Long,
) {
    init {
        require(calibrationId.isNotBlank()) { "calibrationId must not be blank" }
        require(reprojectionErrorPx.isFinite() && reprojectionErrorPx >= 0.0) {
            "reprojectionErrorPx must be finite and >= 0"
        }
    }
}

@Serializable
data class FrameTimingMetadata(
    val fpsActual: Double,
    val medianFrameDtMs: Double,
    val maxFrameDtMs: Double,
    val droppedFrames: Int,
    val isVariableFrameRate: Boolean,
) {
    init {
        require(fpsActual.isFinite() && fpsActual > 0.0) { "fpsActual must be positive" }
        require(medianFrameDtMs.isFinite() && medianFrameDtMs > 0.0) { "medianFrameDtMs must be positive" }
        require(maxFrameDtMs.isFinite() && maxFrameDtMs >= medianFrameDtMs) {
            "maxFrameDtMs must be >= medianFrameDtMs"
        }
        require(droppedFrames >= 0) { "droppedFrames must be >= 0" }
    }
}

@Serializable
data class MeasurementProvenance(
    val calibration: CalibrationQuality,
    val timing: FrameTimingMetadata,
    val landmarkProvenance: LandmarkProvenance,
    val algorithmVersion: String,
    val recordedAtEpochMs: Long,
    val limitations: List<String> = emptyList(),
)

@Serializable
data class ConfidenceReport(
    val score: Double,
    val notes: List<String> = emptyList(),
) {
    init {
        require(score.isFinite() && score in 0.0..1.0) { "Confidence score must be in [0.0, 1.0]" }
    }
}

/**
 * Explicit outcome wrapper forbidding silent fallbacks when calibration,
 * landmarks, or camera timing cannot support a valid physical measurement.
 */
sealed interface MeasurementOutcome<out T> {
    data class Success<T>(
        val value: T,
        val unit: MeasurementUnit,
        val uncertainty: Double,
        val confidence: ConfidenceReport,
        val provenance: MeasurementProvenance,
        val requiresExaminerReview: Boolean = false,
    ) : MeasurementOutcome<T> {
        init {
            require(uncertainty.isFinite() && uncertainty >= 0.0) {
                "uncertainty must be finite and >= 0"
            }
        }
    }

    data class CalibrationRequired(
        val requiredPlane: CalibrationPlaneType,
        val reason: String,
    ) : MeasurementOutcome<Nothing>

    data class LowConfidence(
        val reason: String,
        val observedScore: Double,
        val minimumRequired: Double,
    ) : MeasurementOutcome<Nothing>

    data class InvalidAttempt(
        val reasons: List<String>,
        val requiresExaminerReview: Boolean = true,
    ) : MeasurementOutcome<Nothing> {
        init {
            require(reasons.isNotEmpty()) { "InvalidAttempt requires at least one reason" }
        }
    }

    data class InsufficientData(val reason: String) : MeasurementOutcome<Nothing>

    data class Unavailable(val reason: String) : MeasurementOutcome<Nothing>
}
