package com.athletiq.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.Height
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.athletiq.app.state.AthletiqMainUiState
import com.athletiq.app.ui.components.AthletiqTopBar
import com.athletiq.app.ui.components.EmptyStateCard
import com.athletiq.app.ui.components.PillTone
import com.athletiq.app.ui.components.StatusPill
import com.athletiq.app.ui.components.TelemetryCard
import com.athletiq.app.ui.components.UnavailableBanner
import com.athletiq.app.ui.theme.AthletiqColors
import com.athletiq.core.model.CalibrationPlaneType
import com.athletiq.core.model.TestType

@Composable
fun DashboardScreen(
    state: AthletiqMainUiState,
    onSyncNow: () -> Unit,
    onOpenCalibration: (CalibrationPlaneType) -> Unit,
    onLaunchTest: (TestType) -> Unit,
    onOpenExamRunner: (String) -> Unit,
    onOpenRoster: () -> Unit,
) {
    val session = state.session
    val verticalCal = state.calibrations.firstOrNull {
        it.planeType == CalibrationPlaneType.VERTICAL_PLANE.name && it.isActive
    }
    val groundCal = state.calibrations.firstOrNull {
        it.planeType == CalibrationPlaneType.GROUND_PLANE.name && it.isActive
    }
    val caps = state.cameraCapabilities
    val fpsLabel = when {
        caps == null -> "Camera status pending"
        caps.supportsHighSpeed120FpsVideo -> "120 FPS Video Capable"
        caps.supportsFixed60FpsAnalysis -> "60 FPS Live Analysis"
        else -> "${caps.negotiatedAnalysisFpsRange.upper} FPS Mode"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AthletiqColors.SurfaceCanvas),
    ) {
        AthletiqTopBar(
            title = "Performance Dashboard",
            subtitle = "${session?.institutionName ?: "AthletiQ"} • ${session?.role?.name ?: ""}",
            pendingSyncCount = state.pendingSyncCount,
            onSyncClick = onSyncNow,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Surface(
                    color = AthletiqColors.SurfaceSoftBlue,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = session?.userName ?: "Staff Session",
                                style = MaterialTheme.typography.titleMedium,
                                color = AthletiqColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${state.students.size} registered athlete(s) • ${state.examinations.size} exam(s)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AthletiqColors.TextSecondary,
                            )
                        }
                        StatusPill(text = fpsLabel, tone = PillTone.INFO)
                    }
                }
            }

            if (!state.visionAssets.isPoseReady) {
                item {
                    UnavailableBanner(
                        title = "MediaPipe Model Assets Not Bundled",
                        message = "Place pose_landmarker_full.task (and optionally hand_landmarker.task, efficientdet_lite0.tflite) in app/src/main/assets/ before running live pose inference.",
                        tone = PillTone.WARNING,
                    )
                }
            }

            item {
                Text(
                    text = "Optical Plane Calibration",
                    style = MaterialTheme.typography.titleMedium,
                    color = AthletiqColors.TextPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CalibrationStatusCard(
                        title = "Vertical-Plane Calibration",
                        subtitle = "Required for Standing Height & Vertical Jump",
                        reprojectionErrorPx = verticalCal?.reprojectionErrorPx,
                        method = verticalCal?.method,
                        onCalibrate = { onOpenCalibration(CalibrationPlaneType.VERTICAL_PLANE) },
                    )
                    CalibrationStatusCard(
                        title = "Ground-Plane Calibration",
                        subtitle = "Required for Standing Broad Jump & Sprint Timing",
                        reprojectionErrorPx = groundCal?.reprojectionErrorPx,
                        method = groundCal?.method,
                        onCalibrate = { onOpenCalibration(CalibrationPlaneType.GROUND_PLANE) },
                    )
                }
            }

            item {
                Text(
                    text = "Core Assessments",
                    style = MaterialTheme.typography.titleMedium,
                    color = AthletiqColors.TextPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AssessmentLaunchTile(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Height,
                            title = "Standing Height",
                            subtitle = "Head-ROI + Floor",
                            calibrated = verticalCal != null,
                            onClick = { onLaunchTest(TestType.HEIGHT) },
                        )
                        AssessmentLaunchTile(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.DirectionsRun,
                            title = "Vertical Jump",
                            subtitle = "Reach & Flight Time",
                            calibrated = verticalCal != null,
                            onClick = { onLaunchTest(TestType.VERTICAL_JUMP) },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AssessmentLaunchTile(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Straighten,
                            title = "Broad Jump",
                            subtitle = "Full-Contact Landing",
                            calibrated = groundCal != null,
                            onClick = { onLaunchTest(TestType.STANDING_BROAD_JUMP) },
                        )
                        AssessmentLaunchTile(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Timer,
                            title = "Sprint Timing",
                            subtitle = "Start/Finish Gates",
                            calibrated = groundCal != null,
                            onClick = { onLaunchTest(TestType.SPRINT) },
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Examinations",
                        style = MaterialTheme.typography.titleMedium,
                        color = AthletiqColors.TextPrimary,
                    )
                    FilledTonalButton(onClick = onOpenRoster) {
                        Text("Athlete Roster", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (state.examinations.isEmpty()) {
                    EmptyStateCard(
                        icon = Icons.Rounded.Assignment,
                        title = "No Examinations Created",
                        message = "Create an examination in the Runner / Exams tab and register athletes to begin verified multi-attempt scoring.",
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.examinations.forEach { exam ->
                            val examAttempts = state.attempts.count { it.examinationId == exam.id }
                            TelemetryCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = exam.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = AthletiqColors.TextPrimary,
                                        )
                                        Text(
                                            text = "$examAttempts recorded attempt(s) • ${exam.status}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = AthletiqColors.TextSecondary,
                                        )
                                    }
                                    OutlinedButton(onClick = { onOpenExamRunner(exam.id) }) {
                                        Text("Open", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalibrationStatusCard(
    title: String,
    subtitle: String,
    reprojectionErrorPx: Double?,
    method: String?,
    onCalibrate: () -> Unit,
) {
    TelemetryCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AthletiqColors.TextPrimary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AthletiqColors.TextSecondary,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (reprojectionErrorPx != null) {
                StatusPill(
                    text = "${"%.2f".format(reprojectionErrorPx)} px RMS",
                    tone = PillTone.VALID,
                )
            } else {
                StatusPill(text = "Required", tone = PillTone.ERROR)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = method ?: "Uncalibrated",
                style = MaterialTheme.typography.labelMedium,
                color = AthletiqColors.TextMuted,
            )
            Button(
                onClick = onCalibrate,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (reprojectionErrorPx == null) AthletiqColors.PrimaryCobalt else AthletiqColors.SurfaceSoftBlue,
                    contentColor = if (reprojectionErrorPx == null) androidx.compose.ui.graphics.Color.White else AthletiqColors.PrimaryDark,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Rounded.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (reprojectionErrorPx == null) "Calibrate" else "Recalibrate",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun AssessmentLaunchTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    calibrated: Boolean,
    onClick: () -> Unit,
) {
    TelemetryCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AthletiqColors.PrimaryCobalt,
                modifier = Modifier.size(24.dp),
            )
            StatusPill(
                text = if (calibrated) "Ready" else "Calibrate",
                tone = if (calibrated) PillTone.VALID else PillTone.WARNING,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = AthletiqColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = AthletiqColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = AthletiqColors.PrimaryCobalt),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
        ) {
            Text("Launch", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}
