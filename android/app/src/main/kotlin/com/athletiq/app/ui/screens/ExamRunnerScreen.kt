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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.athletiq.app.state.AthletiqMainUiState
import com.athletiq.app.ui.components.AthletiqTopBar
import com.athletiq.app.ui.components.EmptyStateCard
import com.athletiq.app.ui.components.PillTone
import com.athletiq.app.ui.components.StatusPill
import com.athletiq.app.ui.components.TelemetryCard
import com.athletiq.app.ui.theme.AthletiqColors
import com.athletiq.core.database.AttemptEntity
import com.athletiq.core.model.ApprovalStatus
import com.athletiq.core.model.TestType
import com.athletiq.core.model.UserRole

@Composable
fun ExamRunnerScreen(
    state: AthletiqMainUiState,
    onSyncNow: () -> Unit,
    onSelectExam: (String) -> Unit,
    onSelectStudent: (String) -> Unit,
    onSelectTest: (TestType) -> Unit,
    onCreateExamination: (title: String, tests: List<TestType>) -> Unit,
    onStartCapture: () -> Unit,
    onInspectAttempt: (AttemptEntity) -> Unit,
    onApproveAttempt: (AttemptEntity) -> Unit,
    onRejectAttempt: (AttemptEntity) -> Unit,
) {
    var showCreateExam by remember { mutableStateOf(false) }
    val canManageExams = state.session?.role in setOf(UserRole.ADMIN, UserRole.INSTRUCTOR)
    val selectedExam = state.examinations.firstOrNull { it.id == state.selectedExamId }
    val selectedStudent = state.students.firstOrNull { it.id == state.selectedStudentId }

    val filteredAttempts = remember(state.attempts, state.selectedExamId, state.selectedStudentId, state.selectedTestType) {
        state.attempts.filter { att ->
            att.examinationId == state.selectedExamId &&
                att.studentId == state.selectedStudentId &&
                att.testType == state.selectedTestType.name
        }.sortedBy { it.recordedAtEpochMs }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AthletiqColors.SurfaceCanvas),
    ) {
        AthletiqTopBar(
            title = "Examination Runner",
            subtitle = selectedExam?.title ?: "Multi-Attempt Assessment Mode",
            pendingSyncCount = state.pendingSyncCount,
            onSyncClick = onSyncNow,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Active Examination",
                        style = MaterialTheme.typography.titleMedium,
                        color = AthletiqColors.TextPrimary,
                    )
                    if (canManageExams) {
                        OutlinedButton(onClick = { showCreateExam = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Text("New Exam", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (state.examinations.isEmpty()) {
                    EmptyStateCard(
                        icon = Icons.Rounded.Assignment,
                        title = "No Examinations Available",
                        message = "Create an examination to organize student tests, multiple attempts, and examiner approvals.",
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.examinations, key = { it.id }) { exam ->
                            FilterChip(
                                selected = exam.id == state.selectedExamId,
                                onClick = { onSelectExam(exam.id) },
                                label = { Text(exam.title) },
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Select Athlete",
                    style = MaterialTheme.typography.titleMedium,
                    color = AthletiqColors.TextPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (state.students.isEmpty()) {
                    EmptyStateCard(
                        title = "No Athletes Registered",
                        message = "Add athletes in the Roster tab before recording attempts.",
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.students, key = { it.id }) { student ->
                            FilterChip(
                                selected = student.id == state.selectedStudentId,
                                onClick = { onSelectStudent(student.id) },
                                label = { Text("${student.institutionStudentCode} • ${student.fullName}") },
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Test Protocol",
                    style = MaterialTheme.typography.titleMedium,
                    color = AthletiqColors.TextPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TestType.entries) { tt ->
                        FilterChip(
                            selected = tt == state.selectedTestType,
                            onClick = { onSelectTest(tt) },
                            label = { Text(tt.name.replace('_', ' ')) },
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = onStartCapture,
                    enabled = selectedExam != null && selectedStudent != null,
                    colors = ButtonDefaults.buttonColors(containerColor = AthletiqColors.PrimaryCobalt),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text(
                        text = "Record Attempt #${filteredAttempts.size + 1} (${state.selectedTestType.name.replace('_', ' ')})",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            item {
                Text(
                    text = "Recorded Attempts (${filteredAttempts.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = AthletiqColors.TextPrimary,
                )
            }

            if (filteredAttempts.isEmpty()) {
                item {
                    EmptyStateCard(
                        title = "No Attempts Recorded Yet",
                        message = "Recorded camera assessments for this athlete and test will appear here with best-attempt selection.",
                    )
                }
            } else {
                itemsIndexed(filteredAttempts, key = { _, a -> a.clientUuid }) { index, attempt ->
                    val validityTone = when (attempt.validityStatus) {
                        "VALID" -> PillTone.VALID
                        "FLAGGED" -> PillTone.WARNING
                        else -> PillTone.ERROR
                    }
                    val approvalTone = when (attempt.approvalStatus) {
                        "APPROVED" -> PillTone.VALID
                        "REJECTED" -> PillTone.ERROR
                        else -> PillTone.INFO
                    }
                    TelemetryCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Attempt ${index + 1}",
                                style = MaterialTheme.typography.titleMedium,
                                color = AthletiqColors.TextPrimary,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (attempt.isBestAttempt) {
                                    StatusPill(text = "Best Valid", tone = PillTone.VALID)
                                }
                                StatusPill(text = attempt.validityStatus, tone = validityTone)
                                StatusPill(text = attempt.approvalStatus, tone = approvalTone)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${"%.2f".format(attempt.value)} ${attempt.unit} (± ${"%.2f".format(attempt.uncertainty)})",
                            style = MaterialTheme.typography.displayMedium,
                            color = AthletiqColors.TextPrimary,
                        )
                        Text(
                            text = "Confidence: ${"%.0f".format(attempt.confidenceScore * 100)}% • ${"%.1f".format(attempt.fpsActual)} FPS • ${attempt.landmarkProvenance}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AthletiqColors.TextSecondary,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { onInspectAttempt(attempt) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Review", style = MaterialTheme.typography.labelLarge)
                            }
                            if (canManageExams && attempt.approvalStatus != ApprovalStatus.APPROVED.name) {
                                Button(
                                    onClick = { onApproveAttempt(attempt) },
                                    colors = ButtonDefaults.buttonColors(containerColor = AthletiqColors.EmeraldValid),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Approve", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            if (canManageExams && attempt.approvalStatus != ApprovalStatus.REJECTED.name) {
                                OutlinedButton(
                                    onClick = { onRejectAttempt(attempt) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Reject", color = AthletiqColors.CrimsonText, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateExam) {
        CreateExamDialog(
            onDismiss = { showCreateExam = false },
            onCreate = { title, tests ->
                onCreateExamination(title, tests)
                showCreateExam = false
            },
        )
    }
}

@Composable
private fun CreateExamDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, tests: List<TestType>) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var selectedTests by remember { mutableStateOf(setOf(TestType.HEIGHT, TestType.VERTICAL_JUMP, TestType.STANDING_BROAD_JUMP)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Examination") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Examination Title *") },
                    singleLine = true,
                )
                TestType.entries.forEach { tt ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = tt in selectedTests,
                            onCheckedChange = { checked ->
                                selectedTests = if (checked) selectedTests + tt else selectedTests - tt
                            },
                        )
                        Text(
                            text = tt.name.replace('_', ' '),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(title, selectedTests.toList()) },
                enabled = title.isNotBlank() && selectedTests.isNotEmpty(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
