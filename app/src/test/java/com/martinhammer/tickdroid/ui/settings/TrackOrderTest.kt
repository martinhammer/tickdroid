package com.martinhammer.tickdroid.ui.settings

import com.martinhammer.tickdroid.data.sync.mergeOrder
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackOrderTest {

    private fun row(localId: Long) = TrackRowState(
        Track(localId, serverId = localId * 10, name = "T$localId", type = TrackType.YesNo, sortOrder = localId.toInt(), private = false),
        TrackPrefs(),
    )

    private fun List<TrackRowState>.ids() = map { it.track.localId }

    @Test fun `moved down, up and to the ends`() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), list.moved(0, 2))
        assertEquals(listOf("a", "d", "b", "c"), list.moved(3, 1))
        assertEquals(listOf("b", "c", "d", "a"), list.moved(0, 3))
        assertEquals(listOf("d", "a", "b", "c"), list.moved(3, 0))
        assertEquals(list, list.moved(1, 1))
        assertEquals(list, list.moved(1, 9))
    }

    @Test fun `applyOrder follows the pending order`() {
        val rows = listOf(row(1), row(2), row(3))
        assertEquals(listOf(3L, 1L, 2L), applyOrder(rows, listOf(3, 1, 2)).ids())
        assertEquals(listOf(1L, 2L, 3L), applyOrder(rows, null).ids())
    }

    @Test fun `applyOrder appends rows the pending list doesn't know`() {
        val rows = listOf(row(1), row(2), row(3), row(4))
        assertEquals(listOf(2L, 1L, 3L, 4L), applyOrder(rows, listOf(2, 1)).ids())
    }

    @Test fun `applyOrder drops pending ids that are gone`() {
        val rows = listOf(row(1), row(3))
        assertEquals(listOf(3L, 1L), applyOrder(rows, listOf(3, 2, 1)).ids())
    }

    @Test fun `mergeOrder appends server-only ids in server order`() {
        assertEquals(listOf(3L, 1L, 2L, 5L, 4L), mergeOrder(listOf(3, 1, 2), listOf(1, 2, 5, 3, 4)))
    }

    @Test fun `mergeOrder drops ids the server doesn't have`() {
        assertEquals(listOf(3L, 1L), mergeOrder(listOf(3, 9, 1), listOf(1, 3)))
    }

    @Test fun `move up and down at the ends`() {
        assertFalse(canMoveUp(0))
        assertTrue(canMoveUp(1))
        assertTrue(canMoveDown(0, 3))
        assertFalse(canMoveDown(2, 3))
        assertFalse(canMoveDown(0, 1))
    }
}
