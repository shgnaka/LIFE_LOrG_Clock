package com.example.orgclock.ui.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.data.RootAccess
import com.example.orgclock.domain.ClockMutationResult
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingViewItem
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
    listHeadings: suspend (String) -> Result<List<HeadingViewItem>>,
    startClock: suspend (String, Int) -> Result<ClockMutationResult>,
    stopClock: suspend (String, Int) -> Result<ClockMutationResult>,
    cancelClock: suspend (String, Int) -> Result<ClockMutationResult>,
    listClosedClocks: suspend (String, Int) -> Result<List<ClosedClockEntry>>,
    editClosedClock: suspend (String, Int, Int, ZonedDateTime, ZonedDateTime) -> Result<Unit>,
    deleteClosedClock: suspend (String, Int, Int) -> Result<Unit>,
    createL1Heading: suspend (String, String) -> Result<Unit>,
    createL2Heading: suspend (String, Int, String) -> Result<Unit>,
    performanceMonitor: PerformanceMonitor,
    showPerfOverlay: Boolean,
) {
    val factory = remember(
        loadSavedUri,
        saveUri,
        openRoot,
        listFiles,
        listHeadings,
        startClock,
        stopClock,
        cancelClock,
        listClosedClocks,
        editClosedClock,
        deleteClosedClock,
        createL1Heading,
        createL2Heading,
        showPerfOverlay,
    ) {
        orgClockViewModelFactory(
            loadSavedUri = loadSavedUri,
            saveUri = saveUri,
            openRoot = openRoot,
            listFiles = listFiles,
            listHeadings = listHeadings,
            startClock = startClock,
            stopClock = stopClock,
            cancelClock = cancelClock,
            listClosedClocks = listClosedClocks,
            editClosedClock = editClosedClock,
            deleteClosedClock = deleteClosedClock,
            createL1Heading = createL1Heading,
            createL2Heading = createL2Heading,
            showPerfOverlay = showPerfOverlay,
        )
    }

    val viewModel: OrgClockViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsState()

    val rootPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            viewModel.onAction(OrgClockUiAction.PickRoot(uri))
        }
    }

    LaunchedEffect(Unit) {
        viewModel.onAction(OrgClockUiAction.Initialize)
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
    listHeadings: suspend (String) -> Result<List<HeadingViewItem>>,
    startClock: suspend (String, Int) -> Result<ClockMutationResult>,
    stopClock: suspend (String, Int) -> Result<ClockMutationResult>,
    cancelClock: suspend (String, Int) -> Result<ClockMutationResult>,
    listClosedClocks: suspend (String, Int) -> Result<List<ClosedClockEntry>>,
    editClosedClock: suspend (String, Int, Int, ZonedDateTime, ZonedDateTime) -> Result<Unit>,
    deleteClosedClock: suspend (String, Int, Int) -> Result<Unit>,
    createL1Heading: suspend (String, String) -> Result<Unit>,
    createL2Heading: suspend (String, Int, String) -> Result<Unit>,
    showPerfOverlay: Boolean,
): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return OrgClockViewModel(
                loadSavedUri = loadSavedUri,
                saveUri = saveUri,
                openRoot = openRoot,
                listFiles = listFiles,
                listHeadings = listHeadings,
                startClock = startClock,
                stopClock = stopClock,
                cancelClock = cancelClock,
                listClosedClocks = listClosedClocks,
                editClosedClock = editClosedClock,
                deleteClosedClock = deleteClosedClock,
                createL1Heading = createL1Heading,
                createL2Heading = createL2Heading,
                showPerfOverlay = showPerfOverlay,
            ) as T
        }
    }
}
