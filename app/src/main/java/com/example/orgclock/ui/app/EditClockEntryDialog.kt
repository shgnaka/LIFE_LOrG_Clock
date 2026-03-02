package com.example.orgclock.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.orgclock.R
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.time.toJavaZonedDateTime
import com.example.orgclock.ui.state.ClockEditDraft
import com.example.orgclock.ui.time.minuteStepOptions
import java.time.ZoneId

@Composable
internal fun EditClockEntryDialog(
    entry: ClosedClockEntry,
    draft: ClockEditDraft,
    onCancel: () -> Unit,
    onSelectStartHour: (Int) -> Unit,
    onSelectStartMinute: (Int) -> Unit,
    onSelectEndHour: (Int) -> Unit,
    onSelectEndMinute: (Int) -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.clock_time_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${entry.start.toJavaZonedDateTime(ZoneId.systemDefault()).toLocalDate()} / " +
                        entry.end.toJavaZonedDateTime(ZoneId.systemDefault()).toLocalDate(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TimeFieldEditor(
                    label = stringResource(R.string.start_label),
                    hour = draft.startHour,
                    minute = draft.startMinute,
                    onHourSelected = onSelectStartHour,
                    onMinuteSelected = onSelectStartMinute,
                )
                TimeFieldEditor(
                    label = stringResource(R.string.end_label),
                    hour = draft.endHour,
                    minute = draft.endMinute,
                    onHourSelected = onSelectEndHour,
                    onMinuteSelected = onSelectEndMinute,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text(stringResource(R.string.save))
            }
        },
    )
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
    val minuteOptions = remember { minuteStepOptions() }

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
