package com.athletiq.core.vision

import android.graphics.Bitmap
import com.athletiq.core.biomechanics.CalibrationQualityGate
import com.athletiq.core.biomechanics.PlanarHomography
import com.athletiq.core.biomechanics.Point2D
import com.athletiq.core.model.CalibrationMethod
import com.athletiq.core.model.CalibrationPlaneType
import com.athletiq.core.model.CalibrationQuality
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.CharucoBoard
import org.opencv.objdetect.CharucoDetector
import org.opencv.objdetect.CharucoParameters
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect
import java.util.UUID

data class CharucoBoardSpec(
    val squaresX: Int = 5,
    val squaresY: Int = 7,
    val squareLengthCm: Double = 4.0,
    val markerLengthCm: Double = 2.0,
    val dictionaryId: Int = Objdetect.DICT_5X5_100,
) {
    init {
        require(squaresX >= 3 && squaresY >= 3) { "ChArUco board requires at least 3x3 squares" }
        require(squareLengthCm > 0.0 && markerLengthCm > 0.0 && markerLengthCm < squareLengthCm) {
            "markerLengthCm must be positive and smaller than squareLengthCm"
        }
    }
}

sealed interface PlanarCalibrationOutcome {
    data class Success(
        val quality: CalibrationQuality,
        val homography: PlanarHomography,
        val detectedCornersCount: Int,
    ) : PlanarCalibrationOutcome

    data class Failed(val reason: String) : PlanarCalibrationOutcome
}

/**
 * Standardized OpenCV 4.11 ChArUco planar calibration engine.
 *
 * Strictly separates vertical-plane calibration (height & vertical jump) from
 * ground-plane calibration (standing broad jump & sprint).
 */
