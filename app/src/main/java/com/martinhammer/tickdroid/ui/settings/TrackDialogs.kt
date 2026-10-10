package com.martinhammer.tickdroid.ui.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
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

/** Delete confirmation. While [deleting], Delete shows a spinner and both buttons are disabled. */
@Composable
internal fun DeleteTrackDialog(
    name: String,
    deleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text("Delete “$name”?") },
        text = {
            Text(
                "This deletes the track and all its ticks from Tickbuddy and from every device " +
                    "that syncs with it. It can't be undone.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !deleting,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                if (deleting) ButtonSpinner() else Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) { Text("Cancel") }
        },
    )
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
