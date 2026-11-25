package com.athletiq.core.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class FrameTimingTrackerTest {
    @Test
    fun `reports steady 60fps capture with zero drops`() {
        val tracker = FrameTimingTracker(expectedFps = 60.0)
        assertNull(tracker.snapshot())

        val stepNs = 16_666_667L
        for (i in 0..10) {
            tracker.recordFrameTimestampNs(i * stepNs)
        }
        val snapshot = tracker.snapshot()!!
        assertEquals(0, snapshot.droppedFrames)
        assertFalse(snapshot.isVariableFrameRate)
        assertTrue(abs(snapshot.fpsActual - 60.0) < 0.05)
    }

    @Test
    fun `detects dropped frames and variable frame rate`() {
        val tracker = FrameTimingTracker(expectedFps = 60.0)
        val stepNs = 16_666_667L
        tracker.recordFrameTimestampNs(0L)
        tracker.recordFrameTimestampNs(stepNs)
        // Simulate 2 dropped frames (3x step = 50ms gap)
        tracker.recordFrameTimestampNs(stepNs * 4)
        tracker.recordFrameTimestampNs(stepNs * 5)

        val snapshot = tracker.snapshot()!!
        assertEquals(2, snapshot.droppedFrames)
        assertTrue(snapshot.isVariableFrameRate)
        assertTrue(snapshot.maxFrameDtMs >= 49.0)
    }

    @Test
    fun `rejects non monotonic timestamps`() {
        val tracker = FrameTimingTracker(expectedFps = 30.0)
        tracker.recordFrameTimestampNs(100_000_000L)
        assertThrows(IllegalArgumentException::class.java) {
            tracker.recordFrameTimestampNs(100_000_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tracker.recordFrameTimestampNs(90_000_000L)
        }
    }
}
