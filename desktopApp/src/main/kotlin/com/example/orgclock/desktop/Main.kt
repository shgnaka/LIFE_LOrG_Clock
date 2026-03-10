package com.example.orgclock.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.example.orgclock.presentation.RootReference
import com.example.orgclock.presentation.UiStatus
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.launch
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
    val graph = remember { DesktopAppGraph() }
    val scope = rememberCoroutineScope()
    var snapshot by remember {
        mutableStateOf(
            DesktopHostSnapshot(
                presentationState = com.example.orgclock.presentation.OrgClockPresentationState(),
            ),
        )
    }

    LaunchedEffect(Unit) {
        snapshot = graph.snapshot()
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
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF16302B),
                        contentColor = Color(0xFFF8F5ED),
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 32.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = "Org Clock Desktop MVP",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Desktop composition root restores the saved org root and wires JVM-only dependencies.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        StatusChip(status = snapshot.presentationState.status)
                        Text(
                            text = "screen=${snapshot.presentationState.screen}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFD3E6D6),
                        )
                        Text(
                            text = snapshot.presentationState.rootReference?.rawValue ?: "No org root selected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFD3E6D6),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    chooseRootDirectory(snapshot.presentationState.rootReference)?.let { root ->
                                        scope.launch {
                                            graph.openRoot(root)
                                            snapshot = graph.snapshot()
                                        }
                                    }
                                },
                            ) {
                                Text("Select org root")
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        snapshot = graph.snapshot()
                                    }
                                },
                            ) {
                                Text("Reload")
                            }
                        }
                        HorizontalDivider(color = Color(0xFF3B564F))
                        Text(
                            text = "Files: ${snapshot.files.size} | Running: ${snapshot.openClockCount} | Scan failures: ${snapshot.fileFailureCount}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (snapshot.files.isEmpty()) {
                            Text(
                                text = "No org files loaded yet. Select a root to restore or inspect the MVP workspace.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFD3E6D6),
                            )
                        } else {
                            snapshot.files.take(6).forEach { file ->
                                val running = if (file.fileId in snapshot.filesWithOpenClock) "running" else "idle"
                                Text(
                                    text = "${file.displayName} [$running]",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, Color(0xFF3B564F), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                )
                            }
                            if (snapshot.files.size > 6) {
                                Text(
                                    text = "+${snapshot.files.size - 6} more files",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFD3E6D6),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: UiStatus) {
    val label = when (status.text.key) {
        com.example.orgclock.presentation.StatusMessageKey.SelectOrgDirectory -> "Select an org root to begin."
        com.example.orgclock.presentation.StatusMessageKey.RootSet -> "Root restored and ready."
        com.example.orgclock.presentation.StatusMessageKey.FailedOpenRoot -> "Saved root could not be opened: ${status.text.args.firstOrNull().orEmpty()}"
        com.example.orgclock.presentation.StatusMessageKey.FailedListingFiles -> "File listing failed: ${status.text.args.firstOrNull().orEmpty()}"
        else -> "${status.text.key}: ${status.text.args.joinToString()}"
    }
    val color = when (status.tone) {
        com.example.orgclock.presentation.StatusTone.Info -> Color(0xFFB9D3C2)
        com.example.orgclock.presentation.StatusTone.Success -> Color(0xFF8FE0A8)
        com.example.orgclock.presentation.StatusTone.Warning -> Color(0xFFF2D38A)
        com.example.orgclock.presentation.StatusTone.Error -> Color(0xFFF5A3A3)
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
