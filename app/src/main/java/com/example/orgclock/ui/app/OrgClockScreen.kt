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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.ui.perf.PerformanceMonitor
import com.example.orgclock.ui.state.OrgClockUiAction
import com.example.orgclock.ui.state.OrgClockUiState
import com.example.orgclock.ui.state.Screen
import com.example.orgclock.ui.state.StatusTone
import com.example.orgclock.ui.state.UiStatus
import com.example.orgclock.ui.state.CreateHeadingMode
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
private val ClockDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
fun OrgClockScreen(
    state: OrgClockUiState,
    performanceMonitor: PerformanceMonitor,
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
                onPickRoot = onPickRoot,
                onSelectFile = { onAction(OrgClockUiAction.SelectFile(it)) },
                onOpenSettings = { onAction(OrgClockUiAction.OpenSettings) },
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
                onLongPressL2 = { onAction(OrgClockUiAction.OpenHistory(it)) },
                onOpenCreateL1 = { onAction(OrgClockUiAction.OpenCreateL1Dialog) },
                onOpenFilePicker = { onAction(OrgClockUiAction.OpenFilePicker) },
                onOpenSettings = { onAction(OrgClockUiAction.OpenSettings) },
                onStart = { onAction(OrgClockUiAction.StartClock(it)) },
                onStop = { onAction(OrgClockUiAction.StopClock(it)) },
                onCancel = { onAction(OrgClockUiAction.CancelClock(it)) },
                performanceMonitor = performanceMonitor,
                showPerfOverlay = state.showPerfOverlay,
            )

            Screen.Settings -> SettingsScreen(
                status = state.status,
                rootUri = state.rootUri,
                onChangeRoot = onPickRoot,
                onBack = { onAction(OrgClockUiAction.BackFromSettings) },
            )
        }

        if (state.historyTarget != null) {
            val target = state.historyTarget
            AlertDialog(
                onDismissRequest = {
                    if (!state.historyLoading) {
                        onAction(OrgClockUiAction.DismissHistory)
                    }
                },
                title = { Text("Clock履歴: ${target.node.title}") },
                text = {
                    if (state.historyLoading) {
                        Text("読み込み中...")
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (state.historyEntries.isEmpty()) {
                                Text("履歴がありません。")
                            } else {
                                state.historyEntries.forEach { entry ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                "${entry.start.format(ClockDateTimeFormatter)} - ${entry.end.format(ClockDateTimeFormatter)}",
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                        TextButton(
                                            onClick = { onAction(OrgClockUiAction.BeginEdit(entry)) },
                                        ) {
                                            Text("編集")
                                        }
                                        TextButton(
                                            onClick = { onAction(OrgClockUiAction.BeginDelete(entry)) },
                                        ) {
                                            Text("削除")
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onAction(OrgClockUiAction.DismissHistory) }) {
                        Text("閉じる")
                    }
                },
            )
        }

        if (state.deletingEntry != null) {
            val entry = state.deletingEntry
            AlertDialog(
                onDismissRequest = {
                    if (!state.deletingInProgress) {
                        onAction(OrgClockUiAction.CancelDelete)
                    }
                },
                title = { Text("Clock履歴削除") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("この履歴を削除しますか？")
                        Text(
                            "${entry.start.format(ClockDateTimeFormatter)} - ${entry.end.format(ClockDateTimeFormatter)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { onAction(OrgClockUiAction.CancelDelete) },
                        enabled = !state.deletingInProgress,
                    ) {
                        Text("キャンセル")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { onAction(OrgClockUiAction.ConfirmDelete) },
                        enabled = !state.deletingInProgress,
                    ) {
                        Text("削除")
                    }
                },
            )
        }

        if (state.editingEntry != null && state.editingDraft != null) {
            val entry = state.editingEntry
            val draft = state.editingDraft
            AlertDialog(
                onDismissRequest = { onAction(OrgClockUiAction.CancelEdit) },
                title = { Text("Clock時刻編集") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "${entry.start.toLocalDate()} / ${entry.end.toLocalDate()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TimeFieldEditor(
                            label = "開始",
                            hour = draft.startHour,
                            minute = draft.startMinute,
                            onHourSelected = { onAction(OrgClockUiAction.SelectStartHour(it)) },
                            onMinuteSelected = { onAction(OrgClockUiAction.SelectStartMinute(it)) },
                        )
                        TimeFieldEditor(
                            label = "終了",
                            hour = draft.endHour,
                            minute = draft.endMinute,
                            onHourSelected = { onAction(OrgClockUiAction.SelectEndHour(it)) },
                            onMinuteSelected = { onAction(OrgClockUiAction.SelectEndMinute(it)) },
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onAction(OrgClockUiAction.CancelEdit) }) {
                        Text("キャンセル")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onAction(OrgClockUiAction.SaveEdit) }) {
                        Text("保存")
                    }
                },
            )
        }

        if (state.createHeadingDialog != null) {
            val dialog = state.createHeadingDialog
            val dialogTitle = if (dialog.mode == CreateHeadingMode.L1) {
                "Create L1 heading"
            } else {
                "Create L2 heading"
            }
            AlertDialog(
                onDismissRequest = {
                    if (!dialog.submitting) {
                        onAction(OrgClockUiAction.DismissCreateHeadingDialog)
                    }
                },
                title = { Text(dialogTitle) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (dialog.mode == CreateHeadingMode.L2 && !dialog.parentL1Title.isNullOrBlank()) {
                            Text(
                                "Parent: ${dialog.parentL1Title}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextField(
                            value = dialog.titleInput,
                            onValueChange = { onAction(OrgClockUiAction.UpdateCreateHeadingTitle(it)) },
                            label = { Text("Heading title") },
                            singleLine = true,
                            enabled = !dialog.submitting,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (dialog.canAttachTplTag) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = dialog.attachTplTag,
                                    onCheckedChange = { onAction(OrgClockUiAction.SetCreateHeadingTplTag(it)) },
                                    enabled = !dialog.submitting,
                                )
                                Text("Add TPL tag")
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { onAction(OrgClockUiAction.DismissCreateHeadingDialog) },
                        enabled = !dialog.submitting,
                    ) {
                        Text("Cancel")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { onAction(OrgClockUiAction.SubmitCreateHeading) },
                        enabled = !dialog.submitting,
                    ) {
                        Text(if (dialog.submitting) "Creating..." else "Create")
                    }
                },
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
        Text("Org Clock", style = MaterialTheme.typography.headlineSmall)
        StatusBanner(status)
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Set your default org directory to start.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onPickRoot) {
                    Text("Select org directory")
                }
            }
        }
    }
}

