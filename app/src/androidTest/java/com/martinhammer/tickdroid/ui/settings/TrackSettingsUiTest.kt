package com.martinhammer.tickdroid.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.ui.theme.TickdroidTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The track-management composables with hand-built state; no Hilt, no ViewModels. */
class TrackSettingsUiTest {

    @get:Rule val rule = createComposeRule()

    private fun track(localId: Long, name: String = "Track $localId", private: Boolean = false) =
        Track(localId, serverId = localId * 10, name = name, type = TrackType.BOOLEAN, sortOrder = localId.toInt(), private = private)

    private val exercise = track(1, "Exercise")

    // --- New track sheet ---

    @Test fun newTrack_addDisabledWhileBlank_yesNoByDefault() {
        var added: Triple<String, TrackType, Boolean>? = null
        rule.setContent {
            TickdroidTheme { NewTrackForm(adding = false, onAdd = { n, t, p -> added = Triple(n, t, p) }) }
        }
        rule.onNodeWithText("Add track").assertIsNotEnabled()
        rule.onNodeWithText("Yes/No").assertIsSelected()

        rule.onNodeWithTag("newTrackName").performTextInput("   ")
        rule.onNodeWithText("Add track").assertIsNotEnabled()

        rule.onNodeWithTag("newTrackName").performTextInput("Water")
        rule.onNodeWithText("Counter").performClick()
        rule.onNodeWithText("Add track").assertIsEnabled().performClick()
        assertEquals(Triple("   Water", TrackType.COUNTER, false), added)
    }

    @Test fun newTrack_nameStopsAt255Characters() {
        rule.setContent { TickdroidTheme { NewTrackForm(adding = false, onAdd = { _, _, _ -> }) } }
        rule.onNodeWithTag("newTrackName").performTextInput("a".repeat(300))
        val text = rule.onNodeWithTag("newTrackName").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text
        assertEquals(255, text.length)
    }

    // --- Rename dialog ---

    @Test fun rename_selectsTheWholeName_saveDisabledWhileBlank_doneSaves() {
        var saved: String? = null
        rule.setContent {
            TickdroidTheme {
                RenameTrackDialog(currentName = "Exercise", saving = false, onSave = { saved = it }, onDismiss = {})
            }
        }
        val field = rule.onNodeWithTag("renameField")
        assertEquals(TextRange(0, 8), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])

        field.performTextClearance()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        field.performImeAction()
        assertEquals(null, saved)

