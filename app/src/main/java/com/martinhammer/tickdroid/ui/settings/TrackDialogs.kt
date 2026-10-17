package com.martinhammer.tickdroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.martinhammer.tickdroid.domain.capTrackName

/**
 * "Rename track". Opens with the whole current name selected and the keyboard up. Save (or
 * Done on the keyboard) is disabled while the trimmed name is blank. While [saving], Save shows
 * a spinner and both buttons are disabled; on failure the caller leaves it open, so Save retries.
 */
@Composable
internal fun RenameTrackDialog(
    currentName: String,
    saving: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(currentName, TextRange(0, currentName.length)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val canSave = value.text.isNotBlank() && !saving
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Rename track") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.copy(text = capTrackName(it.text)) },
                singleLine = true,
                enabled = !saving,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (canSave) onSave(value.text) }),
                modifier = Modifier.focusRequester(focusRequester).testTag("renameField"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.text) }, enabled = canSave) {
                if (saving) ButtonSpinner() else Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
        },
    )
}

/**
 * Delete confirmation. While [deleting] (or [archiving]), the busy button shows a spinner and all
 * buttons are disabled. [onArchiveInstead] non-null (an active track, on a server that can
 * archive) adds a second paragraph and an "Archive instead" button at the start of the row,
 * which archives straight away with no second confirm. Null keeps the step 4 dialog exactly.
 */
@Composable
internal fun DeleteTrackDialog(
    name: String,
    deleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onArchiveInstead: (() -> Unit)? = null,
    archiving: Boolean = false,
) {
    val busy = deleting || archiving
    val delete: @Composable () -> Unit = {
        TextButton(
            onClick = onConfirm,
            enabled = !busy,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            if (deleting) ButtonSpinner() else Text("Delete")
        }
    }
    val cancel: @Composable () -> Unit = {
        TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
    }
    val warning = "This deletes the track and all its ticks from Tickbuddy and from every device " +
        "that syncs with it. It can't be undone."
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Delete “$name”?") },
        text = {
            if (onArchiveInstead == null) {
                Text(warning)
            } else {
                Column {
                    Text(warning)
                    Spacer(Modifier.height(12.dp))
                    Text("To keep its history, archive it instead.")
                }
            }
        },
        confirmButton = {
            if (onArchiveInstead == null) {
                delete()
            } else {
                SplitDialogButtons(
                    start = {
                        TextButton(onClick = onArchiveInstead, enabled = !busy) {
                            if (archiving) ButtonSpinner() else Text("Archive instead")
                        }
                    },
                    end = {
                        cancel()
                        delete()
                    },
                )
            }
        },
        dismissButton = if (onArchiveInstead == null) cancel else null,
    )
}

/**
 * Archive confirmation (no question mark, same wording as the web). No warning colour: nothing is
 * lost. While [archiving], Archive shows a spinner and both buttons are disabled.
 */
@Composable
internal fun ArchiveTrackDialog(
    name: String,
    archiving: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!archiving) onDismiss() },
        title = { Text("Archive “$name”") },
        text = {
            Text(
                "Archived tracks are no longer tracked or shown in the journal and widgets. All " +
                    "their ticks are kept, and you can restore them at any time.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !archiving) {
                if (archiving) ButtonSpinner() else Text("Archive")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !archiving) { Text("Cancel") }
        },
    )
}

/**
 * An archived track, tapped in Tracks settings: Restore at the start, Cancel and Delete (error
 * colour) at the end. Restore has no confirm; while [restoring] it shows a spinner and all three
 * buttons are disabled. Delete hands over to the delete confirm (without "Archive instead").
 */
@Composable
internal fun ArchivedTrackDialog(
    name: String,
    dateText: String,
    restoring: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!restoring) onDismiss() },
        title = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { Text(archivedLabel(dateText)) },
        confirmButton = {
            SplitDialogButtons(
                start = {
                    TextButton(onClick = onRestore, enabled = !restoring) {
                        if (restoring) ButtonSpinner() else Text("Restore")
                    }
                },
                end = {
                    TextButton(onClick = onDismiss, enabled = !restoring) { Text("Cancel") }
                    TextButton(
                        onClick = onDelete,
                        enabled = !restoring,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Delete")
                    }
                },
            )
        },
    )
}

/**
 * A three-button row for an [AlertDialog], which only has `confirmButton` and `dismissButton` and
 * places them together at the end. Pass this as `confirmButton` (with `dismissButton = null`):
 * [start] sits at the start edge and [end] at the end edge; when they don't fit on one line (large
 * font sizes), [start] gets its own line above [end] instead of squeezing it. Mirrored in RTL.
 */
@Composable
internal fun SplitDialogButtons(
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        start()
        // Weighted: takes what's left of the line (or the whole of its own line after a wrap),
        // with its buttons pushed to the end edge. FlowRow breaks lines on the intrinsic width.
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) { end() }
    }
}

/** Stands in for a button's label while its change is on its way. */
@Composable
internal fun ButtonSpinner() {
    CircularProgressIndicator(
        strokeWidth = 2.dp,
        color = LocalContentColor.current,
        modifier = Modifier.size(20.dp),
    )
}
