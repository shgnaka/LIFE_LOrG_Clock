package com.example.orgclock

import android.net.Uri
import android.os.Bundle
import android.content.pm.ApplicationInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.orgclock.data.SafOrgRepository
import com.example.orgclock.domain.ClockService
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.ui.perf.PerformanceMonitor
import com.example.orgclock.ui.theme.CalmBorder
import com.example.orgclock.ui.theme.CalmOnAccent
import com.example.orgclock.ui.theme.CalmSurfaceAlt
import com.example.orgclock.ui.theme.OrgClockTheme
import com.example.orgclock.ui.theme.StateErrorBg
import com.example.orgclock.ui.theme.StateErrorFg
import com.example.orgclock.ui.theme.StateInfoBg
import com.example.orgclock.ui.theme.StateInfoFg
import com.example.orgclock.ui.theme.StateSuccessBg
import com.example.orgclock.ui.theme.StateSuccessFg
import com.example.orgclock.ui.theme.StateWarningBg
import com.example.orgclock.ui.theme.StateWarningFg
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("org-clock", MODE_PRIVATE)
        val repository = SafOrgRepository(this)
        val clockService = ClockService(repository)
        val showPerfOverlay = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        setContent {
            val performanceMonitor = remember { PerformanceMonitor(window) }
            OrgClockTheme {
                OrgClockApp(
                    loadSavedUri = { prefs.getString("root_uri", null)?.let(Uri::parse) },
                    saveUri = { uri -> prefs.edit().putString("root_uri", uri.toString()).apply() },
                    openRoot = { uri -> repository.openRoot(uri) },
                    listFiles = { repository.listOrgFiles() },
                    listHeadings = { fileId -> clockService.listHeadings(fileId) },
                    startClock = { fileId, lineIndex ->
                        clockService.startClockInFile(fileId, lineIndex, java.time.ZonedDateTime.now())
                    },
                    stopClock = { fileId, lineIndex ->
                        clockService.stopClockInFile(fileId, lineIndex, java.time.ZonedDateTime.now())
                    },
                    cancelClock = { fileId, lineIndex ->
                        clockService.cancelClockInFile(fileId, lineIndex)
                    },
                    listClosedClocks = { fileId, lineIndex ->
                        clockService.listClosedClocksInFile(fileId, lineIndex, java.time.ZonedDateTime.now())
                    },
                    editClosedClock = { fileId, headingLineIndex, clockLineIndex, start, end ->
                        clockService.editClosedClockInFile(fileId, headingLineIndex, clockLineIndex, start, end)
                    },
                    performanceMonitor = performanceMonitor,
                    showPerfOverlay = showPerfOverlay,
                )
            }
        }
    }
}

private enum class Screen {
    RootSetup,
    FilePicker,
    HeadingList,
    Settings,
}

private enum class StatusTone {
    Info,
    Success,
    Warning,
    Error,
}

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

private data class UiStatus(
    val message: String,
    val tone: StatusTone,
)

private data class RunningClockUiItem(
    val lineIndex: Int,
    val l2Title: String,
    val l1Title: String?,
    val startedAt: ZonedDateTime,
    val showL1Hint: Boolean,
    val source: HeadingViewItem,
)