@Composable
private fun FilePickerScreen(
    status: UiStatus,
    files: List<OrgFileEntry>,
    onPickRoot: () -> Unit,
    onSelectFile: (OrgFileEntry) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickRoot) { Text("Change root") }
            Button(onClick = onOpenSettings) { Text("Settings") }
        }
        StatusBanner(status)
        Text("Select org file", style = MaterialTheme.typography.titleMedium)

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
                        Text(file.displayName, fontWeight = FontWeight.SemiBold)
                        val modified = file.modifiedAt?.let {
                            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                                .withLocale(Locale.getDefault())
                                .format(it.atZone(java.time.ZoneId.systemDefault()))
                        } ?: "Unknown modified time"
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
    onLongPressL2: (HeadingViewItem) -> Unit,
    onOpenCreateL1: () -> Unit,
    onOpenFilePicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onStart: (HeadingViewItem) -> Unit,
    onStop: (HeadingViewItem) -> Unit,
    onCancel: (HeadingViewItem) -> Unit,
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
    modifier: Modifier = Modifier,
) {
    if (runningItems.isEmpty()) return

    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(runningItems.isNotEmpty()) {
        if (!runningItems.isNotEmpty()) return@LaunchedEffect
        while (true) {
            val current = ZonedDateTime.now()
            now = current
            val delayMs = 60_000L - ((current.second * 1000L) + (current.nano / 1_000_000L))
            delay(if (delayMs > 0L) delayMs else 60_000L)
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
            Text("記録中 ${runningItems.size}件", fontWeight = FontWeight.SemiBold)
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
                            text = "$startedText 開始 / ${minutes}分経過",
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
            Button(onClick = onOpenFilePicker) { Text("Files") }
            Button(onClick = onOpenSettings) { Text("Settings") }
            Button(onClick = onCreateL1) { Text("+L1") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selectedFile?.displayName ?: "No file selected", style = MaterialTheme.typography.titleMedium)
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
                    BulkHeadingActionButton(
                        label = "+++",
                        contentDescription = "Expand all headings",
                        containerColor = StateSuccessFg,
                        onClick = onExpandAll,
                    )
                    BulkHeadingActionButton(
                        label = "---",
                        contentDescription = "Collapse all headings",
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
private fun TimeFieldEditor(
    label: String,
    hour: Int,
    minute: Int,
    onHourSelected: (Int) -> Unit,
    onMinuteSelected: (Int) -> Unit,
) {
    var hourExpanded by remember { mutableStateOf(false) }
    var minuteExpanded by remember { mutableStateOf(false) }
    val minuteOptions = remember { (0..55 step 5).toList() }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Button(onClick = { hourExpanded = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("%02d".format(hour))
                }
                DropdownMenu(expanded = hourExpanded, onDismissRequest = { hourExpanded = false }) {
                    (0..23).forEach { h ->
                        DropdownMenuItem(
                            text = { Text("%02d".format(h)) },
                            onClick = {
                                onHourSelected(h)
                                hourExpanded = false
                            },
                        )
                    }
                }
            }
            Text(":")
            Box {
                Button(onClick = { minuteExpanded = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("%02d".format(minute))
                }
                DropdownMenu(expanded = minuteExpanded, onDismissRequest = { minuteExpanded = false }) {
                    minuteOptions.forEach { m ->
                        DropdownMenuItem(
                            text = { Text("%02d".format(m)) },
                            onClick = {
                                onMinuteSelected(m)
                                minuteExpanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    status: UiStatus,
    rootUri: Uri?,
    onChangeRoot: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        StatusBanner(status)
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Current root", style = MaterialTheme.typography.titleMedium)
                Text(rootUri?.toString() ?: "(none)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onChangeRoot) { Text("Change org directory") }
                    Button(onClick = onBack) { Text("Back") }
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
            text = status.message,
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
        ClockActionType.Start -> Triple(Icons.Filled.PlayArrow, "Start", MaterialTheme.colorScheme.primary)
        ClockActionType.Stop -> Triple(Icons.Filled.Stop, "Stop", StateSuccessFg)
        ClockActionType.Cancel -> Triple(Icons.Filled.Close, "Cancel", StateWarningFg)
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
