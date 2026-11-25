package com.athletiq.core.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Standard CameraX controller for live ImageAnalysis (30/60fps negotiated via Camera2Interop)
 * and standard VideoCapture when constrained 120fps Camera2 mode is unavailable or not active.
 */
class DualModeCameraController(
    private val context: Context,
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    val timingTracker = FrameTimingTracker(expectedFps = 30.0)
    private var currentTimingTracker: FrameTimingTracker = timingTracker

    @OptIn(ExperimentalCamera2Interop::class)
    fun bindLiveAnalysisAndVideo(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider?,
        capabilities: CameraHardwareCapabilities,
        targetAnalysisSize: Size = Size(1280, 720),
        onFrame: (ImageProxy, FrameTimingTracker) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    provider.unbindAll()

                    val negotiatedUpperFps = capabilities.negotiatedAnalysisFpsRange.upper.toDouble()
                    currentTimingTracker = FrameTimingTracker(expectedFps = negotiatedUpperFps)

                    val preview = Preview.Builder().build().apply {
                        if (surfaceProvider != null) {
                            setSurfaceProvider(surfaceProvider)
                        }
                    }

                    val analysisBuilder = ImageAnalysis.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        targetAnalysisSize,
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                    ),
                                )
                                .build(),
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)

                    Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        capabilities.negotiatedAnalysisFpsRange,
                    )

                    val imageAnalysis = analysisBuilder.build().apply {
                        setAnalyzer(analyzerExecutor) { imageProxy ->
                            try {
                                currentTimingTracker.recordFrameTimestampNs(imageProxy.imageInfo.timestamp)
                                onFrame(imageProxy, currentTimingTracker)
                            } catch (t: Throwable) {
                                onError(t)
                            } finally {
                                imageProxy.close()
                            }
                        }
                    }

                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD))
                        .build()
                    val videoCap = VideoCapture.withOutput(recorder)
                    videoCapture = videoCap

                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                        videoCap,
                    )
                } catch (t: Throwable) {
                    onError(t)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun startStandardVideoRecording(
        outputFile: File,
        onEvent: (VideoRecordEvent) -> Unit,
    ): Boolean {
        val capture = videoCapture ?: return false
        val outputOptions = FileOutputOptions.Builder(outputFile).build()
        activeRecording = capture.output
            .prepareRecording(context, outputOptions)
            .start(ContextCompat.getMainExecutor(context), onEvent)
        return true
    }

    fun stopStandardVideoRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    fun release() {
        stopStandardVideoRecording()
        cameraProvider?.unbindAll()
        cameraProvider = null
        analyzerExecutor.shutdown()
    }
}
