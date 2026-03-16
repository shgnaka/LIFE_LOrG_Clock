@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.orgclock.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.presentation.RootReference
import com.example.orgclock.presentation.Screen
import com.example.orgclock.presentation.StatusMessageKey
import com.example.orgclock.presentation.StatusTone
import com.example.orgclock.presentation.UiStatus
import com.example.orgclock.ui.state.ClockEditDraft
import com.example.orgclock.ui.state.CreateHeadingDialogState
import com.example.orgclock.ui.state.CreateHeadingMode
import com.example.orgclock.ui.state.OrgClockUiAction
import com.example.orgclock.ui.state.OrgClockUiState
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import javax.swing.JFileChooser

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Org Clock Desktop",
    ) {
        DesktopApp()
    }
}

@Composable
private fun DesktopApp() {
    val scope = rememberCoroutineScope()
    val graph = remember { DesktopAppGraph() }
    val store = remember(graph) { graph.createStore(scope) }
    val state by store.uiState.collectAsState()

    LaunchedEffect(store) {
        store.onAction(OrgClockUiAction.Initialize)
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFF4F1E8),
                                Color(0xFFD9E5D6),
                                Color(0xFFB9D3C2),
                            ),
                        ),
                    )
                    .padding(24.dp),
            ) {
                DesktopHostCard(
                    state = state,
                    onPickRoot = {
                        chooseRootDirectory(state.rootReference)?.let { root ->
                            store.onAction(OrgClockUiAction.PickRoot(root))
                        }
                    },
                    onAction = store::onAction,
                )
                DesktopDialogs(
                    state = state,
                    onAction = store::onAction,
                )
            }
        }
    }
}

@Composable
private fun DesktopHostCard(
    state: OrgClockUiState,
    onPickRoot: () -> Unit,
    onAction: (OrgClockUiAction) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF16302B),
            contentColor = Color(0xFFF8F5ED),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Org Clock Desktop",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Desktop-adapted shared flows with explicit actions instead of mobile-only gestures.",
                style = MaterialTheme.typography.bodyLarge,
            )
            StatusChip(status = state.status)
            RootSummary(rootReference = state.rootReference)
            DesktopToolbar(
                state = state,
                onPickRoot = onPickRoot,
                onAction = onAction,
            )
            if (state.externalChangePending) {
                ExternalChangeBanner(
                    state = state,
                    onReload = { onAction(OrgClockUiAction.RefreshFiles) },
                )
            }
            HorizontalDivider(color = Color(0xFF3B564F))
            when (state.screen) {
                Screen.RootSetup -> RootSetupPane(onPickRoot = onPickRoot)
                Screen.FilePicker -> FilePickerPane(
                    files = state.files,
                    filesWithOpenClock = state.filesWithOpenClock,
                    onSelectFile = { onAction(OrgClockUiAction.SelectFile(it)) },
                )
                Screen.HeadingList -> HeadingListPane(
                    state = state,
                    onAction = onAction,
                )
                Screen.Settings -> SettingsPane(
                    rootReference = state.rootReference,
                    onChangeRoot = onPickRoot,
                    onBack = { onAction(OrgClockUiAction.BackFromSettings) },
                )
            }
        }
    }
}

@Composable
private fun DesktopToolbar(
    state: OrgClockUiState,
    onPickRoot: () -> Unit,
    onAction: (OrgClockUiAction) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onPickRoot) {
            Text(if (state.rootReference == null) "Choose org root" else "Change org root")
        }
        Button(
            onClick = { onAction(OrgClockUiAction.RefreshFiles) },
            enabled = state.rootReference != null,
        ) {
            Text("Reload files")
        }
        Button(
            onClick = { onAction(OrgClockUiAction.OpenSettings) },
            enabled = state.rootReference != null,
        ) {
            Text("Settings")
        }
        if (state.screen == Screen.HeadingList) {
            Button(onClick = { onAction(OrgClockUiAction.OpenCreateL1Dialog) }) {
                Text("Add top-level heading")
            }
        }
    }
}

@Composable
private fun RootSetupPane(onPickRoot: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "No org root is configured yet. Choose a local directory to initialize the shared desktop host.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onPickRoot) {
            Text("Select local directory")
        }
    }
}

