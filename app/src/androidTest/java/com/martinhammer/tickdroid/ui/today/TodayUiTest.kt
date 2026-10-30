package com.martinhammer.tickdroid.ui.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.dp
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackRun
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.ui.MainNavigationBar
import com.martinhammer.tickdroid.ui.MainTab
import com.martinhammer.tickdroid.ui.theme.TickdroidTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** The Today composables with hand-built state. */
class TodayUiTest {

    @get:Rule val rule = createComposeRule()

    private val today = LocalDate.parse("2026-10-28")
    private val track = Track(localId = 1L, serverId = 10L, name = "Exercise", type = TrackType.YesNo, sortOrder = 0, private = false)

    private fun row(
        note: String? = null,
        editable: Boolean = true,
        notes: Boolean = true,
        run: TrackRun? = null,
        onOpenNote: () -> Unit = {},
        onLockedTap: () -> Unit = {},
        width: Dp? = null,
    ) {
        rule.setContent {
            TickdroidTheme {
                Box(if (width != null) Modifier.width(width) else Modifier) {
                TodayRow(
                    track = track,
                    prefs = TrackPrefs(),
                    day = today,
                    today = today,
                    ticks = emptyMap(),
                    note = note,
                    run = run,
                    showRun = true,
                    notesSupported = notes,
                    editable = editable,
                    onToggleBoolean = {},
                    onAdjustCounter = {},
                    onOpenNote = onOpenNote,
                    onLockedTap = onLockedTap,
                )
                }
            }
        }
    }

    @Test fun openDay_noNote_showsTheIcon_nameOpensTheCard() {
        var opened = 0
        row(onOpenNote = { opened++ })
        rule.onNodeWithContentDescription("Add a note for Exercise").assertExists().performClick()
        rule.onNodeWithText("Exercise").performClick()
        assertEquals(2, opened)
    }

    @Test fun withANote_noLineOneIcon_noteLineShown() {
        row(note = "5k along the river")
        rule.onNodeWithContentDescription("Add a note for Exercise").assertDoesNotExist()
        rule.onNodeWithContentDescription("Edit the note for Exercise").assertExists()
        rule.onNodeWithText("5k along the river").assertExists()
        rule.onNodeWithText("Show more").assertDoesNotExist()
    }

    @Test fun longNote_showMore_thenShowLess() {
        row(note = (1..12).joinToString("\n") { "Line $it" })
        rule.onNodeWithText("Show more").performClick()
        rule.onNodeWithText("Show less").assertExists().performClick()
        rule.onNodeWithText("Show more").assertExists()
    }

    @Test fun lockedDay_noIcon_nameAndNoteGiveTheLockedToast() {
        var opened = 0
        var locked = 0
        row(note = "kept", editable = false, onOpenNote = { opened++ }, onLockedTap = { locked++ })
        rule.onNodeWithContentDescription("Add a note for Exercise").assertDoesNotExist()
        rule.onNodeWithTag("todayName").performClick()
        rule.onNodeWithTag("todayNote", useUnmergedTree = true).performClick()
        assertEquals(0, opened)
        assertEquals(2, locked)
    }

    @Test fun lockedDay_withoutNotes_nameDoesNothing() {
        var locked = 0
        row(editable = false, notes = false, onLockedTap = { locked++ })
        rule.onNodeWithTag("todayName").assert(hasClickAction().not())
        assertEquals(0, locked)
    }

    @Test fun withoutNotes_noIconsAndNoNoteLine() {
        row(note = "hidden", notes = false)
        rule.onNodeWithContentDescription("Add a note for Exercise").assertDoesNotExist()
        rule.onNodeWithText("hidden").assertDoesNotExist()
    }

    // The streak is cleared from TalkBack for now (as the cells are), so it is found by its tag;
    // its wording is runText's, tested on the JVM.
    @Test fun streakText_fromTheRun() {
        row(run = TrackRun(isStreak = true, length = 3, upToYesterday = false, maxValue = 1))
        rule.onNodeWithTag("todayRun", useUnmergedTree = true).assertExists()
    }

    @Test fun noRun_noStreakText() {
        row()
        rule.onNodeWithTag("todayRun", useUnmergedTree = true).assertDoesNotExist()
    }

    // Spacing (decision row 17): line 1 as tall as the name, 4dp to line 2, 8dp to the note line.

