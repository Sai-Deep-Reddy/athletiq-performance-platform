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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.athletiq.app.state.AthletiqMainUiState
import com.athletiq.app.ui.components.AthletiqTopBar
import com.athletiq.app.ui.components.EmptyStateCard
import com.athletiq.app.ui.components.MetricReadout
import com.athletiq.app.ui.components.PillTone
import com.athletiq.app.ui.components.StatusPill
import com.athletiq.app.ui.components.TelemetryCard
import com.athletiq.app.ui.components.UnavailableBanner
import com.athletiq.app.ui.theme.AthletiqColors
import com.athletiq.core.biomechanics.LandingStabilityStatus
import com.athletiq.core.database.AttemptEntity
import com.athletiq.core.model.UserRole

@Composable
fun PostLandingInspectionScreen(
    state: AthletiqMainUiState,
    onBack: () -> Unit,
    onApprove: (AttemptEntity, String?) -> Unit,
    onReject: (AttemptEntity, String?) -> Unit,
) {
    val bundle = state.activeInspection
    val canReview = state.session?.role in setOf(UserRole.ADMIN, UserRole.INSTRUCTOR)
    var reviewNote by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AthletiqColors.SurfaceCanvas),
    ) {
        AthletiqTopBar(
            title = "Broad Jump Landing Audit",
            subtitle = "Post-Landing Contact & Stability Rules",
            pendingSyncCount = state.pendingSyncCount,
            onBackClick = onBack,
        )

        if (bundle == null) {
            Column(modifier = Modifier.padding(16.dp)) {
                EmptyStateCard(
                    title = "No Broad Jump Attempt Loaded",
                    message = "Select a standing broad jump attempt to inspect heel, shoe-sole, hand, or backward contact details.",
                )
            }
            return
        }

        val attempt = bundle.attempt
        val detail = bundle.broadJumpDetail

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                TelemetryCard {
                    MetricReadout(
                        label = "Measured Broad Jump Distance",
                        valueText = "%.1f".format(attempt.value),
                        unitText = attempt.unit,
                        uncertaintyText = "± ${"%.1f".format(attempt.uncertainty)} cm • Validity: ${attempt.validityStatus}",
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (detail != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusPill(
                                text = "Contact: ${detail.landingContactKind.name}",
                                tone = if (detail.stabilityStatus == LandingStabilityStatus.STABLE) PillTone.VALID else PillTone.WARNING,
                            )
                            StatusPill(
                                text = detail.stabilityStatus.name,
                                tone = if (detail.stabilityStatus == LandingStabilityStatus.STABLE) PillTone.VALID else PillTone.ERROR,
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Protocol: ${detail.protocolUsed.name} | Start Toe: ${"%.1f".format(detail.startingFootXCm)} cm | Initial Landing: ${"%.1f".format(detail.initialLandingXCm)} cm | Nearest Contact: ${"%.1f".format(detail.nearestContactXCm)} cm",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AthletiqColors.TextSecondary,
                        )
                    } else {
                        Text(
                            text = "Stored raw summary: ${attempt.rawMetricsJson}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AthletiqColors.TextSecondary,
                        )
                    }
                }
            }

            if (attempt.validityStatus == "FLAGGED") {
                item {
                    UnavailableBanner(
                        title = "Examiner Review Required",
                        message = "System detected a questionable landing contact (hand touch, backward body contact, or post-landing backward step). Confirm or reject this attempt.",
                        tone = PillTone.WARNING,
                    )
                }
            }

            if (canReview) {
                item {
                    TelemetryCard {
                        Text(
                            text = "Examiner Verdict",
                            style = MaterialTheme.typography.titleMedium,
                            color = AthletiqColors.TextPrimary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = reviewNote,
                            onValueChange = { reviewNote = it },
                            label = { Text("Audit Note (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = { onApprove(attempt, reviewNote.ifBlank { null }) },
                                colors = ButtonDefaults.buttonColors(containerColor = AthletiqColors.PrimaryCobalt),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                            ) {
                                Text("Approve Result", style = MaterialTheme.typography.labelLarge)
                            }
                            OutlinedButton(
                                onClick = { onReject(attempt, reviewNote.ifBlank { null }) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                            ) {
                                Text("Disqualify", color = AthletiqColors.CrimsonText, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
