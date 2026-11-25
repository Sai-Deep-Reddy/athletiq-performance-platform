package com.athletiq.core.model

import kotlinx.serialization.Serializable

@Serializable
data class StudentProfile(
    val id: String,
    val institutionId: String,
    val institutionStudentCode: String,
    val fullName: String,
    val dateOfBirthIso: String? = null,
    val genderCategory: String? = null,
    val department: String? = null,
    val isActive: Boolean = true,
)

@Serializable
data class ExaminationSummary(
    val id: String,
    val institutionId: String,
    val title: String,
    val testTypes: List<TestType>,
    val status: String,
    val createdAtEpochMs: Long,
)

@Serializable
data class AttemptRecord(
    val clientUuid: String,
    val institutionId: String,
    val examinationId: String,
    val studentId: String,
    val testType: TestType,
    val value: Double,
    val unit: MeasurementUnit,
    val uncertainty: Double,
    val confidenceScore: Double,
    val provenance: MeasurementProvenance,
    val validityStatus: AttemptValidityStatus,
    val approvalStatus: ApprovalStatus,
    val isBestAttempt: Boolean,
)

@Serializable
data class ScoringStandardSpec(
    val id: String,
    val institutionId: String,
    val name: String,
    val testType: TestType,
    val ageMin: Int,
    val ageMax: Int,
    val genderCategory: String? = null,
    val discipline: String? = null,
    val unit: MeasurementUnit,
    val sourceCitation: String,
)
