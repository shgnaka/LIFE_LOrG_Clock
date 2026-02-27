package com.example.orgclock.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.orgclock.R
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.time.formatWithZone
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ClockDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
internal fun DeleteClockEntryDialog(
    entry: ClosedClockEntry,
    deletingInProgress: Boolean,
    onCancel: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (!deletingInProgress) {
                onCancel()
            }
        },
        title = { Text(stringResource(R.string.clock_history_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.clock_history_delete_confirm))
                Text(
                    "${entry.start.formatWithZone(ClockDateTimeFormatter, ZoneId.systemDefault())} - " +
                        entry.end.formatWithZone(ClockDateTimeFormatter, ZoneId.systemDefault()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !deletingInProgress,
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirmDelete,
                enabled = !deletingInProgress,
            ) {
                Text(stringResource(R.string.delete))
            }
        },
    )
}