@Composable
private fun FilePickerPane(
    files: List<OrgFileEntry>,
    filesWithOpenClock: Set<String>,
    onSelectFile: (OrgFileEntry) -> Unit,
) {
    if (files.isEmpty()) {
        Text(
            text = "No daily org file matched today yet. You can keep this window open and reload after adding one.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFD3E6D6),
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Choose a file",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files, key = { it.fileId }) { file ->
                val running = file.fileId in filesWithOpenClock
                FileRow(
                    file = file,
                    running = running,
                    onClick = { onSelectFile(file) },
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    file: OrgFileEntry,
    running: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF3B564F), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = file.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = file.fileId,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD3E6D6),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (running) "running" else "idle",
                style = MaterialTheme.typography.bodySmall,
                color = if (running) Color(0xFF8FE0A8) else Color(0xFFD3E6D6),
            )
            Button(onClick = onClick) {
                Text("Open")
            }
        }
    }
}

@Composable
private fun HeadingListPane(
    state: OrgClockUiState,
    onAction: (OrgClockUiAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = state.selectedFile?.displayName ?: "No file selected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Button(onClick = { onAction(OrgClockUiAction.RefreshHeadings) }) { Text("Refresh headings") }
            Button(onClick = { onAction(OrgClockUiAction.OpenFilePicker) }) { Text("Back to files") }
            Button(onClick = { onAction(OrgClockUiAction.CollapseAll) }) { Text("Collapse all") }
            Button(onClick = { onAction(OrgClockUiAction.ExpandAll) }) { Text("Expand all") }
        }
        if (state.headings.isEmpty()) {
            Text(
                text = "No headings loaded for this file.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFD3E6D6),
            )
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.headings, key = { it.node.path.toString() }) { item ->
                HeadingRow(
                    item = item,
                    selected = state.selectedHeadingPath == item.node.path,
                    collapsed = item.node.level == 1 && item.node.title in state.collapsedL1,
                    pending = item.node.path in state.pendingClockOps,
                    hiddenByCollapse = item.node.level > 1 && item.node.parentL1 in state.collapsedL1,
                    onAction = onAction,
                )
            }
        }
    }
}

