package com.martinhammer.tickdroid.data.repository

import com.martinhammer.tickdroid.data.local.TickEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TickRepositoryMathTest {

    private val date = "2026-10-23"

    private fun clean(value: Int) = TickEntity(localId = 1, serverId = 5, trackLocalId = 2, date = date, value = value)

    /** Taps in turn, as `adjustCounter` writes them (a tap that changes nothing writes nothing). */
    private fun taps(start: TickEntity?, vararg deltas: Int): TickEntity? =
        deltas.fold(start) { row, delta -> counterTap(row, 2, date, delta) ?: row }

    @Test fun `plus one on no row gives 1, pending plus one`() {
        val row = counterTap(null, 2, date, +1)!!
        assertEquals(1, row.value)
        assertEquals(1, row.pendingDelta)
        assertTrue(row.dirty)
        assertFalse(row.deleted)
    }

    @Test fun `plus, plus, minus on a clean 2 gives 3, pending plus one`() {
        val row = taps(clean(2), +1, +1, -1)!!
        assertEquals(3, row.value)
        assertEquals(1, row.pendingDelta)
        assertTrue(row.dirty)
    }

    @Test fun `minus twice on a clean 1 records the change seen, not the taps`() {
        val row = taps(clean(1), -1, -1)!!
        assertEquals(0, row.value)
        assertEquals(-1, row.pendingDelta)
        assertTrue(row.deleted)
        assertTrue(row.dirty)
    }

    @Test fun `minus on 0 changes nothing`() {
        assertNull(counterTap(null, 2, date, -1))
        assertNull(counterTap(clean(0).copy(deleted = true), 2, date, -1))
    }

    @Test fun `taps that cancel out leave the row clean`() {
        val row = taps(clean(2), +1, -1)!!
        assertEquals(2, row.value)
        assertNull(row.pendingDelta)
        assertFalse(row.dirty)
    }

    @Test fun `taps that cancel out at 0 stay dirty for the push to settle`() {
        val row = taps(null, +1, -1)!!
        assertEquals(0, row.value)
        assertEquals(0, row.pendingDelta)
        assertTrue(row.deleted)
        assertTrue(row.dirty)
    }

    @Test fun `a dirty absolute row stays absolute`() {
        val row = counterTap(clean(4).copy(dirty = true), 2, date, +1)!!
        assertEquals(5, row.value)
        assertNull(row.pendingDelta)
        assertTrue(row.dirty)
    }

    @Test fun `taps that cancel out with an increment awaiting its answer stay dirty`() {
        val sent = clean(3).copy(dirty = true, pendingDelta = 1, sentDelta = 1, sentKey = "k")
        val row = taps(sent, +1, -1, -1)!!
        assertEquals(2, row.value)
        assertEquals(0, row.pendingDelta)
        assertTrue(row.dirty)
        assertEquals("k", row.sentKey)
        assertEquals(1, row.sentDelta)
    }
}