class CharucoCalibrationEngine(
    private val boardSpec: CharucoBoardSpec = CharucoBoardSpec(),
    private val maxAllowedReprojectionErrorPx: Double = CalibrationQualityGate.DEFAULT_MAX_REPROJECTION_ERROR_PX,
) {
    private val dictionary by lazy { Objdetect.getPredefinedDictionary(boardSpec.dictionaryId) }
    private val board by lazy {
        CharucoBoard(
            Size(boardSpec.squaresX.toDouble(), boardSpec.squaresY.toDouble()),
            (boardSpec.squareLengthCm / 100.0).toFloat(),
            (boardSpec.markerLengthCm / 100.0).toFloat(),
            dictionary,
        )
    }
    private val detector by lazy {
        CharucoDetector(
            board,
            CharucoParameters(),
            DetectorParameters(),
        )
    }

    fun calibrateVerticalPlane(
        bitmap: Bitmap,
        cameraMatrix3x3: DoubleArray? = null,
        distCoeffs: DoubleArray? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): PlanarCalibrationOutcome = calibratePlane(
        bitmap = bitmap,
        planeType = CalibrationPlaneType.VERTICAL_PLANE,
        cameraMatrix3x3 = cameraMatrix3x3,
        distCoeffs = distCoeffs,
        nowEpochMs = nowEpochMs,
    )

    fun calibrateGroundPlane(
        bitmap: Bitmap,
        cameraMatrix3x3: DoubleArray? = null,
        distCoeffs: DoubleArray? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): PlanarCalibrationOutcome = calibratePlane(
        bitmap = bitmap,
        planeType = CalibrationPlaneType.GROUND_PLANE,
        cameraMatrix3x3 = cameraMatrix3x3,
        distCoeffs = distCoeffs,
        nowEpochMs = nowEpochMs,
    )

    private fun calibratePlane(
        bitmap: Bitmap,
        planeType: CalibrationPlaneType,
        cameraMatrix3x3: DoubleArray?,
        distCoeffs: DoubleArray?,
        nowEpochMs: Long,
    ): PlanarCalibrationOutcome {
        val initState = OpenCvBootstrap.ensureInitialized()
        if (initState is OpenCvInitState.Failed) {
            return PlanarCalibrationOutcome.Failed(initState.reason)
        }

        val rgbaMat = Mat()
        val grayMat = Mat()
        val undistortedMat = Mat()
        val charucoCorners = Mat()
        val charucoIds = Mat()
        val markerCorners = mutableListOf<Mat>()
        val markerIds = Mat()

        try {
            Utils.bitmapToMat(bitmap, rgbaMat)
            Imgproc.cvtColor(rgbaMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

            val detectionInput = if (cameraMatrix3x3 != null && distCoeffs != null &&
                cameraMatrix3x3.size == 9 && distCoeffs.size >= 4
            ) {
                val kMat = Mat(3, 3, CvType.CV_64FC1).apply { put(0, 0, *cameraMatrix3x3) }
                val dMat = Mat(1, distCoeffs.size, CvType.CV_64FC1).apply { put(0, 0, *distCoeffs) }
                try {
                    Calib3d.undistort(grayMat, undistortedMat, kMat, dMat)
                    undistortedMat
                } finally {
                    kMat.release()
                    dMat.release()
                }
            } else {
                grayMat
            }

            detector.detectBoard(
                detectionInput,
                charucoCorners,
                charucoIds,
                markerCorners,
                markerIds,
            )

            val cornerCount = charucoIds.rows()
            if (cornerCount < 6) {
                return PlanarCalibrationOutcome.Failed(
                    "Detected only $cornerCount ChArUco corners for $planeType; at least 6 required",
                )
            }

            val imgPoints = mutableListOf<Point2D>()
            val planePointsCm = mutableListOf<Point2D>()
            val innerCols = boardSpec.squaresX - 1
            val innerRows = boardSpec.squaresY - 1

            for (i in 0 until cornerCount) {
                val id = charucoIds.get(i, 0)[0].toInt()
                val px = charucoCorners.get(i, 0)
                val col = id % innerCols
                val row = id / innerCols
                val xCm = (col + 1) * boardSpec.squareLengthCm
                // OpenCV ChArUco row=0 is at the top of the board; invert Y so +Y_cm points upward (matching Height/VerticalJump).
                val yCm = (innerRows - row) * boardSpec.squareLengthCm

                imgPoints.add(Point2D(px[0], px[1]))
                planePointsCm.add(Point2D(xCm, yCm))
            }

            val srcMat = MatOfPoint2f(*imgPoints.map { Point(it.x, it.y) }.toTypedArray())
            val dstMat = MatOfPoint2f(*planePointsCm.map { Point(it.x, it.y) }.toTypedArray())
            val ransacH = Calib3d.findHomography(srcMat, dstMat, Calib3d.RANSAC, 3.0)

            try {
                if (ransacH.empty() || ransacH.rows() != 3 || ransacH.cols() != 3) {
                    return PlanarCalibrationOutcome.Failed(
                        "OpenCV findHomography returned empty matrix for $planeType",
                    )
                }
                val hArray = DoubleArray(9)
                ransacH.get(0, 0, hArray)
                val homography = PlanarHomography(hArray)
                val reprojErrorPx = homography.computeRmsReprojectionErrorPx(imgPoints, planePointsCm)

                if (reprojErrorPx > maxAllowedReprojectionErrorPx) {
                    return PlanarCalibrationOutcome.Failed(
                        "Reprojection error ${"%.2f".format(reprojErrorPx)}px exceeds limit ${maxAllowedReprojectionErrorPx}px",
                    )
                }

                val quality = CalibrationQuality(
                    calibrationId = UUID.randomUUID().toString(),
                    planeType = planeType,
                    method = CalibrationMethod.OPENCV_CHARUCO,
                    reprojectionErrorPx = reprojErrorPx,
                    calibratedAtEpochMs = nowEpochMs,
                )
                return PlanarCalibrationOutcome.Success(
                    quality = quality,
                    homography = homography,
                    detectedCornersCount = cornerCount,
                )
            } finally {
                srcMat.release()
                dstMat.release()
                ransacH.release()
            }
        } catch (t: Throwable) {
            return PlanarCalibrationOutcome.Failed("ChArUco calibration failed: ${t.message}")
        } finally {
            rgbaMat.release()
            grayMat.release()
            undistortedMat.release()
            charucoCorners.release()
            charucoIds.release()
            markerCorners.forEach { it.release() }
            markerIds.release()
        }
    }
}
