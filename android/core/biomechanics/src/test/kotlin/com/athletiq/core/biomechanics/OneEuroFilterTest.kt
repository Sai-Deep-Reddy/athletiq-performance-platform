package com.athletiq.core.biomechanics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sin

class OneEuroFilterTest {
    @Test
    fun `reduces high frequency jitter during stationary posture`() {
        val filter = OneEuroFilter(minCutoffHz = 0.8, beta = 0.005, nominalFps = 60.0)
        val dtNs = 16_666_667L
        val baseline = 1.50
        var maxFilteredError = 0.0

        for (i in 0 until 120) {
            val tNs = i * dtNs
            val jitter = 0.02 * sin(i * 1.9)
            val filtered = filter.filter(baseline + jitter, tNs)
            if (i >= 30) {
                maxFilteredError = maxOf(maxFilteredError, abs(filtered - baseline))
            }
        }
        assertTrue(maxFilteredError < 0.01, "Expected jitter < 0.01, got $maxFilteredError")
    }

    @Test
    fun `tracks rapid vertical movement with minimal lag under variable frame rate`() {
        // Normalized [0..1] coordinate scale requires higher beta (~3.0) so cutoff rises during fast motion.
        val filter = OneEuroFilter(minCutoffHz = 1.0, beta = 3.0, nominalFps = 60.0)
        var tNs = 0L
        // Stationary baseline
        for (i in 0 until 20) {
            filter.filter(0.0, tNs)
            tNs += 16_666_667L
        }
        // Rapid upward jump motion with a simulated dropped frame (33.3ms step)
        var lastOutput = 0.0
        val target = 0.55
        for (step in 1..15) {
            tNs += if (step == 5) 33_333_333L else 16_666_667L
            val value = target * (step / 15.0)
            lastOutput = filter.filter(value, tNs)
        }
        assertTrue(abs(lastOutput - target) < 0.08, "Expected fast tracking close to $target, got $lastOutput")
    }

    @Test
    fun `rejects non finite values and non monotonic timestamps`() {
        val filter = OneEuroFilter()
        filter.filter(1.0, 1_000_000L)

        assertThrows(IllegalArgumentException::class.java) {
            filter.filter(Double.NaN, 2_000_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            filter.filter(Double.POSITIVE_INFINITY, 2_000_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            filter.filter(1.0, 1_000_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            filter.filter(1.0, 500_000L)
        }
    }

    @Test
    fun `landmark smoother filters 33 landmarks independently and preserves count`() {
        val smoother = LandmarkSmoother3D(landmarkCount = 33, nominalFps = 30.0)
        val input = List(33) { idx ->
            LandmarkPoint3D(idx * 0.01, idx * 0.02, 0.0, 0.95)
        }
        val first = smoother.smooth(input, 10_000_000L)
        assertEquals(33, first.size)
        assertEquals(input[0], first[0])

        assertThrows(IllegalArgumentException::class.java) {
            smoother.smooth(input.take(17), 43_333_333L)
        }
    }
}
