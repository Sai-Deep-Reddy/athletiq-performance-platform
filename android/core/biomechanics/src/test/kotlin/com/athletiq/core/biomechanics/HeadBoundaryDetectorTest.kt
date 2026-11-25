package com.athletiq.core.biomechanics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class HeadBoundaryDetectorTest {
    @Test
    fun `ignores stray mask noise pixels and refines crown via luminance gradient`() {
        val w = 100
        val h = 100
        val mask = FloatArray(w * h) { 0f }
        val gray = FloatArray(w * h) { 220f } // bright wall background

        // Fill solid head silhouette from y = 20..40, x = 44..56
        for (y in 20..40) {
            for (x in 44..56) {
                mask[y * w + x] = 0.95f
                gray[y * w + x] = 45f // dark hair/head against bright wall
            }
        }
        // Inject a stray 1-pixel mask artifact way above the head at y = 10
        mask[10 * w + 50] = 0.99f

        val landmarks = MutableList(PoseLandmarkIndex.LANDMARK_COUNT) {
            LandmarkPoint3D(0.5, 0.5, 0.0, 0.95)
        }
        landmarks[PoseLandmarkIndex.NOSE] = LandmarkPoint3D(0.50, 0.30, 0.0, 0.98)
        landmarks[PoseLandmarkIndex.LEFT_EYE] = LandmarkPoint3D(0.47, 0.28, 0.0, 0.98)
        landmarks[PoseLandmarkIndex.RIGHT_EYE] = LandmarkPoint3D(0.53, 0.28, 0.0, 0.98)
        landmarks[PoseLandmarkIndex.LEFT_EAR] = LandmarkPoint3D(0.44, 0.29, 0.0, 0.95)
        landmarks[PoseLandmarkIndex.RIGHT_EAR] = LandmarkPoint3D(0.56, 0.29, 0.0, 0.95)

        val crown = HeadBoundaryDetector.detectInHeadRegion(
            imageWidth = 1000,
            imageHeight = 1000,
            landmarks = landmarks,
            maskWidth = w,
            maskHeight = h,
            maskConfidence = mask,
            grayscalePatch = gray,
        )

        assertNotNull(crown)
        // Should land near y = 0.20 (row 20), NOT y = 0.10 stray pixel!
        assertTrue(abs(crown!!.crownYNormalized - 0.20) <= 0.02, "Unexpected crownY=${crown.crownYNormalized}")
        assertFalse(crown.possibleHairOrHeadwear)
        assertTrue(crown.boundaryConfidence > 0.7)
    }

    @Test
    fun `flags tall hair or headwear profile for examiner review`() {
        val w = 100
        val h = 100
        val mask = FloatArray(w * h) { 0f }
        // Tall headwear extending well above eyes (eyes at y=35, crown at y=10)
        for (y in 10..42) {
            for (x in 44..56) {
                mask[y * w + x] = 0.9f
            }
        }
        val landmarks = MutableList(PoseLandmarkIndex.LANDMARK_COUNT) {
            LandmarkPoint3D(0.5, 0.5, 0.0, 0.95)
        }
        landmarks[PoseLandmarkIndex.NOSE] = LandmarkPoint3D(0.50, 0.37, 0.0, 0.98)
        landmarks[PoseLandmarkIndex.LEFT_EYE] = LandmarkPoint3D(0.47, 0.35, 0.0, 0.98)
        landmarks[PoseLandmarkIndex.RIGHT_EYE] = LandmarkPoint3D(0.53, 0.35, 0.0, 0.98)
        landmarks[PoseLandmarkIndex.LEFT_EAR] = LandmarkPoint3D(0.44, 0.36, 0.0, 0.95)
        landmarks[PoseLandmarkIndex.RIGHT_EAR] = LandmarkPoint3D(0.56, 0.36, 0.0, 0.95)

        val crown = HeadBoundaryDetector.detectInHeadRegion(
            imageWidth = 1000,
            imageHeight = 1000,
            landmarks = landmarks,
            maskWidth = w,
            maskHeight = h,
            maskConfidence = mask,
            grayscalePatch = null,
        )

        assertNotNull(crown)
        assertEquals(true, crown!!.possibleHairOrHeadwear)
    }
}
