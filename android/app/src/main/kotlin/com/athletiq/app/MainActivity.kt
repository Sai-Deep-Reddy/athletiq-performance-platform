package com.athletiq.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.athletiq.app.state.AthletiqViewModel
import com.athletiq.app.ui.screens.AdminSecurityAndReportsScreen
import com.athletiq.app.ui.screens.AthleteHistoryAndRankingsScreen
import com.athletiq.app.ui.screens.AttemptReviewScreen
import com.athletiq.app.ui.screens.AuthScreen
import com.athletiq.app.ui.screens.CalibrationScreen
import com.athletiq.app.ui.screens.DashboardScreen
import com.athletiq.app.ui.screens.ExamRunnerScreen
import com.athletiq.app.ui.screens.LiveCaptureScreen
import com.athletiq.app.ui.screens.PostLandingInspectionScreen
import com.athletiq.app.ui.screens.RosterScreen
import com.athletiq.app.ui.theme.AthletiqColors
import com.athletiq.app.ui.theme.AthletiqTheme
import com.athletiq.core.biomechanics.CalibrationQualityGate
import com.athletiq.core.model.ApprovalStatus
import java.io.File

private enum class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    DASHBOARD("dashboard", "Dashboard", Icons.Rounded.Dashboard),
    EXAMS("exams", "Assess", Icons.Rounded.Timer),
    ROSTER("roster", "Roster", Icons.Rounded.Groups),
    GOVERNANCE("governance", "Reports", Icons.Rounded.AdminPanelSettings),
}

private const val ROUTE_CALIBRATION = "calibration"
private const val ROUTE_CAPTURE = "capture"
private const val ROUTE_REVIEW = "review"
private const val ROUTE_BROAD_JUMP_AUDIT = "broad_jump_audit"
private const val ROUTE_HISTORY = "history"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AthletiqTheme {
                val vm: AthletiqViewModel = viewModel()
                AthletiqAppShell(
                    viewModel = vm,
                    onSharePdf = { file -> sharePdfFile(file) },
                )
            }
        }
    }

    private fun sharePdfFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Open AthletiQ PDF Report")
        startActivity(chooser)
    }
}

