package com.example.orgclock.ui.app

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.data.RootAccess
import com.example.orgclock.domain.ClockMutationResult
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.notification.NotificationDisplayMode
import com.example.orgclock.ui.perf.PerformanceMonitor
import com.example.orgclock.ui.state.OrgClockUiAction
import com.example.orgclock.ui.state.OrgClockUiState
import com.example.orgclock.ui.state.Screen
import com.example.orgclock.ui.viewmodel.OrgClockViewModel
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

data class OrgClockRouteDependencies(
    val loadSavedUri: () -> Uri?,
    val saveUri: (Uri) -> Unit,
    val openRoot: suspend (Uri) -> Result<RootAccess>,
    val listFiles: suspend () -> Result<List<OrgFileEntry>>,
    val listFilesWithOpenClock: suspend () -> Result<Set<String>>,
    val listHeadings: suspend (String) -> Result<List<HeadingViewItem>>,
    val startClock: suspend (String, Int) -> Result<ClockMutationResult>,
    val stopClock: suspend (String, Int) -> Result<ClockMutationResult>,
    val cancelClock: suspend (String, Int) -> Result<ClockMutationResult>,
    val listClosedClocks: suspend (String, Int) -> Result<List<ClosedClockEntry>>,
    val editClosedClock: suspend (String, Int, Int, ZonedDateTime, ZonedDateTime) -> Result<Unit>,
    val deleteClosedClock: suspend (String, Int, Int) -> Result<Unit>,
    val createL1Heading: suspend (String, String, Boolean) -> Result<Unit>,
    val createL2Heading: suspend (String, Int, String, Boolean) -> Result<Unit>,
    val loadNotificationEnabled: () -> Boolean,
    val saveNotificationEnabled: (Boolean) -> Unit,
    val loadNotificationDisplayMode: () -> NotificationDisplayMode,
    val saveNotificationDisplayMode: (NotificationDisplayMode) -> Unit,
    val notificationPermissionGrantedProvider: () -> Boolean,
    val syncNotificationService: (Boolean, NotificationDisplayMode) -> Unit,
    val stopNotificationService: () -> Unit,
    val openAppNotificationSettings: () -> Unit,
    val nowProvider: () -> ZonedDateTime,
    val todayProvider: () -> LocalDate,
    val zoneIdProvider: () -> ZoneId,
    val showPerfOverlay: Boolean,
)

@Composable
fun OrgClockRoute(
    dependencies: OrgClockRouteDependencies,
    performanceMonitor: PerformanceMonitor,
) {
    val factory = remember(dependencies) {
        orgClockViewModelFactory(dependencies)
    }

    val viewModel: OrgClockViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    val rootPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            viewModel.onAction(OrgClockUiAction.PickRoot(uri))
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onAction(OrgClockUiAction.NotificationPermissionResult(granted))
    }

    LaunchedEffect(Unit) {
        viewModel.onAction(OrgClockUiAction.Initialize)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onAction(OrgClockUiAction.RefreshNotificationPermissionState)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(state.notificationPermissionRequestPending) {
        if (!state.notificationPermissionRequestPending) return@LaunchedEffect
        viewModel.onAction(OrgClockUiAction.RequestNotificationPermissionHandled)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.onAction(OrgClockUiAction.NotificationPermissionResult(true))
        }
    }
    LaunchedEffect(state.openAppNotificationSettingsPending) {
        if (!state.openAppNotificationSettingsPending) return@LaunchedEffect
        dependencies.openAppNotificationSettings()
        viewModel.onAction(OrgClockUiAction.AppNotificationSettingsOpened)
    }
    val notificationSyncKey = remember(state) { buildNotificationSyncKey(state) }
    LaunchedEffect(notificationSyncKey) {
        if (!state.notificationEnabled || state.rootUri == null) {
            dependencies.stopNotificationService()
            return@LaunchedEffect
        }
        delay(NOTIFICATION_SYNC_DEBOUNCE_MS)
        dependencies.syncNotificationService(
            state.notificationEnabled,
            state.notificationDisplayMode,
        )
    }

    DisposableEffect(state.screen) {
        if (state.screen == Screen.HeadingList) {
            performanceMonitor.reset()
            performanceMonitor.setTrackingEnabled(true)
        } else {
            performanceMonitor.setTrackingEnabled(false)
        }
        onDispose { }
    }

    OrgClockScreen(
        state = state,
        performanceMonitor = performanceMonitor,
        zoneIdProvider = dependencies.zoneIdProvider,
        nowProvider = dependencies.nowProvider,
        onPickRoot = { rootPicker.launch(null) },
        onAction = viewModel::onAction,
    )
}

private fun orgClockViewModelFactory(
    dependencies: OrgClockRouteDependencies,
): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(OrgClockViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            val viewModel = OrgClockViewModel(
                loadSavedUri = dependencies.loadSavedUri,
                saveUri = dependencies.saveUri,
                openRoot = dependencies.openRoot,
                listFiles = dependencies.listFiles,
                listFilesWithOpenClock = dependencies.listFilesWithOpenClock,
                listHeadings = dependencies.listHeadings,
                startClock = dependencies.startClock,
                stopClock = dependencies.stopClock,
                cancelClock = dependencies.cancelClock,
                listClosedClocks = dependencies.listClosedClocks,
                editClosedClock = dependencies.editClosedClock,
                deleteClosedClock = dependencies.deleteClosedClock,
                createL1Heading = dependencies.createL1Heading,
                createL2Heading = dependencies.createL2Heading,
                loadNotificationEnabled = dependencies.loadNotificationEnabled,
                saveNotificationEnabled = dependencies.saveNotificationEnabled,
                loadNotificationDisplayMode = dependencies.loadNotificationDisplayMode,
                saveNotificationDisplayMode = dependencies.saveNotificationDisplayMode,
                notificationPermissionGrantedProvider = dependencies.notificationPermissionGrantedProvider,
                nowProvider = dependencies.nowProvider,
                todayProvider = dependencies.todayProvider,
                showPerfOverlay = dependencies.showPerfOverlay,
            )
            return modelClass.cast(viewModel)
                ?: throw IllegalStateException("Failed to cast ViewModel: ${modelClass.name}")
        }
    }
}

internal data class NotificationSyncKey(
    val notificationEnabled: Boolean,
    val notificationDisplayMode: NotificationDisplayMode,
    val rootUri: Uri?,
    val openClockFootprint: Set<Int>,
)

internal fun buildNotificationSyncKey(state: OrgClockUiState): NotificationSyncKey {
    return NotificationSyncKey(
        notificationEnabled = state.notificationEnabled,
        notificationDisplayMode = state.notificationDisplayMode,
        rootUri = state.rootUri,
        openClockFootprint = state.headings
            .asSequence()
            .filter { it.openClock != null }
            .map { it.node.lineIndex }
            .toSet(),
    )
}

private const val NOTIFICATION_SYNC_DEBOUNCE_MS = 120L
