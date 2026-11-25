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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.athletiq.app.state.AthletiqMainUiState
import com.athletiq.app.ui.components.AthletiqTopBar
import com.athletiq.app.ui.components.DarkTelemetryContainer
import com.athletiq.app.ui.components.EmptyStateCard
import com.athletiq.app.ui.components.PillTone
import com.athletiq.app.ui.components.StatusPill
import com.athletiq.app.ui.components.TelemetryCard
import com.athletiq.app.ui.theme.AthletiqColors
import com.athletiq.core.database.ExaminationEntity
import com.athletiq.core.domain.ScoringBand
import com.athletiq.core.model.MeasurementUnit
import com.athletiq.core.model.TestType
import com.athletiq.core.model.UserRole

@Composable
fun AdminSecurityAndReportsScreen(
    state: AthletiqMainUiState,
    onSyncNow: () -> Unit,
    onSetRetentionDays: (Int) -> Unit,
    onSetUploadConsent: (Boolean) -> Unit,
    onPurgeExpiredVideos: () -> Unit,
    onDeleteAllVideos: () -> Unit,
    onExportExamPdf: (ExaminationEntity) -> Unit,
    onCreateStandard: (
        name: String,
        testType: TestType,
        ageMin: Int,
        ageMax: Int,
        gender: String?,
        discipline: String?,
        unit: MeasurementUnit,
        direction: String,
        bands: List<ScoringBand>,
        citation: String,
    ) -> Unit,
    onLogout: () -> Unit,
) {
    var showCreateStandard by remember { mutableStateOf(false) }
    val isAdmin = state.session?.role == UserRole.ADMIN
    val storageKb = state.videoStorage.totalBytes / 1024.0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AthletiqColors.SurfaceCanvas),
    ) {
        AthletiqTopBar(
            title = "Reports, Standards & Security",
            subtitle = state.session?.institutionName ?: "Governance",
            pendingSyncCount = state.pendingSyncCount,
            onSyncClick = onSyncNow,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                DarkTelemetryContainer {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Local Data Protection",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                        StatusPill(text = "SQLCipher + Keystore", tone = PillTone.VALID)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Database encrypted via SQLCipher 4.20 with a 256-bit key wrapped by AndroidKeyStore AES-GCM. JWT credentials are stored encrypted at rest.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }

            item {
                TelemetryCard {
                    Text(
                        text = "Encrypted Video Storage Governance",
                        style = MaterialTheme.typography.titleMedium,
                        color = AthletiqColors.TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${state.videoStorage.encryptedFileCount} encrypted clip(s) stored locally (${"%.1f".format(storageKb)} KB)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AthletiqColors.TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Retention Window",
                        style = MaterialTheme.typography.labelMedium,
                        color = AthletiqColors.TextMuted,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(7, 30, 90).forEach { days ->
                            FilterChip(
                                selected = state.videoRetentionDays == days,
                                onClick = { onSetRetentionDays(days) },
                                label = { Text("$days Days") },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Opt-In Server Video Upload",
                                style = MaterialTheme.typography.titleMedium,
                                color = AthletiqColors.TextPrimary,
                            )
                            Text(
                                text = "Off by default; raw clips stay on device unless explicitly authorized",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AthletiqColors.TextSecondary,
                            )
                        }
                        Switch(
                            checked = state.videoUploadConsentOptIn,
                            onCheckedChange = onSetUploadConsent,
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = onPurgeExpiredVideos,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Purge Expired", style = MaterialTheme.typography.labelLarge)
                        }
                        OutlinedButton(
                            onClick = onDeleteAllVideos,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Delete All", color = AthletiqColors.CrimsonText, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Institutional PDF Reports",
                    style = MaterialTheme.typography.titleMedium,
                    color = AthletiqColors.TextPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (state.examinations.isEmpty()) {
                    EmptyStateCard(
                        title = "No Examination Reports Yet",
                        message = "Create an examination and approve attempts to export institutional PDF summaries.",
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.examinations.forEach { exam ->
                            val approvedCount = state.attempts.count {
                                it.examinationId == exam.id && it.approvalStatus == "APPROVED"
                            }
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
                                            text = "$approvedCount approved attempt(s)",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = AthletiqColors.TextSecondary,
                                        )
                                    }
                                    Button(
                                        onClick = { onExportExamPdf(exam) },
                                        colors = ButtonDefaults.buttonColors(containerColor = AthletiqColors.PrimaryCobalt),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Text("Export PDF", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
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
                        text = "Configurable Scoring Standards",
                        style = MaterialTheme.typography.titleMedium,
                        color = AthletiqColors.TextPrimary,
                    )
                    if (isAdmin) {
                        OutlinedButton(onClick = { showCreateStandard = true }) {
                            Text("Add Standard", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (state.standards.isEmpty()) {
                    EmptyStateCard(
                        title = "No Scoring Standards Configured",
                        message = "Administrators configure official age/gender/discipline rubrics with explicit source citations. AthletiQ never invents qualification cutoffs.",
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.standards.forEach { std ->
                            TelemetryCard {
                                Text(
                                    text = std.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AthletiqColors.TextPrimary,
                                )
                                Text(
                                    text = "${std.testType} • Age ${std.ageMin}-${std.ageMax} • ${std.genderCategory ?: "All"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AthletiqColors.TextSecondary,
                                )
                                Text(
                                    text = "Source: ${std.sourceCitation}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AthletiqColors.TextMuted,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Local Audit Log (${state.auditLogs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = AthletiqColors.TextPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (state.auditLogs.isEmpty()) {
                    EmptyStateCard(
                        title = "Audit Trail Empty",
                        message = "All registrations, calibrations, recorded attempts, and approvals are logged here.",
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.auditLogs.take(15).forEach { log ->
                            TelemetryCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = log.action,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = AthletiqColors.TextPrimary,
                                    )
                                    Text(
                                        text = log.resourceType,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = AthletiqColors.TextMuted,
                                    )
                                }
                                Text(
                                    text = log.detailsJson,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AthletiqColors.TextSecondary,
                                )
                            }
                        }
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = onLogout,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text("Sign Out", color = AthletiqColors.CrimsonText, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    if (showCreateStandard) {
        CreateStandardDialog(
            onDismiss = { showCreateStandard = false },
            onCreate = { name, testType, ageMin, ageMax, gender, unit, dir, bands, citation ->
                onCreateStandard(name, testType, ageMin, ageMax, gender, null, unit, dir, bands, citation)
                showCreateStandard = false
            },
        )
    }
}

@Composable
private fun CreateStandardDialog(
    onDismiss: () -> Unit,
    onCreate: (
        name: String,
        testType: TestType,
        ageMin: Int,
        ageMax: Int,
        gender: String?,
        unit: MeasurementUnit,
        direction: String,
        bands: List<ScoringBand>,
        citation: String,
    ) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var testType by remember { mutableStateOf(TestType.VERTICAL_JUMP) }
    var ageMinText by remember { mutableStateOf("14") }
    var ageMaxText by remember { mutableStateOf("18") }
    var gender by remember { mutableStateOf("") }
    var qualifyThresholdText by remember { mutableStateOf("35.0") }
    var citation by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Configurable Standard") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Standard Name *") }, singleLine = true)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(TestType.entries) { tt ->
                        FilterChip(
                            selected = tt == testType,
                            onClick = { testType = tt },
                            label = { Text(tt.name.replace('_', ' ')) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ageMinText,
                        onValueChange = { ageMinText = it },
                        label = { Text("Min Age") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = ageMaxText,
                        onValueChange = { ageMaxText = it },
                        label = { Text("Max Age") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                OutlinedTextField(value = gender, onValueChange = { gender = it }, label = { Text("Gender (optional)") }, singleLine = true)
                OutlinedTextField(
                    value = qualifyThresholdText,
                    onValueChange = { qualifyThresholdText = it },
                    label = { Text(if (testType == TestType.SPRINT) "Max Qualifying Time (s)" else "Min Qualifying Value (cm)") },
                    singleLine = true,
                )
                OutlinedTextField(value = citation, onValueChange = { citation = it }, label = { Text("Official Source Citation *") }, singleLine = true)
            }
        },
        confirmButton = {
            val minAge = ageMinText.toIntOrNull()
            val maxAge = ageMaxText.toIntOrNull()
            val threshold = qualifyThresholdText.toDoubleOrNull()
            Button(
                onClick = {
                    if (minAge != null && maxAge != null && threshold != null) {
                        val isSprint = testType == TestType.SPRINT
                        val unit = if (isSprint) MeasurementUnit.SECONDS else MeasurementUnit.CENTIMETERS
                        val dir = if (isSprint) "lower_is_better" else "higher_is_better"
                        val bands = if (isSprint) {
                            listOf(
                                ScoringBand("Qualified", 0.0, threshold, 100.0, qualified = true),
                                ScoringBand("Developing", threshold + 0.01, null, 50.0, qualified = false),
                            )
                        } else {
                            listOf(
                                ScoringBand("Developing", 0.0, (threshold - 0.01).coerceAtLeast(0.0), 50.0, qualified = false),
                                ScoringBand("Qualified", threshold, null, 100.0, qualified = true),
                            )
                        }
                        onCreate(name, testType, minAge, maxAge, gender.ifBlank { null }, unit, dir, bands, citation)
                    }
                },
                enabled = name.isNotBlank() && citation.isNotBlank() && minAge != null && maxAge != null && maxAge >= minAge && threshold != null,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
