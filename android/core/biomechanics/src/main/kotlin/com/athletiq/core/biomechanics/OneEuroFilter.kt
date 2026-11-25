package com.athletiq.core.biomechanics

import kotlin.math.PI
import kotlin.math.abs

private class LowPassFilter(initialAlpha: Double) {
    private var alpha: Double = initialAlpha.coerceIn(0.0, 1.0)
    private var state: Double = 0.0
    private var initialized: Boolean = false

    fun filter(value: Double, nextAlpha: Double): Double {
        alpha = nextAlpha.coerceIn(0.0, 1.0)
        val output = if (initialized) {
            alpha * value + (1.0 - alpha) * state
        } else {
            value
        }
        state = output
        initialized = true
        return output
    }

    fun isInitialized(): Boolean = initialized

    fun lastOutput(): Double = state

    fun reset() {
        state = 0.0
        initialized = false
    }
}

/**
 * Adaptive 1-Euro low-pass filter (Casiez et al., CHI 2012) for real-time landmark smoothing.
 *
 * Uses monotonic timestamps in nanoseconds to handle variable frame rates and dropped frames
 * without introducing artificial phase lag during explosive jump take-off and landing.
 */
class OneEuroFilter(
    private val minCutoffHz: Double = 1.0,
    private val beta: Double = 0.007,
    private val derivativeCutoffHz: Double = 1.0,
    private val nominalFps: Double = 30.0,
) {
    init {
        require(minCutoffHz.isFinite() && minCutoffHz > 0.0) { "minCutoffHz must be > 0" }
        require(beta.isFinite() && beta >= 0.0) { "beta must be >= 0" }
        require(derivativeCutoffHz.isFinite() && derivativeCutoffHz > 0.0) { "derivativeCutoffHz must be > 0" }
        require(nominalFps.isFinite() && nominalFps > 0.0) { "nominalFps must be > 0" }
    }

    private val valueFilter = LowPassFilter(alpha(minCutoffHz, nominalFps))
    private val derivativeFilter = LowPassFilter(alpha(derivativeCutoffHz, nominalFps))
    private var lastTimestampNs: Long = Long.MIN_VALUE

    fun filter(value: Double, timestampNs: Long): Double {
        require(value.isFinite()) { "Filtered value must be finite (was $value)" }

        if (!valueFilter.isInitialized()) {
            lastTimestampNs = timestampNs
            derivativeFilter.filter(0.0, alpha(derivativeCutoffHz, nominalFps))
            return valueFilter.filter(value, alpha(minCutoffHz, nominalFps))
        }

        require(timestampNs > lastTimestampNs) {
            "Timestamps must be strictly increasing (prev=$lastTimestampNs, current=$timestampNs)"
        }

        val dtSeconds = (timestampNs - lastTimestampNs) / 1_000_000_000.0
        val currentFreqHz = 1.0 / dtSeconds
        lastTimestampNs = timestampNs

        val rawDerivative = (value - valueFilter.lastOutput()) * currentFreqHz
        val filteredDerivative = derivativeFilter.filter(
            rawDerivative,
            alpha(derivativeCutoffHz, currentFreqHz),
        )
        val adaptiveCutoffHz = minCutoffHz + beta * abs(filteredDerivative)
        return valueFilter.filter(value, alpha(adaptiveCutoffHz, currentFreqHz))
    }

    fun reset() {
        valueFilter.reset()
        derivativeFilter.reset()
        lastTimestampNs = Long.MIN_VALUE
    }

    private fun alpha(cutoffHz: Double, freqHz: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoffHz)
        val te = 1.0 / freqHz
        return 1.0 / (1.0 + tau / te)
    }
}
