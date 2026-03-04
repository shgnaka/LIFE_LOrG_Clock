package com.example.orgclock.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.orgclock.R
import com.example.orgclock.ui.state.CreateHeadingDialogState
import com.example.orgclock.ui.state.CreateHeadingMode

@Composable
internal fun CreateHeadingInputDialog(
    dialog: CreateHeadingDialogState,
    onDismiss: () -> Unit,
    onUpdateTitle: (String) -> Unit,
    onSetTplTag: (Boolean) -> Unit,
    onSubmit: () -> Unit,
) {
    val dialogTitle = if (dialog.mode == CreateHeadingMode.L1) {
        stringResource(R.string.create_l1_heading)
    } else {
        stringResource(R.string.create_l2_heading)
    }
    val canSubmit = !dialog.submitting && dialog.titleInput.trim().isNotEmpty()
    AlertDialog(
        onDismissRequest = {
            if (!dialog.submitting) {
                onDismiss()
            }
        },
        title = { Text(dialogTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (dialog.mode == CreateHeadingMode.L2 && !dialog.parentL1Title.isNullOrBlank()) {
                    Text(
                        stringResource(R.string.parent_heading_label, dialog.parentL1Title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextField(
                    value = dialog.titleInput,
                    onValueChange = onUpdateTitle,
                    label = { Text(stringResource(R.string.heading_title_label)) },
                    supportingText = {
                        if (dialog.titleInput.trim().isEmpty()) {
                            Text(stringResource(R.string.heading_title_required_hint))
                        }
                    },
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
                            onCheckedChange = onSetTplTag,
                            enabled = !dialog.submitting,
                        )
                        Text(stringResource(R.string.add_tpl_tag))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !dialog.submitting,
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSubmit,
                enabled = canSubmit,
            ) {
                Text(
                    if (dialog.submitting) {
                        stringResource(R.string.creating)
                    } else {
                        stringResource(R.string.create)
                    },
                )
            }
        },
    )
}
