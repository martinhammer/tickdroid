package com.martinhammer.tickdroid.ui.entry

import android.text.InputType
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.martinhammer.tickdroid.domain.Tick
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.domain.Typed
import com.martinhammer.tickdroid.domain.editableNumber
import com.martinhammer.tickdroid.domain.numberLimitMessage
import com.martinhammer.tickdroid.domain.parseTyped
import com.martinhammer.tickdroid.domain.roundMilli
import com.martinhammer.tickdroid.domain.ungroupPasted
import com.martinhammer.tickdroid.ui.common.CloseWithKeyboard
import com.martinhammer.tickdroid.ui.common.EnterValidNumber
import com.martinhammer.tickdroid.ui.common.TrackBadge
import com.martinhammer.tickdroid.ui.common.negativeNotAllowedToast
import com.martinhammer.tickdroid.ui.common.notANumberToast
import com.martinhammer.tickdroid.ui.common.optionDescription
import com.martinhammer.tickdroid.ui.today.dayLine
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

/** The Number or Choice day being entered: a track and a day. */
data class EntryTarget(val trackLocalId: Long, val date: LocalDate)

/**
 * Entering a Number or Choice day, over the whole screen: the Number card docked over the
 * keyboard, or the Choice sheet. Used by the Journal, Today and the widgets' window.
 * [current] is the day's tick, null for none. [onSave] gets a Number's thousandths or a
 * Choice's place, or null to clear the day; [onClose] closes the layer.
 */
@Composable
internal fun ValueEntryLayer(
    track: Track,
    prefs: TrackPrefs,
    date: LocalDate,
    today: LocalDate,
    current: Tick?,
    onSave: (Int?) -> Unit,
    onClose: () -> Unit,
) {
    when (track.type) {
        TrackType.Number -> NumberCardLayer(track, prefs, date, today, current?.value, onSave, onClose)
        TrackType.Choice -> ChoiceSheet(track, prefs, date, today, current?.value, onSave, onClose)
        // Only Number and Choice are entered here.
        else -> LaunchedEffect(Unit) { onClose() }
    }
}

/**
 * The Number card and the layer under it (decision 3, mock-up D), built like the note card:
 * Done saves (rounded to the track's decimal places), an empty field clears the day, and a tap
 * outside, Back or closing the keyboard save and close. Text that can't be saved keeps the card
 * open on Done with a toast; closed any other way, the card closes, saves nothing and says so
 * (open decision 5). Going to the background saves a valid change and leaves the card open.
 */
@Composable
private fun NumberCardLayer(
    track: Track,
    prefs: TrackPrefs,
    date: LocalDate,
    today: LocalDate,
    current: Int?,
    onSave: (Int?) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var field by rememberSaveable(track.localId, date, stateSaver = TextFieldValue.Saver) {
        val initial = current?.let { editableNumber(it, track.places, locale) } ?: ""
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    val currentField by rememberUpdatedState(field)
    val currentValue by rememberUpdatedState(current)
    val currentTrack by rememberUpdatedState(track)
    var closed by remember { mutableStateOf(false) }
    val toast = { text: String -> Toast.makeText(context, text, Toast.LENGTH_LONG).show() }

    // Writes a valid change (nothing when it equals what the day shows, row 11d); returns what
    // the text meant, so the caller can say why an invalid one wasn't saved.
    fun commit(): Typed {
        val t = currentTrack
        val typed = parseTyped(currentField.text, t.places, t.allowNegative == true)
        when (typed) {
            Typed.Empty -> if (currentValue != null) onSave(null)
            is Typed.Value -> {
                val shown = currentValue?.let { roundMilli(it, t.places) }
                if (shown != typed.milli.toLong()) onSave(typed.milli)
            }
            else -> Unit
        }
        return typed
    }

    fun done() {
        if (closed) return
        when (commit()) {
            Typed.NotANumber -> toast(EnterValidNumber)
            Typed.Negative -> toast(negativeNotAllowedToast(currentTrack.name))
            Typed.TooLarge -> toast(numberLimitMessage(locale))
            else -> {
                closed = true
                onClose()
            }
        }
    }

    fun leave() {
        if (closed) return
        closed = true
        when (commit()) {
            Typed.NotANumber -> toast(notANumberToast(currentTrack.name))
            Typed.Negative -> toast(negativeNotAllowedToast(currentTrack.name))
            Typed.TooLarge -> toast(numberLimitMessage(locale))
            else -> Unit
        }
        onClose()
    }

    BackHandler { leave() }
    // Saves a valid change when the app goes to the background (harmlessly on a rotation too).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { commit() }
    CloseWithKeyboard(onClosed = { leave() })

    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Save the value",
            ) { leave() }
            .testTag("entryScrim"),
    ) {
        NumberCard(
            track = track,
            prefs = prefs,
            date = date,
            today = today,
            value = field,
            onValueChange = { field = numberFieldChange(currentField, it, locale) },
            onDone = { done() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        )
    }
}

