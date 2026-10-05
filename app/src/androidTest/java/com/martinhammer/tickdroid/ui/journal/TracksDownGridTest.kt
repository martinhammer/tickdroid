package com.martinhammer.tickdroid.ui.journal

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.martinhammer.tickdroid.data.prefs.EditableDays
import com.martinhammer.tickdroid.data.prefs.GridDensity
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.ui.theme.TickdroidTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

class TracksDownGridTest {

    @get:Rule val rule = createComposeRule()

    private val today = LocalDate.parse("2026-10-02")
    private val booleanTrack = Track(localId = 1L, serverId = 10L, name = "Made someone smile today", type = TrackType.BOOLEAN, sortOrder = 0, private = false)
    private val counterTrack = Track(localId = 2L, serverId = 11L, name = "Glasses of water with lunch", type = TrackType.COUNTER, sortOrder = 1, private = false)
    private val defaultWindow = DateWindow(oldestVisible = today.minusDays(29), today = today)

    private fun state(
        editableDays: EditableDays,
        tracks: List<Track>,
        window: DateWindow = defaultWindow,
        density: GridDensity = GridDensity.MEDIUM,
    ) = JournalUiState(
        tracks = tracks,
        window = window,
        density = density,
        loaded = true,
        editableDays = editableDays,
    )

    private fun cellTag(track: Track, day: LocalDate) = "cell-${track.localId}-${day.toEpochDay()}"

