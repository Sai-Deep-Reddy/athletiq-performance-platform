package com.athletiq.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MeasurementModelsTest {
    @Test
    fun `confidence report rejects out of range or non-finite scores`() {
        assertThrows(IllegalArgumentException::class.java) { ConfidenceReport(-0.01) }
        assertThrows(IllegalArgumentException::class.java) { ConfidenceReport(1.01) }
        assertThrows(IllegalArgumentException::class.java) { ConfidenceReport(Double.NaN) }

        val valid = ConfidenceReport(0.92, listOf("Stable bilateral stance"))
        assertEquals(0.92, valid.score)
    }

    @Test
    fun `frame timing metadata validates fps and delta ordering`() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameTimingMetadata(
                fpsActual = 0.0,
                medianFrameDtMs = 16.6,
                maxFrameDtMs = 16.6,
                droppedFrames = 0,
                isVariableFrameRate = false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameTimingMetadata(
                fpsActual = 60.0,
                medianFrameDtMs = 20.0,
                maxFrameDtMs = 16.0,
                droppedFrames = 0,
                isVariableFrameRate = true,
            )
        }
    }
}
