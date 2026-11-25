package com.athletiq.app.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.athletiq.app.state.AthletiqMainUiState
import com.athletiq.app.ui.components.AthletiqTopBar
import com.athletiq.app.ui.components.DarkTelemetryContainer
import com.athletiq.app.ui.components.EmptyStateCard
import com.athletiq.app.ui.components.MetricReadout
import com.athletiq.app.ui.components.PillTone
import com.athletiq.app.ui.components.StatusPill
import com.athletiq.app.ui.components.TelemetryCard
import com.athletiq.app.ui.components.UnavailableBanner
import com.athletiq.app.ui.theme.AthletiqColors
import com.athletiq.core.database.AttemptEntity
import com.athletiq.core.model.ApprovalStatus
import com.athletiq.core.model.UserRole

@Composable
fun AttemptReviewScreen(
    state: AthletiqMainUiState,
    onBack: () -> Unit,
    onApprove: (AttemptEntity) -> Unit,
    onReject: (AttemptEntity) -> Unit,
    onRetake: () -> Unit,
    onOpenBroadJumpInspection: () -> Unit,
) {
    val bundle = state.activeInspection
    val canReview = state.session?.role in setOf(UserRole.ADMIN, UserRole.INSTRUCTOR)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AthletiqColors.SurfaceCanvas),
    ) {
        AthletiqTopBar(
            title = "Attempt & Biomechanics Review",
            subtitle = bundle?.attempt?.testType?.replace('_', ' ') ?: "No Attempt Selected",
            pendingSyncCount = state.pendingSyncCount,
            onBackClick = onBack,
        )

        if (bundle == null) {
            BoxEmptyReview(onBack)
            return
        }

        val attempt = bundle.attempt
        val bio = bundle.biomechanics
        val frames = bio?.frameKinematics.orEmpty()
        var selectedFrameIdx by remember(attempt.clientUuid) { mutableIntStateOf(0) }
        val activeFrame = frames.getOrNull(selectedFrameIdx)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                TelemetryCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusPill(
                            text = attempt.validityStatus,
                            tone = when (attempt.validityStatus) {
                                "VALID" -> PillTone.VALID
                                "FLAGGED" -> PillTone.WARNING
                                else -> PillTone.ERROR
                            },
                        )
                        StatusPill(
                            text = attempt.approvalStatus,
                            tone = when (attempt.approvalStatus) {
                                "APPROVED" -> PillTone.VALID
                                "REJECTED" -> PillTone.ERROR
                                else -> PillTone.INFO
                            },
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    MetricReadout(
                        label = "${attempt.testType.replace('_', ' ')} Result",
                        valueText = "%.2f".format(attempt.value),
                        unitText = attempt.unit,
                        uncertaintyText = "Uncertainty: ± ${"%.2f".format(attempt.uncertainty)} • Confidence: ${"%.0f".format(attempt.confidenceScore * 100)}%",
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { StatusPill(text = "${"%.1f".format(attempt.fpsActual)} FPS", tone = PillTone.INFO) }
                        item { StatusPill(text = "Drops: ${attempt.droppedFrames}", tone = if (attempt.droppedFrames == 0) PillTone.VALID else PillTone.WARNING) }
                        item { StatusPill(text = attempt.landmarkProvenance, tone = PillTone.NEUTRAL) }
                    }

                    bundle.verticalJumpDetail?.let { vj ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Standing Reach: ${"%.1f".format(vj.standingReachCm)} cm • Max Reach: ${"%.1f".format(vj.maxJumpReachCm)} cm • Flight-Time Cross-Check: ${"%.1f".format(vj.flightTimeEstimate.flightHeightCm)} cm (${"%.3f".format(vj.flightTimeEstimate.flightTimeSec)} s)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AthletiqColors.TextSecondary,
                        )
                    }
                    bundle.sprintDetail?.let { sp ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Distance: ${"%.1f".format(sp.distanceMeters)} m • Average Speed: ${"%.2f".format(sp.averageSpeedMps)} m/s",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AthletiqColors.TextSecondary,
                        )
                    }
                }
            }

            if (attempt.testType == "STANDING_BROAD_JUMP") {
                item {
                    OutlinedButton(
                        onClick = onOpenBroadJumpInspection,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Inspect Landing Contact & Fault Details", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            if (bio != null && frames.isNotEmpty()) {
                item {
                    DarkTelemetryContainer {
                        Text(
                            text = "COM Trajectory & Keyframes (${frames.size} frames)",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                        ) {
                            val comPoints = frames.mapNotNull { it.comProxyNorm }
                            if (comPoints.size >= 2) {
                                val path = Path()
                                comPoints.forEachIndexed { idx, pt ->
                                    val x = (idx.toFloat() / (comPoints.size - 1).coerceAtLeast(1)) * size.width
                                    val y = (pt.y * size.height).toFloat()
                                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                drawPath(path = path, color = AthletiqColors.EmeraldValid, style = Stroke(width = 5f))
                                activeFrame?.comProxyNorm?.let { cur ->
                                    val cx = (selectedFrameIdx.toFloat() / (frames.size - 1).coerceAtLeast(1)) * size.width
                                    val cy = (cur.y * size.height).toFloat()
                                    drawCircle(color = Color.White, radius = 8f, center = Offset(cx, cy))
                                }
                            }
                        }

                        if (frames.size > 1) {
                            Slider(
                                value = selectedFrameIdx.toFloat(),
                                onValueChange = { selectedFrameIdx = it.toInt().coerceIn(0, frames.lastIndex) },
                                valueRange = 0f..frames.lastIndex.toFloat(),
                            )
                        }

                        val keyframeButtons = listOfNotNull(
                            "Stance" to bio.keyFrames.initialStanceIndex,
                            bio.keyFrames.deepestCountermovementIndex?.let { "Dip" to it },
                            bio.keyFrames.takeoffIndex?.let { "Take-Off" to it },
                            bio.keyFrames.apexIndex?.let { "Apex" to it },
                            bio.keyFrames.landingIndex?.let { "Landing" to it },
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(keyframeButtons) { (label, idx) ->
                                FilterChip(
                                    selected = selectedFrameIdx == idx,
                                    onClick = { selectedFrameIdx = idx },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }

                item {
                    TelemetryCard {
                        Text(
                            text = "Frame Joint Kinematics (Frame #${selectedFrameIdx + 1})",
                            style = MaterialTheme.typography.titleMedium,
                            color = AthletiqColors.TextPrimary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        JointRow("Knee Flexion", activeFrame?.leftKneeDeg, activeFrame?.rightKneeDeg)
                        JointRow("Hip Flexion", activeFrame?.leftHipDeg, activeFrame?.rightHipDeg)
                        JointRow("Shoulder Elevation", activeFrame?.leftShoulderElevationDeg, activeFrame?.rightShoulderElevationDeg)
                        JointRow("Ankle Angle", activeFrame?.leftAnkleDeg, activeFrame?.rightAnkleDeg)
                        Text(
                            text = "Trunk Lean: ${activeFrame?.trunkLeanDeg?.let { "${"%.1f".format(it)} deg" } ?: "N/A (Occluded)"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AthletiqColors.TextSecondary,
                        )
                        if (bio.indicators.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            bio.indicators.forEach { ind ->
                                Text(
                                    text = "• ${ind.summary}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AthletiqColors.TextPrimary,
                                )
                            }
                        }
                    }
                }
            }

            item {
                UnavailableBanner(
                    title = "Biomechanical Notice",
                    message = "Performance indicators only — not medical diagnoses.",
                    tone = PillTone.INFO,
                )
            }

            if (canReview) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { onApprove(attempt) },
                            enabled = attempt.approvalStatus != ApprovalStatus.APPROVED.name,
                            colors = ButtonDefaults.buttonColors(containerColor = AthletiqColors.PrimaryCobalt),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Text("Approve", style = MaterialTheme.typography.labelLarge)
                        }
                        OutlinedButton(
                            onClick = { onReject(attempt) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Text("Reject", color = AthletiqColors.CrimsonText, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onRetake,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Text("Retake Attempt", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun JointRow(label: String, leftDeg: Double?, rightDeg: Double?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = AthletiqColors.TextPrimary)
        Text(
            text = "L: ${leftDeg?.let { "${"%.1f".format(it)}°" } ?: "N/A"} | R: ${rightDeg?.let { "${"%.1f".format(it)}°" } ?: "N/A"}",
            style = MaterialTheme.typography.bodyMedium,
            color = AthletiqColors.TextSecondary,
        )
    }
}

@Composable
private fun BoxEmptyReview(onBack: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        EmptyStateCard(
            title = "No Attempt Selected",
            message = "Select a recorded attempt from Examination Runner or record a new assessment first.",
            action = {
                Button(onClick = onBack) { Text("Back") }
            },
        )
    }
}
