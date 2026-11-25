package com.athletiq.core.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Range
import android.util.Size

sealed interface DeviceIntrinsicsStatus {
    data class Available(
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float,
        val skew: Float,
        val distortion: FloatArray?,
    ) : DeviceIntrinsicsStatus

    data class Unavailable(val reason: String) : DeviceIntrinsicsStatus
}

data class HighSpeedVideoOption(
    val size: Size,
    val fpsRange: Range<Int>,
)

data class CameraHardwareCapabilities(
    val cameraId: String,
    val sensorOrientationDeg: Int,
    val negotiatedAnalysisFpsRange: Range<Int>,
    val supportsFixed60FpsAnalysis: Boolean,
    val highSpeedVideoOptions: List<HighSpeedVideoOption>,
    val deviceIntrinsics: DeviceIntrinsicsStatus,
) {
    val supportsHighSpeed120FpsVideo: Boolean
        get() = highSpeedVideoOptions.any { it.fpsRange.upper >= 120 }
}

/**
 * Queries real Camera2 hardware characteristics without assuming 60/120fps
 * or calibrated lens intrinsics exist on arbitrary Android devices.
 */
object CameraCapabilityInspector {
    fun inspectBackCamera(context: Context): CameraHardwareCapabilities? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backCameraId = manager.cameraIdList.firstOrNull { id ->
            val chars = manager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return null

        val chars = manager.getCameraCharacteristics(backCameraId)
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val availableFpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()

        val fixed60 = availableFpsRanges.firstOrNull { it.lower == 60 && it.upper == 60 }
        val adaptive60 = availableFpsRanges.firstOrNull { it.upper == 60 && it.lower >= 30 }
        val fixed30 = availableFpsRanges.firstOrNull { it.lower == 30 && it.upper == 30 }
        val negotiatedAnalysisRange = fixed60 ?: adaptive60 ?: fixed30 ?: Range(30, 30)

        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val supportsHighSpeedCapability = capabilities.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO,
        )
        val streamMap: StreamConfigurationMap? =
            chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val highSpeedOptions = if (supportsHighSpeedCapability && streamMap != null) {
            streamMap.highSpeedVideoSizes.flatMap { size ->
                streamMap.getHighSpeedVideoFpsRangesFor(size)
                    .filter { it.upper >= 120 }
                    .map { range -> HighSpeedVideoOption(size, range) }
            }
        } else {
            emptyList()
        }

        val intrinsicsArr = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val distortionArr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            chars.get(CameraCharacteristics.LENS_DISTORTION)
        } else {
            @Suppress("DEPRECATION")
            chars.get(CameraCharacteristics.LENS_RADIAL_DISTORTION)
        }

        val intrinsicsStatus = if (intrinsicsArr != null && intrinsicsArr.size >= 5 &&
            intrinsicsArr[0] > 0f && intrinsicsArr[1] > 0f
        ) {
            DeviceIntrinsicsStatus.Available(
                fx = intrinsicsArr[0],
                fy = intrinsicsArr[1],
                cx = intrinsicsArr[2],
                cy = intrinsicsArr[3],
                skew = intrinsicsArr[4],
                distortion = distortionArr,
            )
        } else {
            DeviceIntrinsicsStatus.Unavailable(
                "CameraCharacteristics.LENS_INTRINSIC_CALIBRATION not populated; ChArUco calibration required",
            )
        }

        return CameraHardwareCapabilities(
            cameraId = backCameraId,
            sensorOrientationDeg = sensorOrientation,
            negotiatedAnalysisFpsRange = negotiatedAnalysisRange,
            supportsFixed60FpsAnalysis = fixed60 != null,
            highSpeedVideoOptions = highSpeedOptions,
            deviceIntrinsics = intrinsicsStatus,
        )
    }
}