@Composable
private fun HeadingRow(
    item: HeadingViewItem,
    selected: Boolean,
    collapsed: Boolean,
    pending: Boolean,
    hiddenByCollapse: Boolean,
    onAction: (OrgClockUiAction) -> Unit,
) {
    if (hiddenByCollapse) return

    val borderColor = when {
        selected -> Color(0xFF8FE0A8)
        pending -> Color(0xFFF2D38A)
        else -> Color(0xFF3B564F)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable { onAction(OrgClockUiAction.SelectHeading(item.node.path)) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = buildString {
                append("L${item.node.level} ${item.node.title}")
                if (item.openClock != null) append("  [running]")
            },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = item.node.path.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFD3E6D6),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (item.node.level == 1) {
                Button(onClick = { onAction(OrgClockUiAction.ToggleL1(item.node.title)) }) {
                    Text(if (collapsed) "Expand children" else "Collapse children")
                }
                Button(onClick = { onAction(OrgClockUiAction.OpenCreateL2Dialog(item)) }) {
                    Text("Add child heading")
                }
            } else {
                Button(onClick = { onAction(OrgClockUiAction.OpenHistory(item)) }) {
                    Text("History")
                }
            }
            when {
                item.openClock != null -> {
                    Button(
                        onClick = { onAction(OrgClockUiAction.StopClock(item.node.path)) },
                        enabled = !pending,
                    ) {
                        Text("Stop")
                    }
                    Button(
                        onClick = { onAction(OrgClockUiAction.CancelClock(item.node.path)) },
                        enabled = !pending,
                    ) {
                        Text("Cancel")
                    }
                }
                item.canStart -> {
                    Button(
                        onClick = { onAction(OrgClockUiAction.StartClock(item.node.path)) },
                        enabled = !pending,
                    ) {
                        Text("Start")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPane(
    rootReference: RootReference?,
    onChangeRoot: () -> Unit,
    onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Desktop settings",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = rootReference?.rawValue ?: "No root selected",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFD3E6D6),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onChangeRoot) {
                Text("Change root")
            }
            Button(onClick = onBack) {
                Text("Back")
            }
        }
        Text(
            text = "Notification, permission, sync runtime, and mobile perf controls stay hidden on desktop.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFD3E6D6),
        )
    }
}

@Composable
private fun DesktopDialogs(
    state: OrgClockUiState,
    onAction: (OrgClockUiAction) -> Unit,
) {
    state.historyTarget?.let { target ->
        HistoryDialog(
            title = target.node.title,
            entries = state.historyEntries,
            loading = state.historyLoading,
            externalChangePending = state.externalChangePending,
            externalChangeAffectsSelectedFile = state.externalChangeAffectsSelectedFile,
            editFailureMessage = state.editFailureMessage,
            deleteFailureMessage = state.deleteFailureMessage,
            onDismiss = { onAction(OrgClockUiAction.DismissHistory) },
            onReload = { onAction(OrgClockUiAction.RefreshFiles) },
            onEdit = { onAction(OrgClockUiAction.BeginEdit(it)) },
            onDelete = { onAction(OrgClockUiAction.BeginDelete(it)) },
        )
    }
    state.createHeadingDialog?.let { dialog ->
        CreateHeadingDialog(
            dialog = dialog,
            onDismiss = { onAction(OrgClockUiAction.DismissCreateHeadingDialog) },
            onUpdateTitle = { onAction(OrgClockUiAction.UpdateCreateHeadingTitle(it)) },
            onSetTpl = { onAction(OrgClockUiAction.SetCreateHeadingTplTag(it)) },
            onSubmit = { onAction(OrgClockUiAction.SubmitCreateHeading) },
        )
    }
    val editingEntry = state.editingEntry
    val editingDraft = state.editingDraft
    if (editingEntry != null && editingDraft != null) {
        EditClockDialog(
            entry = editingEntry,
            draft = editingDraft,
            inProgress = state.editingInProgress,
            failureMessage = state.editFailureMessage,
            externalChangePending = state.externalChangePending,
            onDismiss = { onAction(OrgClockUiAction.CancelEdit) },
            onReload = { onAction(OrgClockUiAction.RefreshFiles) },
            onSetStartHour = { onAction(OrgClockUiAction.SelectStartHour(it)) },
            onSetStartMinute = { onAction(OrgClockUiAction.SelectStartMinute(it)) },
            onSetEndHour = { onAction(OrgClockUiAction.SelectEndHour(it)) },
            onSetEndMinute = { onAction(OrgClockUiAction.SelectEndMinute(it)) },
            onSave = { onAction(OrgClockUiAction.SaveEdit) },
        )
    }
    state.deletingEntry?.let { entry ->
        DeleteConfirmDialog(
            entry = entry,
            inProgress = state.deletingInProgress,
            failureMessage = state.deleteFailureMessage,
            externalChangePending = state.externalChangePending,
            onDismiss = { onAction(OrgClockUiAction.CancelDelete) },
            onReload = { onAction(OrgClockUiAction.RefreshFiles) },
            onConfirm = { onAction(OrgClockUiAction.ConfirmDelete) },
        )
    }
}

@Composable
private fun ExternalChangeBanner(
    state: OrgClockUiState,
    onReload: () -> Unit,
) {
    val text = when {
        state.externalChangeAffectsSelectedFile -> {
            "The selected file changed on disk. Reload from disk to refresh headings and history."
        }
        state.externalChangeChangedFileIds.isNotEmpty() -> {
            "Org files changed on disk. Reload from disk to refresh the desktop view."
        }
        else -> {
            "A filesystem change was detected. Reload from disk to refresh the desktop view."
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFF2D38A), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFF2D38A),
        )
        Button(onClick = onReload) {
            Text("Reload from disk")
        }
    }
}

