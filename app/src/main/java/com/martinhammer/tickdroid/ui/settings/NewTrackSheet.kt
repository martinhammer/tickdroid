package com.martinhammer.tickdroid.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.martinhammer.tickdroid.data.sync.DEFAULT_DECIMAL_PLACES
import com.martinhammer.tickdroid.data.sync.NewTrackSettings
import com.martinhammer.tickdroid.domain.MAX_DECIMAL_PLACES
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.domain.capTrackName
import com.martinhammer.tickdroid.ui.common.DuplicateOptionMessage
import com.martinhammer.tickdroid.ui.common.MaxContentWidth
import com.martinhammer.tickdroid.widget.SegmentLabel
import sh.calvin.reorderable.ReorderableColumn

/**
 * "New track". The caller hides it on success; on failure it stays open with what was typed,
 * and "Add track" retries. Number is offered only with the server's `numberTracks` flag and
 * Choice only with `choiceTracks` (row 11a).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewTrackSheet(
    sheetState: SheetState,
    adding: Boolean,
    onAdd: (name: String, type: TrackType, private: Boolean, settings: NewTrackSettings) -> Unit,
    onDismiss: () -> Unit,
    numberSupported: Boolean = false,
    choiceSupported: Boolean = false,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        NewTrackForm(
            adding = adding,
            onAdd = onAdd,
            numberSupported = numberSupported,
            choiceSupported = choiceSupported,
        )
    }
}

/** A Choice track's options: 2 to 99 (as on the server). */
internal const val MIN_OPTIONS = 2
internal const val MAX_OPTIONS = 99

/**
 * The options whose label an earlier option already has, after trimming and ignoring case (the
 * server's rule): the second of two equal labels shows T23 under its field.
 */
internal fun duplicateOptionIndexes(labels: List<String>): Set<Int> {
    val seen = mutableSetOf<String>()
    return labels.mapIndexedNotNull { index, label ->
        val key = label.trim().lowercase()
        if (key.isEmpty()) return@mapIndexedNotNull null
        if (!seen.add(key)) index else null
    }.toSet()
}

/**
 * Whether "Add track" can send: a name, and for a Choice no blank option and no two the same
 * (after trimming, ignoring case: the server's rule).
 */
internal fun canAddTrack(name: String, type: TrackType, options: List<String>): Boolean {
    if (name.isBlank()) return false
    if (type != TrackType.Choice) return true
    return options.size in MIN_OPTIONS..MAX_OPTIONS &&
        options.none { it.isBlank() } &&
        duplicateOptionIndexes(options).isEmpty()
}

private val OptionsSaver = listSaver<MutableState<List<String>>, String>(
    save = { it.value },
    restore = { mutableStateOf(it) },
)

@Composable
internal fun NewTrackForm(
    adding: Boolean,
    onAdd: (name: String, type: TrackType, private: Boolean, settings: NewTrackSettings) -> Unit,
    numberSupported: Boolean = false,
    choiceSupported: Boolean = false,
) {
    var name by rememberSaveable { mutableStateOf("") }
    // Saved as the wire value: TrackType is a sealed type, not an enum, so not Bundle-able.
    var typeWire by rememberSaveable { mutableStateOf(TrackType.YesNo.wireValue) }
    val type = TrackType.fromWire(typeWire)
    var private by rememberSaveable { mutableStateOf(false) }
    // Both types' fields are kept while switching type; only the chosen type's are sent.
    var unit by rememberSaveable { mutableStateOf("") }
    var decimalPlaces by rememberSaveable { mutableIntStateOf(DEFAULT_DECIMAL_PLACES) }
    var allowNegative by rememberSaveable { mutableStateOf(false) }
    var options by rememberSaveable(saver = OptionsSaver) { mutableStateOf(List(MIN_OPTIONS) { "" }) }
    var orderedScale by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
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
        val types = listOfNotNull(
            TrackType.YesNo,
            TrackType.Counter,
            TrackType.Number.takeIf { numberSupported },
            TrackType.Choice.takeIf { choiceSupported },
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth()) {
            types.forEachIndexed { index, option ->
                val selected = type == option
                SegmentedButton(
                    selected = selected,
                    onClick = { typeWire = option.wireValue },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = types.size),
                    modifier = Modifier.testTag("newTrackType-${option.wireValue}"),
                ) {
                    // Four segments on a 360dp phone: shrinks rather than running under the ✓.
                    SegmentLabel(trackTypeLabel(option), besideCheckMark = selected)
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        when (type) {
            TrackType.Number -> {
                NumberFields(
                    unit = unit,
                    onUnitChange = { unit = capTrackName(it) },
                    decimalPlaces = decimalPlaces,
                    onDecimalPlacesChange = { decimalPlaces = it },
                    allowNegative = allowNegative,
                    onAllowNegativeChange = { allowNegative = it },
                )
                Spacer(Modifier.height(16.dp))
            }
            TrackType.Choice -> {
                ChoiceFields(
                    options = options,
                    onOptionsChange = { options = it },
                    orderedScale = orderedScale,
                    onOrderedScaleChange = { orderedScale = it },
                )
                Spacer(Modifier.height(16.dp))
            }
            else -> Unit
        }

        ToggleRow(label = "Private", checked = private, onCheckedChange = { private = it })
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                val settings = NewTrackSettings(
                    unit = unit,
                    decimalPlaces = decimalPlaces,
                    allowNegative = allowNegative,
                    options = options,
                    orderedScale = orderedScale,
                )
                onAdd(name, type, private, settings)
            },
            enabled = canAddTrack(name, type, options) && !adding,
            modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().testTag("addTrack"),
        ) {
            if (adding) ButtonSpinner() else Text("Add track")
        }
    }
}