/** Badge, name and the day ("Today", or the date line), shared by the card and the sheet. */
@Composable
private fun EntryHeader(track: Track, prefs: TrackPrefs, date: LocalDate, today: LocalDate) {
    val locale = LocalConfiguration.current.locales[0]
    Row(verticalAlignment = Alignment.CenterVertically) {
        TrackBadge(track, prefs, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                track.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (date == today) "Today" else dayLine(date, today, locale),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NumberCard(
    track: Track,
    prefs: TrackPrefs,
    date: LocalDate,
    today: LocalDate,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    val unit = track.unit.orEmpty()

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        shadowElevation = 3.dp,
        modifier = modifier
            .fillMaxWidth()
            // Taps on the card are its own: they mustn't reach the layer behind, which closes it.
            .pointerInput(Unit) { detectTapGestures {} }
            .testTag("numberCard"),
    ) {
        Column(Modifier.padding(16.dp)) {
            EntryHeader(track, prefs, date, today)
            Spacer(Modifier.height(12.dp))
            SignedNumberInput(enabled = track.allowNegative == true) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    suffix = if (unit.isEmpty()) null else ({ Text(unit) }),
                    // The decimal pad on every Number track, also at 0 places (a typed decimal is
                    // rounded); the minus key only where negative values are allowed (row 11i).
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onDone() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .semantics { contentDescription = if (unit.isEmpty()) track.name else "${track.name}, $unit" }
                        .testTag("numberField"),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Done saves · empty clears",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Asks the keyboard for a minus key ([enabled] on a track that allows negative values).
 * `KeyboardOptions` has no signed number type, so the request's `EditorInfo` gets
 * `TYPE_NUMBER_FLAG_SIGNED` on its way to the keyboard. A keyboard that shows no minus anyway
 * still takes a typed or pasted one.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SignedNumberInput(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val signed = PlatformTextInputMethodRequest { outAttributes ->
                request.createInputConnection(outAttributes).also {
                    outAttributes.inputType = outAttributes.inputType or InputType.TYPE_NUMBER_FLAG_SIGNED
                }
            }
            nextHandler.startInputMethod(signed)
        },
        content = content,
    )
}

/** What a Number field keeps: digits, either separator and either minus, at most this many. */
private const val NumberFieldMaxChars = 20
private const val NumberFieldChars = "0123456789.,-−"

/**
 * A Number field's change from [old] to [new]. A change that inserts more than one character at
 * once is a paste: it is read with the web's grouping rule for [locale] ([ungroupPasted]) and
 * lands ungrouped, or as it is when that rule can't read it. Then only digits, `.`, `,`, `-` and
 * `−` are kept, at most 20, as typed or pasted (as `cleanNoteInput` filters notes).
 */
internal fun numberFieldChange(old: TextFieldValue, new: TextFieldValue, locale: Locale): TextFieldValue {
    var text = new.text
    var cursor = new.selection.end
    val before = old.text.substring(0, old.selection.min)
    val after = old.text.substring(old.selection.max)
    val inserted = text.length - before.length - after.length
    if (inserted > 1 && text.startsWith(before) && text.endsWith(after)) {
        val pasted = text.substring(before.length, before.length + inserted)
        val ungrouped = ungroupPasted(pasted, locale)
        if (ungrouped != null) {
            text = before + ungrouped + after
            cursor = before.length + ungrouped.length
        }
    }
    val keptBeforeCursor = text.take(cursor.coerceIn(0, text.length)).count { it in NumberFieldChars }
    val kept = text.filter { it in NumberFieldChars }.take(NumberFieldMaxChars)
    if (kept == new.text && cursor == new.selection.end) return new
    return TextFieldValue(kept, TextRange(keptBeforeCursor.coerceAtMost(kept.length)))
}

/**
 * The Choice sheet (decision 4, mock-up E): the options in place order, each with its place in a
 * filled circle, the picked one marked. A tap on an option saves it and closes; Clear (only once
 * the day has a value) empties the day. Dismissing the sheet changes nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceSheet(
    track: Track,
    prefs: TrackPrefs,
    date: LocalDate,
    today: LocalDate,
    current: Int?,
    onSave: (Int?) -> Unit,
    onClose: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closed by remember { mutableStateOf(false) }
    fun saveAndClose(place: Int?) {
        if (closed) return
        closed = true
        if (place != current) onSave(place)
        scope.launch { sheetState.hide() }.invokeOnCompletion { onClose() }
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        modifier = Modifier.testTag("choiceSheet"),
    ) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Box(Modifier.padding(horizontal = 8.dp)) { EntryHeader(track, prefs, date, today) }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                itemsIndexed(track.options) { index, label ->
                    val place = index + 1
                    ChoiceRow(
                        place = place,
                        label = label,
                        selected = place == current,
                        onClick = { saveAndClose(place) },
                    )
                }
            }
            if (current != null) {
                TextButton(
                    onClick = { saveAndClose(null) },
                    modifier = Modifier.align(Alignment.End).testTag("choiceClear"),
                ) {
                    Text("Clear")
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(place: Int, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = optionDescription(place, label)
                role = Role.RadioButton
                this.selected = selected
                onClick { onClick(); true }
            }
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("choiceOption-$place"),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        PlaceCircle(place)
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

/** A Choice option's place in a 24dp filled circle: the sheet's rows and the track screen's. */
@Composable
internal fun PlaceCircle(place: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            place.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
