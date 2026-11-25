package com.athletiq.core.vision

import org.opencv.android.OpenCVLoader

sealed interface OpenCvInitState {
    data object Ready : OpenCvInitState
    data class Failed(val reason: String) : OpenCvInitState
}

/**
 * Thread-safe OpenCV 4.11 native library initializer using OpenCVLoader.initLocal().
 */
object OpenCvBootstrap {
    @Volatile
    private var initialized: Boolean = false

    @Synchronized
    fun ensureInitialized(): OpenCvInitState {
        if (initialized) return OpenCvInitState.Ready
        return if (OpenCVLoader.initLocal()) {
            initialized = true
            OpenCvInitState.Ready
        } else {
            OpenCvInitState.Failed("OpenCVLoader.initLocal() returned false")
        }
    }
}
