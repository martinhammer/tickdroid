package com.martinhammer.tickdroid.widget

import com.martinhammer.tickdroid.data.repository.TickKey
import com.martinhammer.tickdroid.domain.Tick
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WidgetModelsTest {

    private val today = LocalDate.of(2026, 10, 5)

    private fun track(localId: Long, serverId: Long?, name: String, type: TrackType = TrackType.BOOLEAN, private: Boolean = false) =
        Track(localId = localId, serverId = serverId, name = name, type = type, sortOrder = localId.toInt(), private = private)

    private val exercise = track(1, 101, "Exercise")
    private val coffee = track(2, 102, "Coffee", TrackType.COUNTER)
    private val diary = track(3, 103, "Diary", private = true)
    private val unsynced = track(4, null, "Unsynced")
    private val tracks = listOf(exercise, coffee, diary, unsynced)

    private val ticks = mapOf(
        TickKey(1, today) to Tick(1, today, 1),
        TickKey(2, today) to Tick(2, today, 3),
    )

    private fun list(config: WidgetConfig.TrackSelection? = null, signedIn: Boolean = true) =
        buildListModel(signedIn, today, tracks, emptyMap(), ticks, config)

    private fun button(config: WidgetConfig.Button?, signedIn: Boolean = true, trackList: List<Track> = tracks) =
        buildButtonModel(signedIn, today, trackList, emptyMap(), ticks, config)

    private fun ListModel.ids() = (this as ListModel.Content).rows.map { it.serverId }

    // List / row

    @Test fun `list shows every non-private track by default, in journal order`() {
        assertEquals(listOf(101L, 102L), list().ids())
        assertEquals(listOf(101L, 102L), list(WidgetConfig.TrackSelection()).ids())
    }

    @Test fun `a false entry hides a track`() {
        assertEquals(listOf(102L), list(WidgetConfig.TrackSelection(shown = mapOf(101L to false))).ids())
    }

    @Test fun `a private track is never shown, even with a true entry`() {
        assertEquals(listOf(101L, 102L), list(WidgetConfig.TrackSelection(shown = mapOf(103L to true))).ids())
    }

    @Test fun `a track with no server id is dropped`() {
        val names = (list() as ListModel.Content).rows.map { it.name }
        assertFalse("Unsynced" in names)
    }

    @Test fun `signed out gives SignedOut`() {
        assertEquals(ListModel.SignedOut, list(signedIn = false))
    }

    @Test fun `list rows carry today's values, emoji and colour`() {
        val model = buildListModel(
            true, today, tracks, mapOf(102L to TrackPrefs(colorKey = "red", emoji = "☕")), ticks,
            WidgetConfig.TrackSelection(labels = TrackLabels.Short),
        ) as ListModel.Content
        assertEquals(TrackLabels.Short, model.labels)
        assertEquals(today, model.day)
        val coffeeRow = model.rows.single { it.serverId == 102L }
        assertEquals(3, coffeeRow.value)
        assertEquals("☕", coffeeRow.emoji)
        assertEquals("red", coffeeRow.colorKey)
    }

    // WidgetRow.done

    @Test fun `done is true for a ticked yes-no and a counter above 0`() {
        assertTrue(row(TrackType.BOOLEAN, 1).done)
        assertTrue(row(TrackType.COUNTER, 2).done)
    }

    @Test fun `done is false for zero and missing ticks`() {
        assertFalse(row(TrackType.COUNTER, 0).done)
        assertFalse(row(TrackType.BOOLEAN, 0).done)
        // No tick rows at all for this day.
        val noTicks = buildListModel(true, today.minusDays(1), tracks, emptyMap(), ticks, null) as ListModel.Content
        assertTrue(noTicks.rows.none { it.done })
        assertTrue(noTicks.rows.all { it.value == 0 })
    }

    private fun row(type: TrackType, value: Int) =
        WidgetRow(serverId = 1, name = "T", type = type, value = value, emoji = null, colorKey = null)

    // Button

    @Test fun `button with no config is ChooseTrack`() {
        assertEquals(ButtonModel.ChooseTrack, button(null))
    }

    @Test fun `button whose track is gone is ChooseTrack`() {
        assertEquals(ButtonModel.ChooseTrack, button(WidgetConfig.Button(999)))
    }

    @Test fun `button whose track is private is ChooseTrack`() {
        assertEquals(ButtonModel.ChooseTrack, button(WidgetConfig.Button(103)))
    }

    @Test fun `button content carries the counter value`() {
        val model = button(WidgetConfig.Button(102)) as ButtonModel.Content
        assertEquals(3, model.row.value)
        assertEquals(today, model.day)
    }

    @Test fun `signed out wins over everything`() {
        assertEquals(ButtonModel.SignedOut, button(WidgetConfig.Button(102), signedIn = false))
        assertEquals(ButtonModel.SignedOut, button(null, signedIn = false))
    }

    // Sync slot

    @Test fun `syncing outranks the offline symbol`() {
        assertEquals(SyncSlot.Syncing, SyncBadge(syncing = true, offlinePending = true).slot)
        assertEquals(SyncSlot.Offline, SyncBadge(syncing = false, offlinePending = true).slot)
        assertEquals(SyncSlot.Refresh, SyncBadge().slot)
    }

    // Layout maths

    @Test fun `rowCapacity is 0 below the controls' width`() {
        assertEquals(0, rowCapacity(0f))
        assertEquals(0, rowCapacity(80f))
        assertEquals(0, rowCapacity(119f))
    }

    @Test fun `rowCapacity adds one column per extra 40dp`() {
        assertEquals(1, rowCapacity(120f))
        assertEquals(2, rowCapacity(160f))
        assertEquals(2, rowCapacity(199f))
        assertEquals(3, rowCapacity(200f))
    }

    @Test fun `rowMetrics shrinks below a 64dp row`() {
        assertEquals(36, rowMetrics(64f).cellDp)
        assertEquals(32, rowMetrics(56f).cellDp)
    }

    @Test fun `headerParts sheds the title, then the date`() {
        assertEquals(HeaderParts(showTitle = true, date = HeaderDate.Long, iconTargetDp = 40), headerParts(300f))
        assertEquals(HeaderParts(showTitle = true, date = HeaderDate.Long, iconTargetDp = 40), headerParts(HeaderFullMinWidthDp))
        assertEquals(HeaderParts(showTitle = false, date = HeaderDate.Short, iconTargetDp = 32), headerParts(200f))
        assertEquals(HeaderParts(showTitle = false, date = HeaderDate.Short, iconTargetDp = 32), headerParts(HeaderDateMinWidthDp))
        assertEquals(HeaderParts(showTitle = false, date = HeaderDate.None, iconTargetDp = 32), headerParts(110f))
    }

    // Text

    @Test fun `cell descriptions`() {
        assertEquals("Duolingo, done", cellDescription(row(TrackType.BOOLEAN, 1).copy(name = "Duolingo")))
        assertEquals("Duolingo, not done", cellDescription(row(TrackType.BOOLEAN, 0).copy(name = "Duolingo")))
        assertEquals("Coffee, 3", cellDescription(row(TrackType.COUNTER, 3).copy(name = "Coffee")))
        assertEquals("Coffee, 2, tap to add one", cellDescription(row(TrackType.COUNTER, 2).copy(name = "Coffee"), tapHint = true))
        assertEquals("Exercise, done", cellDescription(row(TrackType.BOOLEAN, 1).copy(name = "Exercise"), tapHint = true))
    }

    @Test fun `abbreviation is the first two letters, upper case`() {
        assertEquals("FL", abbreviation("Floss"))
        assertEquals("X", abbreviation("x"))
    }
}
