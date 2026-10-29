package com.martinhammer.tickdroid.ui.today

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.cleanNoteInput
import com.martinhammer.tickdroid.ui.common.TrackBadge
import java.time.LocalDate

/**
 * The note card and the layer under it, over the whole screen while a note is being written.
 *
 * Leaving saves: Done (Enter), a tap anywhere outside the card (it only closes the card, it
 * doesn't also tick or move the day), Back, and closing the keyboard all save and close, so one
 * Back does it. Going to the home screen or another app saves and leaves the card open. The
 * view model saves only a change, so saving twice is harmless.
 */
@Composable
internal fun NoteCardLayer(
    track: Track,
    prefs: TrackPrefs,
    date: LocalDate,
    today: LocalDate,
    initialText: String,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
) {
    var field by rememberSaveable(track.localId, date, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length)))
    }
    val currentField by rememberUpdatedState(field)
    var closed by remember { mutableStateOf(false) }
    fun saveAndClose() {
        if (closed) return
        closed = true
        onSave(currentField.text)
        onClose()
    }

    BackHandler { saveAndClose() }
    // Saves when the app goes to the background (and on a rotation, harmlessly); the card stays.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { onSave(currentField.text) }
    CloseWithKeyboard(onClosed = { saveAndClose() })

    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Save the note",
            ) { saveAndClose() }
            .testTag("noteCardScrim"),
    ) {
        NoteCard(
            track = track,
            prefs = prefs,
            date = date,
            today = today,
            value = field,
            onValueChange = { field = it },
            onDone = { saveAndClose() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        )
    }
}

/**
 * The keyboard going from shown to hidden closes the card: Android's first Back closes the
 * keyboard and never reaches the app, so without this it would take two. Only while the screen
 * is resumed: going to the home screen also hides the keyboard, and the card must stay then. A
 * physical keyboard never shows the soft one, so the card then stays until Done, Back or a tap
 * outside.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CloseWithKeyboard(onClosed: () -> Unit) {
    val imeVisible = WindowInsets.isImeVisible
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var wasVisible by remember { mutableStateOf(false) }
    val currentOnClosed by rememberUpdatedState(onClosed)
    LaunchedEffect(imeVisible) {
        when {
            imeVisible -> wasVisible = true
            wasVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) -> currentOnClosed()
            else -> wasVisible = false
        }
    }
}

/**
 * The card itself: badge and name, "Note for …", the field (3 lines, growing to 8, then
 * scrolling) and the hint. Enter is Done: the keyboard shows its action key, and two guards catch
 * keyboards that send a line break anyway (see [noteFieldChange]); with a physical keyboard
 * Enter saves and Shift+Enter adds a line break, as on the web.
 */
@Composable
internal fun NoteCard(
    track: Track,
    prefs: TrackPrefs,
    date: LocalDate,
    today: LocalDate,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val currentValue by rememberUpdatedState(value)
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        shadowElevation = 3.dp,
        modifier = modifier
            .fillMaxWidth()
            // Taps on the card are its own: they mustn't reach the layer behind, which closes it.
            .pointerInput(Unit) { detectTapGestures {} }
            .testTag("noteCard"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrackBadge(track, prefs, size = 32.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(track.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        "Note for ${dayLine(date, today, locale)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { new ->
                    when (val change = noteFieldChange(currentValue, new)) {
                        NoteFieldChange.Done -> onDone()
                        is NoteFieldChange.Edit -> onValueChange(change.value)
                    }
                },
                minLines = 3,
                maxLines = 8,
                singleLine = false,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.Sentences,
                ),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                        if (!enter) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown) {
                            if (event.isShiftPressed) {
                                onValueChange(insertLineBreak(currentValue))
                            } else {
                                onDone()
                            }
                        }
                        // Up and down both consumed, so the field never inserts its own break.
                        true
                    }
                    .semantics { contentDescription = "Note for ${track.name}" }
                    .testTag("noteField"),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Done saves · empty deletes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal sealed interface NoteFieldChange {
    data object Done : NoteFieldChange
    data class Edit(val value: TextFieldValue) : NoteFieldChange
}

/**
 * What a change from [old] to [new] means. A change that only puts one line break where the
 * selection was is Enter from a keyboard that sent it despite the Done action: save, insert
 * nothing. Anything else (pasted text keeps its line breaks) is cleaned by [cleanNoteInput] as it
 * arrives, the cursor kept where it was relative to the text.
 */
internal fun noteFieldChange(old: TextFieldValue, new: TextFieldValue): NoteFieldChange {
    val start = old.selection.min
    val end = old.selection.max
    val enter = old.text.substring(0, start) + "\n" + old.text.substring(end)
    if (new.text == enter) return NoteFieldChange.Done
    return NoteFieldChange.Edit(cleaned(new))
}

/** A line break at the selection (Shift+Enter), through the same cleaning and cap. */
internal fun insertLineBreak(value: TextFieldValue): TextFieldValue {
    val start = value.selection.min
    val end = value.selection.max
    val text = value.text.substring(0, start) + "\n" + value.text.substring(end)
    return cleaned(TextFieldValue(text, TextRange(start + 1)))
}

private fun cleaned(value: TextFieldValue): TextFieldValue {
    val text = cleanNoteInput(value.text)
    if (text == value.text) return value
    val cursor = cleanNoteInput(value.text.substring(0, value.selection.end.coerceAtMost(value.text.length))).length
    return TextFieldValue(text, TextRange(cursor.coerceAtMost(text.length)))
}
