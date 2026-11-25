package com.athletiq.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.athletiq.core.biomechanics.PlanarHomography
import com.athletiq.core.camera.CameraCapabilityInspector
import com.athletiq.core.camera.CameraHardwareCapabilities
import com.athletiq.core.database.AthletiqDatabase
import com.athletiq.core.database.AttemptEntity
import com.athletiq.core.database.CalibrationProfileEntity
import com.athletiq.core.database.EncryptedVideoStore
import com.athletiq.core.database.ExaminationEntity
import com.athletiq.core.database.LocalAuditEventEntity
import com.athletiq.core.database.ScoringStandardEntity
import com.athletiq.core.database.StudentEntity
import com.athletiq.core.database.SyncOutboxEntity
import com.athletiq.core.domain.ConfiguredScoringRubric
import com.athletiq.core.domain.ScoringBand
import com.athletiq.core.model.ApprovalStatus
import com.athletiq.core.model.AttemptRecord
import com.athletiq.core.model.AttemptValidityStatus
import com.athletiq.core.model.CalibrationMethod
import com.athletiq.core.model.CalibrationPlaneType
import com.athletiq.core.model.CalibrationQuality
import com.athletiq.core.model.ExaminationSummary
import com.athletiq.core.model.FrameTimingMetadata
import com.athletiq.core.model.LandmarkProvenance
import com.athletiq.core.model.MeasurementProvenance
import com.athletiq.core.model.MeasurementUnit
import com.athletiq.core.model.ScoringStandardSpec
import com.athletiq.core.model.StudentProfile
import com.athletiq.core.model.TestType
import com.athletiq.core.model.UserRole
import com.athletiq.core.network.ApiResult
import com.athletiq.core.network.AthletiqApiClient
import com.athletiq.core.network.AuthSessionCredentials
import com.athletiq.core.network.BootstrapInstitutionPayload
import com.athletiq.core.network.EncryptedTokenStore
import com.athletiq.core.network.OutboxSyncWorker
import com.athletiq.core.reports.OfflinePdfReportGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.Instant
import java.util.UUID

@Serializable
data class AttemptSyncPayload(
    val client_uuid: String,
    val student_id: String,
    val calibration_profile_id: String,
    val test_type: String,
    val value: Double,
    val unit: String,
    val uncertainty: Double,
    val confidence_score: Double,
    val fps_actual: Double,
    val frame_dt_ms: Double,
    val dropped_frames: Int,
    val landmark_provenance: String,
    val algorithm_version: String,
    val validity_status: String,
    val raw_metrics_json: JsonObject,
    val recorded_at: String,
)

@Serializable
data class HomographyPayloadJson(
    val homography_3x3: List<Double>,
)

@Serializable
data class CalibrationCreatePayload(
    val device_model: String,
    val plane_type: String,
    val method: String,
    val matrix_json: HomographyPayloadJson,
    val reprojection_error_px: Double,
    val reference_target_spec_json: Map<String, String>,
    val calibrated_at: String,
)

@Serializable
data class StudentCreatePayload(
    val institution_student_code: String,
    val full_name: String,
    val date_of_birth: String? = null,
    val gender_category: String? = null,
    val department: String? = null,
)

@Serializable
data class ExaminationCreatePayload(
    val title: String,
    val test_types: List<String>,
    val status: String = "ACTIVE",
)

@Serializable
data class StoredBandDto(
    val label: String,
    val min_value: Double? = null,
    val max_value: Double? = null,
    val points: Double? = null,
    val qualified: Boolean = false,
)

@Serializable
data class StoredBandsConfigDto(
    val direction: String = "higher_is_better",
    val bands: List<StoredBandDto> = emptyList(),
)

data class SessionMetadata(
    val institutionId: String,
    val institutionName: String,
    val userEmail: String,
    val userName: String,
    val role: UserRole,
    val apiBaseUrl: String,
)

data class VisionAssetStatus(
    val poseModelAsset: String?,
    val handModelAsset: String?,
    val objectDetectorAsset: String?,
) {
    val isPoseReady: Boolean get() = poseModelAsset != null
}

data class VideoStorageSummary(
    val encryptedFileCount: Int,
    val totalBytes: Long,
)

