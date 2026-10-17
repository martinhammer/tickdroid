package com.martinhammer.tickdroid.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.hasTestTag
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    // --- Archived tracks ---

    private val ukDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)

    private fun archivedRow(localId: Long, name: String, day: Int) =
        TrackRowState(track(localId, name).copy(archivedAt = LocalDate.of(2026, 8, day)), TrackPrefs())

    private fun archivedList(
        state: TracksSettingsUiState,
        online: Boolean = true,
        onOpenArchived: (Long) -> Unit = {},
        onOfflineTap: () -> Unit = {},
    ) {
        rule.setContent {
            TickdroidTheme {
                var open by remember { mutableStateOf(false) }
                TracksSettingsContent(
                    state = state,
                    online = online,
                    listState = rememberLazyListState(),
                    onOpenTrack = {}, onMove = { _, _ -> }, onCommitOrder = {},
                    onMoveUp = {}, onMoveDown = {},
                    onOfflineTap = onOfflineTap,
                    archivedOpen = open,
                    onToggleArchived = { open = !open },
                    onOpenArchived = onOpenArchived,
                    dateFormatter = ukDate,
                )
            }
        }
    }

    // State as the ViewModel builds it: archived rows already sorted.
    private val withArchived = TracksSettingsUiState(
        rows = listOf(TrackRowState(exercise, TrackPrefs())),
        archived = listOf(archivedRow(5, "Couch to 5k", 2), archivedRow(4, "Smoking", 14)),
        loaded = true,
        archiveSupported = true,
    )

    @Test fun archived_headerHiddenWhenNothingIsArchived() {
        archivedList(TracksSettingsUiState(rows = listOf(TrackRowState(exercise, TrackPrefs())), loaded = true))
        rule.onAllNodesWithTag("archived-header").assertCountEquals(0)
    }

    @Test fun archived_startsCollapsed_opensInOrder_withDates_andReportsState() {
        archivedList(withArchived)
        val header = rule.onNodeWithTag("archived-header")
        assertEquals("Collapsed", header.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        assertEquals(listOf("Archived, 2 tracks"), header.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        rule.onAllNodesWithTag("archived-4").assertCountEquals(0)

        header.performClick()
        assertEquals("Expanded", header.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        val couch = rule.onNodeWithTag("archived-5").fetchSemanticsNode().boundsInRoot
        val smoking = rule.onNodeWithTag("archived-4").fetchSemanticsNode().boundsInRoot
        assertTrue("archived rows aren't in the given order", couch.top < smoking.top)
        rule.onNodeWithText("Yes/No · Archived 14 Aug 2026").assertIsDisplayed()
        rule.onNodeWithText("Yes/No · Archived 2 Aug 2026").assertIsDisplayed()

        header.performClick()
        rule.onAllNodesWithTag("archived-4").assertCountEquals(0)
    }

    @Test fun archived_rowsHaveNoHandleOrMoveActions_andTapOpensTheDialogOnline() {
        val opened = mutableListOf<Long>()
        archivedList(withArchived, onOpenArchived = { opened += it })
        rule.onNodeWithTag("archived-header").performClick()
        rule.onAllNodesWithTag("handle-4").assertCountEquals(0)
        val node = rule.onNodeWithTag("archived-4").fetchSemanticsNode()
        assertEquals(null, node.config.getOrNull(SemanticsActions.CustomActions))
        assertEquals("Restore or delete", node.config[SemanticsActions.OnClick].label)
        rule.onNodeWithTag("archived-4").performClick()
        assertEquals(listOf(40L), opened)
    }

    @Test fun archived_rowTapOffline_givesTheOfflineCallback_headerStillToggles() {
        var blocked = 0
        val opened = mutableListOf<Long>()
        archivedList(withArchived, online = false, onOpenArchived = { opened += it }, onOfflineTap = { blocked++ })
        rule.onNodeWithTag("archived-header").performClick()
        rule.onNodeWithTag("archived-4").performClick()
        assertEquals(1, blocked)
        assertTrue(opened.isEmpty())
    }

    @Test fun archived_everyTrackArchived_showsEmptyTextAndTheArchivedRow() {
        archivedList(withArchived.copy(rows = emptyList()))
        rule.onNodeWithText("No tracks yet. Tap + to add one.").assertIsDisplayed()
        rule.onNodeWithTag("archived-header").assertIsDisplayed()
    }

    @Test fun archivedDialog_restoreFirst_deleteLast_restoringDisablesAll() {
        var restoring by mutableStateOf(false)
        var restores = 0
        var deletes = 0
        rule.setContent {
            TickdroidTheme {
                ArchivedTrackDialog(
                    name = "Smoking", dateText = "14 Aug 2026", restoring = restoring,
                    onRestore = { restores++ }, onDelete = { deletes++ }, onDismiss = {},
                )
            }
        }
        rule.onNodeWithText("Archived 14 Aug 2026").assertIsDisplayed()
        val restore = rule.onNodeWithText("Restore").fetchSemanticsNode().boundsInRoot
        val cancel = rule.onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot
        val delete = rule.onNodeWithText("Delete").fetchSemanticsNode().boundsInRoot
        assertTrue(restore.left < cancel.left && cancel.left < delete.left)
        rule.onNodeWithText("Restore").performClick()
        rule.onNodeWithText("Delete").performClick()
        assertEquals(1, restores)
        assertEquals(1, deletes)

        restoring = true
        rule.onAllNodesWithText("Restore").assertCountEquals(0) // spinner in its place
        rule.onNodeWithText("Cancel").assertIsNotEnabled()
        rule.onNodeWithText("Delete").assertIsNotEnabled()
    }

    @Test fun deleteDialog_withoutArchiveInstead_isStep4s() {
        rule.setContent {
            TickdroidTheme { DeleteTrackDialog(name = "Exercise", deleting = false, onConfirm = {}, onDismiss = {}) }
        }
        rule.onNodeWithText("Delete “Exercise”?").assertIsDisplayed()
        rule.onAllNodesWithText("Archive instead").assertCountEquals(0)
        rule.onAllNodesWithText("To keep its history, archive it instead.").assertCountEquals(0)
    }

    @Test fun deleteDialog_withArchiveInstead_showsTheLineAndTheButton() {
        var archives = 0
        rule.setContent {
            TickdroidTheme {
                DeleteTrackDialog(
                    name = "Exercise", deleting = false, onConfirm = {}, onDismiss = {},
                    onArchiveInstead = { archives++ },
                )
            }
        }
        rule.onNodeWithText("To keep its history, archive it instead.").assertIsDisplayed()
        val archive = rule.onNodeWithText("Archive instead").fetchSemanticsNode().boundsInRoot
        val delete = rule.onNodeWithText("Delete").fetchSemanticsNode().boundsInRoot
        assertTrue(archive.left < delete.left)
        rule.onNodeWithText("Archive instead").performClick()
        assertEquals(1, archives)
    }

    @Test fun trackScreen_archiveButton_onlyWithSupport_aboveDelete_offlineBlocked() {
        var supported by mutableStateOf(false)
        var online by mutableStateOf(true)
        var archives = 0
        var blocked = 0
        rule.setContent {
            TickdroidTheme {
                TrackDetailContent(
                    track = exercise, prefs = TrackPrefs(), online = online, serverEditable = true,
                    pendingPrivate = null,
                    onPrivateChange = {}, onColorKeyChange = {}, onEmojiChange = {},
                    onMoreEmojis = {}, onDelete = {}, onOfflineTap = { blocked++ },
                    archiveSupported = supported, onArchive = { archives++ },
                )
            }
        }
        rule.onAllNodesWithText("Archive track").assertCountEquals(0)

        supported = true
        val archive = rule.onNodeWithText("Archive track").fetchSemanticsNode().boundsInRoot
        val delete = rule.onNodeWithText("Delete track").fetchSemanticsNode().boundsInRoot
        assertTrue("Archive track isn't above Delete track", archive.bottom <= delete.top)
        rule.onNodeWithText("Archive track").performClick()
        assertEquals(1, archives)

        online = false
        rule.onNodeWithText("Archive track").assertIsNotEnabled().performClick()
        assertEquals(1, archives)
        assertEquals(1, blocked)
    }
}