        field.performTextInput("Gym")
        rule.onNodeWithText("Save").assertIsEnabled()
        field.performImeAction()
        assertEquals("Gym", saved)
    }

    // --- Offline ---

    @Test fun offline_listControls_areDisabled_andTapsGiveTheOfflineCallback() {
        var blocked = 0
        var fabClicks = 0
        rule.setContent {
            TickdroidTheme {
                Box(Modifier.fillMaxSize()) {
                    TracksSettingsContent(
                        state = TracksSettingsUiState(rows = listOf(TrackRowState(exercise, TrackPrefs())), loaded = true),
                        online = false,
                        listState = rememberLazyListState(),
                        onOpenTrack = {}, onMove = { _, _ -> }, onCommitOrder = {},
                        onMoveUp = {}, onMoveDown = {},
                        onOfflineTap = { blocked++ },
                    )
                    // Where the Scaffold puts it, clear of the rows.
                    Box(Modifier.align(Alignment.BottomEnd)) {
                        AddTrackFab(online = false, onClick = { fabClicks++ })
                    }
                }
            }
        }
        rule.onNodeWithTag("handle-1").assertIsNotEnabled().performClick()
        assertEquals(1, blocked)
        rule.onNodeWithContentDescription("Add track").assertIsNotEnabled().performClick()
        assertEquals(1, fabClicks)
    }

    @Test fun offline_trackScreenControls_areDisabled_andTapsGiveTheOfflineCallback() {
        var blocked = 0
        var actions = 0
        rule.setContent {
            TickdroidTheme {
                androidx.compose.foundation.layout.Column {
                    TrackTitle(
                        name = "Exercise", showRename = true, online = false, renameEnabled = true,
                        onRename = { actions++ }, onOfflineTap = { blocked++ },
                    )
                    TrackDetailContent(
                        track = exercise, prefs = TrackPrefs(), online = false, serverEditable = true,
                        pendingPrivate = null,
                        onPrivateChange = { actions++ }, onColorKeyChange = {}, onEmojiChange = {},
                        onMoreEmojis = {}, onDelete = { actions++ }, onOfflineTap = { blocked++ },
                    )
                }
            }
        }
        rule.onNodeWithContentDescription("Rename track").assertIsNotEnabled().performClick()
        rule.onNodeWithText("Private").assertIsNotEnabled().performClick()
        rule.onNodeWithText("Delete track").assertIsNotEnabled().performClick()
        assertEquals(3, blocked)
        assertEquals(0, actions)
    }

    // --- Icon row ---

    @Test fun iconRow_moreIsSecond_inViewWithoutScrolling() {
        var more = 0
        rule.setContent {
            TickdroidTheme {
                Box(Modifier.width(300.dp)) {
                    TrackIconRow(trackName = "Exercise", current = null, enabled = true, onSelect = {}, onMore = { more++ })
                }
            }
        }
        val letters = rule.onNodeWithContentDescription("No icon").fetchSemanticsNode().boundsInRoot
        val dots = rule.onNodeWithContentDescription("All emojis").assertIsDisplayed()
        assertTrue("⋯ isn't right after the letters", dots.fetchSemanticsNode().boundsInRoot.left > letters.right)
        dots.performClick()
        assertEquals(1, more)
    }

    @Test fun iconRow_selectedNinthHabit_isInViewOnOpen() {
        val ninth = HABIT_EMOJIS[8]
        rule.setContent {
            TickdroidTheme {
                Box(Modifier.width(300.dp)) {
                    TrackIconRow(trackName = "Exercise", current = ninth, enabled = true, onSelect = {}, onMore = {})
                }
            }
        }
        rule.waitForIdle()
        val circle = rule.onNodeWithContentDescription(ninth)
        circle.assertIsDisplayed().assertIsSelected()
        val rowRight = rule.onNodeWithTag("iconRow").fetchSemanticsNode().boundsInRoot.right
        val circleRight = circle.fetchSemanticsNode().boundsInRoot.right
        assertTrue("selected circle ends past the row's edge", circleRight <= rowRight)
    }

    @Test fun iconRow_tappingTheLetters_clearsTheIcon() {
        var selected: String? = "unset"
        rule.setContent {
            TickdroidTheme {
                TrackIconRow(trackName = "Exercise", current = HABIT_EMOJIS[1], enabled = true, onSelect = { selected = it }, onMore = {})
            }
        }
        rule.onNodeWithContentDescription("No icon").performClick()
        assertEquals(null, selected)
    }

    // --- Screen-reader reorder actions ---

    @Test fun list_rowsCarryMoveUpAndDownActions() {
        val movedUp = mutableListOf<Long>()
        val movedDown = mutableListOf<Long>()
        val rows = listOf(track(1), track(2), track(3)).map { TrackRowState(it, TrackPrefs()) }
        rule.setContent {
            TickdroidTheme {
                TracksSettingsContent(
                    state = TracksSettingsUiState(rows = rows, loaded = true),
                    online = true,
                    listState = rememberLazyListState(),
                    onOpenTrack = {}, onMove = { _, _ -> }, onCommitOrder = {},
                    onMoveUp = { movedUp += it }, onMoveDown = { movedDown += it },
                    onOfflineTap = {},
                )
            }
        }
        fun labels(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
        assertEquals(listOf("Move down"), labels("track-1"))
        assertEquals(listOf("Move up", "Move down"), labels("track-2"))
        assertEquals(listOf("Move up"), labels("track-3"))

        val middle = rule.onNodeWithTag("track-2").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        rule.runOnIdle {
            middle.first { it.label == "Move up" }.action()
            middle.first { it.label == "Move down" }.action()
        }
        assertEquals(listOf(2L), movedUp)
        assertEquals(listOf(2L), movedDown)
    }
}
