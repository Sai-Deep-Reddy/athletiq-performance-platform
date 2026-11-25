package com.athletiq.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.athletiq.app.state.AthletiqMainUiState
import com.athletiq.app.ui.components.AthletiqTopBar
import com.athletiq.app.ui.components.DarkTelemetryContainer
import com.athletiq.app.ui.components.MetricReadout
import com.athletiq.app.ui.components.PillTone
import com.athletiq.app.ui.components.StatusPill
import com.athletiq.app.ui.components.TelemetryCard
import com.athletiq.app.ui.components.UnavailableBanner
import com.athletiq.app.ui.theme.AthletiqColors
import com.athletiq.core.camera.DeviceIntrinsicsStatus
import com.athletiq.core.camera.DualModeCameraController
import com.athletiq.core.model.CalibrationPlaneType
import java.util.concurrent.atomic.AtomicReference

@Composable
fun CalibrationScreen(
    state: AthletiqMainUiState,
    onBack: () -> Unit,
    onSelectPlane: (CalibrationPlaneType) -> Unit,
    onCalibrateBitmap: (Bitmap) -> Unit,
    onProceedToCapture: () -> Unit,
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

    val latestFrameRef = remember { AtomicReference<Bitmap?>(null) }
    val controller = remember { DualModeCameraController(context) }
    val imageImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
            if (decoded != null) {
                val argb = if (decoded.config != Bitmap.Config.ARGB_8888) {
                    decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
                } else {
                    decoded
                }
                onCalibrateBitmap(argb)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            controller.release()
            latestFrameRef.getAndSet(null)?.recycle()
        }
    }

    val calState = state.liveCalibration
    val activeSaved = state.calibrations.firstOrNull {
        it.planeType == calState.selectedPlane.name && it.isActive
    }
    val caps = state.cameraCapabilities

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AthletiqColors.SurfaceCanvas),
    ) {
        AthletiqTopBar(
            title = "Camera Calibration",
            subtitle = "ChArUco Optical Quality Gate",
            pendingSyncCount = state.pendingSyncCount,
            onBackClick = onBack,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = calState.selectedPlane == CalibrationPlaneType.VERTICAL_PLANE,
                        onClick = { onSelectPlane(CalibrationPlaneType.VERTICAL_PLANE) },
                        label = { Text("Vertical Plane (Height / VJ)", style = MaterialTheme.typography.labelLarge) },
                    )
                    FilterChip(
                        selected = calState.selectedPlane == CalibrationPlaneType.GROUND_PLANE,
                        onClick = { onSelectPlane(CalibrationPlaneType.GROUND_PLANE) },
                        label = { Text("Ground Plane (Broad / Sprint)", style = MaterialTheme.typography.labelLarge) },
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
                            text = if (calState.selectedPlane == CalibrationPlaneType.VERTICAL_PLANE) {
                                "Aim at wall ChArUco target (5x7, 4cm)"
                            } else {
                                "Aim at floor ChArUco target (5x7, 4cm)"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                        )
                        StatusPill(
                            text = if (calState.isGatePassed || activeSaved != null) "Gate Ready" else "Needs Target",
                            tone = if (calState.isGatePassed || activeSaved != null) PillTone.VALID else PillTone.WARNING,
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    if (!hasCameraPermission) {
                        UnavailableBanner(
                            title = "Camera Permission Required",
                            message = "Grant camera access to preview and detect ChArUco calibration boards.",
                            tone = PillTone.WARNING,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Grant Camera Permission")
                        }
                    } else if (caps == null) {
                        UnavailableBanner(
                            title = "Back Camera Unavailable",
                            message = "No back-facing Camera2 device detected on this hardware.",
                            tone = PillTone.ERROR,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .clip(RoundedCornerShape(16.dp)),
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    PreviewView(ctx).also { previewView ->
                                        controller.bindLiveAnalysisAndVideo(
                                            lifecycleOwner = lifecycleOwner,
                                            surfaceProvider = previewView.surfaceProvider,
                                            capabilities = caps,
                                            onFrame = { proxy, _ ->
                                                val bmp = proxy.toBitmap()
                                                latestFrameRef.getAndSet(bmp)?.recycle()
                                            },
                                            onError = {},
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            item {
                TelemetryCard {
                    Text(
                        text = "Reprojection Quality Gate",
                        style = MaterialTheme.typography.titleMedium,
                        color = AthletiqColors.TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val rms = calState.lastReprojectionErrorPx ?: activeSaved?.reprojectionErrorPx
                    MetricReadout(
                        label = "RMS Reprojection Error (Limit <= 2.00 px)",
                        valueText = rms?.let { "%.2f".format(it) } ?: "--",
                        unitText = "px",
                        uncertaintyText = calState.latestOutcomeMessage
                            ?: activeSaved?.let { "Saved active ${it.planeType} calibration" }
                            ?: "Capture a clear ChArUco frame to compute homography",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Hardware FPS Capability",
                                style = MaterialTheme.typography.labelMedium,
                                color = AthletiqColors.TextMuted,
                            )
                            Text(
                                text = when {
                                    caps == null -> "Unavailable"
                                    caps.supportsHighSpeed120FpsVideo -> "120+ FPS Video + ${caps.negotiatedAnalysisFpsRange.upper} FPS Live"
                                    else -> "${caps.negotiatedAnalysisFpsRange.upper} FPS Live"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = AthletiqColors.TextPrimary,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Device Intrinsics (K)",
                                style = MaterialTheme.typography.labelMedium,
                                color = AthletiqColors.TextMuted,
                            )
                            Text(
                                text = when (val intr = caps?.deviceIntrinsics) {
                                    is DeviceIntrinsicsStatus.Available -> "fx=${"%.0f".format(intr.fx)}, fy=${"%.0f".format(intr.fy)}"
                                    is DeviceIntrinsicsStatus.Unavailable -> "Not populated (Planar ChArUco used)"
                                    null -> "Unavailable"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = AthletiqColors.TextPrimary,
                            )
                        }
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = {
                        latestFrameRef.get()?.let { bmp ->
                            onCalibrateBitmap(bmp.copy(Bitmap.Config.ARGB_8888, false))
                        }
                    },
                    enabled = hasCameraPermission && caps != null,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text("Capture & Verify ChArUco Target", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { imageImportLauncher.launch(arrayOf("image/*")) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text("Import ChArUco Board Image", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onProceedToCapture,
                    enabled = calState.isGatePassed || activeSaved != null,
                    colors = ButtonDefaults.buttonColors(containerColor = AthletiqColors.PrimaryCobalt),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text("Lock Calibration & Proceed to Capture", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
