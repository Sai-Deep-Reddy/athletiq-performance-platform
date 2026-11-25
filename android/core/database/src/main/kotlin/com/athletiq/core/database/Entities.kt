package com.athletiq.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "students",
    indices = [
        Index(value = ["institution_id", "institution_student_code"], unique = true),
    ],
)
data class StudentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "institution_id") val institutionId: String,
    @ColumnInfo(name = "institution_student_code") val institutionStudentCode: String,
    @ColumnInfo(name = "full_name") val fullName: String,
    @ColumnInfo(name = "date_of_birth_iso") val dateOfBirthIso: String?,
    @ColumnInfo(name = "gender_category") val genderCategory: String?,
    @ColumnInfo(name = "department") val department: String?,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "calibration_profiles",
    indices = [Index(value = ["institution_id", "plane_type"])],
)
data class CalibrationProfileEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "institution_id") val institutionId: String,
    @ColumnInfo(name = "device_model") val deviceModel: String,
    @ColumnInfo(name = "plane_type") val planeType: String,
    @ColumnInfo(name = "method") val method: String,
    @ColumnInfo(name = "matrix_json") val matrixJson: String,
    @ColumnInfo(name = "reprojection_error_px") val reprojectionErrorPx: Double,
    @ColumnInfo(name = "reference_target_spec_json") val referenceTargetSpecJson: String,
    @ColumnInfo(name = "calibrated_at_epoch_ms") val calibratedAtEpochMs: Long,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
)

@Entity(
    tableName = "examinations",
    indices = [Index(value = ["institution_id"])],
)
data class ExaminationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "institution_id") val institutionId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "test_types_json") val testTypesJson: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
)

@Entity(
    tableName = "attempts",
    foreignKeys = [
        ForeignKey(
            entity = ExaminationEntity::class,
            parentColumns = ["id"],
            childColumns = ["examination_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StudentEntity::class,
            parentColumns = ["id"],
            childColumns = ["student_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CalibrationProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["calibration_profile_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["institution_id", "client_uuid"], unique = true),
        Index(value = ["examination_id"]),
        Index(value = ["student_id"]),
        Index(value = ["calibration_profile_id"]),
    ],
)
data class AttemptEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @ColumnInfo(name = "institution_id") val institutionId: String,
    @ColumnInfo(name = "examination_id") val examinationId: String,
    @ColumnInfo(name = "student_id") val studentId: String,
    @ColumnInfo(name = "calibration_profile_id") val calibrationProfileId: String,
    @ColumnInfo(name = "test_type") val testType: String,
    @ColumnInfo(name = "value") val value: Double,
    @ColumnInfo(name = "unit") val unit: String,
    @ColumnInfo(name = "uncertainty") val uncertainty: Double,
    @ColumnInfo(name = "confidence_score") val confidenceScore: Double,
    @ColumnInfo(name = "fps_actual") val fpsActual: Double,
    @ColumnInfo(name = "frame_dt_ms") val frameDtMs: Double,
    @ColumnInfo(name = "dropped_frames") val droppedFrames: Int,
    @ColumnInfo(name = "landmark_provenance") val landmarkProvenance: String,
    @ColumnInfo(name = "algorithm_version") val algorithmVersion: String,
    @ColumnInfo(name = "validity_status") val validityStatus: String,
    @ColumnInfo(name = "approval_status") val approvalStatus: String,
    @ColumnInfo(name = "is_best_attempt") val isBestAttempt: Boolean,
    @ColumnInfo(name = "raw_metrics_json") val rawMetricsJson: String,
    @ColumnInfo(name = "encrypted_video_path") val encryptedVideoPath: String?,
    @ColumnInfo(name = "video_upload_consent") val videoUploadConsent: Boolean,
    @ColumnInfo(name = "recorded_at_epoch_ms") val recordedAtEpochMs: Long,
)

@Entity(
    tableName = "scoring_standards",
    indices = [Index(value = ["institution_id", "test_type"])],
)
data class ScoringStandardEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "institution_id") val institutionId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "test_type") val testType: String,
    @ColumnInfo(name = "age_min") val ageMin: Int,
    @ColumnInfo(name = "age_max") val ageMax: Int,
    @ColumnInfo(name = "gender_category") val genderCategory: String?,
    @ColumnInfo(name = "discipline") val discipline: String?,
    @ColumnInfo(name = "metric_unit") val metricUnit: String,
    @ColumnInfo(name = "bands_json") val bandsJson: String,
    @ColumnInfo(name = "source_citation") val sourceCitation: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
)

@Entity(
    tableName = "sync_outbox",
    indices = [Index(value = ["institution_id", "status"])],
)
data class SyncOutboxEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "institution_id") val institutionId: String,
    @ColumnInfo(name = "resource_type") val resourceType: String,
    @ColumnInfo(name = "resource_id") val resourceId: String,
    @ColumnInfo(name = "endpoint_path") val endpointPath: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "status") val status: String, // PENDING | FAILED_RETRYABLE | FAILED_TERMINAL | SYNCED
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
)

@Entity(
    tableName = "local_audit_events",
    indices = [Index(value = ["institution_id", "created_at_epoch_ms"])],
)
data class LocalAuditEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "institution_id") val institutionId: String,
    @ColumnInfo(name = "action") val action: String,
    @ColumnInfo(name = "resource_type") val resourceType: String,
    @ColumnInfo(name = "resource_id") val resourceId: String,
    @ColumnInfo(name = "details_json") val detailsJson: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
)
