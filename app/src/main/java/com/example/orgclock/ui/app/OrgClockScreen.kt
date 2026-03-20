package com.example.orgclock.ui.app
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.orgclock.R
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.data.OrgFileNames
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.ui.perf.PerformanceMonitor
import com.example.orgclock.ui.state.OrgClockUiAction
import com.example.orgclock.ui.state.OrgClockUiState
import com.example.orgclock.ui.state.PeerUiItem
import com.example.orgclock.ui.state.Screen
import com.example.orgclock.ui.state.StatusTone
import com.example.orgclock.ui.state.UiStatus
import com.example.orgclock.ui.time.RUNNING_PANEL_TICK_MS
import com.example.orgclock.notification.NotificationDisplayMode
import com.example.orgclock.presentation.RootReference
import com.example.orgclock.sync.ClockEventSyncState
import com.example.orgclock.sync.ClockEventSyncStatus
import com.example.orgclock.sync.SyncDeliveryState
import com.example.orgclock.sync.SyncRuntimeMode
import com.example.orgclock.ui.state.OrgDivergenceRecommendedAction
import com.example.orgclock.ui.state.OrgDivergenceSeverity
import com.example.orgclock.template.ScheduleRuleType
import com.example.orgclock.template.ScheduleWeekday
import com.example.orgclock.template.TemplateAutoGenerationRuntimeState
import com.example.orgclock.template.TemplateAvailability
import com.example.orgclock.template.TemplateFileStatus
import com.example.orgclock.template.TemplateReferenceMode
import com.example.orgclock.time.toJavaZonedDateTime
import com.example.orgclock.ui.theme.CalmBorder
import com.example.orgclock.ui.theme.CalmOnAccent
import com.example.orgclock.ui.theme.CalmSurfaceAlt
import com.example.orgclock.ui.theme.StateErrorBg
import com.example.orgclock.ui.theme.StateErrorFg
import com.example.orgclock.ui.theme.StateInfoBg
import com.example.orgclock.ui.theme.StateInfoFg
import com.example.orgclock.ui.theme.StateSuccessBg
import com.example.orgclock.ui.theme.StateSuccessFg
import com.example.orgclock.ui.theme.StateWarningBg
import com.example.orgclock.ui.theme.StateWarningFg
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.datetime.Instant

private enum class ClockActionType {
    Start,
    Stop,
    Cancel,
}

private enum class RowContentType {
    L1Header,
    L2Row,
    OtherRow,
}

private data class RunningClockUiItem(
    val headingPath: HeadingPath,
    val l2Title: String,
    val l1Title: String?,
    val startedAt: Instant,
    val showL1Hint: Boolean,
)

private sealed interface HeadingListRow {
    val key: String
    val contentType: RowContentType

    data class L1Header(
        val item: HeadingViewItem,
    ) : HeadingListRow {
        override val key: String = "l1:${item.node.path}"
        override val contentType: RowContentType = RowContentType.L1Header
    }

    data class ChildItem(
        val item: HeadingViewItem,
    ) : HeadingListRow {
        override val key: String = "child:${item.node.path}"
        override val contentType: RowContentType = if (item.node.level == 2) RowContentType.L2Row else RowContentType.OtherRow
    }
}

private val ClockStartTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val HeadingListTag = "heading_list"
private const val RunningClocksPanelTag = "running_clocks_panel"
private const val FileRowTagPrefix = "file_row:"
private const val SettingsRootTag = "settings_root"
private const val NotificationSectionTag = "settings_notification_section"
private const val SyncSettingsSectionTag = "settings_sync_section"
private const val SyncDebugSectionTag = "settings_sync_debug_section"
private const val HeadingRowTagPrefix = "heading_row:"
private const val RunningPanelRowTagPrefix = "running_panel_row:"
private const val RunningPanelToggleTag = "running_panel_toggle"
private const val RunningPanelCompactTag = "running_panel_compact"
private const val RunningPanelCollapseThreshold = 5

