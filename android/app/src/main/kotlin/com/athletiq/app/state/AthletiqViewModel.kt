package com.athletiq.app.state

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.athletiq.app.data.AthletiqRepository
import com.athletiq.app.data.SessionMetadata
import com.athletiq.app.data.VideoStorageSummary
import com.athletiq.app.data.VisionAssetStatus
import com.athletiq.core.biomechanics.BiomechanicsAnalyzer
import com.athletiq.core.biomechanics.BiomechanicsFrameSample
import com.athletiq.core.biomechanics.BiomechanicsReport
import com.athletiq.core.biomechanics.BroadJumpFrameSample
import com.athletiq.core.biomechanics.BroadJumpMeasurement
import com.athletiq.core.biomechanics.BroadJumpProtocol
import com.athletiq.core.biomechanics.CalibrationQualityGate
import com.athletiq.core.biomechanics.HeightFrameSample
import com.athletiq.core.biomechanics.HeightMeasurement
import com.athletiq.core.biomechanics.LandmarkPoint3D
import com.athletiq.core.biomechanics.LandmarkSmoother3D
import com.athletiq.core.biomechanics.MaskGroundContactCandidates
import com.athletiq.core.biomechanics.PlanarHomography
import com.athletiq.core.biomechanics.PoseLandmarkIndex
import com.athletiq.core.biomechanics.RefinedFingertipPoint
import com.athletiq.core.biomechanics.RefinedHeadCrown
import com.athletiq.core.biomechanics.SprintFrameSample
import com.athletiq.core.biomechanics.SprintMeasurement
import com.athletiq.core.biomechanics.VerticalJumpFrameSample
import com.athletiq.core.biomechanics.VerticalJumpMeasurement
import com.athletiq.core.biomechanics.VerticalJumpProtocol
import com.athletiq.core.camera.CameraHardwareCapabilities
import com.athletiq.core.camera.FrameTimingTracker
import com.athletiq.core.database.AttemptEntity
import com.athletiq.core.database.CalibrationProfileEntity
import com.athletiq.core.database.ExaminationEntity
import com.athletiq.core.database.LocalAuditEventEntity
import com.athletiq.core.database.ScoringStandardEntity
import com.athletiq.core.database.StudentEntity
import com.athletiq.core.domain.BroadJumpSessionCoordinator
import com.athletiq.core.domain.HeightSessionCoordinator
import com.athletiq.core.domain.ScoringBand
import com.athletiq.core.domain.SprintSessionCoordinator
import com.athletiq.core.domain.VerticalJumpSessionCoordinator
import com.athletiq.core.model.ApprovalStatus
import com.athletiq.core.model.AttemptValidityStatus
import com.athletiq.core.model.CalibrationPlaneType
import com.athletiq.core.model.CalibrationQuality
import com.athletiq.core.model.FrameTimingMetadata
import com.athletiq.core.model.MeasurementOutcome
import com.athletiq.core.model.MeasurementUnit
import com.athletiq.core.model.TestType
import com.athletiq.core.vision.CharucoBoardSpec
import com.athletiq.core.vision.CharucoCalibrationEngine
import com.athletiq.core.vision.GroundContactExtractor
import com.athletiq.core.vision.HeadBoundaryRefiner
import com.athletiq.core.vision.MediaPipeHandRoiEngine
import com.athletiq.core.vision.MediaPipePoseEngine
import com.athletiq.core.vision.PoseDetectionResult
import com.athletiq.core.vision.PoseFrameObservation
import com.athletiq.core.vision.PlanarCalibrationOutcome
import com.athletiq.core.vision.SubjectRoiDetector
import com.athletiq.core.vision.SubjectScreeningResult
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class LivePoseOverlayState(
    val landmarks: List<LandmarkPoint3D> = emptyList(),
    val timing: FrameTimingMetadata? = null,
    val subjectCountWarning: String? = null,
    val isRecording: Boolean = false,
    val recordedFramesCount: Int = 0,
    val statusMessage: String = "Align athlete inside frame",
)

data class LiveCalibrationState(
    val selectedPlane: CalibrationPlaneType = CalibrationPlaneType.VERTICAL_PLANE,
    val latestOutcomeMessage: String? = null,
    val lastReprojectionErrorPx: Double? = null,
    val lastDetectedCorners: Int = 0,
    val isGatePassed: Boolean = false,
)

data class AttemptInspectionBundle(
    val attempt: AttemptEntity,
    val biomechanics: BiomechanicsReport? = null,
    val heightDetail: HeightMeasurement? = null,
    val verticalJumpDetail: VerticalJumpMeasurement? = null,
    val broadJumpDetail: BroadJumpMeasurement? = null,
    val sprintDetail: SprintMeasurement? = null,
)

