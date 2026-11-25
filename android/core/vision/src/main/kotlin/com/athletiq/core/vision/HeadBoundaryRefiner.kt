package com.athletiq.core.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.athletiq.core.biomechanics.HeadBoundaryDetector
import com.athletiq.core.biomechanics.RefinedHeadCrown

/**
 * Android-side Bitmap luminance bridge that feeds [PoseFrameObservation] segmentation masks
 * and original image pixels into [HeadBoundaryDetector] without allocating full-frame buffers
 * unless a segmentation mask is present.
 */
object HeadBoundaryRefiner {
    fun refineCrown(
        frameBitmap: Bitmap,
        observation: PoseFrameObservation,
    ): RefinedHeadCrown? {
        val maskW = observation.segmentationMaskWidth ?: return null
        val maskH = observation.segmentationMaskHeight ?: return null
        val maskFloats = observation.segmentationMaskConfidence ?: return null

        val scaledBitmap = if (frameBitmap.width != maskW || frameBitmap.height != maskH) {
            Bitmap.createScaledBitmap(frameBitmap, maskW, maskH, true)
        } else {
            frameBitmap
        }
        try {
            val pixelCount = maskW * maskH
            val argb = IntArray(pixelCount)
            scaledBitmap.getPixels(argb, 0, maskW, 0, 0, maskW, maskH)
            val grayscale = FloatArray(pixelCount) { idx ->
                val c = argb[idx]
                (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c))
            }
            return HeadBoundaryDetector.detectInHeadRegion(
                imageWidth = observation.imageWidth,
                imageHeight = observation.imageHeight,
                landmarks = observation.landmarks,
                maskWidth = maskW,
                maskHeight = maskH,
                maskConfidence = maskFloats,
                grayscalePatch = grayscale,
                grayscaleWidth = maskW,
                grayscaleHeight = maskH,
            )
        } finally {
            if (scaledBitmap !== frameBitmap) {
                scaledBitmap.recycle()
            }
        }
    }
}