@Composable
fun OrgClockScreen(
    state: OrgClockUiState,
    performanceMonitor: PerformanceMonitor,
    zoneIdProvider: () -> ZoneId,
    nowProvider: () -> ZonedDateTime,
    onPickRoot: () -> Unit,
    onAction: (OrgClockUiAction) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (state.screen) {
            Screen.RootSetup -> RootSetupScreen(
                status = state.status,
                onPickRoot = onPickRoot,
            )

            Screen.FilePicker -> FilePickerScreen(
                status = state.status,
                files = if (state.selectingTemplateFile) state.templateCandidateFiles else state.files,
                filesWithOpenClock = state.filesWithOpenClock,
                selectingTemplateFile = state.selectingTemplateFile,
                zoneIdProvider = zoneIdProvider,
                onPickRoot = onPickRoot,
                onSelectFile = {
                    onAction(
                        if (state.selectingTemplateFile) {
                            OrgClockUiAction.SelectTemplateFile(it)
                        } else {
                            OrgClockUiAction.SelectFile(it)
                        },
                    )
                },
                onOpenSettings = { onAction(OrgClockUiAction.OpenSettings) },
                onBackFromTemplatePicker = { onAction(OrgClockUiAction.BackFromSettings) },
                onRefreshFiles = {
                    onAction(
                        if (state.selectingTemplateFile) {
                            OrgClockUiAction.RefreshTemplateCandidates
                        } else {
                            OrgClockUiAction.RefreshFiles
                        },
                    )
                },
            )

            Screen.HeadingList -> HeadingListScreen(
                status = state.status,
                selectedFile = state.selectedFile,
                headings = state.headings,
                selectedHeadingPath = state.selectedHeadingPath,
                pendingClockOps = state.pendingClockOps,
                collapsedL1 = state.collapsedL1,
                onToggleL1 = { onAction(OrgClockUiAction.ToggleL1(it)) },
                onSelectHeading = { onAction(OrgClockUiAction.SelectHeading(it)) },
                onLongPressL1 = { onAction(OrgClockUiAction.OpenCreateL2Dialog(it)) },
                onCollapseAll = { onAction(OrgClockUiAction.CollapseAll) },
                onExpandAll = { onAction(OrgClockUiAction.ExpandAll) },
                onRefresh = { onAction(OrgClockUiAction.RefreshHeadings) },
                onLongPressL2 = { onAction(OrgClockUiAction.OpenHistory(it)) },
                onOpenCreateL1 = { onAction(OrgClockUiAction.OpenCreateL1Dialog) },
                onOpenFilePicker = { onAction(OrgClockUiAction.OpenFilePicker) },
                onOpenSettings = { onAction(OrgClockUiAction.OpenSettings) },
                onStart = { onAction(OrgClockUiAction.StartClock(it)) },
                onStop = { onAction(OrgClockUiAction.StopClock(it)) },
                onCancel = { onAction(OrgClockUiAction.CancelClock(it)) },
                nowProvider = nowProvider,
                performanceMonitor = performanceMonitor,
                showPerfOverlay = state.showPerfOverlay,
            )

            Screen.Settings -> SettingsScreen(
                status = state.status,
                rootReference = state.rootReference,
                notificationEnabled = state.notificationEnabled,
                notificationDisplayMode = state.notificationDisplayMode,
                notificationPermissionGranted = state.notificationPermissionGranted,
                autoGenerationEnabled = state.autoGenerationEnabled,
                autoGenerationRule = state.autoGenerationRule,
                autoGenerationHourInput = state.autoGenerationHourInput,
                autoGenerationMinuteInput = state.autoGenerationMinuteInput,
                autoGenerationDaysOfWeek = state.autoGenerationDaysOfWeek,
                autoGenerationRuntimeState = state.autoGenerationRuntimeState,
                templateFileStatus = state.templateFileStatus,
                templateAutoGenerationFailure = state.templateAutoGenerationFailure,
                syncFeatureVisible = state.syncFeatureVisible,
                syncDebugVisible = state.syncDebugVisible,
                syncRuntimeEnabled = state.syncRuntimeEnabled,
                syncDefaultPeerId = state.syncDefaultPeerId,
                syncPeers = state.syncPeers,
                syncPeerInput = state.syncPeerInput,
                syncPeerDisplayName = state.syncPeerDisplayName,
                syncPeerPublicKey = state.syncPeerPublicKey,
                syncPeerViewerModeEnabled = state.syncPeerViewerModeEnabled,
                syncPeerInputError = state.syncPeerInputError,
                syncPeerBusy = state.syncPeerBusy,
                syncRuntimeMode = state.syncRuntimeMode,
                syncLastResultSummary = state.syncLastResultSummary,
                syncLastError = state.syncLastError,
                syncViewerPeerCount = state.syncViewerPeerCount,
                syncViewerProjectionSummary = state.syncViewerProjectionSummary,
                syncMetricsSummary = "submitted=${state.syncMetrics.commandsSubmittedTotal}, applied=${state.syncMetrics.commandsAppliedTotal}, retries=${state.syncMetrics.retryAttemptsTotal}, depth=${state.syncMetrics.queueDepth}",
                syncDeliveryStates = state.syncDeliveryStates.takeLast(3),
                localClockEventSyncState = state.localClockEventSyncState,
                divergenceSnapshot = state.divergenceSnapshot,
                onChangeRoot = onPickRoot,
                onToggleNotificationEnabled = {
                    onAction(OrgClockUiAction.ToggleNotificationEnabled(it))
                },
                onChangeNotificationDisplayMode = {
                    onAction(OrgClockUiAction.ChangeNotificationDisplayMode(it))
                },
                onOpenAppNotificationSettings = {
                    onAction(OrgClockUiAction.OpenAppNotificationSettings)
                },
                onToggleAutoGenerationEnabled = {
                    onAction(OrgClockUiAction.ToggleAutoGenerationEnabled(it))
                },
                onSetAutoGenerationRule = {
                    onAction(OrgClockUiAction.SetAutoGenerationRule(it))
                },
                onUpdateAutoGenerationHour = {
                    onAction(OrgClockUiAction.UpdateAutoGenerationHour(it))
                },
                onUpdateAutoGenerationMinute = {
                    onAction(OrgClockUiAction.UpdateAutoGenerationMinute(it))
                },
                onToggleAutoGenerationDay = {
                    onAction(OrgClockUiAction.ToggleAutoGenerationDay(it))
                },
                onSaveAutoGenerationSchedule = {
                    onAction(OrgClockUiAction.SaveAutoGenerationSchedule)
                },
                onOpenTemplateFilePicker = { onAction(OrgClockUiAction.OpenTemplateFilePicker) },
                onClearExplicitTemplateFile = { onAction(OrgClockUiAction.ClearExplicitTemplateFile) },
                onSyncFlushNow = { onAction(OrgClockUiAction.SyncFlushNow) },
                onSyncEnableStandard = { onAction(OrgClockUiAction.SyncEnableStandard) },
                onSyncEnableActive = { onAction(OrgClockUiAction.SyncEnableActive) },
                onSyncStopRuntime = { onAction(OrgClockUiAction.SyncStopRuntime) },
                onSyncSetEnabled = { onAction(OrgClockUiAction.SyncSetEnabled(it)) },
                onSyncSetDefaultPeerId = { onAction(OrgClockUiAction.SyncSetDefaultPeerId(it)) },
                onSyncUpdatePeerInput = { onAction(OrgClockUiAction.SyncUpdatePeerInput(it)) },
                onSyncUpdatePeerDisplayName = { onAction(OrgClockUiAction.SyncUpdatePeerDisplayName(it)) },
                onSyncUpdatePeerPublicKey = { onAction(OrgClockUiAction.SyncUpdatePeerPublicKey(it)) },
                onSyncSetPeerViewerMode = { onAction(OrgClockUiAction.SyncSetPeerViewerMode(it)) },
                onSyncAddPeer = { onAction(OrgClockUiAction.SyncAddPeer) },
                onSyncRevokePeer = { onAction(OrgClockUiAction.SyncRevokePeer(it)) },
                onSyncProbePeer = { onAction(OrgClockUiAction.SyncProbePeer(it)) },
                onReloadFromDisk = { onAction(OrgClockUiAction.ReloadFromDisk) },
                onBack = { onAction(OrgClockUiAction.BackFromSettings) },
            )
        }

        state.historyTarget?.let { target ->
            ClockHistoryDialog(
                target = target,
                historyEntries = state.historyEntries,
                historyLoading = state.historyLoading,
                onDismiss = { onAction(OrgClockUiAction.DismissHistory) },
                onBeginEdit = { onAction(OrgClockUiAction.BeginEdit(it)) },
                onBeginDelete = { onAction(OrgClockUiAction.BeginDelete(it)) },
            )
        }

        state.deletingEntry?.let { entry ->
            DeleteClockEntryDialog(
                entry = entry,
                deletingInProgress = state.deletingInProgress,
                onCancel = { onAction(OrgClockUiAction.CancelDelete) },
                onConfirmDelete = { onAction(OrgClockUiAction.ConfirmDelete) },
            )
        }

        val editingEntry = state.editingEntry
        val editingDraft = state.editingDraft
        if (editingEntry != null && editingDraft != null) {
            EditClockEntryDialog(
                entry = editingEntry,
                draft = editingDraft,
                editingInProgress = state.editingInProgress,
                onCancel = { onAction(OrgClockUiAction.CancelEdit) },
                onSelectStartHour = { onAction(OrgClockUiAction.SelectStartHour(it)) },
                onSelectStartMinute = { onAction(OrgClockUiAction.SelectStartMinute(it)) },
                onSelectEndHour = { onAction(OrgClockUiAction.SelectEndHour(it)) },
                onSelectEndMinute = { onAction(OrgClockUiAction.SelectEndMinute(it)) },
                onSave = { onAction(OrgClockUiAction.SaveEdit) },
            )
        }

        state.createHeadingDialog?.let { dialog ->
            CreateHeadingInputDialog(
                dialog = dialog,
                onDismiss = { onAction(OrgClockUiAction.DismissCreateHeadingDialog) },
                onUpdateTitle = { onAction(OrgClockUiAction.UpdateCreateHeadingTitle(it)) },
                onSetTplTag = { onAction(OrgClockUiAction.SetCreateHeadingTplTag(it)) },
                onSubmit = { onAction(OrgClockUiAction.SubmitCreateHeading) },
            )
        }
    }
}

