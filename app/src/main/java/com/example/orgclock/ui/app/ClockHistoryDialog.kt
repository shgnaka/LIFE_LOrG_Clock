package com.example.orgclock.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.orgclock.R
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingViewItem
import java.time.format.DateTimeFormatter

private val ClockDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
internal fun ClockHistoryDialog(
    target: HeadingViewItem,
    historyEntries: List<ClosedClockEntry>,
    historyLoading: Boolean,
    onDismiss: () -> Unit,
    onBeginEdit: (ClosedClockEntry) -> Unit,
    onBeginDelete: (ClosedClockEntry) -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (!historyLoading) {
                onDismiss()
            }
        },
        title = {
            Text(stringResource(R.string.clock_history_title, target.node.title))
        },
        text = {
            if (historyLoading) {
                Text(stringResource(R.string.loading))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (historyEntries.isEmpty()) {
                        Text(stringResource(R.string.clock_history_empty))
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
                                    onClick = { onBeginEdit(entry) },
                                ) {
                                    Text(stringResource(R.string.edit))
                                }
                                TextButton(
                                    onClick = { onBeginDelete(entry) },
                                ) {
                                    Text(stringResource(R.string.delete))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}