/** A Number's Unit (optional), Decimal places (0 to 3) and "Allow negative values" (T2 to T4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NumberFields(
    unit: String,
    onUnitChange: (String) -> Unit,
    decimalPlaces: Int,
    onDecimalPlacesChange: (Int) -> Unit,
    allowNegative: Boolean,
    onAllowNegativeChange: (Boolean) -> Unit,
) {
    SectionLabel(label = "Unit")
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = unit,
        onValueChange = onUnitChange,
        singleLine = true,
        placeholder = { Text("Optional") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().testTag("newTrackUnit"),
    )
    Spacer(Modifier.height(16.dp))
    SectionLabel(label = "Decimal places")
    Spacer(Modifier.height(8.dp))
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = decimalPlaces.toString(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .widthIn(max = MaxContentWidth)
                .fillMaxWidth()
                .testTag("newTrackDecimalPlaces"),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (0..MAX_DECIMAL_PLACES).forEach { places ->
                DropdownMenuItem(
                    text = { Text(places.toString()) },
                    onClick = {
                        onDecimalPlacesChange(places)
                        expanded = false
                    },
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    ToggleRow(label = "Allow negative values", checked = allowNegative, onCheckedChange = onAllowNegativeChange)
}

/**
 * A Choice's options (T5 to T8): two empty fields to start, Add option up to 99, ✕ while there
 * are more than two, and drag to reorder (with Move up / Move down for TalkBack), as Tracks
 * settings reorders tracks. Options can't be added, removed or reordered later.
 */
@Composable
private fun ChoiceFields(
    options: List<String>,
    onOptionsChange: (List<String>) -> Unit,
    orderedScale: Boolean,
    onOrderedScaleChange: (Boolean) -> Unit,
) {
    // Moves the focus to a field just added (Add option, or Next on the last one).
    var focusIndex by remember { mutableStateOf<Int?>(null) }
    val duplicates = duplicateOptionIndexes(options)
    fun add() {
        if (options.size >= MAX_OPTIONS) return
        onOptionsChange(options + "")
        focusIndex = options.size
    }
    fun move(from: Int, to: Int) {
        if (from == to || to !in options.indices) return
        onOptionsChange(options.toMutableList().apply { add(to, removeAt(from)) })
    }

    SectionLabel(label = "Options")
    Spacer(Modifier.height(8.dp))
    ReorderableColumn(
        list = options,
        onSettle = { from, to -> move(from, to) },
        modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
    ) { index, label, _ ->
        ReorderableItem {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(focusIndex) {
                if (focusIndex == index) {
                    focusRequester.requestFocus()
                    focusIndex = null
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .semantics {
                        customActions = listOfNotNull(
                            if (index > 0) CustomAccessibilityAction("Move up") { move(index, index - 1); true } else null,
                            if (index < options.lastIndex) CustomAccessibilityAction("Move down") { move(index, index + 1); true } else null,
                        )
                    },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .draggableHandle(),
                ) {
                    Icon(
                        Icons.Filled.DragHandle,
                        contentDescription = "Reorder option",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val isLast = index == options.lastIndex
                OutlinedTextField(
                    value = label,
                    onValueChange = { new ->
                        onOptionsChange(options.toMutableList().apply { set(index, capTrackName(new)) })
                    },
                    singleLine = true,
                    placeholder = { Text("Option ${index + 1}") },
                    isError = index in duplicates,
                    supportingText = if (index in duplicates) ({ Text(DuplicateOptionMessage) }) else null,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = if (isLast && options.size >= MAX_OPTIONS) ImeAction.Done else ImeAction.Next,
                    ),
                    // Next on the last option adds one, as on the web.
                    keyboardActions = KeyboardActions(onNext = { if (isLast) add() else defaultKeyboardAction(ImeAction.Next) }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .testTag("newTrackOption-$index"),
                )
                if (options.size > MIN_OPTIONS) {
                    IconButton(
                        onClick = { onOptionsChange(options.toMutableList().apply { removeAt(index) }) },
                        modifier = Modifier.testTag("removeOption-$index"),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove option")
                    }
                }
            }
        }
    }
    if (options.size < MAX_OPTIONS) {
        TextButton(onClick = { add() }, modifier = Modifier.testTag("addOption")) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Add option")
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "Options can't be added, removed or reordered later.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    ToggleRow(label = "Ordered scale", checked = orderedScale, onCheckedChange = onOrderedScaleChange)
}
