package com.athletiq.core.camera

import com.athletiq.core.model.FrameTimingMetadata
import kotlin.math.roundToInt

/**
 * Tracks monotonic frame timestamps (nanoseconds) from CameraX [androidx.camera.core.ImageProxy]
 * or decoded video PTS timestamps to quantify actual FPS, variable-frame-rate jitter,
 * and dropped-frame count without assuming a constant frame interval.
 */
class FrameTimingTracker(
    private val expectedFps: Double,
    private val vfrJitterRatioThreshold: Double = 0.25,
) {
    init {
        require(expectedFps.isFinite() && expectedFps > 0.0) { "expectedFps must be > 0" }
        require(vfrJitterRatioThreshold.isFinite() && vfrJitterRatioThreshold > 0.0) {
            "vfrJitterRatioThreshold must be > 0"
        }
    }

    private val expectedDtMs: Double = 1000.0 / expectedFps
    private val frameDeltasMs = mutableListOf<Double>()
    private var lastTimestampNs: Long = Long.MIN_VALUE
    private var droppedFramesCount: Int = 0

    @Synchronized
    fun recordFrameTimestampNs(timestampNs: Long) {
        if (lastTimestampNs != Long.MIN_VALUE) {
            require(timestampNs > lastTimestampNs) {
                "Frame timestamps must be strictly increasing (prev=$lastTimestampNs, current=$timestampNs)"
            }
            val dtMs = (timestampNs - lastTimestampNs) / 1_000_000.0
            frameDeltasMs.add(dtMs)

            val nominalMultiple = (dtMs / expectedDtMs).roundToInt()
            if (nominalMultiple > 1 && dtMs > expectedDtMs * 1.5) {
                droppedFramesCount += (nominalMultiple - 1)
            }
        }
        lastTimestampNs = timestampNs
    }

    @Synchronized
    fun snapshot(): FrameTimingMetadata? {
        if (frameDeltasMs.isEmpty()) return null
        val sorted = frameDeltasMs.sorted()
        val medianDtMs = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        val maxDtMs = sorted.last()
        val avgDtMs = frameDeltasMs.average()
        val fpsActual = 1000.0 / avgDtMs
        val isVfr = droppedFramesCount > 0 ||
            (maxDtMs - medianDtMs) / medianDtMs > vfrJitterRatioThreshold

        return FrameTimingMetadata(
            fpsActual = fpsActual,
            medianFrameDtMs = medianDtMs,
            maxFrameDtMs = maxDtMs,
            droppedFrames = droppedFramesCount,
            isVariableFrameRate = isVfr,
        )
    }

    @Synchronized
    fun reset() {
        frameDeltasMs.clear()
        lastTimestampNs = Long.MIN_VALUE
        droppedFramesCount = 0
    }
}