class AthletiqRepository(private val context: Context) {
    private val appContext = context.applicationContext
    private val db = AthletiqDatabase.getInstance(appContext)
    private val tokenStore = EncryptedTokenStore(appContext)
    private val apiClient = AthletiqApiClient()
    private val videoStore = EncryptedVideoStore(appContext)
    private val pdfGenerator = OfflinePdfReportGenerator()
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionMetaPrefs = appContext.getSharedPreferences("athletiq_session_meta", Context.MODE_PRIVATE)

    fun loadSavedSession(): SessionMetadata? {
        val creds = tokenStore.loadSession() ?: return null
        val userName = sessionMetaPrefs.getString("user_name", null) ?: return null
        val userEmail = sessionMetaPrefs.getString("user_email", null) ?: return null
        val roleStr = sessionMetaPrefs.getString("user_role", null) ?: return null
        val instName = sessionMetaPrefs.getString("institution_name", null) ?: "Institution"
        val role = runCatching { UserRole.valueOf(roleStr) }.getOrNull() ?: return null
        return SessionMetadata(
            institutionId = creds.institutionId,
            institutionName = instName,
            userEmail = userEmail,
            userName = userName,
            role = role,
            apiBaseUrl = creds.baseUrl,
        )
    }

    suspend fun login(baseUrl: String, email: String, password: String): Result<SessionMetadata> =
        withContext(Dispatchers.IO) {
            when (val res = apiClient.login(baseUrl, email.trim(), password)) {
                is ApiResult.Success -> {
                    val user = res.data.user
                    val saved = runCatching {
                        tokenStore.saveSession(baseUrl, res.data.access_token, user.institution_id)
                    }
                    if (saved.isFailure) {
                        return@withContext Result.failure(
                            IllegalStateException(saved.exceptionOrNull()?.message ?: "Failed to store session credentials"),
                        )
                    }
                    val role = runCatching { UserRole.valueOf(user.role) }.getOrDefault(UserRole.INSTRUCTOR)
                    val meta = SessionMetadata(
                        institutionId = user.institution_id,
                        institutionName = sessionMetaPrefs.getString("institution_name", null) ?: "Institution",
                        userEmail = user.email,
                        userName = user.full_name,
                        role = role,
                        apiBaseUrl = baseUrl.trimEnd('/'),
                    )
                    saveSessionMeta(meta)
                    enqueueSync()
                    Result.success(meta)
                }
                is ApiResult.AuthRejected -> Result.failure(IllegalStateException("Invalid credentials (${res.statusCode})"))
                is ApiResult.ValidationError -> Result.failure(IllegalStateException(res.detail))
                is ApiResult.RateLimited -> Result.failure(IllegalStateException("Rate limited; retry in ${res.retryAfterSeconds}s"))
                is ApiResult.ServerError -> Result.failure(IllegalStateException(res.detail))
                is ApiResult.NetworkUnavailable -> Result.failure(IllegalStateException("Server unreachable: ${res.message}"))
            }
        }

    suspend fun bootstrapAndLogin(
        baseUrl: String,
        institutionName: String,
        institutionCode: String,
        adminName: String,
        adminEmail: String,
        adminPassword: String,
    ): Result<SessionMetadata> = withContext(Dispatchers.IO) {
        val payload = BootstrapInstitutionPayload(
            institution_name = institutionName.trim(),
            institution_code = institutionCode.trim(),
            admin_email = adminEmail.trim(),
            admin_full_name = adminName.trim(),
            admin_password = adminPassword,
        )
        when (val res = apiClient.bootstrapInstitution(baseUrl, payload)) {
            is ApiResult.Success -> {
                sessionMetaPrefs.edit().putString("institution_name", res.data.institution.name).apply()
                login(baseUrl, adminEmail, adminPassword)
            }
            is ApiResult.ValidationError -> Result.failure(IllegalStateException(res.detail))
            is ApiResult.AuthRejected -> Result.failure(IllegalStateException(res.detail))
            is ApiResult.RateLimited -> Result.failure(IllegalStateException("Rate limited; retry in ${res.retryAfterSeconds}s"))
            is ApiResult.ServerError -> Result.failure(IllegalStateException(res.detail))
            is ApiResult.NetworkUnavailable -> Result.failure(IllegalStateException("Server unreachable: ${res.message}"))
        }
    }