@Composable
private fun HistoryDialog(
    title: String,
    entries: List<ClosedClockEntry>,
    loading: Boolean,
    externalChangePending: Boolean,
    externalChangeAffectsSelectedFile: Boolean,
    editFailureMessage: String?,
    deleteFailureMessage: String?,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onEdit: (ClosedClockEntry) -> Unit,
    onDelete: (ClosedClockEntry) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("History: $title") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().height(360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (externalChangePending) {
                    Text(
                        text = if (externalChangeAffectsSelectedFile) {
                            "This history view may be stale because the selected file changed on disk."
                        } else {
                            "This history view may be stale because org files changed on disk."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFF2D38A),
                    )
                    Button(onClick = onReload) {
                        Text("Reload from disk")
                    }
                }
                editFailureMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFF5A3A3),
                    )
                }
                deleteFailureMessage?.takeIf { it != editFailureMessage }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFF5A3A3),
                    )
                }
                when {
                    loading -> Text("Loading clock history...")
                    entries.isEmpty() -> Text("No closed clocks yet.")
                    else -> entries.forEach { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color(0xFF3B564F), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(formatClockEntry(entry), style = MaterialTheme.typography.bodyMedium)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(onClick = { onEdit(entry) }) { Text("Edit") }
                                Button(onClick = { onDelete(entry) }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun CreateHeadingDialog(
    dialog: CreateHeadingDialogState,
    onDismiss: () -> Unit,
    onUpdateTitle: (String) -> Unit,
    onSetTpl: (Boolean) -> Unit,
    onSubmit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (dialog.mode == CreateHeadingMode.L1) "Create top-level heading" else "Create child heading",
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                dialog.parentL1Title?.let {
                    Text("Parent: $it", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = dialog.titleInput,
                    onValueChange = onUpdateTitle,
                    label = { Text("Heading title") },
                    singleLine = true,
                    enabled = !dialog.submitting,
                )
                if (dialog.canAttachTplTag) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = dialog.attachTplTag,
                            onCheckedChange = if (dialog.submitting) null else onSetTpl,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Attach :tpl: tag")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmit,
                enabled = !dialog.submitting,
            ) {
                Text(if (dialog.submitting) "Saving..." else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !dialog.submitting) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun EditClockDialog(
    entry: ClosedClockEntry,
    draft: ClockEditDraft,
    inProgress: Boolean,
    failureMessage: String?,
    externalChangePending: Boolean,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onSetStartHour: (Int) -> Unit,
    onSetStartMinute: (Int) -> Unit,
    onSetEndHour: (Int) -> Unit,
    onSetEndMinute: (Int) -> Unit,
    onSave: () -> Unit,
) {
    var startHourText by remember(draft.startHour) { mutableStateOf(draft.startHour.toString()) }
    var startMinuteText by remember(draft.startMinute) { mutableStateOf(draft.startMinute.toString().padStart(2, '0')) }
    var endHourText by remember(draft.endHour) { mutableStateOf(draft.endHour.toString()) }
    var endMinuteText by remember(draft.endMinute) { mutableStateOf(draft.endMinute.toString().padStart(2, '0')) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit clock entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(formatClockEntry(entry), style = MaterialTheme.typography.bodyMedium)
                failureMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFF5A3A3),
                    )
                }
                if (externalChangePending) {
                    Text(
                        text = "The file changed on disk. Reload from disk to refresh this entry before retrying.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFF2D38A),
                    )
                }
                TimeFieldRow(
                    label = "Start",
                    hourValue = startHourText,
                    minuteValue = startMinuteText,
                    enabled = !inProgress,
                    onHourChange = {
                        startHourText = it
                        it.toIntOrNull()?.takeIf { value -> value in 0..23 }?.let(onSetStartHour)
                    },
                    onMinuteChange = {
                        startMinuteText = it
                        it.toIntOrNull()?.takeIf { value -> value in 0..59 }?.let(onSetStartMinute)
                    },
                )
                TimeFieldRow(
                    label = "End",
                    hourValue = endHourText,
                    minuteValue = endMinuteText,
                    enabled = !inProgress,
                    onHourChange = {
                        endHourText = it
                        it.toIntOrNull()?.takeIf { value -> value in 0..23 }?.let(onSetEndHour)
                    },
                    onMinuteChange = {
                        endMinuteText = it
                        it.toIntOrNull()?.takeIf { value -> value in 0..59 }?.let(onSetEndMinute)
                    },
                )
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !inProgress) {
                Text(if (inProgress) "Saving..." else "Save")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (externalChangePending) {
                    TextButton(onClick = onReload, enabled = !inProgress) {
                        Text("Reload")
                    }
                }
                TextButton(onClick = onDismiss, enabled = !inProgress) {
                    Text("Cancel")
                }
            }
        },
    )
}

@Composable
private fun TimeFieldRow(
    label: String,
    hourValue: String,
    minuteValue: String,
    enabled: Boolean,
    onHourChange: (String) -> Unit,
    onMinuteChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.width(48.dp))
        OutlinedTextField(
            value = hourValue,
            onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) onHourChange(it) },
            label = { Text("HH") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.width(88.dp),
        )
        OutlinedTextField(
            value = minuteValue,
            onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) onMinuteChange(it) },
            label = { Text("MM") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.width(88.dp),
        )
    }
}

