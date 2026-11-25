package com.athletiq.core.domain

import com.athletiq.core.biomechanics.BroadJumpCalculator
import com.athletiq.core.biomechanics.BroadJumpFrameSample
import com.athletiq.core.biomechanics.BroadJumpMeasurement
import com.athletiq.core.biomechanics.BroadJumpProtocol
import com.athletiq.core.biomechanics.CalibrationGateResult
import com.athletiq.core.biomechanics.CalibrationQualityGate
import com.athletiq.core.biomechanics.HeightCalculator
import com.athletiq.core.biomechanics.HeightFrameSample
import com.athletiq.core.biomechanics.HeightMeasurement
import com.athletiq.core.biomechanics.PlanarHomography
import com.athletiq.core.biomechanics.SprintFrameSample
import com.athletiq.core.biomechanics.SprintMeasurement
import com.athletiq.core.biomechanics.SprintTimerCalculator
import com.athletiq.core.biomechanics.VerticalJumpCalculator
import com.athletiq.core.biomechanics.VerticalJumpFrameSample
import com.athletiq.core.biomechanics.VerticalJumpMeasurement
import com.athletiq.core.biomechanics.VerticalJumpProtocol
import com.athletiq.core.model.CalibrationPlaneType
import com.athletiq.core.model.CalibrationQuality
import com.athletiq.core.model.FrameTimingMetadata
import com.athletiq.core.model.MeasurementOutcome
import com.athletiq.core.model.TestType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI-agnostic state contract for any Jetpack Compose screen driving AthletiQ assessments.
 */
sealed interface AssessmentUiState<out T> {
    data class Idle(val testType: TestType) : AssessmentUiState<Nothing>
    data class CalibrationRequired(val requiredPlane: CalibrationPlaneType, val reason: String) : AssessmentUiState<Nothing>
    data class Ready(val calibration: CalibrationQuality) : AssessmentUiState<Nothing>
    data class Capturing(val framesCollected: Int) : AssessmentUiState<Nothing>
    data object Processing : AssessmentUiState<Nothing>
    data class Completed<T>(val outcome: MeasurementOutcome<T>) : AssessmentUiState<T>
}

class HeightSessionCoordinator(
    private val calculator: HeightCalculator = HeightCalculator(),
) {
    private val _uiState = MutableStateFlow<AssessmentUiState<HeightMeasurement>>(AssessmentUiState.Idle(TestType.HEIGHT))
    val uiState: StateFlow<AssessmentUiState<HeightMeasurement>> = _uiState.asStateFlow()

    fun prepare(calibration: CalibrationQuality?) {
        when (val gate = CalibrationQualityGate.validate(TestType.HEIGHT, calibration)) {
            is CalibrationGateResult.Valid -> _uiState.value = AssessmentUiState.Ready(gate.calibration)
            is CalibrationGateResult.Rejected -> _uiState.value = AssessmentUiState.CalibrationRequired(gate.requiredPlane, gate.reason)
        }
    }

    fun evaluateAttempt(
        samples: List<HeightFrameSample>,
        calibration: CalibrationQuality?,
        verticalHomography: PlanarHomography?,
        timing: FrameTimingMetadata?,
        recordedAtEpochMs: Long,
    ): MeasurementOutcome<HeightMeasurement> {
        _uiState.value = AssessmentUiState.Processing
        val outcome = calculator.calculate(samples, calibration, verticalHomography, timing, recordedAtEpochMs)
        _uiState.value = AssessmentUiState.Completed(outcome)
        return outcome
    }
}

