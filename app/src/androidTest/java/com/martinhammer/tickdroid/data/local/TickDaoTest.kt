package com.martinhammer.tickdroid.data.local

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TickDaoTest {

    private lateinit var db: TickdroidDatabase
    private val date = "2026-04-30"

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, TickdroidDatabase::class.java).build()
    }

    @After fun tearDown() {
        db.close()
    }

    private suspend fun seedTrack(): Long = db.trackDao().insert(
        TrackEntity(serverId = 1L, name = "T", type = "counter", sortOrder = 0, private = false),
    )

    @Test fun observeRange_excludesDeletedRows() = runTest {
        val trackId = seedTrack()
        db.tickDao().upsert(TickEntity(serverId = null, trackLocalId = trackId, date = date, value = 1))
        db.tickDao().upsert(
            TickEntity(serverId = null, trackLocalId = trackId, date = "2026-04-29", value = 1, deleted = true),
        )

        db.tickDao().observeRange("2026-04-01", "2026-04-30").test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(date, list.first().date)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun getDirty_includesDeletedRows() = runTest {
        // Pending-removal rows must still be visible to the push worker.
        val trackId = seedTrack()
        db.tickDao().upsert(
            TickEntity(serverId = 1, trackLocalId = trackId, date = date, value = 0, dirty = true, deleted = true),
        )

        val dirty = db.tickDao().getDirty()
        assertEquals(1, dirty.size)
        assertTrue(dirty.first().deleted)
    }

    @Test fun observeHasDirty_flipsAcrossWriteAndClear() = runTest {
        val trackId = seedTrack()

        db.tickDao().observeHasDirty().test {
            assertEquals(false, awaitItem())
            db.tickDao().upsert(
                TickEntity(serverId = null, trackLocalId = trackId, date = date, value = 1, dirty = true),
            )
            assertEquals(true, awaitItem())
            // clear dirty
            val row = db.tickDao().find(trackId, date)!!
            db.tickDao().update(row.copy(dirty = false))
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun uniqueIndex_onTrackAndDate_replacesOnConflict() = runTest {
        val trackId = seedTrack()
        db.tickDao().upsert(TickEntity(serverId = null, trackLocalId = trackId, date = date, value = 1))
        db.tickDao().upsert(TickEntity(serverId = null, trackLocalId = trackId, date = date, value = 5))

        val rows = db.tickDao().getRange(date, date)
        assertEquals(1, rows.size)
        assertEquals(5, rows.first().value)
    }
}