@Composable
private fun DeleteConfirmDialog(
    entry: ClosedClockEntry,
    inProgress: Boolean,
    failureMessage: String?,
    externalChangePending: Boolean,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete clock entry?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(formatClockEntry(entry), style = MaterialTheme.typography.bodyMedium)
                failureMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFF5A3A3),
                    )
                }
                if (externalChangePending) {
                    Text(
                        text = "The file changed on disk. Reload from disk to refresh this entry before retrying.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFF2D38A),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !inProgress) {
                Text(if (inProgress) "Deleting..." else "Delete")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (externalChangePending) {
                    TextButton(onClick = onReload, enabled = !inProgress) {
                        Text("Reload")
                    }
                }
                TextButton(onClick = onDismiss, enabled = !inProgress) {
                    Text("Cancel")
                }
            }
        },
    )
}

@Composable
private fun RootSummary(rootReference: RootReference?) {
    Text(
        text = rootReference?.rawValue ?: "No org root selected",
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFFD3E6D6),
    )
}

@Composable
private fun StatusChip(status: UiStatus) {
    val label = when (status.text.key) {
        StatusMessageKey.SelectOrgDirectory -> "Select an org root to begin."
        StatusMessageKey.RootSet -> "Root restored and ready."
        StatusMessageKey.LoadedFile -> "Loaded ${status.text.args.firstOrNull().orEmpty()}."
        StatusMessageKey.TodayFileNotFound -> "Today's org file was not found."
        StatusMessageKey.FailedOpenRoot -> "Saved root could not be opened: ${status.text.args.firstOrNull().orEmpty()}"
        StatusMessageKey.FailedListingFiles -> "File listing failed: ${status.text.args.firstOrNull().orEmpty()}"
        StatusMessageKey.FailedLoadingHeadings -> "Heading load failed: ${status.text.args.firstOrNull().orEmpty()}"
        StatusMessageKey.FailedLoadingHistory -> "Clock history failed to load: ${status.text.args.firstOrNull().orEmpty()}"
        StatusMessageKey.ClockStarted -> "Clock started."
        StatusMessageKey.ClockStopped -> "Clock stopped."
        StatusMessageKey.ClockCancelled -> "Open clock cancelled."
        StatusMessageKey.ClockHistoryUpdated -> "Clock history updated."
        StatusMessageKey.ClockHistoryDeleted -> "Clock history deleted."
        StatusMessageKey.UpdateFailed -> "Clock history update failed: ${status.text.args.firstOrNull().orEmpty()}"
        StatusMessageKey.DeleteFailed -> "Clock history delete failed: ${status.text.args.firstOrNull().orEmpty()}"
        StatusMessageKey.HeadingCreated -> "Heading created."
        StatusMessageKey.HeadingTitleEmpty -> "Heading title is required."
        StatusMessageKey.EndTimeMustBeAfterStart -> "End time must be after start time."
        StatusMessageKey.ExternalFilesChanged -> "Org files changed on disk. Reload from disk to refresh."
        StatusMessageKey.SelectedFileChangedExternally -> "Selected file changed on disk. Reload from disk to refresh."
        StatusMessageKey.SelectedFileNoLongerAvailable -> "Selected file is no longer available: ${status.text.args.firstOrNull().orEmpty()}"
        else -> "${status.text.key}: ${status.text.args.joinToString()}"
    }
    val color = when (status.tone) {
        StatusTone.Info -> Color(0xFFB9D3C2)
        StatusTone.Success -> Color(0xFF8FE0A8)
        StatusTone.Warning -> Color(0xFFF2D38A)
        StatusTone.Error -> Color(0xFFF5A3A3)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
    )
}

private fun formatClockEntry(entry: ClosedClockEntry): String {
    val timeZone = TimeZone.currentSystemDefault()
    val start = entry.start.toLocalDateTime(timeZone)
    val end = entry.end.toLocalDateTime(timeZone)
    return "${entry.headingPath} | ${start.date} ${start.hour.toString().padStart(2, '0')}:${start.minute.toString().padStart(2, '0')} - ${end.hour.toString().padStart(2, '0')}:${end.minute.toString().padStart(2, '0')} (${entry.durationMinutes} min)"
}

private fun chooseRootDirectory(currentRoot: RootReference?): RootReference? {
    val chooser = JFileChooser(currentRoot?.rawValue).apply {
        dialogTitle = "Select Org Clock root"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.toPath()?.toAbsolutePath()?.normalize()?.toString()?.let(::RootReference)
    } else {
        null
    }
}