    private fun tag(t: String) = rule.onNodeWithTag(t, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun assertAbout(what: String, expected: Dp, actual: Dp) =
        assertTrue("$what: $actual, expected $expected", abs(actual.value - expected.value) <= 1f)

    @Test fun lineOne_isTheNamesHeight_iconKeepsA48dpTouchTarget() {
        row()
        val line1 = tag("todayLine1")
        assertTrue("line 1 is ${line1.height}", line1.height < 32.dp)
        assertAbout("line 1 to line 2", 4.dp, tag("todayLine2").top - line1.bottom)
        rule.onNodeWithContentDescription("Add a note for Exercise")
            .assertTouchHeightIsEqualTo(48.dp)
            .assertTouchWidthIsEqualTo(48.dp)
    }

    @Test fun noteLine_8dpUnderLineTwo_startsUnderTheName() {
        row(note = "5k along the river")
        assertAbout("line 2 to the note line", 8.dp, tag("todayNoteLine").top - tag("todayLine2").bottom)
        assertAbout("note line start", tag("todayName").left, tag("todayNoteLine").left)
        assertAbout("line 1 to line 2", 4.dp, tag("todayLine2").top - tag("todayLine1").bottom)
    }

    @Test fun wrappedStreak_sits4dpUnderTheSquares() {
        // Narrow enough that "265-day streak, up to yesterday" can't sit beside the squares.
        row(run = TrackRun(isStreak = true, length = 265, upToYesterday = true, maxValue = 1), width = 260.dp)
        val squares = tag("todaySquares")
        val run = tag("todayRun")
        assertTrue("wrapped: run top ${run.top}, squares bottom ${squares.bottom}", run.top >= squares.bottom)
        assertAbout("squares (outline included) to the wrapped streak", 4.dp, run.top - squares.bottom)
    }

    @Test fun streakBesideTheSquares_noExtraHeight() {
        row(run = TrackRun(isStreak = true, length = 3, upToYesterday = false, maxValue = 1))
        val squares = tag("todaySquares")
        val run = tag("todayRun")
        assertTrue("beside: run starts after the squares", run.left > squares.right)
        assertAbout("line 2 is one line", maxOf(squares.height, run.height), tag("todayLine2").height)
    }

    // The card.

    private class CardHarness {
        var saved = mutableListOf<String>()
        var value by mutableStateOf(TextFieldValue(""))
    }

    private fun card(initial: String = ""): CardHarness {
        val h = CardHarness()
        h.value = TextFieldValue(initial)
        rule.setContent {
            TickdroidTheme {
                Column {
                    NoteCard(
                        track = track,
                        prefs = TrackPrefs(),
                        date = today,
                        today = today,
                        value = h.value,
                        onValueChange = { h.value = it },
                        onDone = { h.saved += h.value.text },
                    )
                }
            }
        }
        return h
    }

    @Test fun card_namesTheTrackAndDay_fieldFocused() {
        card("hello")
        rule.onNodeWithText("Exercise").assertExists()
        // In the device's language (the line under Today's title), so only the start is fixed.
        rule.onNodeWithText("Note for ", substring = true).assertExists()
        rule.onNodeWithTag("noteField").assertIsFocused()
    }

    @Test fun card_imeDone_savesOnce() {
        val h = card("hello")
        rule.onNodeWithTag("noteField").performImeAction()
        assertEquals(listOf("hello"), h.saved)
    }

    @Test fun card_typedLineBreak_savesAndInsertsNothing() {
        val h = card("hello")
        rule.onNodeWithTag("noteField").performTextInput("\n")
        assertEquals(listOf("hello"), h.saved)
        assertEquals("hello", h.value.text)
    }

    @Test fun card_pastedCrLf_keepsALineBreak() {
        val h = card("")
        rule.onNodeWithTag("noteField").performTextInput("a\r\nb")
        assertEquals("a\nb", h.value.text)
        assertEquals(emptyList<String>(), h.saved)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun card_hardwareEnterSaves_shiftEnterAddsABreak() {
        val h = card("a")
        rule.onNodeWithTag("noteField").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        assertEquals(emptyList<String>(), h.saved)
        assertEquals(true, h.value.text.contains('\n'))
        rule.onNodeWithTag("noteField").performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, h.saved.size)
    }

    @Test fun card_600CharactersPasted_endUpAs500() {
        val h = card("")
        rule.onNodeWithTag("noteField").performTextInput("x".repeat(600))
        assertEquals(500, h.value.text.length)
    }

    // The bar.

    @Test fun bar_twoItems_todaySelected_clicksReachTheCallbacks() {
        var today = 0
        var journal = 0
        rule.setContent {
            TickdroidTheme {
                MainNavigationBar(selected = MainTab.Today, onToday = { today++ }, onJournal = { journal++ })
            }
        }
        rule.onNodeWithTag("tab-today").assertIsSelected().performClick()
        rule.onNodeWithTag("tab-journal").performClick()
        rule.onNodeWithText("Journal").assertExists()
        assertEquals(1, today)
        assertEquals(1, journal)
    }
}