@Composable
private fun RootSetupScreen(
    status: UiStatus,
    onPickRoot: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        StatusBanner(status)
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.set_default_org_directory), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onPickRoot) {
                    Text(stringResource(R.string.select_org_directory))
                }
            }
        }
    }
}

@Composable
private fun FilePickerScreen(
    status: UiStatus,
    files: List<OrgFileEntry>,
    filesWithOpenClock: Set<String>,
    selectingTemplateFile: Boolean,
    zoneIdProvider: () -> ZoneId,
    onPickRoot: () -> Unit,
    onSelectFile: (OrgFileEntry) -> Unit,
    onOpenSettings: () -> Unit,
    onBackFromTemplatePicker: () -> Unit,
    onRefreshFiles: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onPickRoot) { Text(stringResource(R.string.change_root)) }
            Button(
                onClick = if (selectingTemplateFile) onBackFromTemplatePicker else onOpenSettings,
            ) {
                Text(stringResource(if (selectingTemplateFile) R.string.back else R.string.settings))
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = onRefreshFiles,
                modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.refresh),
                )
            }
        }
        StatusBanner(status)
        Text(
            stringResource(if (selectingTemplateFile) R.string.template_picker_title else R.string.select_org_file),
            style = MaterialTheme.typography.titleMedium,
        )
        if (selectingTemplateFile) {
            Text(
                text = stringResource(R.string.template_picker_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (files.isEmpty()) {
            Text(
                text = stringResource(
                    if (selectingTemplateFile) {
                        R.string.template_picker_empty_message
                    } else {
                        R.string.empty_files_message
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(files, key = { it.fileId }) { file ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(fileRowTag(file.fileId))
                            .semantics { role = Role.Button }
                            .clickable { onSelectFile(file) },
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(file.displayName, fontWeight = FontWeight.SemiBold)
                                if (file.fileId in filesWithOpenClock) {
                                    Box(
                                        modifier = Modifier
                                            .padding(start = 8.dp)
                                            .size(8.dp)
                                            .background(StateSuccessFg, CircleShape),
                                    )
                                }
                                if (selectingTemplateFile && OrgFileNames.isTemplateFileName(file.displayName)) {
                                    Text(
                                        text = stringResource(R.string.template_default_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                            val modified = file.modifiedAt?.let {
                                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                                    .withLocale(Locale.getDefault())
                                    .format(it.toJavaZonedDateTime(zoneIdProvider()))
                            } ?: stringResource(R.string.unknown_modified_time)
                            Text(modified, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HeadingListScreen(
    status: UiStatus,
    selectedFile: OrgFileEntry?,
    headings: List<HeadingViewItem>,
    selectedHeadingPath: HeadingPath?,
    pendingClockOps: Set<HeadingPath>,
    collapsedL1: Set<String>,
    onToggleL1: (String) -> Unit,
    onSelectHeading: (HeadingPath) -> Unit,
    onLongPressL1: (HeadingViewItem) -> Unit,
    onCollapseAll: () -> Unit,
    onExpandAll: () -> Unit,
    onRefresh: () -> Unit,
    onLongPressL2: (HeadingViewItem) -> Unit,
    onOpenCreateL1: () -> Unit,
    onOpenFilePicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onStart: (HeadingPath) -> Unit,
    onStop: (HeadingPath) -> Unit,
    onCancel: (HeadingPath) -> Unit,
    nowProvider: () -> ZonedDateTime,
    performanceMonitor: PerformanceMonitor,
    showPerfOverlay: Boolean,
) {
    val rows by remember(headings, collapsedL1) {
        derivedStateOf { buildVisibleRows(headings, collapsedL1) }
    }
    val runningItems by remember(headings) {
        derivedStateOf {
            val active = headings.filter { it.node.level == 2 && it.openClock != null }
            val l2Counts = active.groupingBy { it.node.title }.eachCount()
            active
                .sortedByDescending { it.openClock?.startedAt }
                .mapNotNull { item ->
                    val startedAt = item.openClock?.startedAt ?: return@mapNotNull null
                    RunningClockUiItem(
                        headingPath = item.node.path,
                        l2Title = item.node.title,
                        l1Title = item.node.parentL1,
                        startedAt = startedAt,
                        showL1Hint = (l2Counts[item.node.title] ?: 0) > 1,
                    )
                }
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        HeadingListTopBar(
            status = status,
            selectedFile = selectedFile,
            onOpenFilePicker = onOpenFilePicker,
            onOpenSettings = onOpenSettings,
            onCreateL1 = onOpenCreateL1,
            onCollapseAll = onCollapseAll,
            onExpandAll = onExpandAll,
            onRefresh = onRefresh,
            performanceMonitor = performanceMonitor,
            showPerfOverlay = showPerfOverlay,
        )

        HeadingListWithRunningPanel(
            modifier = Modifier.weight(1f),
            rows = rows,
            collapsedL1 = collapsedL1,
            selectedHeadingPath = selectedHeadingPath,
            pendingClockOps = pendingClockOps,
            runningItems = runningItems,
            onToggleL1 = onToggleL1,
            onLongPressL1 = onLongPressL1,
            onSelectHeading = onSelectHeading,
            onLongPressL2 = onLongPressL2,
            onStart = onStart,
            onStop = onStop,
            onCancel = onCancel,
            nowProvider = nowProvider,
        )
    }
}

@Composable
private fun HeadingListWithRunningPanel(
    modifier: Modifier = Modifier,
    rows: List<HeadingListRow>,
    collapsedL1: Set<String>,
    selectedHeadingPath: HeadingPath?,
    pendingClockOps: Set<HeadingPath>,
    runningItems: List<RunningClockUiItem>,
    onToggleL1: (String) -> Unit,
    onLongPressL1: (HeadingViewItem) -> Unit,
    onSelectHeading: (HeadingPath) -> Unit,
    onLongPressL2: (HeadingViewItem) -> Unit,
    onStart: (HeadingPath) -> Unit,
    onStop: (HeadingPath) -> Unit,
    onCancel: (HeadingPath) -> Unit,
    nowProvider: () -> ZonedDateTime,
) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        val panelPlaceables = subcompose("panel") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(12.dp),
            ) {
                RunningClocksPanel(
                    runningItems = runningItems,
                    onStop = { onStop(it.headingPath) },
                    onCancel = { onCancel(it.headingPath) },
                    pendingClockOps = pendingClockOps,
                    nowProvider = nowProvider,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .testTag(RunningClocksPanelTag),
                )
            }
        }.map { measurable ->
            measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        }
        val panelHeight = panelPlaceables.maxOfOrNull { it.height } ?: 0
        val listPlaceables = subcompose("list") {
            HeadingListContent(
                rows = rows,
                collapsedL1 = collapsedL1,
                selectedHeadingPath = selectedHeadingPath,
                pendingClockOps = pendingClockOps,
                reservedBottomPadding = panelHeight.toDp(),
                onToggleL1 = onToggleL1,
                onLongPressL1 = onLongPressL1,
                onSelectHeading = onSelectHeading,
                onLongPressL2 = onLongPressL2,
                onStart = onStart,
            )
        }.map { measurable ->
            measurable.measure(constraints)
        }

        layout(constraints.maxWidth, constraints.maxHeight) {
            listPlaceables.forEach { it.placeRelative(0, 0) }
            panelPlaceables.forEach { it.placeRelative(0, constraints.maxHeight - it.height) }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HeadingListContent(
    rows: List<HeadingListRow>,
    collapsedL1: Set<String>,
    selectedHeadingPath: HeadingPath?,
    pendingClockOps: Set<HeadingPath>,
    reservedBottomPadding: Dp,
    onToggleL1: (String) -> Unit,
    onLongPressL1: (HeadingViewItem) -> Unit,
    onSelectHeading: (HeadingPath) -> Unit,
    onLongPressL2: (HeadingViewItem) -> Unit,
    onStart: (HeadingPath) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(HeadingListTag),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            bottom = reservedBottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (rows.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.empty_headings_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
        items(
            items = rows,
            key = { it.key },
            contentType = { it.contentType },
        ) { row ->
            when (row) {
                is HeadingListRow.L1Header -> {
                    val title = row.item.node.title
                    val collapsed = title in collapsedL1
                    val headingStateDescription = if (collapsed) {
                        stringResource(R.string.heading_state_collapsed)
                    } else {
                        stringResource(R.string.heading_state_expanded)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.85f),
                            thickness = 1.dp,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    heading()
                                    role = Role.Button
                                    stateDescription = headingStateDescription
                                }
                                .combinedClickable(
                                    onClick = { onToggleL1(title) },
                                    onLongClick = { onLongPressL1(row.item) },
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(title, fontWeight = FontWeight.Bold)
                            Text(
                                if (collapsed) "+" else "-",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                is HeadingListRow.ChildItem -> {
                    val child = row.item
                    val isSelected = child.node.path == selectedHeadingPath
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(headingRowTag(child.node.path))
                            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else CalmSurfaceAlt)
                            .combinedClickable(
                                onClick = {
                                    if (child.node.level == 2) onSelectHeading(child.node.path)
                                },
                                onLongClick = {
                                    if (child.node.level == 2) onLongPressL2(child)
                                },
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(child.node.title, modifier = Modifier.weight(1f))
                        if (child.node.level == 2 && child.canStart && child.openClock == null) {
                            ClockActionIconButton(
                                actionType = ClockActionType.Start,
                                onClick = { onStart(child.node.path) },
                                enabled = child.node.path !in pendingClockOps,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunningClocksPanel(
    runningItems: List<RunningClockUiItem>,
    onStop: (RunningClockUiItem) -> Unit,
    onCancel: (RunningClockUiItem) -> Unit,
    pendingClockOps: Set<HeadingPath>,
    nowProvider: () -> ZonedDateTime,
    modifier: Modifier = Modifier,
) {
    if (runningItems.isEmpty()) return

    val shouldAutoCollapse = runningItems.size >= RunningPanelCollapseThreshold
    var expandedByUser by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(shouldAutoCollapse) {
        if (!shouldAutoCollapse) expandedByUser = false
    }
    val isExpanded = !shouldAutoCollapse || expandedByUser

    var now by remember { mutableStateOf(nowProvider()) }
    LaunchedEffect(runningItems.isNotEmpty()) {
        if (!runningItems.isNotEmpty()) return@LaunchedEffect
        while (true) {
            val current = nowProvider()
            now = current
            val elapsedMs = (current.second * 1_000L) + (current.nano / 1_000_000L)
            val delayMs = RUNNING_PANEL_TICK_MS - elapsedMs
            delay(if (delayMs > 0L) delayMs else RUNNING_PANEL_TICK_MS)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        tonalElevation = 8.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.running_count, runningItems.size), fontWeight = FontWeight.SemiBold)
                if (shouldAutoCollapse) {
                    TextButton(
                        onClick = { expandedByUser = !isExpanded },
                        modifier = Modifier.testTag(RunningPanelToggleTag),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (isExpanded) R.string.running_panel_collapse else R.string.running_panel_expand,
                            ),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }

            if (!isExpanded) {
                Text(
                    text = stringResource(R.string.running_panel_expand),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                    modifier = Modifier.testTag(RunningPanelCompactTag),
                )
            } else {
                runningItems.forEach { item ->
                    val minutes = maxOf(0L, Duration.between(item.startedAt.toJavaZonedDateTime(now.zone), now).toMinutes())
                    val startedText = remember(item.headingPath, item.startedAt) {
                        item.startedAt.toJavaZonedDateTime(now.zone).format(ClockStartTimeFormatter)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(runningPanelRowTag(item.headingPath)),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            val title = if (item.showL1Hint && !item.l1Title.isNullOrBlank()) {
                                "${item.l2Title} (${item.l1Title})"
                            } else {
                                item.l2Title
                            }
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.started_elapsed, startedText, minutes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ClockActionIconButton(
                                actionType = ClockActionType.Stop,
                                onClick = { onStop(item) },
                                backgroundColor = Color.White.copy(alpha = 0.18f),
                                enabled = item.headingPath !in pendingClockOps,
                            )
                            ClockActionIconButton(
                                actionType = ClockActionType.Cancel,
                                onClick = { onCancel(item) },
                                backgroundColor = Color.White.copy(alpha = 0.18f),
                                enabled = item.headingPath !in pendingClockOps,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun headingRowTag(path: HeadingPath): String = "$HeadingRowTagPrefix${path}"
private fun runningPanelRowTag(path: HeadingPath): String = "$RunningPanelRowTagPrefix${path}"
private fun fileRowTag(fileId: String): String = "$FileRowTagPrefix$fileId"

@Composable
private fun HeadingListTopBar(
    status: UiStatus,
    selectedFile: OrgFileEntry?,
    onOpenFilePicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onCreateL1: () -> Unit,
    onCollapseAll: () -> Unit,
    onExpandAll: () -> Unit,
    onRefresh: () -> Unit,
    performanceMonitor: PerformanceMonitor,
    showPerfOverlay: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenFilePicker) { Text(stringResource(R.string.files)) }
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.settings)) }
            Button(onClick = onCreateL1) { Text(stringResource(R.string.add_l1)) }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selectedFile?.displayName ?: stringResource(R.string.no_file_selected), style = MaterialTheme.typography.titleMedium)
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (showPerfOverlay) {
                    val perfSnapshot = performanceMonitor.snapshot
                    Text(
                        "Jank ${"%.1f".format(perfSnapshot.jankPercent)}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                        )
                    }
                    BulkHeadingActionButton(
                        label = stringResource(R.string.expand_all_label),
                        contentDescription = stringResource(R.string.expand_all_headings),
                        containerColor = StateSuccessFg,
                        onClick = onExpandAll,
                    )
                    BulkHeadingActionButton(
                        label = stringResource(R.string.collapse_all_label),
                        contentDescription = stringResource(R.string.collapse_all_headings),
                        containerColor = StateWarningFg,
                        onClick = onCollapseAll,
                    )
                }
            }
        }
        StatusBanner(status)
    }
}

@Composable
private fun BulkHeadingActionButton(
    label: String,
    contentDescription: String,
    containerColor: Color,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = CalmOnAccent,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clearAndSetSemantics {
                this.contentDescription = contentDescription
            },
        )
    }
}

@Composable
private fun SettingsScreen(
    status: UiStatus,
    rootReference: RootReference?,
    notificationEnabled: Boolean,
    notificationDisplayMode: NotificationDisplayMode,
    notificationPermissionGranted: Boolean,
    autoGenerationEnabled: Boolean,
    autoGenerationRule: ScheduleRuleType,
    autoGenerationHourInput: String,
    autoGenerationMinuteInput: String,
    autoGenerationDaysOfWeek: Set<ScheduleWeekday>,
    autoGenerationRuntimeState: TemplateAutoGenerationRuntimeState,
    templateFileStatus: TemplateFileStatus,
    templateAutoGenerationFailure: String?,
    syncFeatureVisible: Boolean,
    syncDebugVisible: Boolean,
    syncRuntimeEnabled: Boolean,
    syncDefaultPeerId: String,
    syncPeers: List<PeerUiItem>,
    syncPeerInput: String,
    syncPeerDisplayName: String,
    syncPeerPublicKey: String,
    syncPeerViewerModeEnabled: Boolean,
    syncPeerInputError: String?,
    syncPeerBusy: Boolean,
    syncRuntimeMode: SyncRuntimeMode,
    syncLastResultSummary: String?,
    syncLastError: String?,
    syncViewerPeerCount: Int,
    syncViewerProjectionSummary: String?,
    syncMetricsSummary: String,
    syncDeliveryStates: List<SyncDeliveryState>,
    localClockEventSyncState: ClockEventSyncState,
    divergenceSnapshot: com.example.orgclock.ui.state.OrgDivergenceSnapshot?,
    onChangeRoot: () -> Unit,
    onToggleNotificationEnabled: (Boolean) -> Unit,
    onChangeNotificationDisplayMode: (NotificationDisplayMode) -> Unit,
    onOpenAppNotificationSettings: () -> Unit,
    onToggleAutoGenerationEnabled: (Boolean) -> Unit,
    onSetAutoGenerationRule: (ScheduleRuleType) -> Unit,
    onUpdateAutoGenerationHour: (String) -> Unit,
    onUpdateAutoGenerationMinute: (String) -> Unit,
    onToggleAutoGenerationDay: (ScheduleWeekday) -> Unit,
    onSaveAutoGenerationSchedule: () -> Unit,
    onOpenTemplateFilePicker: () -> Unit,
    onClearExplicitTemplateFile: () -> Unit,
    onSyncFlushNow: () -> Unit,
    onSyncEnableStandard: () -> Unit,
    onSyncEnableActive: () -> Unit,
    onSyncStopRuntime: () -> Unit,
    onSyncSetEnabled: (Boolean) -> Unit,
    onSyncSetDefaultPeerId: (String) -> Unit,
    onSyncUpdatePeerInput: (String) -> Unit,
    onSyncUpdatePeerDisplayName: (String) -> Unit,
    onSyncUpdatePeerPublicKey: (String) -> Unit,
    onSyncSetPeerViewerMode: (Boolean) -> Unit,
    onSyncAddPeer: () -> Unit,
    onSyncRevokePeer: (String) -> Unit,
    onSyncProbePeer: (String) -> Unit,
    onReloadFromDisk: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag(SettingsRootTag)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
        StatusBanner(status)
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.current_root), style = MaterialTheme.typography.titleMedium)
                Text(rootReference?.rawValue ?: stringResource(R.string.none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onChangeRoot) { Text(stringResource(R.string.change_org_directory)) }
                    Button(onClick = onBack) { Text(stringResource(R.string.back)) }
                }
            }
        }
        SectionCard(modifier = Modifier.testTag(NotificationSectionTag)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.notification_feature), style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = notificationEnabled,
                        onCheckedChange = onToggleNotificationEnabled,
                    )
                }
                Text(
                    if (notificationPermissionGranted) {
                        stringResource(R.string.notification_permission_granted)
                    } else {
                        stringResource(R.string.notification_permission_not_granted)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!notificationPermissionGranted) {
                    Button(onClick = onOpenAppNotificationSettings) {
                        Text(stringResource(R.string.open_notification_settings))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.keep_notification_visible),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = notificationDisplayMode == NotificationDisplayMode.Always,
                        onCheckedChange = { enabled ->
                            onChangeNotificationDisplayMode(
                                if (enabled) NotificationDisplayMode.Always
                                else NotificationDisplayMode.ActiveOnly
                            )
                        },
                        enabled = notificationEnabled,
                    )
                }
            }
        }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.auto_generation_title), style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = autoGenerationEnabled,
                        onCheckedChange = onToggleAutoGenerationEnabled,
                        enabled = rootReference != null,
                    )
                }
                Text(
                    stringResource(R.string.auto_generation_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.template_status_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = templateStatusSummary(templateFileStatus),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(
                        if (templateFileStatus.referenceMode == TemplateReferenceMode.Explicit) {
                            R.string.template_reference_selected
                        } else {
                            R.string.template_reference_default
                        },
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                templateFileStatus.detailMessage?.takeIf { it.isNotBlank() }?.let { detail ->
                    Text(
                        text = stringResource(R.string.template_status_detail, detail),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (templateFileStatus.availability == TemplateAvailability.Missing) {
                    Text(
                        text = stringResource(R.string.template_setup_guidance),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = stringResource(R.string.auto_generation_status_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val neverText = stringResource(R.string.auto_generation_status_never)
                Text(
                    text = stringResource(
                        R.string.auto_generation_status_next_run,
                        formatTimestampForStatus(autoGenerationRuntimeState.nextScheduledRunAtEpochMs, neverText),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(
                        R.string.auto_generation_status_last_attempt,
                        formatTimestampForStatus(autoGenerationRuntimeState.lastAttemptAtEpochMs, neverText),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(
                        R.string.auto_generation_status_last_success,
                        formatTimestampForStatus(autoGenerationRuntimeState.lastSuccessAtEpochMs, neverText),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = autoGenerationStatusText(
                        enabled = autoGenerationEnabled,
                        runtimeState = autoGenerationRuntimeState,
                    ),
                    color = if (autoGenerationRuntimeState.overdue || !templateAutoGenerationFailure.isNullOrBlank()) {
                        StateWarningFg
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                templateAutoGenerationFailure?.let { failure ->
                    Text(
                        text = stringResource(R.string.template_auto_generation_last_failure, failure),
                        color = StateWarningFg,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenTemplateFilePicker) {
                        Text(stringResource(R.string.template_choose_file))
                    }
                    if (templateFileStatus.referenceMode == TemplateReferenceMode.Explicit) {
                        Button(onClick = onClearExplicitTemplateFile) {
                            Text(stringResource(R.string.template_use_legacy))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onSetAutoGenerationRule(ScheduleRuleType.Daily) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (autoGenerationRule == ScheduleRuleType.Daily) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    ) {
                        Text(stringResource(R.string.auto_generation_rule_daily))
                    }
                    Button(
                        onClick = { onSetAutoGenerationRule(ScheduleRuleType.Weekly) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (autoGenerationRule == ScheduleRuleType.Weekly) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    ) {
                        Text(stringResource(R.string.auto_generation_rule_weekly))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = autoGenerationHourInput,
                        onValueChange = onUpdateAutoGenerationHour,
                        label = { Text(stringResource(R.string.auto_generation_hour_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = autoGenerationMinuteInput,
                        onValueChange = onUpdateAutoGenerationMinute,
                        label = { Text(stringResource(R.string.auto_generation_minute_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (autoGenerationRule == ScheduleRuleType.Weekly) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.auto_generation_weekdays_label),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        ScheduleWeekday.entries.toList().chunked(4).forEach { rowDays ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                rowDays.forEach { day ->
                                    val selected = day in autoGenerationDaysOfWeek
                                    TextButton(
                                        onClick = { onToggleAutoGenerationDay(day) },
                                    ) {
                                        Text(
                                            dayLabel(day),
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = onSaveAutoGenerationSchedule,
                    enabled = rootReference != null,
                ) {
                    Text(stringResource(R.string.auto_generation_save))
                }
            }
        }
        if (syncFeatureVisible) {
            SectionCard(modifier = Modifier.testTag(SyncSettingsSectionTag)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.sync_settings_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(
                            R.string.local_clock_event_sync_status,
                            stringResource(
                                when (localClockEventSyncState.status) {
                                    ClockEventSyncStatus.Synced -> R.string.local_clock_event_sync_synced
                                    ClockEventSyncStatus.Pending -> R.string.local_clock_event_sync_pending
                                    ClockEventSyncStatus.Error -> R.string.local_clock_event_sync_error
                                    ClockEventSyncStatus.RecoveryRequired -> R.string.local_clock_event_sync_recovery_required
                                },
                            ),
                            localClockEventSyncState.pendingLocalEventCount,
                        ),
                        color = when (localClockEventSyncState.status) {
                            ClockEventSyncStatus.Synced -> MaterialTheme.colorScheme.onSurfaceVariant
                            ClockEventSyncStatus.Pending -> StateWarningFg
                            ClockEventSyncStatus.Error -> StateErrorFg
                            ClockEventSyncStatus.RecoveryRequired -> StateWarningFg
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    localClockEventSyncState.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                        Text(
                            text = stringResource(R.string.local_clock_event_sync_error_detail, error),
                            color = StateErrorFg,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    localClockEventSyncState.lastRejectReason?.takeIf { it.isNotBlank() }?.let { reason ->
                        Text(
                            text = stringResource(R.string.local_clock_event_sync_recovery_detail, reason),
                            color = StateWarningFg,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = "Quarantined events: ${localClockEventSyncState.quarantinedEventCount}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    divergenceSnapshot?.let { divergence ->
                        Text(
                            text = "Divergence: ${divergence.category?.name ?: "unknown"} / ${divergence.severity.name}",
                            color = if (divergence.severity == OrgDivergenceSeverity.RecoveryRequired) StateErrorFg else StateWarningFg,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        divergence.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                            Text(
                                text = "Reason: $reason",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            text = "Recommended action: ${
                                when (divergence.recommendedAction) {
                                    OrgDivergenceRecommendedAction.ReloadFromDisk -> "Reload from disk"
                                    OrgDivergenceRecommendedAction.RebuildFromEventLog -> "Rebuild from event log"
                                    OrgDivergenceRecommendedAction.SyncNow -> "Sync now"
                                }
                            }",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSyncFlushNow) {
                            Text(stringResource(R.string.sync_debug_flush_now))
                        }
                        Button(onClick = onReloadFromDisk) {
                            Text("Reload from disk")
                        }
                        if (localClockEventSyncState.status == ClockEventSyncStatus.RecoveryRequired || localClockEventSyncState.status == ClockEventSyncStatus.Error) {
                            Text(
                                text = "Sync retry is recommended after fixing the rejected event.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.sync_enabled_label), style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = syncRuntimeEnabled,
                            onCheckedChange = onSyncSetEnabled,
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.sync_hub_peer_label,
                            if (syncDefaultPeerId.isBlank()) stringResource(R.string.none) else syncDefaultPeerId,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = syncPeerInput,
                        onValueChange = onSyncUpdatePeerInput,
                        isError = !syncPeerInputError.isNullOrBlank(),
                        label = { Text(stringResource(R.string.sync_add_peer_label)) },
                        supportingText = {
                            if (!syncPeerInputError.isNullOrBlank()) {
                                Text(syncPeerInputError)
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = syncPeerDisplayName,
                        onValueChange = onSyncUpdatePeerDisplayName,
                        label = { Text(stringResource(R.string.sync_peer_display_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = syncPeerPublicKey,
                        onValueChange = onSyncUpdatePeerPublicKey,
                        label = { Text(stringResource(R.string.sync_peer_public_key_label)) },
                        supportingText = { Text(stringResource(R.string.sync_peer_public_key_hint)) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = syncPeerViewerModeEnabled,
                            onCheckedChange = onSyncSetPeerViewerMode,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.sync_peer_viewer_role_label))
                    }
                    Button(
                        onClick = onSyncAddPeer,
                        enabled = !syncPeerBusy,
                    ) {
                        Text(stringResource(R.string.sync_add_peer_button))
                    }
                    if (syncPeers.isEmpty()) {
                        Text(
                            stringResource(R.string.sync_peer_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        syncPeers.forEach { peer ->
                            HorizontalDivider()
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = peer.displayName?.takeIf { it.isNotBlank() } ?: peer.peerId,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.sync_peer_identity_row,
                                        peer.peerId,
                                        stringResource(
                                            when (peer.role) {
                                                com.example.orgclock.sync.PeerTrustRole.Full -> R.string.sync_peer_role_full
                                                com.example.orgclock.sync.PeerTrustRole.Viewer -> R.string.sync_peer_role_viewer
                                            },
                                        ),
                                        if (peer.publicKeyRegistered) stringResource(R.string.sync_peer_public_key_registered) else stringResource(R.string.sync_peer_public_key_missing),
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.sync_peer_status_row,
                                        when (peer.reachable) {
                                            true -> stringResource(R.string.sync_peer_status_ok)
                                            false -> stringResource(R.string.sync_peer_status_ng)
                                            null -> stringResource(R.string.sync_peer_status_unknown)
                                        },
                                        formatEpochMillis(peer.lastSyncedAtEpochMs),
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = "Sync cursors: seen=${peer.lastSeenCursor ?: "-"}, sent=${peer.lastSentCursor ?: "-"}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { onSyncSetDefaultPeerId(peer.peerId) },
                                        enabled = !syncPeerBusy,
                                    ) {
                                        Text(stringResource(R.string.sync_set_hub_button))
                                    }
                                    Button(
                                        onClick = { onSyncProbePeer(peer.peerId) },
                                        enabled = !syncPeerBusy,
                                    ) {
                                        Text(stringResource(R.string.sync_probe_peer_button))
                                    }
                                }
                                Button(
                                    onClick = { onSyncRevokePeer(peer.peerId) },
                                    enabled = !syncPeerBusy,
                                ) {
                                    Text(stringResource(R.string.sync_remove_peer_button))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (syncDebugVisible) {
            SectionCard(modifier = Modifier.testTag(SyncDebugSectionTag)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.sync_debug_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.sync_debug_runtime_mode, syncRuntimeMode.name),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.sync_debug_metrics, syncMetricsSummary),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.sync_debug_last_result,
                            syncLastResultSummary ?: stringResource(R.string.none),
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.sync_debug_last_error,
                            syncLastError ?: stringResource(R.string.none),
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Viewer peers: $syncViewerPeerCount",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Viewer delivery: ${syncViewerProjectionSummary ?: stringResource(R.string.none)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (syncDeliveryStates.isNotEmpty()) {
                        Text(stringResource(R.string.sync_debug_delivery_events), style = MaterialTheme.typography.bodyMedium)
                        syncDeliveryStates.forEach { delivery ->
                            Text(
                                text = "${delivery.state} / ${delivery.commandId} / ${delivery.detail ?: "-"}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSyncFlushNow) { Text(stringResource(R.string.sync_debug_flush_now)) }
                        Button(onClick = onSyncEnableStandard) { Text(stringResource(R.string.sync_debug_standard_mode)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSyncEnableActive) { Text(stringResource(R.string.sync_debug_active_mode)) }
                        Button(onClick = onSyncStopRuntime) { Text(stringResource(R.string.sync_debug_stop_mode)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun templateStatusSummary(status: TemplateFileStatus): String {
    val name = status.displayName ?: stringResource(R.string.none)
    return when (status.referenceMode) {
        TemplateReferenceMode.LegacyHiddenFile -> when (status.availability) {
            TemplateAvailability.Available -> stringResource(R.string.template_status_default_available, name)
            TemplateAvailability.Missing -> stringResource(R.string.template_status_default_missing, name)
            TemplateAvailability.Unreadable -> stringResource(R.string.template_status_default_unreadable, name)
        }
        TemplateReferenceMode.Explicit -> when (status.availability) {
            TemplateAvailability.Available -> stringResource(R.string.template_status_selected_available, name)
            TemplateAvailability.Missing -> stringResource(R.string.template_status_selected_missing, name)
            TemplateAvailability.Unreadable -> stringResource(R.string.template_status_selected_unreadable, name)
        }
    }
}

@Composable
private fun autoGenerationStatusText(
    enabled: Boolean,
    runtimeState: TemplateAutoGenerationRuntimeState,
): String {
    return when {
        !enabled -> stringResource(R.string.auto_generation_status_disabled)
        runtimeState.overdue -> stringResource(R.string.auto_generation_status_overdue)
        !runtimeState.lastFailureMessage.isNullOrBlank() -> stringResource(R.string.auto_generation_status_failure)
        runtimeState.lastSuccessAtEpochMs != null -> stringResource(R.string.auto_generation_status_success)
        else -> stringResource(R.string.auto_generation_status_waiting)
    }
}

@Composable
private fun formatTimestampForStatus(epochMs: Long?, fallback: String): String {
    return if (epochMs == null) fallback else formatEpochMillis(epochMs)
}

private fun formatEpochMillis(epochMs: Long?): String {
    if (epochMs == null) return "-"
    return runCatching {
        val instant = java.time.Instant.ofEpochMilli(epochMs)
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .format(instant.atZone(ZoneId.systemDefault()))
    }.getOrDefault("-")
}

private fun dayLabel(dayOfWeek: ScheduleWeekday): String {
    return when (dayOfWeek) {
        ScheduleWeekday.Monday -> "Mon"
        ScheduleWeekday.Tuesday -> "Tue"
        ScheduleWeekday.Wednesday -> "Wed"
        ScheduleWeekday.Thursday -> "Thu"
        ScheduleWeekday.Friday -> "Fri"
        ScheduleWeekday.Saturday -> "Sat"
        ScheduleWeekday.Sunday -> "Sun"
    }
}

@Composable
private fun StatusBanner(status: UiStatus) {
    val bg = when (status.tone) {
        StatusTone.Info -> StateInfoBg
        StatusTone.Success -> StateSuccessBg
        StatusTone.Warning -> StateWarningBg
        StatusTone.Error -> StateErrorBg
    }
    val fg = when (status.tone) {
        StatusTone.Info -> StateInfoFg
        StatusTone.Success -> StateSuccessFg
        StatusTone.Warning -> StateWarningFg
        StatusTone.Error -> StateErrorFg
    }

    Surface(
        color = bg,
        border = BorderStroke(1.dp, CalmBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = statusText(status),
            color = fg,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
}

@Composable
private fun ClockActionIconButton(
    actionType: ClockActionType,
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: Dp = 48.dp,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
) {
    val (icon, label, color) = when (actionType) {
        ClockActionType.Start -> Triple(Icons.Filled.PlayArrow, stringResource(R.string.start), MaterialTheme.colorScheme.primary)
        ClockActionType.Stop -> Triple(Icons.Filled.Stop, stringResource(R.string.stop), StateSuccessFg)
        ClockActionType.Cancel -> Triple(Icons.Filled.Close, stringResource(R.string.cancel), StateWarningFg)
    }
    val iconTint: Color = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant

    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .background(backgroundColor, CircleShape),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconTint,
        )
    }
}

private fun buildVisibleRows(
    headings: List<HeadingViewItem>,
    collapsedL1: Set<String>,
): List<HeadingListRow> {
    val rows = ArrayList<HeadingListRow>(headings.size)
    var currentL1Title: String? = null

    for (item in headings) {
        if (item.node.level == 1) {
            currentL1Title = item.node.title
            rows += HeadingListRow.L1Header(item)
            continue
        }

        val hiddenByCollapse = currentL1Title != null && currentL1Title in collapsedL1
        if (!hiddenByCollapse) {
            rows += HeadingListRow.ChildItem(item)
        }
    }

    return rows
}