    /** Renders the grid; returns how many day columns are visible at the width the grid gets. */
    private fun render(
        editableDays: EditableDays = EditableDays.ALL_DAYS,
        tracks: List<Track> = listOf(booleanTrack, counterTrack),
        onToggle: (Long, LocalDate) -> Unit = { _, _ -> },
        density: GridDensity = GridDensity.MEDIUM,
    ): Int {
        var daysVisible = 0
        rule.setContent {
            TickdroidTheme {
                // Same width the grid sizes itself from (no horizontal insets in portrait).
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    daysVisible = tracksDownMetrics(maxWidth.value, density.tracksDownDays).daysVisible
                    TracksDownGrid(
                        state = state(editableDays, tracks, density = density),
                        onLoadOlder = {},
                        onToggleBoolean = onToggle,
                        onAdjustCounter = { _, _, _ -> },
                    )
                }
            }
        }
        rule.waitForIdle()
        return daysVisible
    }

    // All in dp, from *unclipped* bounds: off-screen cells composed beyond the viewport would
    // otherwise report bounds clipped to the pager's edge.
    private fun pagerLeftDp(): Float =
        rule.onNodeWithTag("tracksDownGrid").getUnclippedBoundsInRoot().left.value

    private fun slotDp(): Float =
        rule.onNodeWithTag(cellTag(booleanTrack, today)).getUnclippedBoundsInRoot().width.value

    /**
     * Left edges (dp) of the placed cells of [track]'s row, keyed by column index. Cells the
     * pager has composed ahead but not placed report NaN and are skipped.
     */
    private fun cellLefts(track: Track, columns: Int): Map<Int, Float> =
        (0 until columns).mapNotNull { column ->
            val nodes = rule.onAllNodesWithTag(cellTag(track, dayForColumn(column, today)))
            if (nodes.fetchSemanticsNodes().isEmpty()) return@mapNotNull null
            val left = nodes[0].getUnclippedBoundsInRoot().left.value
            if (left.isNaN()) null else column to left
        }.toMap()

    /** Column whose cell starts exactly at the pager's left edge, i.e. the leftmost visible one. */
    private fun leftmostColumn(columns: Int): Int {
        val left = pagerLeftDp()
        return cellLefts(booleanTrack, columns).entries.single { abs(it.value - left) < 0.5f }.key
    }

    private fun rangeLabelText(): String =
        rule.onNodeWithTag("rangeLabel").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString { it.text }

    @Test fun fullTrackNameIsDisplayed() {
        render()
        rule.onNodeWithText("Made someone smile", substring = true).assertIsDisplayed()
    }

    @Test fun wrappedName_makesAllRowsTheSameTallerHeight() {
        val shortTrack = Track(localId = 3L, serverId = 12L, name = "Tea", type = TrackType.BOOLEAN, sortOrder = 2, private = false)
        render(tracks = listOf(shortTrack, booleanTrack))

        val shortLabel = rule.onNodeWithTag("label-${shortTrack.localId}").getUnclippedBoundsInRoot()
        val longLabel = rule.onNodeWithTag("label-${booleanTrack.localId}").getUnclippedBoundsInRoot()
        val shortCell = rule.onNodeWithTag(cellTag(shortTrack, today)).getUnclippedBoundsInRoot()
        val longCell = rule.onNodeWithTag(cellTag(booleanTrack, today)).getUnclippedBoundsInRoot()

        assertTrue("long name should wrap to a taller label", longLabel.height > shortLabel.height)
        // Every row takes the tallest label's height, so the grid stays even.
        assertEquals(longLabel.height.value, longCell.height.value, 0.5f)
        assertEquals(longCell.height.value, shortCell.height.value, 0.5f)
        // The short label is centred in its (taller) row, like the cells.
        val shortLabelCentre = (shortLabel.top + shortLabel.bottom).value / 2
        val shortCellCentre = (shortCell.top + shortCell.bottom).value / 2
        assertEquals(shortCellCentre, shortLabelCentre, 0.5f)
    }

    @Test fun highDensity_keepsNamesOnOneLine() {
        val shortTrack = Track(localId = 3L, serverId = 12L, name = "Tea", type = TrackType.BOOLEAN, sortOrder = 2, private = false)
        // The same long name that wraps at Medium (see above) stays on one line at High.
        render(tracks = listOf(shortTrack, booleanTrack), density = GridDensity.HIGH)

        val shortLabel = rule.onNodeWithTag("label-${shortTrack.localId}").getUnclippedBoundsInRoot()
        val longLabel = rule.onNodeWithTag("label-${booleanTrack.localId}").getUnclippedBoundsInRoot()
        val longCell = rule.onNodeWithTag(cellTag(booleanTrack, today)).getUnclippedBoundsInRoot()

        assertEquals(shortLabel.height.value, longLabel.height.value, 0.5f)
        assertEquals(longLabel.height.value, longCell.height.value, 0.5f)
        // Ellipsized on screen, but the full name is still exposed to semantics.
        rule.onNodeWithText(booleanTrack.name).assertExists()
    }

    @Test fun overlongSingleWord_staysOnOneLine() {
        val shortTrack = Track(localId = 3L, serverId = 12L, name = "Tea", type = TrackType.BOOLEAN, sortOrder = 2, private = false)
        val longWord = "Supercalifragilisticexpialidocious"
        val longWordTrack = Track(localId = 4L, serverId = 13L, name = longWord, type = TrackType.BOOLEAN, sortOrder = 3, private = false)
        render(tracks = listOf(shortTrack, longWordTrack))

        val shortLabel = rule.onNodeWithTag("label-${shortTrack.localId}").getUnclippedBoundsInRoot()
        val longLabel = rule.onNodeWithTag("label-${longWordTrack.localId}").getUnclippedBoundsInRoot()

        // Ellipsized on one line, not wrapped mid-word onto a second.
        assertEquals(shortLabel.height.value, longLabel.height.value, 0.5f)
        // The full name is still exposed to semantics (TalkBack), not the ellipsized text.
        rule.onNodeWithText(longWord).assertExists()
    }

    @Test fun tapTodayCell_togglesToday() {
        val calls = mutableListOf<Pair<Long, LocalDate>>()
        render(onToggle = { id, d -> calls += id to d })

        rule.onNodeWithTag(cellTag(booleanTrack, today)).performClick()

        assertEquals(listOf(booleanTrack.localId to today), calls)
    }

    @Test fun todayColumn_isLabelledToday() {
        render()
        rule.onNodeWithText("Today").assertIsDisplayed()
    }

    @Test fun jumpToTodayChip_hiddenWhileTodayIsInView() {
        render()
        rule.onNodeWithTag("jumpToToday").assertDoesNotExist()
    }

    @Test fun slowDragOfOneColumn_movesBackOneDay_andChipReturnsToToday() {
        val calls = mutableListOf<Pair<Long, LocalDate>>()
        render(onToggle = { id, d -> calls += id to d })
        val columns = columnCount(defaultWindow)
        val rangeAtToday = rangeLabelText()
        val slotPx = slotDp() * rule.density.density

        // Today is leftmost, so older days are to the right: dragging left goes back in time.
        rule.onNodeWithTag("tracksDownGrid").performTouchInput {
            swipe(start = center, end = center - Offset(slotPx, 0f), durationMillis = 1_000)
        }
        rule.waitForIdle()

        assertEquals(1, leftmostColumn(columns))
        val yesterday = today.minusDays(1)
        rule.onNodeWithTag(cellTag(booleanTrack, yesterday)).performClick()
        assertEquals(listOf(booleanTrack.localId to yesterday), calls)
        assertNotEquals(rangeAtToday, rangeLabelText())

        rule.onNodeWithTag("jumpToToday").assertIsDisplayed().performClick()
        rule.waitForIdle()

        assertEquals(0, leftmostColumn(columns))
        assertEquals(rangeAtToday, rangeLabelText())
        rule.onNodeWithTag("jumpToToday").assertDoesNotExist()
    }

    @Test fun fling_snapsToWholeColumns() {
        render()
        val columns = columnCount(defaultWindow)

        val slot = slotDp()
        rule.onNodeWithTag("tracksDownGrid").performTouchInput { swipeLeft() }
        rule.waitForIdle()

        // Moved, and every composed column sits on the slot grid measured from the pager's
        // left edge: none is left partly scrolled.
        assertTrue(leftmostColumn(columns) > 0)
        val left = pagerLeftDp()
        cellLefts(booleanTrack, columns).forEach { (column, cellLeft) ->
            val slots = (cellLeft - left) / slot
            assertEquals("column $column off the slot grid", slots.roundToInt().toFloat(), slots, 0.02f)
        }
    }

    @Test fun scrollingBack_loadsOlderOnceNearTheEnd() {
        // Window grows by 30 days per onLoadOlder, like JournalViewModel.loadOlder().
        var window by mutableStateOf(defaultWindow)
        var loadOlderCalls = 0
        var daysVisible = 0
        rule.setContent {
            TickdroidTheme {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    daysVisible = tracksDownMetrics(maxWidth.value, GridDensity.MEDIUM.tracksDownDays).daysVisible
                    TracksDownGrid(
                        state = state(EditableDays.ALL_DAYS, listOf(booleanTrack), window),
                        onLoadOlder = {
                            loadOlderCalls++
                            window = window.copy(oldestVisible = window.oldestVisible.minusDays(30))
                        },
                        onToggleBoolean = { _, _ -> },
                        onAdjustCounter = { _, _, _ -> },
                    )
                }
            }
        }
        rule.waitForIdle()
        val initialColumns = columnCount(window)
        // Nothing loads while today's end is in view.
        assertEquals(0, loadOlderCalls)

        // Fling back until the leftmost column is within 7 days of the end of what's loaded.
        repeat(10) {
            if (loadOlderCalls > 0) return@repeat
            rule.onNodeWithTag("tracksDownGrid").performTouchInput { swipeLeft() }
            rule.waitForIdle()
            val first = leftmostColumn(columnCount(window))
            if (loadOlderCalls == 0) {
                assertTrue("should have loaded at column $first", first + daysVisible < initialColumns - 7)
            }
        }

        // Loaded exactly once: the window grew, so the leftmost column is no longer near the end.
        assertEquals(1, loadOlderCalls)
        assertTrue(columnCount(window) > initialColumns)
        rule.waitForIdle()
        assertEquals(1, loadOlderCalls)
    }

    @Test fun lockedDay_doesNotInvokeCallback() {
        val calls = mutableListOf<Pair<Long, LocalDate>>()
        render(editableDays = EditableDays.ACTIVE_DAY, onToggle = { id, d -> calls += id to d })

        rule.onNodeWithTag(cellTag(booleanTrack, today.minusDays(1))).performClick()

        assertTrue(calls.isEmpty())
    }
}
