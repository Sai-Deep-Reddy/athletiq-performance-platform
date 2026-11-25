package com.athletiq.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.athletiq.app.state.AthletiqMainUiState
import com.athletiq.app.ui.components.AthletiqTopBar
import com.athletiq.app.ui.components.DarkTelemetryContainer
import com.athletiq.app.ui.components.PillTone
import com.athletiq.app.ui.components.StatusPill
import com.athletiq.app.ui.components.TelemetryCard
import com.athletiq.app.ui.components.UnavailableBanner
import com.athletiq.app.ui.theme.AthletiqColors
import com.athletiq.core.biomechanics.BroadJumpProtocol
import com.athletiq.core.biomechanics.CalibrationQualityGate
import com.athletiq.core.biomechanics.LandmarkPoint3D
import com.athletiq.core.biomechanics.PoseLandmarkIndex
import com.athletiq.core.biomechanics.VerticalJumpProtocol
import com.athletiq.core.camera.DualModeCameraController
import com.athletiq.core.camera.FrameTimingTracker
import com.athletiq.core.model.CalibrationPlaneType
import com.athletiq.core.model.TestType

private val POSE_EDGES = listOf(
    PoseLandmarkIndex.LEFT_SHOULDER to PoseLandmarkIndex.RIGHT_SHOULDER,
    PoseLandmarkIndex.LEFT_SHOULDER to PoseLandmarkIndex.LEFT_ELBOW,
    PoseLandmarkIndex.LEFT_ELBOW to PoseLandmarkIndex.LEFT_WRIST,
    PoseLandmarkIndex.RIGHT_SHOULDER to PoseLandmarkIndex.RIGHT_ELBOW,
    PoseLandmarkIndex.RIGHT_ELBOW to PoseLandmarkIndex.RIGHT_WRIST,
    PoseLandmarkIndex.LEFT_SHOULDER to PoseLandmarkIndex.LEFT_HIP,
    PoseLandmarkIndex.RIGHT_SHOULDER to PoseLandmarkIndex.RIGHT_HIP,
    PoseLandmarkIndex.LEFT_HIP to PoseLandmarkIndex.RIGHT_HIP,
    PoseLandmarkIndex.LEFT_HIP to PoseLandmarkIndex.LEFT_KNEE,
    PoseLandmarkIndex.LEFT_KNEE to PoseLandmarkIndex.LEFT_ANKLE,
    PoseLandmarkIndex.LEFT_ANKLE to PoseLandmarkIndex.LEFT_HEEL,
    PoseLandmarkIndex.LEFT_HEEL to PoseLandmarkIndex.LEFT_FOOT_INDEX,
    PoseLandmarkIndex.RIGHT_HIP to PoseLandmarkIndex.RIGHT_KNEE,
    PoseLandmarkIndex.RIGHT_KNEE to PoseLandmarkIndex.RIGHT_ANKLE,
    PoseLandmarkIndex.RIGHT_ANKLE to PoseLandmarkIndex.RIGHT_HEEL,
    PoseLandmarkIndex.RIGHT_HEEL to PoseLandmarkIndex.RIGHT_FOOT_INDEX,
)

