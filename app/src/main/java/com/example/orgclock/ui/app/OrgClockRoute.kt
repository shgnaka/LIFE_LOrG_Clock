package com.example.orgclock.ui.app

import android.Manifest
import android.os.Build
import android.net.Uri
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.data.RootAccess
import com.example.orgclock.domain.ClockMutationResult
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.notification.NotificationDisplayMode
import com.example.orgclock.ui.perf.PerformanceMonitor
import com.example.orgclock.ui.state.OrgClockUiAction
import com.example.orgclock.ui.state.Screen
import com.example.orgclock.ui.viewmodel.OrgClockViewModel
import java.time.ZonedDateTime

@Composable
fun OrgClockRoute(
    loadSavedUri: () -> Uri?,
    saveUri: (Uri) -> Unit,
    openRoot: suspend (Uri) -> Result<RootAccess>,
    listFiles: suspend () -> Result<List<OrgFileEntry>>,
    listFilesWithOpenClock: suspend () -> Result<Set<String>>,
    listHeadings: suspend (String) -> Result<List<HeadingViewItem>>,
    startClock: suspend (String, Int) -> Result<ClockMutationResult>,
    stopClock: suspend (String, Int) -> Result<ClockMutationResult>,
    cancelClock: suspend (String, Int) -> Result<ClockMutationResult>,
    listClosedClocks: suspend (String, Int) -> Result<List<ClosedClockEntry>>,
    editClosedClock: suspend (String, Int, Int, ZonedDateTime, ZonedDateTime) -> Result<Unit>,
    deleteClosedClock: suspend (String, Int, Int) -> Result<Unit>,
    createL1Heading: suspend (String, String, Boolean) -> Result<Unit>,
    createL2Heading: suspend (String, Int, String, Boolean) -> Result<Unit>,
    loadNotificationEnabled: () -> Boolean,
    saveNotificationEnabled: (Boolean) -> Unit,
    loadNotificationDisplayMode: () -> NotificationDisplayMode,
    saveNotificationDisplayMode: (NotificationDisplayMode) -> Unit,
    notificationPermissionGrantedProvider: () -> Boolean,
    syncNotificationService: (Boolean, NotificationDisplayMode) -> Unit,
    stopNotificationService: () -> Unit,
    openAppNotificationSettings: () -> Unit,
    performanceMonitor: PerformanceMonitor,
    showPerfOverlay: Boolean,
) {
    val factory = remember(
        loadSavedUri,
        saveUri,
        openRoot,
        listFiles,
        listFilesWithOpenClock,
        listHeadings,
        startClock,
        stopClock,
        cancelClock,
        listClosedClocks,
        editClosedClock,
        deleteClosedClock,
        createL1Heading,
        createL2Heading,
        loadNotificationEnabled,
        saveNotificationEnabled,
        loadNotificationDisplayMode,
        saveNotificationDisplayMode,
        notificationPermissionGrantedProvider,
        showPerfOverlay,
    ) {
        orgClockViewModelFactory(
            loadSavedUri = loadSavedUri,
            saveUri = saveUri,
            openRoot = openRoot,
            listFiles = listFiles,
            listFilesWithOpenClock = listFilesWithOpenClock,
            listHeadings = listHeadings,
            startClock = startClock,
            stopClock = stopClock,
            cancelClock = cancelClock,
            listClosedClocks = listClosedClocks,
            editClosedClock = editClosedClock,
            deleteClosedClock = deleteClosedClock,
            createL1Heading = createL1Heading,
            createL2Heading = createL2Heading,
            loadNotificationEnabled = loadNotificationEnabled,
            saveNotificationEnabled = saveNotificationEnabled,
            loadNotificationDisplayMode = loadNotificationDisplayMode,
            saveNotificationDisplayMode = saveNotificationDisplayMode,
            notificationPermissionGrantedProvider = notificationPermissionGrantedProvider,
            showPerfOverlay = showPerfOverlay,
        )
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
        openAppNotificationSettings()
        viewModel.onAction(OrgClockUiAction.AppNotificationSettingsOpened)
    }
    LaunchedEffect(
        state.notificationEnabled,
        state.notificationDisplayMode,
        state.rootUri,
        state.headings,
    ) {
        if (state.notificationEnabled && state.rootUri != null) {
            syncNotificationService(
                state.notificationEnabled,
                state.notificationDisplayMode,
            )
        } else {
            stopNotificationService()
        }
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
        onPickRoot = { rootPicker.launch(null) },
        onAction = viewModel::onAction,
    )
}

private fun orgClockViewModelFactory(
    loadSavedUri: () -> Uri?,
    saveUri: (Uri) -> Unit,
    openRoot: suspend (Uri) -> Result<RootAccess>,
    listFiles: suspend () -> Result<List<OrgFileEntry>>,
    listFilesWithOpenClock: suspend () -> Result<Set<String>>,
    listHeadings: suspend (String) -> Result<List<HeadingViewItem>>,
    startClock: suspend (String, Int) -> Result<ClockMutationResult>,
    stopClock: suspend (String, Int) -> Result<ClockMutationResult>,
    cancelClock: suspend (String, Int) -> Result<ClockMutationResult>,
    listClosedClocks: suspend (String, Int) -> Result<List<ClosedClockEntry>>,
    editClosedClock: suspend (String, Int, Int, ZonedDateTime, ZonedDateTime) -> Result<Unit>,
    deleteClosedClock: suspend (String, Int, Int) -> Result<Unit>,
    createL1Heading: suspend (String, String, Boolean) -> Result<Unit>,
    createL2Heading: suspend (String, Int, String, Boolean) -> Result<Unit>,
    loadNotificationEnabled: () -> Boolean,
    saveNotificationEnabled: (Boolean) -> Unit,
    loadNotificationDisplayMode: () -> NotificationDisplayMode,
    saveNotificationDisplayMode: (NotificationDisplayMode) -> Unit,
    notificationPermissionGrantedProvider: () -> Boolean,
    showPerfOverlay: Boolean,
): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(OrgClockViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            val viewModel = OrgClockViewModel(
                loadSavedUri = loadSavedUri,
                saveUri = saveUri,
                openRoot = openRoot,
                listFiles = listFiles,
                listFilesWithOpenClock = listFilesWithOpenClock,
                listHeadings = listHeadings,
                startClock = startClock,
                stopClock = stopClock,
                cancelClock = cancelClock,
                listClosedClocks = listClosedClocks,
                editClosedClock = editClosedClock,
                deleteClosedClock = deleteClosedClock,
                createL1Heading = createL1Heading,
                createL2Heading = createL2Heading,
                loadNotificationEnabled = loadNotificationEnabled,
                saveNotificationEnabled = saveNotificationEnabled,
                loadNotificationDisplayMode = loadNotificationDisplayMode,
                saveNotificationDisplayMode = saveNotificationDisplayMode,
                notificationPermissionGrantedProvider = notificationPermissionGrantedProvider,
                showPerfOverlay = showPerfOverlay,
            )
            return modelClass.cast(viewModel)
                ?: throw IllegalStateException("Failed to cast ViewModel: ${modelClass.name}")
        }
    }
}
