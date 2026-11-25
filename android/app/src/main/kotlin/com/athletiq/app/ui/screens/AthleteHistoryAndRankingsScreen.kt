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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.athletiq.app.data.AthletiqRepository
import com.athletiq.app.state.AthletiqMainUiState
import com.athletiq.app.ui.components.AthletiqTopBar
import com.athletiq.app.ui.components.EmptyStateCard
import com.athletiq.app.ui.components.PillTone
import com.athletiq.app.ui.components.StatusPill
import com.athletiq.app.ui.components.TelemetryCard
import com.athletiq.app.ui.components.UnavailableBanner
import com.athletiq.app.ui.theme.AthletiqColors
import com.athletiq.core.database.StudentEntity
import com.athletiq.core.domain.ScoringEvaluationOutcome
import com.athletiq.core.domain.ScoringEvaluator
import com.athletiq.core.model.TestType
import java.time.LocalDate

@Composable
fun AthleteHistoryAndRankingsScreen(
    state: AthletiqMainUiState,
    repository: AthletiqRepository,
    onSyncNow: () -> Unit,
    onSelectStudent: (String) -> Unit,
    onExportStudentPdf: (StudentEntity) -> Unit,
) {
    var activeTab by remember { mutableIntStateOf(0) } // 0 = History, 1 = Cohort Rankings
    var rankingTestType by remember { mutableStateOf(TestType.VERTICAL_JUMP) }
    var rankingDeptFilter by remember { mutableStateOf<String?>(null) }

    val selectedStudent = state.students.firstOrNull { it.id == state.selectedStudentId }
    val rubrics = remember(state.standards) {
        state.standards.mapNotNull { repository.decodeRubric(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AthletiqColors.SurfaceCanvas),
    ) {
        AthletiqTopBar(
            title = if (activeTab == 0) "Athlete History & Trends" else "Cohort Rankings",
            subtitle = "Longitudinal Tracking & Talent Identification",
            pendingSyncCount = state.pendingSyncCount,
            onSyncClick = onSyncNow,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        label = { Text("Athlete History", style = MaterialTheme.typography.labelLarge) },
                    )
                    FilterChip(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        label = { Text("Cohort Rankings", style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }

            if (activeTab == 0) {
                if (state.students.isEmpty()) {
                    item {
                        EmptyStateCard(
                            title = "No Athletes Available",
                            message = "Register athletes in the Roster tab to view performance history and trend charts.",
                        )
                    }
                } else {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.students, key = { it.id }) { stu ->
                                FilterChip(
                                    selected = stu.id == selectedStudent?.id,
                                    onClick = { onSelectStudent(stu.id) },
                                    label = { Text("${stu.institutionStudentCode} • ${stu.fullName}") },
                                )
                            }
                        }
                    }

                    if (selectedStudent != null) {
                        val stuAttempts = state.attempts.filter {
                            it.studentId == selectedStudent.id &&
                                it.approvalStatus != "REJECTED" &&
                                it.validityStatus != "INVALID"
                        }.sortedBy { it.recordedAtEpochMs }

                        item {
                            TelemetryCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = selectedStudent.fullName,
                                            style = MaterialTheme.typography.titleLarge,
                                            color = AthletiqColors.TextPrimary,
                                        )
                                        Text(
                                            text = "ID: ${selectedStudent.institutionStudentCode} • ${selectedStudent.department ?: "No department"}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = AthletiqColors.TextSecondary,
                                        )
                                    }
                                    Button(
                                        onClick = { onExportStudentPdf(selectedStudent) },
                                        colors = ButtonDefaults.buttonColors(containerColor = AthletiqColors.PrimaryCobalt),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Text("Export PDF", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }

                        if (stuAttempts.isEmpty()) {
                            item {
                                EmptyStateCard(
                                    title = "No Valid Attempts Yet",
                                    message = "Completed assessments for ${selectedStudent.fullName} will populate longitudinal charts and scoring evaluations.",
                                )
                            }
                        } else {
                            val grouped = stuAttempts.groupBy { TestType.valueOf(it.testType) }
                            val ageYears = computeAge(selectedStudent.dateOfBirthIso)

                            items(grouped.entries.toList()) { (testType, list) ->
                                val latest = list.last()
                                val prev = if (list.size >= 2) list[list.size - 2] else null
                                val delta = prev?.let { latest.value - it.value }
                                val bestVal = if (testType == TestType.SPRINT) list.minOf { it.value } else list.maxOf { it.value }
                                val scoring = ScoringEvaluator.evaluate(
                                    value = bestVal,
                                    testType = testType,
                                    ageYears = ageYears,
                                    genderCategory = selectedStudent.genderCategory,
                                    discipline = selectedStudent.department,
                                    rubrics = rubrics,
                                )

                                TelemetryCard {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = testType.name.replace('_', ' '),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = AthletiqColors.TextPrimary,
                                        )
                                        if (delta != null) {
                                            val improved = if (testType == TestType.SPRINT) delta < 0 else delta > 0
                                            StatusPill(
                                                text = "${if (delta >= 0) "+" else ""}${"%.2f".format(delta)} ${latest.unit}",
                                                tone = if (improved) PillTone.VALID else PillTone.NEUTRAL,
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Best: ${"%.2f".format(bestVal)} ${latest.unit} • Latest: ${"%.2f".format(latest.value)} ${latest.unit} (${list.size} attempt(s))",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AthletiqColors.TextSecondary,
                                    )

                                    if (list.size >= 2) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        TrendLineCanvas(values = list.map { it.value })
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))
                                    when (scoring) {
                                        is ScoringEvaluationOutcome.Evaluated -> {
                                            StatusPill(
                                                text = "${scoring.categoryLabel ?: "Scored"} • ${scoring.standardName} (${scoring.sourceCitation})",
                                                tone = if (scoring.qualified == true) PillTone.VALID else PillTone.INFO,
                                            )
                                        }
                                        is ScoringEvaluationOutcome.NoStandardConfigured -> {
                                            Text(
                                                text = scoring.reason,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = AthletiqColors.TextMuted,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Cohort Rankings tab
                item {
                    UnavailableBanner(
                        title = "Objective Talent Indicators",
                        message = "Rankings compare approved best valid attempts across physical tests. No single test determines overall sporting talent.",
                        tone = PillTone.INFO,
                    )
                }

                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(TestType.entries) { tt ->
                            FilterChip(
                                selected = tt == rankingTestType,
                                onClick = { rankingTestType = tt },
                                label = { Text(tt.name.replace('_', ' ')) },
                            )
                        }
                    }
                }

                val departments = state.students.mapNotNull { it.department }.distinct().sorted()
                if (departments.isNotEmpty()) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                FilterChip(
                                    selected = rankingDeptFilter == null,
                                    onClick = { rankingDeptFilter = null },
                                    label = { Text("All Departments") },
                                )
                            }
                            items(departments) { dept ->
                                FilterChip(
                                    selected = rankingDeptFilter == dept,
                                    onClick = { rankingDeptFilter = dept },
                                    label = { Text(dept) },
                                )
                            }
                        }
                    }
                }

                val studentsById = state.students.associateBy { it.id }
                val approvedBest = state.attempts.filter { att ->
                    att.testType == rankingTestType.name &&
                        att.approvalStatus == "APPROVED" &&
                        att.isBestAttempt &&
                        (rankingDeptFilter == null || studentsById[att.studentId]?.department == rankingDeptFilter)
                }.sortedWith(
                    if (rankingTestType == TestType.SPRINT) compareBy { it.value } else compareByDescending { it.value },
                )

                if (approvedBest.isEmpty()) {
                    item {
                        EmptyStateCard(
                            title = "No Approved Cohort Results",
                            message = "Examiner-approved best attempts for ${rankingTestType.name.replace('_', ' ')} will populate cohort rankings automatically.",
                        )
                    }
                } else {
                    val values = approvedBest.map { it.value }
                    val mean = values.average()
                    val best = if (rankingTestType == TestType.SPRINT) values.min() else values.max()
                    val worst = if (rankingTestType == TestType.SPRINT) values.max() else values.min()

                    item {
                        TelemetryCard {
                            Text(
                                text = "Cohort Summary (${approvedBest.size} Athlete(s))",
                                style = MaterialTheme.typography.titleMedium,
                                color = AthletiqColors.TextPrimary,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Mean: ${"%.2f".format(mean)} ${approvedBest.first().unit} • Best: ${"%.2f".format(best)} ${approvedBest.first().unit}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = AthletiqColors.TextSecondary,
                            )
                        }
                    }

                    items(approvedBest.withIndex().toList(), key = { it.value.clientUuid }) { (idx, att) ->
                        val student = studentsById[att.studentId]
                        val span = kotlin.math.abs(best - worst).coerceAtLeast(1e-6)
                        val normProgress = if (approvedBest.size == 1) 1f else {
                            if (rankingTestType == TestType.SPRINT) {
                                ((worst - att.value) / span).toFloat().coerceIn(0.15f, 1f)
                            } else {
                                ((att.value - worst) / span).toFloat().coerceIn(0.15f, 1f)
                            }
                        }

                        TelemetryCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "#${idx + 1} ${student?.fullName ?: att.studentId}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = AthletiqColors.TextPrimary,
                                    )
                                    Text(
                                        text = "${student?.institutionStudentCode ?: ""} • ${student?.department ?: "No dept"}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AthletiqColors.TextSecondary,
                                    )
                                }
                                Text(
                                    text = "${"%.2f".format(att.value)} ${att.unit}",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = AthletiqColors.PrimaryDark,
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { normProgress },
                                color = AthletiqColors.PrimaryCobalt,
                                trackColor = AthletiqColors.SurfaceSoftBlue,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendLineCanvas(values: List<Double>) {
    val minV = values.min()
    val maxV = values.max()
    val span = (maxV - minV).coerceAtLeast(1e-4)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
            .padding(vertical = 6.dp),
    ) {
        val path = Path()
        values.forEachIndexed { idx, v ->
            val x = (idx.toFloat() / (values.size - 1).coerceAtLeast(1)) * size.width
            val normY = ((v - minV) / span).toFloat()
            val y = size.height - normY * (size.height - 16f) - 8f
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawCircle(color = AthletiqColors.PrimaryCobalt, radius = 6f, center = Offset(x, y))
        }
        drawPath(path = path, color = AthletiqColors.PrimaryCobalt, style = Stroke(width = 4f))
    }
}

private fun computeAge(dobIso: String?): Int? = runCatching {
    if (dobIso == null) return@runCatching null
    val dob = LocalDate.parse(dobIso)
    val now = LocalDate.now()
    var years = now.year - dob.year
    if (now.dayOfYear < dob.dayOfYear) years--
    years.coerceAtLeast(0)
}.getOrNull()
