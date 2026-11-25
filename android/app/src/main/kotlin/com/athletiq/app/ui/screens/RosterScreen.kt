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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.athletiq.core.model.UserRole

@Composable
fun RosterScreen(
    state: AthletiqMainUiState,
    onSyncNow: () -> Unit,
    onRegisterStudent: (code: String, name: String, dobIso: String?, gender: String?, dept: String?) -> Unit,
    onSelectStudentForHistory: (String) -> Unit,
    onStartAssessmentForStudent: (String) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedDept by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    val canEditRoster = state.session?.role in setOf(UserRole.ADMIN, UserRole.INSTRUCTOR)
    val departments = remember(state.students) {
        state.students.mapNotNull { it.department }.distinct().sorted()
    }
    val filteredStudents = remember(state.students, searchQuery, selectedDept) {
        state.students.filter { stu ->
            val matchesQuery = searchQuery.isBlank() ||
                stu.fullName.contains(searchQuery, ignoreCase = true) ||
                stu.institutionStudentCode.contains(searchQuery, ignoreCase = true)
            val matchesDept = selectedDept == null || stu.department == selectedDept
            matchesQuery && matchesDept
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AthletiqColors.SurfaceCanvas),
    ) {
        AthletiqTopBar(
            title = "Athlete Roster",
            subtitle = "Examiner-Managed Institution Cohort",
            pendingSyncCount = state.pendingSyncCount,
            onSyncClick = onSyncNow,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${filteredStudents.size} of ${state.students.size} Athlete(s)",
                        style = MaterialTheme.typography.titleMedium,
                        color = AthletiqColors.TextPrimary,
                    )
                    if (canEditRoster) {
                        Button(
                            onClick = { showAddDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AthletiqColors.PrimaryCobalt),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Rounded.PersonAdd, contentDescription = null)
                            Spacer(modifier = Modifier.padding(horizontal = 3.dp))
                            Text("Add Athlete", style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        StatusPill(text = "Coach View-Only", tone = PillTone.NEUTRAL)
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    label = { Text("Search by Student ID or Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (departments.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = selectedDept == null,
                                onClick = { selectedDept = null },
                                label = { Text("All (${state.students.size})") },
                            )
                        }
                        items(departments) { dept ->
                            FilterChip(
                                selected = selectedDept == dept,
                                onClick = { selectedDept = dept },
                                label = { Text(dept) },
                            )
                        }
                    }
                }
            }

            if (filteredStudents.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Rounded.PersonAdd,
                        title = "No Athletes Registered",
                        message = "Examiners (Admin or PE Instructor) register students with their institution ID before running assessments.",
                    )
                }
            } else {
                items(filteredStudents, key = { it.id }) { student ->
                    val studentAttempts = state.attempts.filter { it.studentId == student.id }
                    val approvedCount = studentAttempts.count { it.approvalStatus == "APPROVED" }
                    TelemetryCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = student.fullName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AthletiqColors.TextPrimary,
                                )
                                Text(
                                    text = "ID: ${student.institutionStudentCode} • ${student.department ?: "No department"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AthletiqColors.TextSecondary,
                                )
                                if (student.genderCategory != null || student.dateOfBirthIso != null) {
                                    Text(
                                        text = listOfNotNull(student.genderCategory, student.dateOfBirthIso).joinToString(" • "),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = AthletiqColors.TextMuted,
                                    )
                                }
                            }
                            StatusPill(
                                text = "$approvedCount/${studentAttempts.size} Approved",
                                tone = if (approvedCount > 0) PillTone.VALID else PillTone.NEUTRAL,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = { onSelectStudentForHistory(student.id) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("History & PDF", style = MaterialTheme.typography.labelLarge)
                            }
                            Button(
                                onClick = { onStartAssessmentForStudent(student.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = AthletiqColors.PrimaryCobalt),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Assess", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddStudentDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { code, name, dob, gender, dept ->
                onRegisterStudent(code, name, dob, gender, dept)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun AddStudentDialog(
    onDismiss: () -> Unit,
    onConfirm: (code: String, name: String, dob: String?, gender: String?, dept: String?) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var dept by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Register Athlete") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Institution Student ID *") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Full Name *") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = dob,
                    onValueChange = { dob = it },
                    label = { Text("DOB (YYYY-MM-DD, optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = gender,
                    onValueChange = { gender = it },
                    label = { Text("Gender Category (optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = dept,
                    onValueChange = { dept = it },
                    label = { Text("Department / Squad (optional)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(code, name, dob.ifBlank { null }, gender.ifBlank { null }, dept.ifBlank { null }) },
                enabled = code.isNotBlank() && name.isNotBlank(),
            ) {
                Text("Register")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