    fun logout() {
        tokenStore.clearSession()
        sessionMetaPrefs.edit().clear().apply()
    }

    fun inspectHardwareCapabilities(): CameraHardwareCapabilities? =
        runCatching { CameraCapabilityInspector.inspectBackCamera(appContext) }.getOrNull()

    fun inspectVisionAssets(): VisionAssetStatus {
        val assets = runCatching { appContext.assets.list("").orEmpty().toSet() }.getOrDefault(emptySet())
        val pose = listOf("pose_landmarker_full.task", "pose_landmarker_heavy.task", "pose_landmarker_lite.task")
            .firstOrNull { it in assets }
        val hand = "hand_landmarker.task".takeIf { it in assets }
        val detector = "efficientdet_lite0.tflite".takeIf { it in assets }
        return VisionAssetStatus(poseModelAsset = pose, handModelAsset = hand, objectDetectorAsset = detector)
    }

    fun observeStudents(institutionId: String): Flow<List<StudentEntity>> =
        db.studentDao().observeByInstitution(institutionId)

    fun observeCalibrations(institutionId: String): Flow<List<CalibrationProfileEntity>> =
        db.calibrationDao().observeActiveByInstitution(institutionId)

    fun observeExaminations(institutionId: String): Flow<List<ExaminationEntity>> =
        db.examinationDao().observeByInstitution(institutionId)

    fun observeAttempts(institutionId: String): Flow<List<AttemptEntity>> =
        db.attemptDao().observeAllByInstitution(institutionId)

    fun observeStandards(institutionId: String): Flow<List<ScoringStandardEntity>> =
        db.scoringStandardDao().observeActiveByInstitution(institutionId)

    fun observePendingSyncCount(institutionId: String): Flow<Int> =
        db.syncOutboxDao().observePendingCount(institutionId)

    fun observeAuditLogs(institutionId: String): Flow<List<LocalAuditEventEntity>> =
        db.localAuditDao().observeRecent(institutionId, limit = 100)

