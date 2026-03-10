package com.example.orgclock.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.presentation.RootReference
import com.example.orgclock.presentation.Screen
import com.example.orgclock.presentation.StatusMessageKey
import com.example.orgclock.presentation.StatusTone
import com.example.orgclock.presentation.UiStatus
import com.example.orgclock.ui.state.OrgClockUiAction
import com.example.orgclock.ui.state.OrgClockUiState
import com.example.orgclock.ui.store.OrgClockStore
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
                text = "Linux-first desktop host for the shared Org Clock store.",
                style = MaterialTheme.typography.bodyLarge,
            )
            StatusChip(status = state.status)
            RootSummary(rootReference = state.rootReference)
            DesktopToolbar(
                state = state,
                onPickRoot = onPickRoot,
                onAction = onAction,
            )
            HorizontalDivider(color = Color(0xFF3B564F))
            when (state.screen) {
                Screen.RootSetup -> RootSetupPane(onPickRoot = onPickRoot)
                Screen.FilePicker -> FilePickerPane(
                    files = state.files,
                    filesWithOpenClock = state.filesWithOpenClock,
                    onSelectFile = { onAction(OrgClockUiAction.SelectFile(it)) },
                )
                Screen.HeadingList -> HeadingListPane(
                    file = state.selectedFile,
                    headings = state.headings,
                    pendingClockOps = state.pendingClockOps.map { it.toString() }.toSet(),
                    onRefresh = { onAction(OrgClockUiAction.RefreshHeadings) },
                    onBack = { onAction(OrgClockUiAction.OpenFilePicker) },
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
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Text(text = file.displayName, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = if (running) "running" else "idle",
            style = MaterialTheme.typography.bodySmall,
            color = if (running) Color(0xFF8FE0A8) else Color(0xFFD3E6D6),
        )
    }
}

@Composable
private fun HeadingListPane(
    file: OrgFileEntry?,
    headings: List<HeadingViewItem>,
    pendingClockOps: Set<String>,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = file?.displayName ?: "No file selected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onRefresh) { Text("Refresh headings") }
            Button(onClick = onBack) { Text("Back to files") }
        }
        if (headings.isEmpty()) {
            Text(
                text = "No headings loaded for this file.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFD3E6D6),
            )
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(headings, key = { it.node.path.toString() }) { item ->
                val levelPrefix = "L${item.node.level}"
                val running = item.openClock != null
                val pending = item.node.path.toString() in pendingClockOps
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF3B564F), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "$levelPrefix ${item.node.title}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = buildString {
                            append(item.node.path.toString())
                            if (running) append(" | running")
                            if (pending) append(" | pending")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (running) Color(0xFF8FE0A8) else Color(0xFFD3E6D6),
                    )
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onChangeRoot) {
                Text("Change root")
            }
            Button(onClick = onBack) {
                Text("Back")
            }
        }
        Text(
            text = "Notification and Android-specific integrations stay disabled on desktop MVP.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFD3E6D6),
        )
    }
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