@Composable
fun LiveCaptureScreen(
    state: AthletiqMainUiState,
    onBack: () -> Unit,
    onOpenCalibration: (CalibrationPlaneType) -> Unit,
    onPrepareEngines: () -> Unit,
    onLiveFrame: (Bitmap, Long, FrameTimingTracker) -> Unit,
    onStartRecording: () -> Unit,
    onStopAndEvaluate: () -> Unit,
    onImportVideo: (Uri) -> Unit,
    onSelectVerticalJumpProtocol: (VerticalJumpProtocol) -> Unit,
    onSelectBroadJumpProtocol: (BroadJumpProtocol) -> Unit,
    onSetSprintDistance: (Double) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }
    val videoImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) onImportVideo(uri)
    }

    LaunchedEffect(Unit) {
        onPrepareEngines()
    }

    val controller = remember { DualModeCameraController(context) }
    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    val requiredPlane = CalibrationQualityGate.requiredPlaneFor(state.selectedTestType)
    val activeCalibration = state.calibrations.firstOrNull {
        it.planeType == requiredPlane.name && it.isActive
    }
    val caps = state.cameraCapabilities
    val overlay = state.livePoseOverlay
    val activeStudent = state.students.firstOrNull { it.id == state.selectedStudentId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AthletiqColors.SurfaceCanvas),
    ) {
        AthletiqTopBar(
            title = state.selectedTestType.name.replace('_', ' '),
            subtitle = activeStudent?.let { "${it.institutionStudentCode} • ${it.fullName}" } ?: "No athlete selected",
            pendingSyncCount = state.pendingSyncCount,
            onBackClick = onBack,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (activeCalibration == null) {
                item {
                    UnavailableBanner(
                        title = "Calibration Required (${requiredPlane.name})",
                        message = "This test requires an active ${requiredPlane.name} ChArUco calibration before recording valid measurements.",
                        tone = PillTone.ERROR,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { onOpenCalibration(requiredPlane) },
                        colors = ButtonDefaults.buttonColors(containerColor = AthletiqColors.PrimaryCobalt),
                    ) {
                        Text("Calibrate ${requiredPlane.name}")
                    }
                }
            }

            if (!state.visionAssets.isPoseReady) {
                item {
                    UnavailableBanner(
                        title = "Pose Model Missing",
                        message = "pose_landmarker_full.task was not found in app/src/main/assets/. Add the MediaPipe .task bundle to run live landmark detection.",
                        tone = PillTone.WARNING,
                    )
                }
            }

            if (state.activeOutcomeMessage != null) {
                item {
                    UnavailableBanner(
                        title = "Attempt Outcome",
                        message = state.activeOutcomeMessage,
                        tone = PillTone.WARNING,
                    )
                }
            }

            item {
                DarkTelemetryContainer {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = overlay.statusMessage,
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            modifier = Modifier.weight(1f),
                        )
                        StatusPill(
                            text = overlay.timing?.let {
                                "${"%.1f".format(it.fpsActual)} FPS • Drop ${it.droppedFrames}"
                            } ?: "Waiting FPS",
                            tone = if ((overlay.timing?.droppedFrames ?: 0) == 0) PillTone.VALID else PillTone.WARNING,
                        )
                    }

                    if (overlay.subjectCountWarning != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        StatusPill(text = overlay.subjectCountWarning, tone = PillTone.WARNING)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (!hasCameraPermission) {
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Grant Camera Permission")
                        }
                    } else if (caps == null) {
                        Text(
                            text = "No back-facing camera detected.",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(340.dp)
                                .clip(RoundedCornerShape(16.dp)),
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    PreviewView(ctx).also { previewView ->
                                        controller.bindLiveAnalysisAndVideo(
                                            lifecycleOwner = lifecycleOwner,
                                            surfaceProvider = previewView.surfaceProvider,
                                            capabilities = caps,
                                            onFrame = { proxy, tracker ->
                                                val bmp = proxy.toBitmap()
                                                try {
                                                    onLiveFrame(bmp, proxy.imageInfo.timestamp, tracker)
                                                } finally {
                                                    bmp.recycle()
                                                }
                                            },
                                            onError = {},
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                            PoseSkeletonOverlay(
                                landmarks = overlay.landmarks,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            if (state.selectedTestType == TestType.VERTICAL_JUMP) {
                item {
                    TelemetryCard {
                        Text(
                            text = "Vertical Jump Measurement Protocol",
                            style = MaterialTheme.typography.titleMedium,
                            color = AthletiqColors.TextPrimary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = state.verticalJumpProtocol == VerticalJumpProtocol.STANDING_REACH_DELTA,
                                onClick = { onSelectVerticalJumpProtocol(VerticalJumpProtocol.STANDING_REACH_DELTA) },
                                label = { Text("Standing Reach Delta") },
                            )
                            FilterChip(
                                selected = state.verticalJumpProtocol == VerticalJumpProtocol.COUNTERMOVEMENT_FLIGHT_TIME,
                                onClick = { onSelectVerticalJumpProtocol(VerticalJumpProtocol.COUNTERMOVEMENT_FLIGHT_TIME) },
                                label = { Text("CMJ Flight Time") },
                            )
                        }
                    }
                }
            }

            if (state.selectedTestType == TestType.STANDING_BROAD_JUMP) {
                item {
                    TelemetryCard {
                        Text(
                            text = "Standing Broad Jump Landing Protocol",
                            style = MaterialTheme.typography.titleMedium,
                            color = AthletiqColors.TextPrimary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = state.broadJumpProtocol == BroadJumpProtocol.NEAREST_BODY_CONTACT,
                                onClick = { onSelectBroadJumpProtocol(BroadJumpProtocol.NEAREST_BODY_CONTACT) },
                                label = { Text("Nearest Body Contact") },
                            )
                            FilterChip(
                                selected = state.broadJumpProtocol == BroadJumpProtocol.NEAREST_FOOT_OR_SHOE_CONTACT,
                                onClick = { onSelectBroadJumpProtocol(BroadJumpProtocol.NEAREST_FOOT_OR_SHOE_CONTACT) },
                                label = { Text("Foot / Shoe Only") },
                            )
                        }
                    }
                }
            }

            if (state.selectedTestType == TestType.SPRINT) {
                item {
                    TelemetryCard {
                        Text(
                            text = "Sprint Distance Configuration",
                            style = MaterialTheme.typography.titleMedium,
                            color = AthletiqColors.TextPrimary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(10.0, 20.0, 30.0).forEach { dist ->
                                FilterChip(
                                    selected = state.sprintDistanceMeters == dist,
                                    onClick = { onSetSprintDistance(dist) },
                                    label = { Text("${dist.toInt()} m") },
                                )
                            }
                        }
                    }
                }
            }

            item {
                val canAnalyzeMedia = activeCalibration != null &&
                    state.visionAssets.isPoseReady &&
                    state.selectedStudentId != null &&
                    state.selectedExamId != null
                val canCapture = hasCameraPermission && caps != null && canAnalyzeMedia

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!overlay.isRecording) {
                        Button(
                            onClick = onStartRecording,
                            enabled = canCapture,
                            colors = ButtonDefaults.buttonColors(containerColor = AthletiqColors.PrimaryCobalt),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                        ) {
                            Text("Start Attempt Recording", style = MaterialTheme.typography.labelLarge)
                        }
                        OutlinedButton(
                            onClick = { videoImportLauncher.launch(arrayOf("video/*")) },
                            enabled = canAnalyzeMedia,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) {
                            Text("Import Video Clip", style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onStopAndEvaluate,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                        ) {
                            Text(
                                text = "Stop & Evaluate (${overlay.recordedFramesCount} frames)",
                                color = AthletiqColors.CrimsonText,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PoseSkeletonOverlay(
    landmarks: List<LandmarkPoint3D>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (landmarks.size != PoseLandmarkIndex.LANDMARK_COUNT) return@Canvas
        for ((aIdx, bIdx) in POSE_EDGES) {
            val a = landmarks[aIdx]
            val b = landmarks[bIdx]
            if (a.visibility >= 0.45 && b.visibility >= 0.45) {
                drawLine(
                    color = AthletiqColors.EmeraldValid,
                    start = Offset((a.x * size.width).toFloat(), (a.y * size.height).toFloat()),
                    end = Offset((b.x * size.width).toFloat(), (b.y * size.height).toFloat()),
                    strokeWidth = 4f,
                )
            }
        }
        for (lm in landmarks) {
            if (lm.visibility >= 0.45) {
                drawCircle(
                    color = Color.White,
                    radius = 6f,
                    center = Offset((lm.x * size.width).toFloat(), (lm.y * size.height).toFloat()),
                )
            }
        }
    }
}
