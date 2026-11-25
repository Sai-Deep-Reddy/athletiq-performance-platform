package com.athletiq.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StudentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(student: StudentEntity)

    @Query("SELECT * FROM students WHERE institution_id = :institutionId ORDER BY institution_student_code ASC")
    fun observeByInstitution(institutionId: String): Flow<List<StudentEntity>>

    @Query("SELECT * FROM students WHERE id = :studentId AND institution_id = :institutionId LIMIT 1")
    suspend fun getById(studentId: String, institutionId: String): StudentEntity?
}

@Dao
interface CalibrationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: CalibrationProfileEntity)

    @Query("SELECT * FROM calibration_profiles WHERE institution_id = :institutionId AND plane_type = :planeType AND is_active = 1 ORDER BY calibrated_at_epoch_ms DESC LIMIT 1")
    suspend fun getLatestActive(institutionId: String, planeType: String): CalibrationProfileEntity?

    @Query("SELECT * FROM calibration_profiles WHERE institution_id = :institutionId AND is_active = 1 ORDER BY calibrated_at_epoch_ms DESC")
    fun observeActiveByInstitution(institutionId: String): Flow<List<CalibrationProfileEntity>>
}

@Dao
interface ExaminationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(exam: ExaminationEntity)

    @Query("SELECT * FROM examinations WHERE institution_id = :institutionId ORDER BY created_at_epoch_ms DESC")
    fun observeByInstitution(institutionId: String): Flow<List<ExaminationEntity>>
}

@Dao
interface AttemptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(attempt: AttemptEntity): Long

    @Query("SELECT * FROM attempts WHERE institution_id = :institutionId AND examination_id = :examId ORDER BY recorded_at_epoch_ms DESC")
    fun observeByExam(institutionId: String, examId: String): Flow<List<AttemptEntity>>

    @Query("SELECT * FROM attempts WHERE institution_id = :institutionId AND student_id = :studentId ORDER BY recorded_at_epoch_ms DESC")
    fun observeByStudent(institutionId: String, studentId: String): Flow<List<AttemptEntity>>

    @Query("SELECT * FROM attempts WHERE institution_id = :institutionId ORDER BY recorded_at_epoch_ms DESC")
    fun observeAllByInstitution(institutionId: String): Flow<List<AttemptEntity>>

    @Query("SELECT * FROM attempts WHERE institution_id = :institutionId AND examination_id = :examId AND student_id = :studentId AND test_type = :testType")
    suspend fun getStudentTestAttempts(
        institutionId: String,
        examId: String,
        studentId: String,
        testType: String,
    ): List<AttemptEntity>

    @Query("UPDATE attempts SET is_best_attempt = :isBest WHERE client_uuid = :clientUuid AND institution_id = :institutionId")
    suspend fun setBestAttemptFlag(clientUuid: String, institutionId: String, isBest: Boolean)

    @Query("UPDATE attempts SET approval_status = :approvalStatus WHERE client_uuid = :clientUuid AND institution_id = :institutionId")
    suspend fun updateApprovalStatus(clientUuid: String, institutionId: String, approvalStatus: String)
}

@Dao
interface ScoringStandardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(standards: List<ScoringStandardEntity>)

    @Query("SELECT * FROM scoring_standards WHERE institution_id = :institutionId AND test_type = :testType AND is_active = 1")
    suspend fun getActiveForTest(institutionId: String, testType: String): List<ScoringStandardEntity>

    @Query("SELECT * FROM scoring_standards WHERE institution_id = :institutionId AND is_active = 1 ORDER BY name ASC")
    fun observeActiveByInstitution(institutionId: String): Flow<List<ScoringStandardEntity>>
}

@Dao
interface SyncOutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: SyncOutboxEntity)

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE institution_id = :institutionId AND status IN ('PENDING', 'FAILED_RETRYABLE')")
    fun observePendingCount(institutionId: String): Flow<Int>

    @Query("SELECT * FROM sync_outbox WHERE status IN ('PENDING', 'FAILED_RETRYABLE') ORDER BY created_at_epoch_ms ASC LIMIT :limit")
    suspend fun getPendingBatch(limit: Int = 25): List<SyncOutboxEntity>

    @Query("UPDATE sync_outbox SET status = :status, attempt_count = attempt_count + 1, last_error = :lastError WHERE id = :id")
    suspend fun markResult(id: String, status: String, lastError: String?)
}

@Dao
interface LocalAuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun append(event: LocalAuditEventEntity)

    @Query("SELECT * FROM local_audit_events WHERE institution_id = :institutionId ORDER BY created_at_epoch_ms DESC LIMIT :limit")
    suspend fun listRecent(institutionId: String, limit: Int = 100): List<LocalAuditEventEntity>

    @Query("SELECT * FROM local_audit_events WHERE institution_id = :institutionId ORDER BY created_at_epoch_ms DESC LIMIT :limit")
    fun observeRecent(institutionId: String, limit: Int = 100): Flow<List<LocalAuditEventEntity>>
}
