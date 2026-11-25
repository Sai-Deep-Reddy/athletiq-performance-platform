package com.athletiq.core.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File

/**
 * Hardware-gated 120/240fps Camera2 constrained high-speed video recorder.
 *
 * Uses ONLY Preview + MediaRecorder surfaces (never ImageReader YUV buffers, which
 * Android's CameraConstrainedHighSpeedCaptureSession prohibits). Recorded video
 * clips are then processed offline via MediaPipe RunningMode.VIDEO with video PTS timestamps.
 */
class HighSpeedVideoRecorder(
    private val context: Context,
) {
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var highSpeedSession: CameraConstrainedHighSpeedCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null

    @SuppressLint("MissingPermission")
    fun startRecording(
        capabilities: CameraHardwareCapabilities,
        option: HighSpeedVideoOption,
        previewSurface: Surface,
        outputFile: File,
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!capabilities.supportsHighSpeed120FpsVideo) {
            onError("Device camera does not support CONSTRAINED_HIGH_SPEED_VIDEO >= 120fps")
            return
        }

        val thread = HandlerThread("AthletiQHighSpeedCam").apply { start() }
        handlerThread = thread
        val bgHandler = Handler(thread.looper)
        handler = bgHandler

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mediaRecorder = recorder

        try {
            recorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(outputFile.absolutePath)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(20_000_000)
                setVideoFrameRate(option.fpsRange.upper)
                setVideoSize(option.size.width, option.size.height)
                setCaptureRate(option.fpsRange.upper.toDouble())
                prepare()
            }
        } catch (t: Throwable) {
            cleanup()
            onError("Failed to prepare MediaRecorder for ${option.fpsRange.upper}fps: ${t.message}")
            return
        }

        val recorderSurface = recorder.surface
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        manager.openCamera(
            capabilities.cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    createHighSpeedSession(device, option, previewSurface, recorderSurface, recorder, bgHandler, onStarted, onError)
                }

                override fun onDisconnected(device: CameraDevice) {
                    cleanup()
                    onError("High-speed camera disconnected")
                }

                override fun onError(device: CameraDevice, error: Int) {
                    cleanup()
                    onError("High-speed camera open failed with code $error")
                }
            },
            bgHandler,
        )
    }

    private fun createHighSpeedSession(
        device: CameraDevice,
        option: HighSpeedVideoOption,
        previewSurface: Surface,
        recorderSurface: Surface,
        recorder: MediaRecorder,
        bgHandler: Handler,
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val surfaces = listOf(previewSurface, recorderSurface)
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                val hsSession = session as? CameraConstrainedHighSpeedCaptureSession
                if (hsSession == null) {
                    cleanup()
                    onError("Session is not a CameraConstrainedHighSpeedCaptureSession")
                    return
                }
                highSpeedSession = hsSession
                try {
                    val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(previewSurface)
                        addTarget(recorderSurface)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, option.fpsRange)
                    }
                    val burst = hsSession.createHighSpeedRequestList(requestBuilder.build())
                    hsSession.setRepeatingBurst(burst, null, bgHandler)
                    recorder.start()
                    onStarted()
                } catch (t: Throwable) {
                    cleanup()
                    onError("Failed to start high-speed burst: ${t.message}")
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                cleanup()
                onError("High-speed capture session configuration failed")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val configs = surfaces.map { OutputConfiguration(it) }
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_HIGH_SPEED,
                configs,
                context.mainExecutor,
                callback,
            )
            device.createCaptureSession(sessionConfig)
        } else {
            @Suppress("DEPRECATION")
            device.createConstrainedHighSpeedCaptureSession(surfaces, callback, bgHandler)
        }
    }

    fun stopRecording() {
        try {
            highSpeedSession?.stopRepeating()
        } catch (_: Throwable) {
        }
        try {
            mediaRecorder?.stop()
        } catch (_: Throwable) {
        }
        cleanup()
    }

    fun cleanup() {
        try {
            highSpeedSession?.close()
        } catch (_: Throwable) {
        }
        highSpeedSession = null
        try {
            cameraDevice?.close()
        } catch (_: Throwable) {
        }
        cameraDevice = null
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Throwable) {
        }
        mediaRecorder = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }
}
