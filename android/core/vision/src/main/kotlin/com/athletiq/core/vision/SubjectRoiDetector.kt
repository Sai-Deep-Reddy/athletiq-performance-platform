package com.athletiq.core.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.Closeable

sealed interface SubjectScreeningResult {
    data class SingleAthlete(val boundingBox: RectF, val score: Float) : SubjectScreeningResult
    data class MultiplePersonsDetected(val count: Int, val boundingBoxes: List<RectF>) : SubjectScreeningResult
    data object NoPersonDetected : SubjectScreeningResult
    data class Unavailable(val reason: String) : SubjectScreeningResult
}

/**
 * Optional Apache-2.0 EfficientDet-Lite person detector used to screen crowded
 * examination rooms so single-person PoseLandmarker does not silently jump between bystanders.
 */
class SubjectRoiDetector(
    private val context: Context,
    private val modelAssetPath: String = "efficientdet_lite0.tflite",
    private val minConfidence: Float = 0.5f,
) : Closeable {

    private var detector: ObjectDetector? = null

    fun initialize(): Boolean {
        close()
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelAssetPath)
                .build()
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setCategoryAllowlist(listOf("person"))
                .setScoreThreshold(minConfidence)
                .setMaxResults(5)
                .build()
            detector = ObjectDetector.createFromOptions(context, options)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun screenFrame(bitmap: Bitmap): SubjectScreeningResult {
        val activeDetector = detector
            ?: return SubjectScreeningResult.Unavailable("ObjectDetector model not initialized")
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = try {
            activeDetector.detect(mpImage)
        } finally {
            mpImage.close()
        }
        val detections = result.detections()
        return when (detections.size) {
            0 -> SubjectScreeningResult.NoPersonDetected
            1 -> {
                val det = detections[0]
                val score = det.categories().firstOrNull()?.score() ?: 0f
                SubjectScreeningResult.SingleAthlete(RectF(det.boundingBox()), score)
            }
            else -> SubjectScreeningResult.MultiplePersonsDetected(
                count = detections.size,
                boundingBoxes = detections.map { RectF(it.boundingBox()) },
            )
        }
    }

    override fun close() {
        detector?.close()
        detector = null
    }
}