class VerticalJumpSessionCoordinator(
    private val calculator: VerticalJumpCalculator = VerticalJumpCalculator(),
) {
    private val _uiState = MutableStateFlow<AssessmentUiState<VerticalJumpMeasurement>>(AssessmentUiState.Idle(TestType.VERTICAL_JUMP))
    val uiState: StateFlow<AssessmentUiState<VerticalJumpMeasurement>> = _uiState.asStateFlow()

    fun prepare(calibration: CalibrationQuality?) {
        when (val gate = CalibrationQualityGate.validate(TestType.VERTICAL_JUMP, calibration)) {
            is CalibrationGateResult.Valid -> _uiState.value = AssessmentUiState.Ready(gate.calibration)
            is CalibrationGateResult.Rejected -> _uiState.value = AssessmentUiState.CalibrationRequired(gate.requiredPlane, gate.reason)
        }
    }

    fun evaluateAttempt(
        samples: List<VerticalJumpFrameSample>,
        calibration: CalibrationQuality?,
        verticalHomography: PlanarHomography?,
        timing: FrameTimingMetadata?,
        recordedAtEpochMs: Long,
        protocol: VerticalJumpProtocol = VerticalJumpProtocol.STANDING_REACH_DELTA,
    ): MeasurementOutcome<VerticalJumpMeasurement> {
        _uiState.value = AssessmentUiState.Processing
        val outcome = calculator.calculate(samples, calibration, verticalHomography, timing, recordedAtEpochMs, protocol)
        _uiState.value = AssessmentUiState.Completed(outcome)
        return outcome
    }
}

class BroadJumpSessionCoordinator(
    private val calculator: BroadJumpCalculator = BroadJumpCalculator(),
) {
    private val _uiState = MutableStateFlow<AssessmentUiState<BroadJumpMeasurement>>(AssessmentUiState.Idle(TestType.STANDING_BROAD_JUMP))
    val uiState: StateFlow<AssessmentUiState<BroadJumpMeasurement>> = _uiState.asStateFlow()

    fun prepare(calibration: CalibrationQuality?) {
        when (val gate = CalibrationQualityGate.validate(TestType.STANDING_BROAD_JUMP, calibration)) {
            is CalibrationGateResult.Valid -> _uiState.value = AssessmentUiState.Ready(gate.calibration)
            is CalibrationGateResult.Rejected -> _uiState.value = AssessmentUiState.CalibrationRequired(gate.requiredPlane, gate.reason)
        }
    }

    fun evaluateAttempt(
        samples: List<BroadJumpFrameSample>,
        calibration: CalibrationQuality?,
        groundHomography: PlanarHomography?,
        timing: FrameTimingMetadata?,
        recordedAtEpochMs: Long,
        protocol: BroadJumpProtocol = BroadJumpProtocol.NEAREST_BODY_CONTACT,
    ): MeasurementOutcome<BroadJumpMeasurement> {
        _uiState.value = AssessmentUiState.Processing
        val outcome = calculator.calculate(samples, calibration, groundHomography, timing, recordedAtEpochMs, protocol)
        _uiState.value = AssessmentUiState.Completed(outcome)
        return outcome
    }
}

class SprintSessionCoordinator(
    private val calculator: SprintTimerCalculator = SprintTimerCalculator(),
) {
    private val _uiState = MutableStateFlow<AssessmentUiState<SprintMeasurement>>(AssessmentUiState.Idle(TestType.SPRINT))
    val uiState: StateFlow<AssessmentUiState<SprintMeasurement>> = _uiState.asStateFlow()

    fun prepare(calibration: CalibrationQuality?) {
        when (val gate = CalibrationQualityGate.validate(TestType.SPRINT, calibration)) {
            is CalibrationGateResult.Valid -> _uiState.value = AssessmentUiState.Ready(gate.calibration)
            is CalibrationGateResult.Rejected -> _uiState.value = AssessmentUiState.CalibrationRequired(gate.requiredPlane, gate.reason)
        }
    }

    fun evaluateAttempt(
        samples: List<SprintFrameSample>,
        targetDistanceMeters: Double,
        calibration: CalibrationQuality?,
        groundHomography: PlanarHomography?,
        timing: FrameTimingMetadata?,
        recordedAtEpochMs: Long,
    ): MeasurementOutcome<SprintMeasurement> {
        _uiState.value = AssessmentUiState.Processing
        val outcome = calculator.calculate(samples, targetDistanceMeters, calibration, groundHomography, timing, recordedAtEpochMs)
        _uiState.value = AssessmentUiState.Completed(outcome)
        return outcome
    }
}