data class AthletiqMainUiState(
    val session: SessionMetadata? = null,
    val isAuthBusy: Boolean = false,
    val authError: String? = null,
    val cameraCapabilities: CameraHardwareCapabilities? = null,
    val visionAssets: VisionAssetStatus = VisionAssetStatus(null, null, null),
    val students: List<StudentEntity> = emptyList(),
    val calibrations: List<CalibrationProfileEntity> = emptyList(),
    val examinations: List<ExaminationEntity> = emptyList(),
    val attempts: List<AttemptEntity> = emptyList(),
    val standards: List<ScoringStandardEntity> = emptyList(),
    val auditLogs: List<LocalAuditEventEntity> = emptyList(),
    val pendingSyncCount: Int = 0,
    val videoStorage: VideoStorageSummary = VideoStorageSummary(0, 0L),
    val videoRetentionDays: Int = 30,
    val videoUploadConsentOptIn: Boolean = false,
    val selectedTestType: TestType = TestType.VERTICAL_JUMP,
    val selectedStudentId: String? = null,
    val selectedExamId: String? = null,
    val sprintDistanceMeters: Double = 10.0,
    val verticalJumpProtocol: VerticalJumpProtocol = VerticalJumpProtocol.STANDING_REACH_DELTA,
    val broadJumpProtocol: BroadJumpProtocol = BroadJumpProtocol.NEAREST_BODY_CONTACT,
    val liveCalibration: LiveCalibrationState = LiveCalibrationState(),
    val livePoseOverlay: LivePoseOverlayState = LivePoseOverlayState(),
    val activeOutcomeMessage: String? = null,
    val activeInspection: AttemptInspectionBundle? = null,
    val statusNotice: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AthletiqViewModel(application: Application) : AndroidViewModel(application) {
    val repository = AthletiqRepository(application)

    private val _session = MutableStateFlow(repository.loadSavedSession())
    private val _isAuthBusy = MutableStateFlow(false)
    private val _authError = MutableStateFlow<String?>(null)
    private val _cameraCaps = MutableStateFlow(repository.inspectHardwareCapabilities())
    private val _visionAssets = MutableStateFlow(repository.inspectVisionAssets())
    private val _videoStorage = MutableStateFlow(VideoStorageSummary(0, 0L))
    private val _videoRetentionDays = MutableStateFlow(30)
    private val _videoUploadConsentOptIn = MutableStateFlow(false)

    private val _selectedTestType = MutableStateFlow(TestType.VERTICAL_JUMP)
    private val _selectedStudentId = MutableStateFlow<String?>(null)
    private val _selectedExamId = MutableStateFlow<String?>(null)
    private val _sprintDistanceMeters = MutableStateFlow(10.0)
    private val _verticalJumpProtocol = MutableStateFlow(VerticalJumpProtocol.STANDING_REACH_DELTA)
    private val _broadJumpProtocol = MutableStateFlow(BroadJumpProtocol.NEAREST_BODY_CONTACT)
    private val _liveCalibration = MutableStateFlow(LiveCalibrationState())
    private val _livePoseOverlay = MutableStateFlow(LivePoseOverlayState())
    private val _activeOutcomeMessage = MutableStateFlow<String?>(null)
    private val _activeInspection = MutableStateFlow<AttemptInspectionBundle?>(null)
    private val _statusNotice = MutableStateFlow<String?>(null)

    private val heightCoordinator = HeightSessionCoordinator()
    private val verticalJumpCoordinator = VerticalJumpSessionCoordinator()
    private val broadJumpCoordinator = BroadJumpSessionCoordinator()
    private val sprintCoordinator = SprintSessionCoordinator()
    private val biomechanicsAnalyzer = BiomechanicsAnalyzer()

    private val charucoEngine = CharucoCalibrationEngine(CharucoBoardSpec())
    private var poseEngine: MediaPipePoseEngine? = null
    private var handRoiEngine: MediaPipeHandRoiEngine? = null
    private var subjectDetector: SubjectRoiDetector? = null
    private var landmarkSmoother = LandmarkSmoother3D(PoseLandmarkIndex.LANDMARK_COUNT)
    private val capturedFrames = mutableListOf<CapturedFrameRecord>()
    private val isImportingVideo = AtomicBoolean(false)
    @Volatile
    private var liveFrameCounter: Long = 0L
    @Volatile
    private var lastMediaPipeVideoMs: Long = 0L

    private data class CapturedFrameRecord(
        val timestampNs: Long,
        val imageWidth: Int,
        val imageHeight: Int,
        val smoothedLandmarks: List<LandmarkPoint3D>,
        val refinedCrown: RefinedHeadCrown? = null,
        val refinedHandTip: RefinedFingertipPoint? = null,
        val groundContacts: MaskGroundContactCandidates? = null,
    )

    private val studentsFlow = _session.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else repository.observeStudents(s.institutionId)
    }
    private val calibrationsFlow = _session.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else repository.observeCalibrations(s.institutionId)
    }
    private val examsFlow = _session.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else repository.observeExaminations(s.institutionId)
    }
    private val attemptsFlow = _session.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else repository.observeAttempts(s.institutionId)
    }
    private val standardsFlow = _session.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else repository.observeStandards(s.institutionId)
    }
    private val auditFlow = _session.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else repository.observeAuditLogs(s.institutionId)
    }
    private val pendingSyncFlow = _session.flatMapLatest { s ->
        if (s == null) flowOf(0) else repository.observePendingSyncCount(s.institutionId)
    }

    private val coreDataFlow = combine(
        studentsFlow,
        calibrationsFlow,
        examsFlow,
        attemptsFlow,
        standardsFlow,
    ) { students, cals, exams, attempts, standards ->
        CoreDbSnapshot(students, cals, exams, attempts, standards)
    }

    private val selectionFlow = combine(
        _selectedTestType,
        _selectedStudentId,
        _selectedExamId,
        _sprintDistanceMeters,
        combine(_broadJumpProtocol, _verticalJumpProtocol) { sbj, vj -> sbj to vj },
    ) { testType, studentId, examId, sprintDist, protocols ->
        SelectionSnapshot(testType, studentId, examId, sprintDist, protocols.first, protocols.second)
    }

    private val metaFlow = combine(
        _session,
        _isAuthBusy,
        _authError,
        _cameraCaps,
        _visionAssets,
    ) { session, busy, err, caps, assets ->
        MetaSnapshot(session, busy, err, caps, assets)
    }

    private val liveFlow = combine(
        _liveCalibration,
        _livePoseOverlay,
        _activeOutcomeMessage,
        _activeInspection,
        _statusNotice,
    ) { cal, overlay, msg, inspection, notice ->
        LiveSnapshot(cal, overlay, msg, inspection, notice)
    }

    val uiState: StateFlow<AthletiqMainUiState> = combine(
        metaFlow,
        coreDataFlow,
        selectionFlow,
        liveFlow,
        combine(auditFlow, pendingSyncFlow, _videoStorage, _videoRetentionDays, _videoUploadConsentOptIn) { a, p, v, r, u ->
            GovernanceSnapshot(a, p, v, r, u)
        },
    ) { meta, dbSnap, sel, live, gov ->
        AthletiqMainUiState(
            session = meta.session,
            isAuthBusy = meta.isAuthBusy,
            authError = meta.authError,
            cameraCapabilities = meta.cameraCaps,
            visionAssets = meta.visionAssets,
            students = dbSnap.students,
            calibrations = dbSnap.calibrations,
            examinations = dbSnap.exams,
            attempts = dbSnap.attempts,
            standards = dbSnap.standards,
            auditLogs = gov.audits,
            pendingSyncCount = gov.pendingSync,
            videoStorage = gov.videoStorage,
            videoRetentionDays = gov.retentionDays,
            videoUploadConsentOptIn = gov.uploadConsent,
            selectedTestType = sel.testType,
            selectedStudentId = sel.studentId ?: dbSnap.students.firstOrNull()?.id,
            selectedExamId = sel.examId ?: dbSnap.exams.firstOrNull()?.id,
            sprintDistanceMeters = sel.sprintDistanceMeters,
            verticalJumpProtocol = sel.verticalJumpProtocol,
            broadJumpProtocol = sel.broadJumpProtocol,
            liveCalibration = live.calibration,
            livePoseOverlay = live.overlay,
            activeOutcomeMessage = live.outcomeMessage,
            activeInspection = live.inspection,
            statusNotice = live.notice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AthletiqMainUiState(),
    )

    init {
        refreshVideoStats()
    }

    fun login(baseUrl: String, email: String, password: String) {
        viewModelScope.launch {
            _isAuthBusy.value = true
            _authError.value = null
            val res = repository.login(baseUrl, email, password)
            _isAuthBusy.value = false
            res.onSuccess { _session.value = it }
                .onFailure { _authError.value = it.message ?: "Authentication failed" }
        }
    }

    fun bootstrapInstitution(
        baseUrl: String,
        institutionName: String,
        institutionCode: String,
        adminName: String,
        adminEmail: String,
        adminPassword: String,
    ) {
        viewModelScope.launch {
            _isAuthBusy.value = true
            _authError.value = null
            val res = repository.bootstrapAndLogin(
                baseUrl, institutionName, institutionCode, adminName, adminEmail, adminPassword,
            )
            _isAuthBusy.value = false
            res.onSuccess { _session.value = it }
                .onFailure { _authError.value = it.message ?: "Institution bootstrap failed" }
        }
    }

    fun logout() {
        repository.logout()
        _session.value = null
        _activeInspection.value = null
    }

    fun refreshCameraAndAssets() {
        _cameraCaps.value = repository.inspectHardwareCapabilities()
        _visionAssets.value = repository.inspectVisionAssets()
        refreshVideoStats()
    }

    fun selectTest(testType: TestType) {
        _selectedTestType.value = testType
        _activeOutcomeMessage.value = null
    }

    fun selectStudent(studentId: String) {
        _selectedStudentId.value = studentId
    }

    fun selectExam(examId: String) {
        _selectedExamId.value = examId
    }

    fun setSprintDistanceMeters(meters: Double) {
        if (meters.isFinite() && meters > 0.0) _sprintDistanceMeters.value = meters
    }

    fun setVerticalJumpProtocol(protocol: VerticalJumpProtocol) {
        _verticalJumpProtocol.value = protocol
    }

    fun setBroadJumpProtocol(protocol: BroadJumpProtocol) {
        _broadJumpProtocol.value = protocol
    }

    fun selectCalibrationPlane(plane: CalibrationPlaneType) {
        _liveCalibration.value = LiveCalibrationState(selectedPlane = plane)
    }

    fun runCalibrationOnFrame(bitmap: Bitmap) {
        val session = _session.value ?: return
        val plane = _liveCalibration.value.selectedPlane
        viewModelScope.launch(Dispatchers.Default) {
            val outcome = if (plane == CalibrationPlaneType.VERTICAL_PLANE) {
                charucoEngine.calibrateVerticalPlane(bitmap)
            } else {
                charucoEngine.calibrateGroundPlane(bitmap)
            }
            when (outcome) {
                is PlanarCalibrationOutcome.Success -> {
                    repository.saveCalibrationProfile(
                        institutionId = session.institutionId,
                        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                        quality = outcome.quality,
                        homography = outcome.homography,
                        targetSpec = mapOf("board" to "ChArUco 5x7", "square_cm" to "4.0"),
                    )
                    _liveCalibration.value = LiveCalibrationState(
                        selectedPlane = plane,
                        latestOutcomeMessage = "Calibrated (${outcome.detectedCornersCount} corners)",
                        lastReprojectionErrorPx = outcome.quality.reprojectionErrorPx,
                        lastDetectedCorners = outcome.detectedCornersCount,
                        isGatePassed = true,
                    )
                }
                is PlanarCalibrationOutcome.Failed -> {
                    _liveCalibration.value = _liveCalibration.value.copy(
                        latestOutcomeMessage = outcome.reason,
                        isGatePassed = false,
                    )
                }
            }
        }
    }

    fun prepareVisionEngines() {
        val assets = repository.inspectVisionAssets()
        _visionAssets.value = assets
        val poseAsset = assets.poseModelAsset ?: return
        if (poseEngine == null) {
            poseEngine = MediaPipePoseEngine(
                context = getApplication(),
                modelAssetPath = poseAsset,
                runningMode = RunningMode.VIDEO,
            ).apply { initialize(preferGpu = true) }
        }
        if (assets.handModelAsset != null && handRoiEngine == null) {
            handRoiEngine = MediaPipeHandRoiEngine(getApplication(), assets.handModelAsset).apply { initialize() }
        }
        if (assets.objectDetectorAsset != null && subjectDetector == null) {
            subjectDetector = SubjectRoiDetector(getApplication(), assets.objectDetectorAsset).apply { initialize() }
        }
    }

    fun startAttemptCapture() {
        synchronized(capturedFrames) {
            capturedFrames.clear()
        }
        landmarkSmoother.reset()
        _activeOutcomeMessage.value = null
        _livePoseOverlay.value = LivePoseOverlayState(
            isRecording = true,
            statusMessage = "Capturing movement...",
        )
    }

    private fun extractCapturedRecord(
        bitmap: Bitmap,
        timestampNs: Long,
        obs: PoseFrameObservation,
        smoothed: List<LandmarkPoint3D>,
        testType: TestType,
        vjProtocol: VerticalJumpProtocol,
    ): CapturedFrameRecord {
        val crown = if (testType == TestType.HEIGHT) HeadBoundaryRefiner.refineCrown(bitmap, obs) else null
        val handTip = if (testType == TestType.VERTICAL_JUMP && vjProtocol == VerticalJumpProtocol.STANDING_REACH_DELTA) {
            handRoiEngine?.detectHighestFingertip(bitmap, smoothed)
        } else null
        val contacts = if (testType == TestType.STANDING_BROAD_JUMP) GroundContactExtractor.extract(obs) else null
        return CapturedFrameRecord(
            timestampNs = timestampNs,
            imageWidth = obs.imageWidth,
            imageHeight = obs.imageHeight,
            smoothedLandmarks = smoothed,
            refinedCrown = crown,
            refinedHandTip = handTip,
            groundContacts = contacts,
        )
    }

    fun onLiveCameraFrame(bitmap: Bitmap, timestampNs: Long, timingTracker: FrameTimingTracker) {
        if (isImportingVideo.get()) return
        val engine = poseEngine ?: return
        val rawMs = (timestampNs / 1_000_000L).coerceAtLeast(1L)
        val mpTimestampMs = maxOf(lastMediaPipeVideoMs + 1L, rawMs)
        lastMediaPipeVideoMs = mpTimestampMs
        val timing = timingTracker.snapshot()

        val frameNum = liveFrameCounter++
        val multiPersonWarning = if (frameNum % 10L == 0L) {
            subjectDetector?.let { detector ->
                when (val res = detector.screenFrame(bitmap)) {
                    is SubjectScreeningResult.MultiplePersonsDetected ->
                        "Multiple people detected (${res.count}) — clear background to avoid tracking jump"
                    is SubjectScreeningResult.NoPersonDetected -> "No athlete detected in frame"
                    else -> null
                }
            }
        } else {
            _livePoseOverlay.value.subjectCountWarning
        }

        when (val poseRes = engine.detectForVideo(bitmap, mpTimestampMs)) {
            is PoseDetectionResult.Detected -> {
                val obs = poseRes.observation
                val smoothed = if (obs.landmarks.size == PoseLandmarkIndex.LANDMARK_COUNT) {
                    landmarkSmoother.smooth(obs.landmarks, timestampNs)
                } else {
                    obs.landmarks
                }
                val isRec = _livePoseOverlay.value.isRecording
                if (isRec) {
                    val rec = extractCapturedRecord(
                        bitmap, timestampNs, obs, smoothed, _selectedTestType.value, _verticalJumpProtocol.value,
                    )
                    synchronized(capturedFrames) {
                        if (capturedFrames.size < 600) {
                            capturedFrames.add(rec)
                        }
                    }
                }
                _livePoseOverlay.value = _livePoseOverlay.value.copy(
                    landmarks = smoothed,
                    timing = timing,
                    subjectCountWarning = multiPersonWarning,
                    recordedFramesCount = if (isRec) synchronized(capturedFrames) { capturedFrames.size } else 0,
                    statusMessage = multiPersonWarning ?: if (isRec) "Tracking athlete" else "Ready to record",
                )
            }
            is PoseDetectionResult.NoPerson -> {
                _livePoseOverlay.value = _livePoseOverlay.value.copy(
                    landmarks = emptyList(),
                    timing = timing,
                    subjectCountWarning = multiPersonWarning ?: "No athlete detected in frame",
                )
            }
            is PoseDetectionResult.Error -> {
                _livePoseOverlay.value = _livePoseOverlay.value.copy(
                    timing = timing,
                    statusMessage = poseRes.message,
                )
            }
        }
    }

    fun importAndEvaluateVideo(uri: Uri, onComplete: () -> Unit) {
        val state = uiState.value
        if (state.selectedStudentId == null) {
            _activeOutcomeMessage.value = "Select or register an athlete first."
            return
        }
        if (state.selectedExamId == null) {
            _activeOutcomeMessage.value = "Create or select an examination first."
            return
        }
        val requiredPlane = CalibrationQualityGate.requiredPlaneFor(state.selectedTestType)
        if (state.calibrations.none { it.planeType == requiredPlane.name && it.isActive }) {
            _activeOutcomeMessage.value = "Calibration required (${requiredPlane.name}) before analyzing video."
            return
        }

        _activeOutcomeMessage.value = null
        _livePoseOverlay.value = LivePoseOverlayState(
            isRecording = true,
            statusMessage = "Decoding imported video...",
        )

        viewModelScope.launch(Dispatchers.Default) {
            isImportingVideo.set(true)
            prepareVisionEngines()
            val engine = poseEngine
            if (engine == null) {
                isImportingVideo.set(false)
                _livePoseOverlay.value = LivePoseOverlayState(isRecording = false, statusMessage = "Pose engine unavailable")
                _activeOutcomeMessage.value = "Pose model not available in assets."
                return@launch
            }

            landmarkSmoother.reset()
            val decodedFrames = mutableListOf<CapturedFrameRecord>()
            val retriever = MediaMetadataRetriever()
            var detectedFps = 30.0
            val testType = state.selectedTestType
            val vjProtocol = state.verticalJumpProtocol

            try {
                retriever.setDataSource(getApplication<Application>(), uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(100L) ?: 0L
                val totalFrameCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
                } else null

                val maxFramesToProcess = 360
                if (totalFrameCount != null && totalFrameCount > 1 && durationMs > 0L) {
                    val stride = maxOf(1, (totalFrameCount + maxFramesToProcess - 1) / maxFramesToProcess)
                    val targetIndices = (0 until totalFrameCount step stride).toList()
                    val nativeFps = (totalFrameCount * 1000.0 / durationMs).coerceIn(10.0, 240.0)
                    detectedFps = nativeFps / stride
                    val frameStepNs = ((durationMs * 1_000_000.0) / totalFrameCount).toLong().coerceAtLeast(1_000_000L)
                    var prevNs = 0L

                    for ((pos, frameIdx) in targetIndices.withIndex()) {
                        val rawBmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            retriever.getFrameAtIndex(frameIdx)
                        } else null
                        if (rawBmp == null) continue

                        val argb = if (rawBmp.config != Bitmap.Config.ARGB_8888) {
                            rawBmp.copy(Bitmap.Config.ARGB_8888, false).also { rawBmp.recycle() }
                        } else rawBmp

                        val tsNs = ((frameIdx + 1L) * frameStepNs).coerceAtLeast(prevNs + 1_000_000L)
                        prevNs = tsNs
                        val mpMs = maxOf(lastMediaPipeVideoMs + 1L, (tsNs / 1_000_000L).coerceAtLeast(1L))
                        lastMediaPipeVideoMs = mpMs

                        try {
                            when (val poseRes = engine.detectForVideo(argb, mpMs)) {
                                is PoseDetectionResult.Detected -> {
                                    val obs = poseRes.observation
                                    val smoothed = if (obs.landmarks.size == PoseLandmarkIndex.LANDMARK_COUNT) {
                                        landmarkSmoother.smooth(obs.landmarks, tsNs)
                                    } else obs.landmarks
                                    decodedFrames.add(
                                        extractCapturedRecord(argb, tsNs, obs, smoothed, testType, vjProtocol),
                                    )
                                    _livePoseOverlay.value = _livePoseOverlay.value.copy(
                                        landmarks = smoothed,
                                        recordedFramesCount = decodedFrames.size,
                                        statusMessage = "Analyzing frame ${pos + 1} / ${targetIndices.size}",
                                    )
                                }
                                else -> {
                                    _livePoseOverlay.value = _livePoseOverlay.value.copy(
                                        recordedFramesCount = decodedFrames.size,
                                        statusMessage = "Scanning frame ${pos + 1} / ${targetIndices.size}",
                                    )
                                }
                            }
                        } finally {
                            argb.recycle()
                        }
                    }
                } else {
                    val stepUs = if (durationMs > 0L) {
                        maxOf(33_333L, (durationMs * 1000L) / maxFramesToProcess)
                    } else 33_333L
                    detectedFps = (1_000_000.0 / stepUs).coerceIn(10.0, 120.0)
                    val endUs = (durationMs * 1000L).coerceAtLeast(stepUs * 10L)
                    var tUs = 0L
                    var prevNs = 0L
                    var count = 0
                    while (tUs <= endUs && count < maxFramesToProcess) {
                        val rawBmp = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        if (rawBmp != null) {
                            val argb = if (rawBmp.config != Bitmap.Config.ARGB_8888) {
                                rawBmp.copy(Bitmap.Config.ARGB_8888, false).also { rawBmp.recycle() }
                            } else rawBmp
                            val tsNs = ((tUs + stepUs) * 1000L).coerceAtLeast(prevNs + 1_000_000L)
                            prevNs = tsNs
                            val mpMs = maxOf(lastMediaPipeVideoMs + 1L, (tsNs / 1_000_000L).coerceAtLeast(1L))
                            lastMediaPipeVideoMs = mpMs
                            try {
                                when (val poseRes = engine.detectForVideo(argb, mpMs)) {
                                    is PoseDetectionResult.Detected -> {
                                        val obs = poseRes.observation
                                        val smoothed = if (obs.landmarks.size == PoseLandmarkIndex.LANDMARK_COUNT) {
                                            landmarkSmoother.smooth(obs.landmarks, tsNs)
                                        } else obs.landmarks
                                        decodedFrames.add(
                                            extractCapturedRecord(argb, tsNs, obs, smoothed, testType, vjProtocol),
                                        )
                                        _livePoseOverlay.value = _livePoseOverlay.value.copy(
                                            landmarks = smoothed,
                                            recordedFramesCount = decodedFrames.size,
                                            statusMessage = "Analyzing video (${decodedFrames.size} pose frames)",
                                        )
                                    }
                                    else -> Unit
                                }
                            } finally {
                                argb.recycle()
                            }
                        }
                        tUs += stepUs
                        count++
                    }
                }
            } catch (t: Throwable) {
                _livePoseOverlay.value = LivePoseOverlayState(isRecording = false, statusMessage = "Ready to record")
                _activeOutcomeMessage.value = "Video import failed: ${t.message}"
                return@launch
            } finally {
                isImportingVideo.set(false)
                runCatching { retriever.release() }
            }

            evaluateCapturedFrames(decodedFrames, detectedFps, onComplete)
        }
    }

    fun stopAndEvaluateAttempt(onComplete: () -> Unit) {
        _livePoseOverlay.value = _livePoseOverlay.value.copy(isRecording = false, statusMessage = "Processing attempt...")
        val framesCopy: List<CapturedFrameRecord> = synchronized(capturedFrames) {
            capturedFrames.toList().also { capturedFrames.clear() }
        }
        viewModelScope.launch(Dispatchers.Default) {
            evaluateCapturedFrames(framesCopy, expectedFps = 30.0, onComplete = onComplete)
        }
    }

    private suspend fun evaluateCapturedFrames(
        framesCopy: List<CapturedFrameRecord>,
        expectedFps: Double,
        onComplete: () -> Unit,
    ) {
        val state = uiState.value
        val session = state.session ?: return
        val studentId = state.selectedStudentId ?: run {
            _livePoseOverlay.value = LivePoseOverlayState(isRecording = false, statusMessage = "Ready to record")
            _activeOutcomeMessage.value = "Select or register an athlete first."
            return
        }
        val examId = state.selectedExamId ?: run {
            _livePoseOverlay.value = LivePoseOverlayState(isRecording = false, statusMessage = "Ready to record")
            _activeOutcomeMessage.value = "Create or select an examination first."
            return
        }
        val testType = state.selectedTestType
        val requiredPlane = CalibrationQualityGate.requiredPlaneFor(testType)
        val calEntity = state.calibrations.firstOrNull { it.planeType == requiredPlane.name && it.isActive }
        val calQuality = calEntity?.let { repository.toCalibrationQuality(it) }
        val homography = calEntity?.let { repository.decodeHomography(it) }

        try {
            if (framesCopy.size < 5) {
                _activeOutcomeMessage.value = "Insufficient pose frames captured (${framesCopy.size}). Record or import a full attempt."
                return
            }
            val tracker = FrameTimingTracker(expectedFps = expectedFps)
            framesCopy.forEach { tracker.recordFrameTimestampNs(it.timestampNs) }
            val timing = tracker.snapshot()
            val nowMs = System.currentTimeMillis()

                when (testType) {
                    TestType.HEIGHT -> {
                        val samples = framesCopy.map { rec ->
                            HeightFrameSample(
                                timestampNs = rec.timestampNs,
                                imageWidth = rec.imageWidth,
                                imageHeight = rec.imageHeight,
                                landmarks = rec.smoothedLandmarks,
                                headCrown = rec.refinedCrown,
                            )
                        }
                        when (val outcome = heightCoordinator.evaluateAttempt(samples, calQuality, homography, timing, nowMs)) {
                            is MeasurementOutcome.Success -> {
                                val entity = repository.saveMeasuredAttempt(
                                    institutionId = session.institutionId,
                                    examinationId = examId,
                                    studentId = studentId,
                                    testType = testType,
                                    value = outcome.value.heightCm,
                                    unit = outcome.unit,
                                    uncertainty = outcome.uncertainty,
                                    confidenceScore = outcome.confidence.score,
                                    provenance = outcome.provenance,
                                    validityStatus = if (outcome.requiresExaminerReview) AttemptValidityStatus.FLAGGED else AttemptValidityStatus.VALID,
                                    rawMetricsJson = """{"iqr_cm":${outcome.value.interQuartileRangeCm},"hair_flag":${outcome.value.possibleHairOrHeadwear}}""",
                                    encryptedVideoPath = null,
                                    videoUploadConsent = false,
                                )
                                _activeInspection.value = AttemptInspectionBundle(entity, heightDetail = outcome.value)
                                withContext(Dispatchers.Main) { onComplete() }
                            }
                            else -> _activeOutcomeMessage.value = formatNonSuccessOutcome(outcome)
                        }
                    }
                    TestType.VERTICAL_JUMP -> {
                        val samples = framesCopy.map { rec ->
                            VerticalJumpFrameSample(
                                timestampNs = rec.timestampNs,
                                imageWidth = rec.imageWidth,
                                imageHeight = rec.imageHeight,
                                landmarks = rec.smoothedLandmarks,
                                refinedHandTip = rec.refinedHandTip,
                            )
                        }
                        when (
                            val outcome = verticalJumpCoordinator.evaluateAttempt(
                                samples, calQuality, homography, timing, nowMs, state.verticalJumpProtocol,
                            )
                        ) {
                            is MeasurementOutcome.Success -> {
                                val bioReport = if (calQuality != null && timing != null) {
                                    val bioSamples = framesCopy.map {
                                        BiomechanicsFrameSample(it.timestampNs, it.imageWidth, it.imageHeight, it.smoothedLandmarks)
                                    }
                                    (biomechanicsAnalyzer.analyze(
                                        bioSamples, calQuality, timing, nowMs, homography,
                                        outcome.value.takeoffTimestampNs, outcome.value.landingTimestampNs,
                                    ) as? MeasurementOutcome.Success)?.value
                                } else null
                                val entity = repository.saveMeasuredAttempt(
                                    institutionId = session.institutionId,
                                    examinationId = examId,
                                    studentId = studentId,
                                    testType = testType,
                                    value = outcome.value.jumpHeightCm,
                                    unit = outcome.unit,
                                    uncertainty = outcome.uncertainty,
                                    confidenceScore = outcome.confidence.score,
                                    provenance = outcome.provenance,
                                    validityStatus = if (outcome.requiresExaminerReview) AttemptValidityStatus.FLAGGED else AttemptValidityStatus.VALID,
                                    rawMetricsJson = """{"standing_reach_cm":${outcome.value.standingReachCm},"max_reach_cm":${outcome.value.maxJumpReachCm},"flight_cm":${outcome.value.flightTimeEstimate.flightHeightCm}}""",
                                    encryptedVideoPath = null,
                                    videoUploadConsent = false,
                                )
                                _activeInspection.value = AttemptInspectionBundle(entity, biomechanics = bioReport, verticalJumpDetail = outcome.value)
                                withContext(Dispatchers.Main) { onComplete() }
                            }
                            else -> _activeOutcomeMessage.value = formatNonSuccessOutcome(outcome)
                        }
                    }
                    TestType.STANDING_BROAD_JUMP -> {
                        val samples = framesCopy.map { rec ->
                            BroadJumpFrameSample(
                                timestampNs = rec.timestampNs,
                                imageWidth = rec.imageWidth,
                                imageHeight = rec.imageHeight,
                                landmarks = rec.smoothedLandmarks,
                                maskContacts = rec.groundContacts,
                            )
                        }
                        when (
                            val outcome = broadJumpCoordinator.evaluateAttempt(
                                samples, calQuality, homography, timing, nowMs, state.broadJumpProtocol,
                            )
                        ) {
                            is MeasurementOutcome.Success -> {
                                val bioReport = if (calQuality != null && timing != null) {
                                    val bioSamples = framesCopy.map {
                                        BiomechanicsFrameSample(it.timestampNs, it.imageWidth, it.imageHeight, it.smoothedLandmarks)
                                    }
                                    (biomechanicsAnalyzer.analyze(
                                        bioSamples, calQuality, timing, nowMs, homography,
                                        outcome.value.takeoffTimestampNs, outcome.value.landingTimestampNs,
                                    ) as? MeasurementOutcome.Success)?.value
                                } else null
                                val entity = repository.saveMeasuredAttempt(
                                    institutionId = session.institutionId,
                                    examinationId = examId,
                                    studentId = studentId,
                                    testType = testType,
                                    value = outcome.value.distanceCm,
                                    unit = outcome.unit,
                                    uncertainty = outcome.uncertainty,
                                    confidenceScore = outcome.confidence.score,
                                    provenance = outcome.provenance,
                                    validityStatus = if (outcome.requiresExaminerReview) AttemptValidityStatus.FLAGGED else AttemptValidityStatus.VALID,
                                    rawMetricsJson = """{"contact":"${outcome.value.landingContactKind.name}","stability":"${outcome.value.stabilityStatus.name}"}""",
                                    encryptedVideoPath = null,
                                    videoUploadConsent = false,
                                )
                                _activeInspection.value = AttemptInspectionBundle(entity, biomechanics = bioReport, broadJumpDetail = outcome.value)
                                withContext(Dispatchers.Main) { onComplete() }
                            }
                            else -> _activeOutcomeMessage.value = formatNonSuccessOutcome(outcome)
                        }
                    }
                    TestType.SPRINT -> {
                        val samples = framesCopy.map { rec ->
                            SprintFrameSample(
                                timestampNs = rec.timestampNs,
                                imageWidth = rec.imageWidth,
                                imageHeight = rec.imageHeight,
                                landmarks = rec.smoothedLandmarks,
                            )
                        }
                        when (
                            val outcome = sprintCoordinator.evaluateAttempt(
                                samples, state.sprintDistanceMeters, calQuality, homography, timing, nowMs,
                            )
                        ) {
                            is MeasurementOutcome.Success -> {
                                val entity = repository.saveMeasuredAttempt(
                                    institutionId = session.institutionId,
                                    examinationId = examId,
                                    studentId = studentId,
                                    testType = testType,
                                    value = outcome.value.splitTimeSec,
                                    unit = outcome.unit,
                                    uncertainty = outcome.uncertainty,
                                    confidenceScore = outcome.confidence.score,
                                    provenance = outcome.provenance,
                                    validityStatus = if (outcome.requiresExaminerReview) AttemptValidityStatus.FLAGGED else AttemptValidityStatus.VALID,
                                    rawMetricsJson = """{"distance_m":${outcome.value.distanceMeters},"avg_speed_mps":${outcome.value.averageSpeedMps}}""",
                                    encryptedVideoPath = null,
                                    videoUploadConsent = false,
                                )
                                _activeInspection.value = AttemptInspectionBundle(entity, sprintDetail = outcome.value)
                                withContext(Dispatchers.Main) { onComplete() }
                            }
                            else -> _activeOutcomeMessage.value = formatNonSuccessOutcome(outcome)
                        }
                    }
                }
            } finally {
                _livePoseOverlay.value = _livePoseOverlay.value.copy(
                    isRecording = false,
                    statusMessage = "Ready to record",
                )
            }
        }

    fun openAttemptForReview(attempt: AttemptEntity) {
        _activeInspection.value = AttemptInspectionBundle(attempt = attempt)
    }

    fun reviewAttempt(attempt: AttemptEntity, status: ApprovalStatus, note: String? = null) {
        val session = _session.value ?: return
        viewModelScope.launch {
            repository.reviewAttempt(session.institutionId, attempt, status, note)
            _activeInspection.value = _activeInspection.value?.let { bundle ->
                if (bundle.attempt.clientUuid == attempt.clientUuid) {
                    bundle.copy(attempt = bundle.attempt.copy(approvalStatus = status.name))
                } else bundle
            }
            _statusNotice.value = "Attempt marked ${status.name}"
        }
    }

    fun registerStudent(code: String, fullName: String, dobIso: String?, gender: String?, dept: String?) {
        val session = _session.value ?: return
        viewModelScope.launch {
            runCatching {
                repository.registerStudent(session.institutionId, code, fullName, dobIso, gender, dept)
            }.onSuccess { created ->
                _selectedStudentId.value = created.id
                _statusNotice.value = "Registered athlete ${created.fullName}"
            }.onFailure { err ->
                _statusNotice.value = "Could not register athlete: ${err.message}"
            }
        }
    }

    fun createExamination(title: String, tests: List<TestType>) {
        val session = _session.value ?: return
        viewModelScope.launch {
            runCatching {
                repository.createExamination(session.institutionId, title, tests)
            }.onSuccess { created ->
                _selectedExamId.value = created.id
                _statusNotice.value = "Created examination ${created.title}"
            }.onFailure { err ->
                _statusNotice.value = "Could not create exam: ${err.message}"
            }
        }
    }

    fun createScoringStandard(
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
    ) {
        val session = _session.value ?: return
        viewModelScope.launch {
            repository.saveScoringStandard(
                session.institutionId, name, testType, ageMin, ageMax, gender, discipline, unit, direction, bands, citation,
            )
            _statusNotice.value = "Saved scoring standard $name"
        }
    }

    fun triggerSyncNow() {
        repository.enqueueSync()
        _statusNotice.value = "Outbox sync scheduled"
    }

    fun setVideoRetentionDays(days: Int) {
        _videoRetentionDays.value = days
    }

    fun setVideoUploadConsentOptIn(enabled: Boolean) {
        _videoUploadConsentOptIn.value = enabled
    }

    fun purgeExpiredVideos() {
        viewModelScope.launch {
            val deleted = repository.purgeExpiredVideos(_videoRetentionDays.value)
            refreshVideoStats()
            _statusNotice.value = "Purged $deleted expired encrypted video clip(s)"
        }
    }

    fun deleteAllVideos() {
        viewModelScope.launch {
            val deleted = repository.deleteAllLocalVideos()
            refreshVideoStats()
            _statusNotice.value = "Deleted $deleted local encrypted video clip(s)"
        }
    }

    fun exportStudentPdf(student: StudentEntity, onReady: (File) -> Unit) {
        val state = uiState.value
        val session = state.session ?: return
        val studentAttempts = state.attempts.filter { it.studentId == student.id }
        viewModelScope.launch {
            val file = repository.exportStudentPdf(session.institutionName, student, studentAttempts)
            onReady(file)
        }
    }

    fun exportExamPdf(exam: ExaminationEntity, onReady: (File) -> Unit) {
        val state = uiState.value
        val session = state.session ?: return
        val examAttempts = state.attempts.filter { it.examinationId == exam.id }
        viewModelScope.launch {
            val file = repository.exportExaminationPdf(session.institutionName, exam, examAttempts, state.students)
            onReady(file)
        }
    }

    fun clearStatusNotice() {
        _statusNotice.value = null
    }

    private fun refreshVideoStats() {
        viewModelScope.launch {
            _videoStorage.value = repository.getVideoStorageSummary()
        }
    }

    private fun formatNonSuccessOutcome(outcome: MeasurementOutcome<*>): String = when (outcome) {
        is MeasurementOutcome.CalibrationRequired -> "Calibration required (${outcome.requiredPlane}): ${outcome.reason}"
        is MeasurementOutcome.LowConfidence -> "Low confidence (${"%.2f".format(outcome.observedScore)} < ${"%.2f".format(outcome.minimumRequired)}): ${outcome.reason}"
        is MeasurementOutcome.InvalidAttempt -> "Invalid attempt: ${outcome.reasons.joinToString("; ")}"
        is MeasurementOutcome.InsufficientData -> outcome.reason
        is MeasurementOutcome.Unavailable -> outcome.reason
        is MeasurementOutcome.Success -> "Completed"
    }

    override fun onCleared() {
        super.onCleared()
        poseEngine?.close()
        handRoiEngine?.close()
        subjectDetector?.close()
    }

    private data class CoreDbSnapshot(
        val students: List<StudentEntity>,
        val calibrations: List<CalibrationProfileEntity>,
        val exams: List<ExaminationEntity>,
        val attempts: List<AttemptEntity>,
        val standards: List<ScoringStandardEntity>,
    )

    private data class SelectionSnapshot(
        val testType: TestType,
        val studentId: String?,
        val examId: String?,
        val sprintDistanceMeters: Double,
        val broadJumpProtocol: BroadJumpProtocol,
        val verticalJumpProtocol: VerticalJumpProtocol,
    )

    private data class MetaSnapshot(
        val session: SessionMetadata?,
        val isAuthBusy: Boolean,
        val authError: String?,
        val cameraCaps: CameraHardwareCapabilities?,
        val visionAssets: VisionAssetStatus,
    )

    private data class LiveSnapshot(
        val calibration: LiveCalibrationState,
        val overlay: LivePoseOverlayState,
        val outcomeMessage: String?,
        val inspection: AttemptInspectionBundle?,
        val notice: String?,
    )

    private data class GovernanceSnapshot(
        val audits: List<LocalAuditEventEntity>,
        val pendingSync: Int,
        val videoStorage: VideoStorageSummary,
        val retentionDays: Int,
        val uploadConsent: Boolean,
    )
}