    suspend fun registerStudent(
        institutionId: String,
        studentCode: String,
        fullName: String,
        dobIso: String?,
        genderCategory: String?,
        department: String?,
    ): StudentEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entity = StudentEntity(
            id = UUID.randomUUID().toString(),
            institutionId = institutionId,
            institutionStudentCode = studentCode.trim(),
            fullName = fullName.trim(),
            dateOfBirthIso = dobIso?.takeIf { it.isNotBlank() },
            genderCategory = genderCategory?.takeIf { it.isNotBlank() },
            department = department?.takeIf { it.isNotBlank() },
            isActive = true,
            updatedAtEpochMs = now,
        )
        db.studentDao().upsert(entity)
        val payload = json.encodeToString(
            StudentCreatePayload(
                institution_student_code = entity.institutionStudentCode,
                full_name = entity.fullName,
                date_of_birth = entity.dateOfBirthIso,
                gender_category = entity.genderCategory,
                department = entity.department,
            ),
        )
        db.syncOutboxDao().enqueue(
            SyncOutboxEntity(
                id = UUID.randomUUID().toString(),
                institutionId = institutionId,
                resourceType = "student",
                resourceId = entity.id,
                endpointPath = "/api/v1/students",
                payloadJson = payload,
                status = "PENDING",
                attemptCount = 0,
                lastError = null,
                createdAtEpochMs = now,
            ),
        )
        db.localAuditDao().append(
            LocalAuditEventEntity(
                id = UUID.randomUUID().toString(),
                institutionId = institutionId,
                action = "STUDENT_CREATED",
                resourceType = "student",
                resourceId = entity.id,
                detailsJson = """{"code":"${entity.institutionStudentCode}"}""",
                createdAtEpochMs = now,
            ),
        )
        enqueueSync()
        entity
    }

    suspend fun createExamination(
        institutionId: String,
        title: String,
        testTypes: List<TestType>,
    ): ExaminationEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val testNames = testTypes.map { it.name }
        val entity = ExaminationEntity(
            id = UUID.randomUUID().toString(),
            institutionId = institutionId,
            title = title.trim(),
            testTypesJson = json.encodeToString(testNames),
            status = "ACTIVE",
            createdAtEpochMs = now,
        )
        db.examinationDao().upsert(entity)
        val payload = json.encodeToString(ExaminationCreatePayload(title = entity.title, test_types = testNames))
        db.syncOutboxDao().enqueue(
            SyncOutboxEntity(
                id = UUID.randomUUID().toString(),
                institutionId = institutionId,
                resourceType = "examination",
                resourceId = entity.id,
                endpointPath = "/api/v1/examinations",
                payloadJson = payload,
                status = "PENDING",
                attemptCount = 0,
                lastError = null,
                createdAtEpochMs = now,
            ),
        )
        db.localAuditDao().append(
            LocalAuditEventEntity(
                id = UUID.randomUUID().toString(),
                institutionId = institutionId,
                action = "EXAMINATION_CREATED",
                resourceType = "examination",
                resourceId = entity.id,
                detailsJson = """{"title":"${entity.title}"}""",
                createdAtEpochMs = now,
            ),
        )
        enqueueSync()
        entity
    }

    suspend fun saveCalibrationProfile(
        institutionId: String,
        deviceModel: String,
        quality: CalibrationQuality,
        homography: PlanarHomography,
        targetSpec: Map<String, String>,
    ): CalibrationProfileEntity = withContext(Dispatchers.IO) {
        val matrixJson = json.encodeToString(HomographyPayloadJson(homography.imageToPlaneMatrix.toList()))
        val specJson = json.encodeToString(targetSpec)
        val entity = CalibrationProfileEntity(
            id = quality.calibrationId,
            institutionId = institutionId,
            deviceModel = deviceModel,
            planeType = quality.planeType.name,
            method = quality.method.name,
            matrixJson = matrixJson,
            reprojectionErrorPx = quality.reprojectionErrorPx,
            referenceTargetSpecJson = specJson,
            calibratedAtEpochMs = quality.calibratedAtEpochMs,
            isActive = true,
        )
        db.calibrationDao().upsert(entity)
        val payload = json.encodeToString(
            CalibrationCreatePayload(
                device_model = deviceModel,
                plane_type = quality.planeType.name,
                method = quality.method.name,
                matrix_json = HomographyPayloadJson(homography.imageToPlaneMatrix.toList()),
                reprojection_error_px = quality.reprojectionErrorPx,
                reference_target_spec_json = targetSpec,
                calibrated_at = Instant.ofEpochMilli(quality.calibratedAtEpochMs).toString(),
            ),
        )
        db.syncOutboxDao().enqueue(
            SyncOutboxEntity(
                id = UUID.randomUUID().toString(),
                institutionId = institutionId,
                resourceType = "calibration",
                resourceId = entity.id,
                endpointPath = "/api/v1/calibrations",
                payloadJson = payload,
                status = "PENDING",
                attemptCount = 0,
                lastError = null,
                createdAtEpochMs = quality.calibratedAtEpochMs,
            ),
        )
        db.localAuditDao().append(
            LocalAuditEventEntity(
                id = UUID.randomUUID().toString(),
                institutionId = institutionId,
                action = "CALIBRATION_SAVED",
                resourceType = "calibration_profile",
                resourceId = entity.id,
                detailsJson = """{"plane":"${quality.planeType.name}","reproj_px":${quality.reprojectionErrorPx}}""",
                createdAtEpochMs = quality.calibratedAtEpochMs,
            ),
        )
        enqueueSync()
        entity
    }

    suspend fun saveMeasuredAttempt(
        institutionId: String,
        examinationId: String,
        studentId: String,
        testType: TestType,
        value: Double,
        unit: MeasurementUnit,
        uncertainty: Double,
        confidenceScore: Double,
        provenance: MeasurementProvenance,
        validityStatus: AttemptValidityStatus,
        rawMetricsJson: String,
        encryptedVideoPath: String?,
        videoUploadConsent: Boolean,
    ): AttemptEntity = withContext(Dispatchers.IO) {
        val clientUuid = UUID.randomUUID().toString()
        val attempt = AttemptEntity(
            clientUuid = clientUuid,
            institutionId = institutionId,
            examinationId = examinationId,
            studentId = studentId,
            calibrationProfileId = provenance.calibration.calibrationId,
            testType = testType.name,
            value = value,
            unit = unit.name,
            uncertainty = uncertainty,
            confidenceScore = confidenceScore,
            fpsActual = provenance.timing.fpsActual,
            frameDtMs = provenance.timing.medianFrameDtMs,
            droppedFrames = provenance.timing.droppedFrames,
            landmarkProvenance = provenance.landmarkProvenance.name,
            algorithmVersion = provenance.algorithmVersion,
            validityStatus = validityStatus.name,
            approvalStatus = ApprovalStatus.PENDING.name,
            isBestAttempt = false,
            rawMetricsJson = rawMetricsJson,
            encryptedVideoPath = encryptedVideoPath,
            videoUploadConsent = videoUploadConsent,
            recordedAtEpochMs = provenance.recordedAtEpochMs,
        )
        val parsedRawMetrics = runCatching { json.parseToJsonElement(rawMetricsJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        val outboxPayload = json.encodeToString(
            AttemptSyncPayload(
                client_uuid = clientUuid,
                student_id = studentId,
                calibration_profile_id = provenance.calibration.calibrationId,
                test_type = testType.name,
                value = value,
                unit = unit.name,
                uncertainty = uncertainty,
                confidence_score = confidenceScore,
                fps_actual = provenance.timing.fpsActual,
                frame_dt_ms = provenance.timing.medianFrameDtMs,
                dropped_frames = provenance.timing.droppedFrames,
                landmark_provenance = provenance.landmarkProvenance.name,
                algorithm_version = provenance.algorithmVersion,
                validity_status = validityStatus.name,
                raw_metrics_json = parsedRawMetrics,
                recorded_at = Instant.ofEpochMilli(provenance.recordedAtEpochMs).toString(),
            ),
        )
        val outbox = SyncOutboxEntity(
            id = UUID.randomUUID().toString(),
            institutionId = institutionId,
            resourceType = "attempt",
            resourceId = clientUuid,
            endpointPath = "/api/v1/examinations/$examinationId/attempts/sync",
            payloadJson = outboxPayload,
            status = "PENDING",
            attemptCount = 0,
            lastError = null,
            createdAtEpochMs = provenance.recordedAtEpochMs,
        )
        val audit = LocalAuditEventEntity(
            id = UUID.randomUUID().toString(),
            institutionId = institutionId,
            action = "ATTEMPT_RECORDED",
            resourceType = "attempt",
            resourceId = clientUuid,
            detailsJson = """{"test_type":"${testType.name}","validity":"${validityStatus.name}"}""",
            createdAtEpochMs = provenance.recordedAtEpochMs,
        )
        db.recordAttemptWithOutboxAndAudit(attempt, outbox, audit)
        recomputeBestAttempt(institutionId, examinationId, studentId, testType)
        enqueueSync()
        attempt
    }

    suspend fun reviewAttempt(
        institutionId: String,
        attempt: AttemptEntity,
        approvalStatus: ApprovalStatus,
        note: String?,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.attemptDao().updateApprovalStatus(attempt.clientUuid, institutionId, approvalStatus.name)
        recomputeBestAttempt(
            institutionId,
            attempt.examinationId,
            attempt.studentId,
            TestType.valueOf(attempt.testType),
        )
        db.localAuditDao().append(
            LocalAuditEventEntity(
                id = UUID.randomUUID().toString(),
                institutionId = institutionId,
                action = "ATTEMPT_REVIEWED",
                resourceType = "attempt",
                resourceId = attempt.clientUuid,
                detailsJson = """{"approval":"${approvalStatus.name}","has_note":${!note.isNullOrBlank()}}""",
                createdAtEpochMs = now,
            ),
        )
        enqueueSync()
    }

    suspend fun saveScoringStandard(
        institutionId: String,
        name: String,
        testType: TestType,
        ageMin: Int,
        ageMax: Int,
        genderCategory: String?,
        discipline: String?,
        unit: MeasurementUnit,
        direction: String,
        bands: List<ScoringBand>,
        sourceCitation: String,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val bandsConfig = StoredBandsConfigDto(
            direction = direction,
            bands = bands.map { StoredBandDto(it.label, it.minValue, it.maxValue, it.points, it.qualified) },
        )
        val entity = ScoringStandardEntity(
            id = id,
            institutionId = institutionId,
            name = name.trim(),
            testType = testType.name,
            ageMin = ageMin,
            ageMax = ageMax,
            genderCategory = genderCategory?.takeIf { it.isNotBlank() },
            discipline = discipline?.takeIf { it.isNotBlank() },
            metricUnit = unit.name,
            bandsJson = json.encodeToString(bandsConfig),
            sourceCitation = sourceCitation.trim(),
            isActive = true,
        )
        db.scoringStandardDao().upsertAll(listOf(entity))
        db.localAuditDao().append(
            LocalAuditEventEntity(
                id = UUID.randomUUID().toString(),
                institutionId = institutionId,
                action = "SCORING_STANDARD_CREATED",
                resourceType = "scoring_standard",
                resourceId = id,
                detailsJson = """{"test_type":"${testType.name}","name":"${entity.name}"}""",
                createdAtEpochMs = now,
            ),
        )
    }

    fun decodeRubric(entity: ScoringStandardEntity): ConfiguredScoringRubric? = runCatching {
        val cfg = json.decodeFromString(StoredBandsConfigDto.serializer(), entity.bandsJson)
        ConfiguredScoringRubric(
            spec = ScoringStandardSpec(
                id = entity.id,
                institutionId = entity.institutionId,
                name = entity.name,
                testType = TestType.valueOf(entity.testType),
                ageMin = entity.ageMin,
                ageMax = entity.ageMax,
                genderCategory = entity.genderCategory,
                discipline = entity.discipline,
                unit = MeasurementUnit.valueOf(entity.metricUnit),
                sourceCitation = entity.sourceCitation,
            ),
            direction = cfg.direction,
            bands = cfg.bands.map { ScoringBand(it.label, it.min_value, it.max_value, it.points, it.qualified) },
        )
    }.getOrNull()

    fun decodeHomography(entity: CalibrationProfileEntity): PlanarHomography? = runCatching {
        val payload = json.decodeFromString(HomographyPayloadJson.serializer(), entity.matrixJson)
        PlanarHomography(payload.homography_3x3.toDoubleArray())
    }.getOrNull()

    fun toCalibrationQuality(entity: CalibrationProfileEntity): CalibrationQuality? = runCatching {
        CalibrationQuality(
            calibrationId = entity.id,
            planeType = CalibrationPlaneType.valueOf(entity.planeType),
            method = CalibrationMethod.valueOf(entity.method),
            reprojectionErrorPx = entity.reprojectionErrorPx,
            calibratedAtEpochMs = entity.calibratedAtEpochMs,
        )
    }.getOrNull()

    suspend fun getVideoStorageSummary(): VideoStorageSummary = withContext(Dispatchers.IO) {
        val dir = File(appContext.filesDir, "exam_videos")
        if (!dir.exists()) return@withContext VideoStorageSummary(0, 0L)
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".enc") }.orEmpty()
        VideoStorageSummary(files.size, files.sumOf { it.length() })
    }

    suspend fun purgeExpiredVideos(retentionDays: Int): Int = withContext(Dispatchers.IO) {
        val maxAgeMs = retentionDays.coerceAtLeast(1) * 24L * 60L * 60L * 1000L
        videoStore.purgeExpiredVideos(maxAgeMs)
    }

    suspend fun deleteAllLocalVideos(): Int = withContext(Dispatchers.IO) {
        val dir = File(appContext.filesDir, "exam_videos")
        var count = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.delete()) count++
        }
        count
    }

    suspend fun exportStudentPdf(
        institutionName: String,
        student: StudentEntity,
        attempts: List<AttemptEntity>,
    ): File = withContext(Dispatchers.IO) {
        val outFile = File(appContext.cacheDir, "reports/student_${student.institutionStudentCode}.pdf")
        pdfGenerator.generateStudentReport(
            institutionName = institutionName,
            student = student.toDomain(),
            attempts = attempts.mapNotNull { it.toDomain() },
            outputFile = outFile,
        )
    }

    suspend fun exportExaminationPdf(
        institutionName: String,
        exam: ExaminationEntity,
        attempts: List<AttemptEntity>,
        students: List<StudentEntity>,
    ): File = withContext(Dispatchers.IO) {
        val outFile = File(appContext.cacheDir, "reports/exam_${exam.id}.pdf")
        val testTypes = runCatching { json.decodeFromString<List<String>>(exam.testTypesJson) }
            .getOrDefault(emptyList())
            .mapNotNull { runCatching { TestType.valueOf(it) }.getOrNull() }
        val summary = ExaminationSummary(
            id = exam.id,
            institutionId = exam.institutionId,
            title = exam.title,
            testTypes = testTypes,
            status = exam.status,
            createdAtEpochMs = exam.createdAtEpochMs,
        )
        pdfGenerator.generateExaminationReport(
            institutionName = institutionName,
            examination = summary,
            attempts = attempts.mapNotNull { it.toDomain() },
            studentsById = students.associate { it.id to it.toDomain() },
            outputFile = outFile,
        )
    }

    fun enqueueSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<OutboxSyncWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "athletiq_outbox_sync",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private suspend fun recomputeBestAttempt(
        institutionId: String,
        examinationId: String,
        studentId: String,
        testType: TestType,
    ) {
        val list = db.attemptDao().getStudentTestAttempts(institutionId, examinationId, studentId, testType.name)
        for (item in list) {
            db.attemptDao().setBestAttemptFlag(item.clientUuid, institutionId, false)
        }
        val eligible = list.filter { att ->
            att.approvalStatus != ApprovalStatus.REJECTED.name &&
                (att.validityStatus == AttemptValidityStatus.VALID.name ||
                    (att.validityStatus == AttemptValidityStatus.FLAGGED.name &&
                        att.approvalStatus == ApprovalStatus.APPROVED.name))
        }
        if (eligible.isEmpty()) return
        val approved = eligible.filter { it.approvalStatus == ApprovalStatus.APPROVED.name }
        val pool = approved.ifEmpty { eligible }
        val best = if (testType == TestType.SPRINT) pool.minBy { it.value } else pool.maxBy { it.value }
        db.attemptDao().setBestAttemptFlag(best.clientUuid, institutionId, true)
    }

    private fun saveSessionMeta(meta: SessionMetadata) {
        sessionMetaPrefs.edit()
            .putString("user_name", meta.userName)
            .putString("user_email", meta.userEmail)
            .putString("user_role", meta.role.name)
            .putString("institution_name", meta.institutionName)
            .apply()
    }

    private fun StudentEntity.toDomain() = StudentProfile(
        id = id,
        institutionId = institutionId,
        institutionStudentCode = institutionStudentCode,
        fullName = fullName,
        dateOfBirthIso = dateOfBirthIso,
        genderCategory = genderCategory,
        department = department,
        isActive = isActive,
    )

    private fun AttemptEntity.toDomain(): AttemptRecord? = runCatching {
        val tt = TestType.valueOf(testType)
        val calPlane = if (tt == TestType.HEIGHT || tt == TestType.VERTICAL_JUMP) {
            CalibrationPlaneType.VERTICAL_PLANE
        } else {
            CalibrationPlaneType.GROUND_PLANE
        }
        AttemptRecord(
            clientUuid = clientUuid,
            institutionId = institutionId,
            examinationId = examinationId,
            studentId = studentId,
            testType = tt,
            value = value,
            unit = MeasurementUnit.valueOf(unit),
            uncertainty = uncertainty,
            confidenceScore = confidenceScore,
            provenance = MeasurementProvenance(
                calibration = CalibrationQuality(
                    calibrationId = calibrationProfileId,
                    planeType = calPlane,
                    method = CalibrationMethod.OPENCV_CHARUCO,
                    reprojectionErrorPx = 0.0,
                    calibratedAtEpochMs = recordedAtEpochMs,
                ),
                timing = FrameTimingMetadata(
                    fpsActual = fpsActual,
                    medianFrameDtMs = frameDtMs,
                    maxFrameDtMs = frameDtMs,
                    droppedFrames = droppedFrames,
                    isVariableFrameRate = false,
                ),
                landmarkProvenance = LandmarkProvenance.valueOf(landmarkProvenance),
                algorithmVersion = algorithmVersion,
                recordedAtEpochMs = recordedAtEpochMs,
            ),
            validityStatus = AttemptValidityStatus.valueOf(validityStatus),
            approvalStatus = ApprovalStatus.valueOf(approvalStatus),
            isBestAttempt = isBestAttempt,
        )
    }.getOrNull()
}
