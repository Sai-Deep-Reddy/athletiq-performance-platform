package com.athletiq.core.vision

import android.content.Context
import android.graphics.Bitmap
import com.athletiq.core.biomechanics.LandmarkPoint3D
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.Closeable

data class PoseFrameObservation(
    val timestampMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val landmarks: List<LandmarkPoint3D>,
    val worldLandmarks: List<LandmarkPoint3D>,
    val segmentationMaskWidth: Int?,
    val segmentationMaskHeight: Int?,
    val segmentationMaskConfidence: FloatArray?,
    val activeDelegate: Delegate,
)

sealed interface PoseDetectionResult {
    data class Detected(val observation: PoseFrameObservation) : PoseDetectionResult
    data class NoPerson(val timestampMs: Long) : PoseDetectionResult
    data class Error(val message: String) : PoseDetectionResult
}

/**
 * MediaPipe PoseLandmarker wrapper supporting both LIVE_STREAM (live framing guidance)
 * and VIDEO (deterministic frame-by-frame post-processing of 120/240fps clips) with
 * segmentation masks enabled for Stage C1 head-region refinement and C3 landing contact checks.
 */
class MediaPipePoseEngine(
    private val context: Context,
    private val modelAssetPath: String = "pose_landmarker_full.task",
    private val runningMode: RunningMode = RunningMode.LIVE_STREAM,
    private val minPoseDetectionConfidence: Float = 0.5f,
    private val minPosePresenceConfidence: Float = 0.5f,
    private val minTrackingConfidence: Float = 0.5f,
    private val onLiveResult: ((PoseDetectionResult) -> Unit)? = null,
) : Closeable {

    private var poseLandmarker: PoseLandmarker? = null
    var activeDelegate: Delegate = Delegate.CPU
        private set

    fun initialize(preferGpu: Boolean = true) {
        close()
        if (preferGpu) {
            try {
                poseLandmarker = createLandmarker(Delegate.GPU)
                activeDelegate = Delegate.GPU
                return
            } catch (_: Throwable) {
                // Explicit CPU fallback when device GPU delegate is unsupported.
            }
        }
        poseLandmarker = createLandmarker(Delegate.CPU)
        activeDelegate = Delegate.CPU
    }

    private fun createLandmarker(delegate: Delegate): PoseLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelAssetPath)
            .setDelegate(delegate)
            .build()

        val builder = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(runningMode)
            .setNumPoses(1)
            .setOutputSegmentationMasks(true)
            .setMinPoseDetectionConfidence(minPoseDetectionConfidence)
            .setMinPosePresenceConfidence(minPosePresenceConfidence)
            .setMinTrackingConfidence(minTrackingConfidence)

        if (runningMode == RunningMode.LIVE_STREAM) {
            builder.setResultListener { result, inputImage ->
                onLiveResult?.invoke(
                    convertResult(
                        result = result,
                        timestampMs = result.timestampMs(),
                        width = inputImage.width,
                        height = inputImage.height,
                    ),
                )
            }
            builder.setErrorListener { error ->
                onLiveResult?.invoke(PoseDetectionResult.Error(error.message ?: "MediaPipe pose error"))
            }
        }

        return PoseLandmarker.createFromOptions(context, builder.build())
    }

    fun detectAsync(bitmap: Bitmap, timestampMs: Long) {
        require(runningMode == RunningMode.LIVE_STREAM) { "detectAsync requires RunningMode.LIVE_STREAM" }
        val landmarker = poseLandmarker ?: run {
            onLiveResult?.invoke(PoseDetectionResult.Error("PoseLandmarker not initialized"))
            return
        }
        val mpImage = BitmapImageBuilder(bitmap).build()
        landmarker.detectAsync(mpImage, timestampMs)
    }

    fun detectForVideo(bitmap: Bitmap, timestampMs: Long): PoseDetectionResult {
        require(runningMode == RunningMode.VIDEO) { "detectForVideo requires RunningMode.VIDEO" }
        val landmarker = poseLandmarker
            ?: return PoseDetectionResult.Error("PoseLandmarker not initialized")
        val mpImage = BitmapImageBuilder(bitmap).build()
        return try {
            val result = landmarker.detectForVideo(mpImage, timestampMs)
            convertResult(result, timestampMs, bitmap.width, bitmap.height)
        } finally {
            mpImage.close()
        }
    }

    private fun convertResult(
        result: PoseLandmarkerResult,
        timestampMs: Long,
        width: Int,
        height: Int,
    ): PoseDetectionResult {
        val normalized = result.landmarks().firstOrNull()
            ?: return PoseDetectionResult.NoPerson(timestampMs)
        val world = result.worldLandmarks().firstOrNull().orEmpty()

        val posePoints = normalized.map { lm ->
            LandmarkPoint3D(
                x = lm.x().toDouble(),
                y = lm.y().toDouble(),
                z = lm.z().toDouble(),
                visibility = (lm.visibility().orElse(0f)).toDouble().coerceIn(0.0, 1.0),
            )
        }
        val worldPoints = world.map { lm ->
            LandmarkPoint3D(
                x = lm.x().toDouble(),
                y = lm.y().toDouble(),
                z = lm.z().toDouble(),
                visibility = (lm.visibility().orElse(0f)).toDouble().coerceIn(0.0, 1.0),
            )
        }

        val maskOptional = result.segmentationMasks()
        var maskW: Int? = null
        var maskH: Int? = null
        var maskFloats: FloatArray? = null
        if (maskOptional.isPresent && maskOptional.get().isNotEmpty()) {
            val maskImage = maskOptional.get()[0]
            maskW = maskImage.width
            maskH = maskImage.height
            val buffer = ByteBufferExtractor.extract(maskImage).asFloatBuffer()
            val array = FloatArray(buffer.remaining())
            buffer.get(array)
            maskFloats = array
        }

        return PoseDetectionResult.Detected(
            PoseFrameObservation(
                timestampMs = timestampMs,
                imageWidth = width,
                imageHeight = height,
                landmarks = posePoints,
                worldLandmarks = worldPoints,
                segmentationMaskWidth = maskW,
                segmentationMaskHeight = maskH,
                segmentationMaskConfidence = maskFloats,
                activeDelegate = activeDelegate,
            ),
        )
    }

    override fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
    }
}
