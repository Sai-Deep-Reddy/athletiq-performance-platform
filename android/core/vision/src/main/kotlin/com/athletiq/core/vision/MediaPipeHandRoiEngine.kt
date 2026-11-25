package com.athletiq.core.vision

import android.content.Context
import android.graphics.Bitmap
import com.athletiq.core.biomechanics.LandmarkPoint3D
import com.athletiq.core.biomechanics.PoseLandmarkIndex
import com.athletiq.core.biomechanics.ReachHandSide
import com.athletiq.core.biomechanics.RefinedFingertipPoint
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.Closeable
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Targeted 21-landmark HandLandmarker ROI refiner invoked selectively on standing-reach
 * baseline and jump-apex keyframes instead of running heavy HolisticLandmarker on every frame.
 */
class MediaPipeHandRoiEngine(
    private val context: Context,
    private val modelAssetPath: String = "hand_landmarker.task",
    private val minConfidence: Float = 0.5f,
) : Closeable {

    companion object {
        private const val INDEX_FINGER_TIP = 8
        private const val MIDDLE_FINGER_TIP = 12
    }

    private var handLandmarker: HandLandmarker? = null

    fun initialize(): Boolean {
        close()
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelAssetPath)
                .build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .setMinHandDetectionConfidence(minConfidence)
                .setMinHandPresenceConfidence(minConfidence)
                .setMinTrackingConfidence(minConfidence)
                .build()
            handLandmarker = HandLandmarker.createFromOptions(context, options)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Crop around the highest overhead wrist/elbow and detect fingertip reach coordinates
     * mapped back to full-frame normalized [0.0..1.0] coordinates. Returns null cleanly if
     * unavailable so [VerticalJumpCalculator] records explicit Pose fallback provenance.
     */
    fun detectHighestFingertip(
        frameBitmap: Bitmap,
        poseLandmarks: List<LandmarkPoint3D>,
    ): RefinedFingertipPoint? {
        val landmarker = handLandmarker ?: return null
        if (poseLandmarks.size != PoseLandmarkIndex.LANDMARK_COUNT) return null

        val leftWrist = poseLandmarks[PoseLandmarkIndex.LEFT_WRIST]
        val rightWrist = poseLandmarks[PoseLandmarkIndex.RIGHT_WRIST]
        val useLeft = when {
            leftWrist.visibility >= 0.4 && rightWrist.visibility >= 0.4 -> leftWrist.y <= rightWrist.y
            leftWrist.visibility >= 0.4 -> true
            rightWrist.visibility >= 0.4 -> false
            else -> return null
        }

        val side = if (useLeft) ReachHandSide.LEFT else ReachHandSide.RIGHT
        val wrist = if (useLeft) leftWrist else rightWrist
        val elbow = poseLandmarks[if (useLeft) PoseLandmarkIndex.LEFT_ELBOW else PoseLandmarkIndex.RIGHT_ELBOW]

        val w = frameBitmap.width
        val h = frameBitmap.height
        val forearmPx = hypot((wrist.x - elbow.x) * w, (wrist.y - elbow.y) * h)
        val halfBoxPx = max(64, (forearmPx * 1.1).toInt())

        val cxPx = (wrist.x * w).toInt()
        val cyPx = ((wrist.y * h) - halfBoxPx * 0.45).toInt()
        val left = (cxPx - halfBoxPx).coerceIn(0, max(0, w - 2))
        val top = (cyPx - halfBoxPx).coerceIn(0, max(0, h - 2))
        val right = (cxPx + halfBoxPx).coerceIn(left + 1, w)
        val bottom = (cyPx + halfBoxPx).coerceIn(top + 1, h)
        val cropW = right - left
        val cropH = bottom - top
        if (cropW < 16 || cropH < 16) return null

        val roiBitmap = Bitmap.createBitmap(frameBitmap, left, top, cropW, cropH)
        val mpImage = BitmapImageBuilder(roiBitmap).build()
        return try {
            val result = landmarker.detect(mpImage)
            val hand = result.landmarks().firstOrNull() ?: return null
            if (hand.size <= MIDDLE_FINGER_TIP) return null

            val indexTip = hand[INDEX_FINGER_TIP]
            val middleTip = hand[MIDDLE_FINGER_TIP]
            // Use highest extended fingertip (smaller normalized y = higher in image)
            val chosenTip = if (middleTip.y() < indexTip.y()) middleTip else indexTip

            val fullX = ((left + chosenTip.x() * cropW) / w).toDouble().coerceIn(0.0, 1.0)
            val fullY = ((top + chosenTip.y() * cropH) / h).toDouble().coerceIn(0.0, 1.0)
            val handedScore = result.handedness().firstOrNull()?.firstOrNull()?.score()?.toDouble() ?: 0.85

            RefinedFingertipPoint(
                xNormalized = fullX,
                yNormalized = fullY,
                confidence = min(1.0, handedScore),
                side = side,
            )
        } finally {
            mpImage.close()
            roiBitmap.recycle()
        }
    }

    override fun close() {
        handLandmarker?.close()
        handLandmarker = null
    }
}
