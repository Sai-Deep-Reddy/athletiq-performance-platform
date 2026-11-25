package com.athletiq.core.biomechanics

data class LandmarkPoint3D(
    val x: Double,
    val y: Double,
    val z: Double,
    val visibility: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Landmark coordinates must be finite" }
        require(visibility.isFinite() && visibility in 0.0..1.0) { "visibility must be in [0.0, 1.0]" }
    }
}

/**
 * Applies independent [OneEuroFilter] instances to each coordinate (x, y, z)
 * of a fixed-size landmark array (e.g. 33 MediaPipe Pose or 21 Hand landmarks).
 *
 * Defaults are tuned for MediaPipe normalized `[0..1]` landmark coordinates
 * (`beta = 3.0` scales cutoff during fast ballistic motion in normalized space).
 */
class LandmarkSmoother3D(
    private val landmarkCount: Int,
    minCutoffHz: Double = 1.0,
    beta: Double = 3.0,
    derivativeCutoffHz: Double = 1.0,
    nominalFps: Double = 30.0,
) {
    init {
        require(landmarkCount > 0) { "landmarkCount must be > 0" }
    }

    private val filtersX = Array(landmarkCount) {
        OneEuroFilter(minCutoffHz, beta, derivativeCutoffHz, nominalFps)
    }
    private val filtersY = Array(landmarkCount) {
        OneEuroFilter(minCutoffHz, beta, derivativeCutoffHz, nominalFps)
    }
    private val filtersZ = Array(landmarkCount) {
        OneEuroFilter(minCutoffHz, beta, derivativeCutoffHz, nominalFps)
    }

    fun smooth(landmarks: List<LandmarkPoint3D>, timestampNs: Long): List<LandmarkPoint3D> {
        require(landmarks.size == landmarkCount) {
            "Expected $landmarkCount landmarks but received ${landmarks.size}"
        }
        return landmarks.indices.map { index ->
            val point = landmarks[index]
            LandmarkPoint3D(
                x = filtersX[index].filter(point.x, timestampNs),
                y = filtersY[index].filter(point.y, timestampNs),
                z = filtersZ[index].filter(point.z, timestampNs),
                visibility = point.visibility,
            )
        }
    }

    fun reset() {
        for (i in 0 until landmarkCount) {
            filtersX[i].reset()
            filtersY[i].reset()
            filtersZ[i].reset()
        }
    }
}