@Composable
private fun AthletiqAppShell(
    viewModel: AthletiqViewModel,
    onSharePdf: (File) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.statusNotice) {
        val msg = state.statusNotice
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusNotice()
        }
    }

    if (state.session == null) {
        AuthScreen(
            isBusy = state.isAuthBusy,
            errorMessage = state.authError,
            onLogin = viewModel::login,
            onBootstrapInstitution = viewModel::bootstrapInstitution,
        )
        return
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in TopDestination.entries.map { it.route } || currentRoute == ROUTE_HISTORY

    Scaffold(
        containerColor = AthletiqColors.SurfaceCanvas,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = AthletiqColors.SurfaceCard) {
                    TopDestination.entries.forEach { dest ->
                        val selected = currentRoute == dest.route || (dest == TopDestination.ROSTER && currentRoute == ROUTE_HISTORY)
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (dest == TopDestination.DASHBOARD) {
                                    navController.popBackStack(TopDestination.DASHBOARD.route, inclusive = false)
                                } else {
                                    navController.navigate(dest.route) {
                                        popUpTo(TopDestination.DASHBOARD.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            NavHost(
                navController = navController,
                startDestination = TopDestination.DASHBOARD.route,
            ) {
                composable(TopDestination.DASHBOARD.route) {
                    DashboardScreen(
                        state = state,
                        onSyncNow = viewModel::triggerSyncNow,
                        onOpenCalibration = { plane ->
                            viewModel.selectCalibrationPlane(plane)
                            navController.navigate(ROUTE_CALIBRATION)
                        },
                        onLaunchTest = { testType ->
                            viewModel.selectTest(testType)
                            val reqPlane = CalibrationQualityGate.requiredPlaneFor(testType)
                            val hasCal = state.calibrations.any { it.planeType == reqPlane.name && it.isActive }
                            if (!hasCal) {
                                viewModel.selectCalibrationPlane(reqPlane)
                                navController.navigate(ROUTE_CALIBRATION)
                            } else {
                                navController.navigate(ROUTE_CAPTURE)
                            }
                        },
                        onOpenExamRunner = { examId ->
                            viewModel.selectExam(examId)
                            navController.navigate(TopDestination.EXAMS.route)
                        },
                        onOpenRoster = { navController.navigate(TopDestination.ROSTER.route) },
                    )
                }

                composable(TopDestination.EXAMS.route) {
                    ExamRunnerScreen(
                        state = state,
                        onSyncNow = viewModel::triggerSyncNow,
                        onSelectExam = viewModel::selectExam,
                        onSelectStudent = viewModel::selectStudent,
                        onSelectTest = viewModel::selectTest,
                        onCreateExamination = viewModel::createExamination,
                        onStartCapture = { navController.navigate(ROUTE_CAPTURE) },
                        onInspectAttempt = { attempt ->
                            viewModel.openAttemptForReview(attempt)
                            navController.navigate(ROUTE_REVIEW)
                        },
                        onApproveAttempt = { attempt -> viewModel.reviewAttempt(attempt, ApprovalStatus.APPROVED) },
                        onRejectAttempt = { attempt -> viewModel.reviewAttempt(attempt, ApprovalStatus.REJECTED) },
                    )
                }

                composable(TopDestination.ROSTER.route) {
                    RosterScreen(
                        state = state,
                        onSyncNow = viewModel::triggerSyncNow,
                        onRegisterStudent = viewModel::registerStudent,
                        onSelectStudentForHistory = { stuId ->
                            viewModel.selectStudent(stuId)
                            navController.navigate(ROUTE_HISTORY)
                        },
                        onStartAssessmentForStudent = { stuId ->
                            viewModel.selectStudent(stuId)
                            navController.navigate(TopDestination.EXAMS.route)
                        },
                    )
                }

                composable(ROUTE_HISTORY) {
                    AthleteHistoryAndRankingsScreen(
                        state = state,
                        repository = viewModel.repository,
                        onSyncNow = viewModel::triggerSyncNow,
                        onSelectStudent = viewModel::selectStudent,
                        onExportStudentPdf = { stu ->
                            viewModel.exportStudentPdf(stu, onSharePdf)
                        },
                    )
                }

                composable(TopDestination.GOVERNANCE.route) {
                    AdminSecurityAndReportsScreen(
                        state = state,
                        onSyncNow = viewModel::triggerSyncNow,
                        onSetRetentionDays = viewModel::setVideoRetentionDays,
                        onSetUploadConsent = viewModel::setVideoUploadConsentOptIn,
                        onPurgeExpiredVideos = viewModel::purgeExpiredVideos,
                        onDeleteAllVideos = viewModel::deleteAllVideos,
                        onExportExamPdf = { exam -> viewModel.exportExamPdf(exam, onSharePdf) },
                        onCreateStandard = viewModel::createScoringStandard,
                        onLogout = viewModel::logout,
                    )
                }

                composable(ROUTE_CALIBRATION) {
                    CalibrationScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        onSelectPlane = viewModel::selectCalibrationPlane,
                        onCalibrateBitmap = viewModel::runCalibrationOnFrame,
                        onProceedToCapture = {
                            navController.navigate(ROUTE_CAPTURE) {
                                popUpTo(ROUTE_CALIBRATION) { inclusive = true }
                            }
                        },
                    )
                }

                composable(ROUTE_CAPTURE) {
                    LiveCaptureScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        onOpenCalibration = { plane ->
                            viewModel.selectCalibrationPlane(plane)
                            navController.navigate(ROUTE_CALIBRATION)
                        },
                        onPrepareEngines = viewModel::prepareVisionEngines,
                        onLiveFrame = viewModel::onLiveCameraFrame,
                        onStartRecording = viewModel::startAttemptCapture,
                        onStopAndEvaluate = {
                            viewModel.stopAndEvaluateAttempt {
                                navController.navigate(ROUTE_REVIEW)
                            }
                        },
                        onImportVideo = { uri ->
                            viewModel.importAndEvaluateVideo(uri) {
                                navController.navigate(ROUTE_REVIEW)
                            }
                        },
                        onSelectVerticalJumpProtocol = viewModel::setVerticalJumpProtocol,
                        onSelectBroadJumpProtocol = viewModel::setBroadJumpProtocol,
                        onSetSprintDistance = viewModel::setSprintDistanceMeters,
                    )
                }

                composable(ROUTE_REVIEW) {
                    AttemptReviewScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        onApprove = { att -> viewModel.reviewAttempt(att, ApprovalStatus.APPROVED) },
                        onReject = { att -> viewModel.reviewAttempt(att, ApprovalStatus.REJECTED) },
                        onRetake = {
                            navController.navigate(ROUTE_CAPTURE) {
                                popUpTo(ROUTE_REVIEW) { inclusive = true }
                            }
                        },
                        onOpenBroadJumpInspection = { navController.navigate(ROUTE_BROAD_JUMP_AUDIT) },
                    )
                }

                composable(ROUTE_BROAD_JUMP_AUDIT) {
                    PostLandingInspectionScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        onApprove = { att, note ->
                            viewModel.reviewAttempt(att, ApprovalStatus.APPROVED, note)
                            navController.popBackStack()
                        },
                        onReject = { att, note ->
                            viewModel.reviewAttempt(att, ApprovalStatus.REJECTED, note)
                            navController.popBackStack()
                        },
                    )
                }
            }
        }
    }
}