private data class ClockEditDraft(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
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
private fun OrgClockApp(
    loadSavedUri: () -> Uri?,
    saveUri: (Uri) -> Unit,
    openRoot: suspend (Uri) -> Result<com.example.orgclock.data.RootAccess>,
    listFiles: suspend () -> Result<List<OrgFileEntry>>,
    listHeadings: suspend (String) -> Result<List<HeadingViewItem>>,
    startClock: suspend (String, Int) -> Result<Unit>,
    stopClock: suspend (String, Int) -> Result<Unit>,
    cancelClock: suspend (String, Int) -> Result<Unit>,
    listClosedClocks: suspend (String, Int) -> Result<List<ClosedClockEntry>>,
    editClosedClock: suspend (String, Int, Int, ZonedDateTime, ZonedDateTime) -> Result<Unit>,
    performanceMonitor: PerformanceMonitor,
    showPerfOverlay: Boolean,
) {
    val scope = rememberCoroutineScope()
    val todayFileName = remember { "${LocalDate.now()}.org" }

    var screen by remember { mutableStateOf(Screen.RootSetup) }
    var rootUri by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf(UiStatus("Select org directory", StatusTone.Info)) }

    var files by remember { mutableStateOf<List<OrgFileEntry>>(emptyList()) }
    var selectedFile by remember { mutableStateOf<OrgFileEntry?>(null) }
    var headings by remember { mutableStateOf<List<HeadingViewItem>>(emptyList()) }
    var collapsedL1 by remember { mutableStateOf(setOf<String>()) }
    var historyTarget by remember { mutableStateOf<HeadingViewItem?>(null) }
    var historyEntries by remember { mutableStateOf<List<ClosedClockEntry>>(emptyList()) }
    var historyLoading by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<ClosedClockEntry?>(null) }
    var editingDraft by remember { mutableStateOf<ClockEditDraft?>(null) }
    val l1Titles = remember(headings) {
        headings.asSequence()
            .filter { it.node.level == 1 }
            .map { it.node.title }
            .distinct()
            .toList()
    }

    DisposableEffect(screen) {
        if (screen == Screen.HeadingList) {
            performanceMonitor.reset()
            performanceMonitor.setTrackingEnabled(true)
        } else {
            performanceMonitor.setTrackingEnabled(false)
        }
        onDispose { }
    }

    suspend fun loadHeadingsFor(file: OrgFileEntry) {
        val loaded = listHeadings(file.fileId)
        if (loaded.isSuccess) {
            selectedFile = file
            headings = loaded.getOrThrow()
            collapsedL1 = emptySet()
            historyTarget = null
            historyEntries = emptyList()
            historyLoading = false
            editingEntry = null
            editingDraft = null
            screen = Screen.HeadingList
            status = UiStatus("Loaded ${file.displayName}", StatusTone.Success)
        } else {
            status = UiStatus(
                "Failed loading headings: ${loaded.exceptionOrNull()?.message ?: "unknown"}",
                StatusTone.Error,
            )
        }
    }

    suspend fun refreshFilesAndRoute() {
        val result = listFiles()
        if (result.isFailure) {
            status = UiStatus(
                "Failed listing files: ${result.exceptionOrNull()?.message ?: "unknown"}",
                StatusTone.Error,
            )
            screen = Screen.FilePicker
            return
        }

        val listed = result.getOrThrow()
        files = listed
        val today = listed.firstOrNull { it.displayName == todayFileName }
        if (today != null) {
            loadHeadingsFor(today)
        } else {
            selectedFile = null
            headings = emptyList()
            screen = Screen.FilePicker
            status = UiStatus("Today's file not found. Please select a file.", StatusTone.Warning)
        }
    }

    suspend fun applyRoot(uri: Uri) {
        val opened = openRoot(uri)
        if (opened.isFailure) {
            status = UiStatus(
                "Failed to open root: ${opened.exceptionOrNull()?.message ?: "unknown"}",
                StatusTone.Error,
            )
            screen = Screen.RootSetup
            return
        }
        rootUri = uri
        saveUri(uri)
        status = UiStatus("Root set", StatusTone.Success)
        refreshFilesAndRoute()
    }

    suspend fun reloadHistoryIfNeeded() {
        val file = selectedFile ?: return
        val target = historyTarget ?: return
        val result = listClosedClocks(file.fileId, target.node.lineIndex)
        if (result.isSuccess) {
            historyEntries = result.getOrThrow()
        } else {
            historyEntries = emptyList()
            status = UiStatus(
                "Failed loading history: ${result.exceptionOrNull()?.message ?: "unknown"}",
                StatusTone.Error,
            )
        }
    }

    suspend fun refreshSelectedFileHeadings() {
        val file = selectedFile ?: return
        val loaded = listHeadings(file.fileId)
        if (loaded.isSuccess) {
            headings = loaded.getOrThrow()
        } else {
            status = UiStatus(
                "Failed loading headings: ${loaded.exceptionOrNull()?.message ?: "unknown"}",
                StatusTone.Error,
            )
        }
    }

    val rootPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            applyRoot(uri)
        }
    }

    LaunchedEffect(Unit) {
        val saved = loadSavedUri()
        if (saved == null) {
            screen = Screen.RootSetup
            return@LaunchedEffect
        }
        applyRoot(saved)
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (screen) {
            Screen.RootSetup -> RootSetupScreen(
                status = status,
                onPickRoot = { rootPicker.launch(null) },
            )

            Screen.FilePicker -> FilePickerScreen(
                status = status,
                files = files,
                onPickRoot = { rootPicker.launch(null) },
                onSelectFile = { file ->
                    scope.launch { loadHeadingsFor(file) }
                },
                onOpenSettings = { screen = Screen.Settings },
            )

            Screen.HeadingList -> HeadingListScreen(
                status = status,
                selectedFile = selectedFile,
                headings = headings,
                collapsedL1 = collapsedL1,
                onToggleL1 = { title ->
                    collapsedL1 = if (title in collapsedL1) collapsedL1 - title else collapsedL1 + title
                },
                onCollapseAll = {
                    collapsedL1 = l1Titles.toSet()
                },
                onExpandAll = {
                    collapsedL1 = emptySet()
                },
                onLongPressL2 = { item ->
                    scope.launch {
                        val file = selectedFile ?: return@launch
                        historyTarget = item
                        historyLoading = true
                        val result = listClosedClocks(file.fileId, item.node.lineIndex)
                        historyLoading = false
                        if (result.isSuccess) {
                            historyEntries = result.getOrThrow()
                        } else {
                            historyEntries = emptyList()
                            status = UiStatus(
                                "Failed loading history: ${result.exceptionOrNull()?.message ?: "unknown"}",
                                StatusTone.Error,
                            )
                        }
                    }
                },
                onOpenFilePicker = { screen = Screen.FilePicker },
                onOpenSettings = { screen = Screen.Settings },
                onStart = { item ->
                    scope.launch {
                        val file = selectedFile ?: return@launch
                        val result = startClock(file.fileId, item.node.lineIndex)
                        status = if (result.isSuccess) {
                            UiStatus("Clock started", StatusTone.Success)
                        } else {
                            val msg = result.exceptionOrNull()?.message ?: "unknown"
                            val tone = if (msg.contains("already", ignoreCase = true)) StatusTone.Warning else StatusTone.Error
                            UiStatus("Start failed: $msg", tone)
                        }
                        loadHeadingsFor(file)
                    }
                },
                onStop = { item ->
                    scope.launch {
                        val file = selectedFile ?: return@launch
                        val result = stopClock(file.fileId, item.node.lineIndex)
                        status = if (result.isSuccess) {
                            UiStatus("Clock stopped", StatusTone.Success)
                        } else {
                            UiStatus("Stop failed: ${result.exceptionOrNull()?.message ?: "unknown"}", StatusTone.Error)
                        }
                        loadHeadingsFor(file)
                    }
                },
                onCancel = { item ->
                    scope.launch {
                        val file = selectedFile ?: return@launch
                        val result = cancelClock(file.fileId, item.node.lineIndex)
                        status = if (result.isSuccess) {
                            UiStatus("Clock cancelled", StatusTone.Warning)
                        } else {
                            UiStatus("Cancel failed: ${result.exceptionOrNull()?.message ?: "unknown"}", StatusTone.Error)
                        }
                        loadHeadingsFor(file)
                    }
                },
                performanceMonitor = performanceMonitor,
                showPerfOverlay = showPerfOverlay,
            )

            Screen.Settings -> SettingsScreen(
                status = status,
                rootUri = rootUri,
                onChangeRoot = { rootPicker.launch(null) },
                onBack = {
                    screen = if (selectedFile != null) Screen.HeadingList else Screen.FilePicker
                },
            )
        }

        if (historyTarget != null) {
            val target = historyTarget!!
            AlertDialog(
                onDismissRequest = {
                    if (!historyLoading) {
                        historyTarget = null
                        historyEntries = emptyList()
                    }
                },
                title = { Text("Clock履歴: ${target.node.title}") },
                text = {
                    if (historyLoading) {
                        Text("読み込み中...")
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (historyEntries.isEmpty()) {
                                Text("履歴がありません。")
                            } else {
                                historyEntries.forEach { entry ->
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
                                            onClick = {
                                                editingEntry = entry
                                                editingDraft = ClockEditDraft(
                                                    startHour = entry.start.hour,
                                                    startMinute = roundToNearest5(entry.start.minute),
                                                    endHour = entry.end.hour,
                                                    endMinute = roundToNearest5(entry.end.minute),
                                                )
                                            },
                                        ) {
                                            Text("編集")
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            historyTarget = null
                            historyEntries = emptyList()
                        },
                    ) {
                        Text("閉じる")
                    }
                },
            )
        }

        if (editingEntry != null && editingDraft != null) {
            val entry = editingEntry!!
            val draft = editingDraft!!
            AlertDialog(
                onDismissRequest = {
                    editingEntry = null
                    editingDraft = null
                },
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
                            onHourSelected = { editingDraft = draft.copy(startHour = it) },
                            onMinuteSelected = { editingDraft = draft.copy(startMinute = it) },
                        )
                        TimeFieldEditor(
                            label = "終了",
                            hour = draft.endHour,
                            minute = draft.endMinute,
                            onHourSelected = { editingDraft = draft.copy(endHour = it) },
                            onMinuteSelected = { editingDraft = draft.copy(endMinute = it) },
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            editingEntry = null
                            editingDraft = null
                        },
                    ) {
                        Text("キャンセル")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val file = selectedFile ?: return@TextButton
                            val currentDraft = editingDraft ?: return@TextButton
                            val updatedStart = entry.start
                                .withHour(currentDraft.startHour)
                                .withMinute(currentDraft.startMinute)
                                .withSecond(0)
                                .withNano(0)
                            val updatedEnd = entry.end
                                .withHour(currentDraft.endHour)
                                .withMinute(currentDraft.endMinute)
                                .withSecond(0)
                                .withNano(0)
                            if (updatedEnd.isBefore(updatedStart)) {
                                status = UiStatus("終了時刻は開始時刻以降にしてください", StatusTone.Warning)
                                return@TextButton
                            }
                            scope.launch {
                                val result = editClosedClock(
                                    file.fileId,
                                    entry.headingLineIndex,
                                    entry.clockLineIndex,
                                    updatedStart,
                                    updatedEnd,
                                )
                                if (result.isSuccess) {
                                    status = UiStatus("Clock履歴を更新しました", StatusTone.Success)
                                    editingEntry = null
                                    editingDraft = null
                                    reloadHistoryIfNeeded()
                                    refreshSelectedFileHeadings()
                                } else {
                                    status = UiStatus(
                                        "更新に失敗: ${result.exceptionOrNull()?.message ?: "unknown"}",
                                        StatusTone.Error,
                                    )
                                }
                            }
                        },
                    ) {
                        Text("保存")
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
    collapsedL1: Set<String>,
    onToggleL1: (String) -> Unit,
    onCollapseAll: () -> Unit,
    onExpandAll: () -> Unit,
    onLongPressL2: (HeadingViewItem) -> Unit,
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
                                        .clickable { onToggleL1(title) }
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
                                if (child.node.level == 2 && child.canStart) {
                                    ClockActionIconButton(
                                        actionType = ClockActionType.Start,
                                        onClick = { onStart(child) },
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
                        )
                        ClockActionIconButton(
                            actionType = ClockActionType.Cancel,
                            onClick = { onCancel(item) },
                            backgroundColor = Color.White.copy(alpha = 0.18f),
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
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
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

private fun roundToNearest5(minute: Int): Int {
    val normalized = ((minute + 2) / 5) * 5
    return if (normalized == 60) 55 else normalized
}
