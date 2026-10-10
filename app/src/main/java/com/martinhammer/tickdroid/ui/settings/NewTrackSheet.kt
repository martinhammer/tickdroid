package com.martinhammer.tickdroid.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.domain.capTrackName
import com.martinhammer.tickdroid.ui.common.MaxContentWidth

/**
 * "New track". The caller hides it on success; on failure it stays open with what was typed,
 * and "Add track" retries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewTrackSheet(
    sheetState: SheetState,
    adding: Boolean,
    onAdd: (name: String, type: TrackType, private: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        NewTrackForm(adding = adding, onAdd = onAdd)
    }
}

@Composable
internal fun NewTrackForm(
    adding: Boolean,
    onAdd: (name: String, type: TrackType, private: Boolean) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(TrackType.BOOLEAN) }
    var private by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
    ) {
        Text("New track", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))

        SectionLabel(label = "Name")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = capTrackName(it) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier
                .widthIn(max = MaxContentWidth)
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag("newTrackName"),
        )
        Spacer(Modifier.height(24.dp))

        SectionLabel(label = "Type", subtitle = "Track type can't be changed later")
        Spacer(Modifier.height(12.dp))
        val types = listOf(TrackType.BOOLEAN, TrackType.COUNTER)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth()) {
            types.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = type == option,
                    onClick = { type = option },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = types.size),
                ) {
                    Text(if (option == TrackType.COUNTER) "Counter" else "Yes/No")
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        ToggleRow(label = "Private", checked = private, onCheckedChange = { private = it })
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onAdd(name, type, private) },
            enabled = name.isNotBlank() && !adding,
            modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().testTag("addTrack"),
        ) {
            if (adding) ButtonSpinner() else Text("Add track")
        }
    }
}
