package com.example.orgclock.ui.app

import android.net.Uri
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.orgclock.R
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.ui.perf.PerformanceMonitor
import com.example.orgclock.ui.state.OrgClockUiAction
import com.example.orgclock.ui.state.OrgClockUiState
import com.example.orgclock.ui.state.Screen
import com.example.orgclock.ui.state.StatusTone
import com.example.orgclock.ui.state.UiStatus
import com.example.orgclock.ui.time.RUNNING_PANEL_TICK_MS
import com.example.orgclock.notification.NotificationDisplayMode
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
    val lineIndex: Int,
    val l2Title: String,
    val l1Title: String?,
    val startedAt: ZonedDateTime,
    val showL1Hint: Boolean,
    val source: HeadingViewItem,
)

private sealed interface HeadingListRow {
    val key: Int
    val contentType: RowContentType

    data class L1Header(
        val item: HeadingViewItem,
    ) : HeadingListRow {
        override val key: Int = item.node.lineIndex
        override val contentType: RowContentType = RowContentType.L1Header
    }

    data class ChildItem(
        val item: HeadingViewItem,
    ) : HeadingListRow {
        override val key: Int = item.node.lineIndex
        override val contentType: RowContentType = if (item.node.level == 2) RowContentType.L2Row else RowContentType.OtherRow
    }
}

private val ClockStartTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

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
                files = state.files,
                filesWithOpenClock = state.filesWithOpenClock,
                zoneIdProvider = zoneIdProvider,
                onPickRoot = onPickRoot,
                onSelectFile = { onAction(OrgClockUiAction.SelectFile(it)) },
                onOpenSettings = { onAction(OrgClockUiAction.OpenSettings) },
                onRefreshFiles = { onAction(OrgClockUiAction.RefreshFiles) },
            )

            Screen.HeadingList -> HeadingListScreen(
                status = state.status,
                selectedFile = state.selectedFile,
                headings = state.headings,
                pendingClockOps = state.pendingClockOps,
                collapsedL1 = state.collapsedL1,
                onToggleL1 = { onAction(OrgClockUiAction.ToggleL1(it)) },
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
                rootUri = state.rootUri,
                notificationEnabled = state.notificationEnabled,
                notificationDisplayMode = state.notificationDisplayMode,
                notificationPermissionGranted = state.notificationPermissionGranted,
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

        if (state.editingEntry != null && state.editingDraft != null) {
            EditClockEntryDialog(
                entry = state.editingEntry,
                draft = state.editingDraft,
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
    zoneIdProvider: () -> ZoneId,
    onPickRoot: () -> Unit,
    onSelectFile: (OrgFileEntry) -> Unit,
    onOpenSettings: () -> Unit,
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
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.settings)) }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onRefreshFiles) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.refresh),
                )
            }
        }
        StatusBanner(status)
        Text(stringResource(R.string.select_org_file), style = MaterialTheme.typography.titleMedium)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(files, key = { it.fileId }) { file ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
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
                        }
                        val modified = file.modifiedAt?.let {
                            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                                .withLocale(Locale.getDefault())
                                .format(it.atZone(zoneIdProvider()))
                        } ?: stringResource(R.string.unknown_modified_time)
                        Text(modified, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    pendingClockOps: Set<Int>,
    collapsedL1: Set<String>,
    onToggleL1: (String) -> Unit,
    onLongPressL1: (HeadingViewItem) -> Unit,
    onCollapseAll: () -> Unit,
    onExpandAll: () -> Unit,
    onRefresh: () -> Unit,
    onLongPressL2: (HeadingViewItem) -> Unit,
    onOpenCreateL1: () -> Unit,
    onOpenFilePicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onStart: (HeadingViewItem) -> Unit,
    onStop: (HeadingViewItem) -> Unit,
    onCancel: (HeadingViewItem) -> Unit,
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
                        lineIndex = item.node.lineIndex,
                        l2Title = item.node.title,
                        l1Title = item.node.parentL1,
                        startedAt = startedAt,
                        showL1Hint = (l2Counts[item.node.title] ?: 0) > 1,
                        source = item,
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

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 170.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = rows,
                    key = { it.key },
                    contentType = { it.contentType },
                ) { row ->
                    when (row) {
                        is HeadingListRow.L1Header -> {
                            val title = row.item.node.title
                            val collapsed = title in collapsedL1
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
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(CalmSurfaceAlt)
                                    .combinedClickable(
                                        onClick = {},
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
                                        onClick = { onStart(child) },
                                        enabled = child.node.lineIndex !in pendingClockOps,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            RunningClocksPanel(
                runningItems = runningItems,
                onStop = { onStop(it.source) },
                onCancel = { onCancel(it.source) },
                pendingClockOps = pendingClockOps,
                nowProvider = nowProvider,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun RunningClocksPanel(
    runningItems: List<RunningClockUiItem>,
    onStop: (RunningClockUiItem) -> Unit,
    onCancel: (RunningClockUiItem) -> Unit,
    pendingClockOps: Set<Int>,
    nowProvider: () -> ZonedDateTime,
    modifier: Modifier = Modifier,
) {
    if (runningItems.isEmpty()) return

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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.running_count, runningItems.size), fontWeight = FontWeight.SemiBold)
            runningItems.forEach { item ->
                val minutes = maxOf(0L, Duration.between(item.startedAt, now).toMinutes())
                val startedText = remember(item.lineIndex, item.startedAt) {
                    item.startedAt.format(ClockStartTimeFormatter)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                            enabled = item.lineIndex !in pendingClockOps,
                        )
                        ClockActionIconButton(
                            actionType = ClockActionType.Cancel,
                            onClick = { onCancel(item) },
                            backgroundColor = Color.White.copy(alpha = 0.18f),
                            enabled = item.lineIndex !in pendingClockOps,
                        )
                    }
                }
            }
        }
    }
}

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
                    IconButton(onClick = onRefresh) {
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
    rootUri: Uri?,
    notificationEnabled: Boolean,
    notificationDisplayMode: NotificationDisplayMode,
    notificationPermissionGranted: Boolean,
    onChangeRoot: () -> Unit,
    onToggleNotificationEnabled: (Boolean) -> Unit,
    onChangeNotificationDisplayMode: (NotificationDisplayMode) -> Unit,
    onOpenAppNotificationSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
        StatusBanner(status)
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.current_root), style = MaterialTheme.typography.titleMedium)
                Text(rootUri?.toString() ?: stringResource(R.string.none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onChangeRoot) { Text(stringResource(R.string.change_org_directory)) }
                    Button(onClick = onBack) { Text(stringResource(R.string.back)) }
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
            text = stringResource(status.messageResId, *status.messageArgs.toTypedArray()),
            color = fg,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
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
    size: Dp = 42.dp,
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
