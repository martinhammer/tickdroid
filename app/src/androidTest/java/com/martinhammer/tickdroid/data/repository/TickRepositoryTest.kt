package com.martinhammer.tickdroid.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.turbine.test
import com.martinhammer.tickdroid.data.local.TickEntity
import com.martinhammer.tickdroid.data.local.TickdroidDatabase
import com.martinhammer.tickdroid.data.local.TrackEntity
import com.martinhammer.tickdroid.data.sync.SyncScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class TickRepositoryTest {

    private lateinit var db: TickdroidDatabase
    private lateinit var scheduler: RecordingScheduler
    private lateinit var repo: TickRepository
    private val date = LocalDate.parse("2026-04-30")

    @Before fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(android.util.Log.DEBUG).build(),
        )
        db = Room.inMemoryDatabaseBuilder(context, TickdroidDatabase::class.java).build()
        scheduler = RecordingScheduler(context)
        repo = TickRepository(db.tickDao(), db, scheduler)
    }

    private class RecordingScheduler(context: Context) : SyncScheduler(context) {
        var pushNowCalls = 0
        override fun schedulePushNow() { pushNowCalls++ }
        override fun schedulePeriodicPush() {}
        override fun cancelAll() {}
    }

    @After fun tearDown() {
        db.close()
    }

    private suspend fun seedTrack(serverId: Long? = 1L, type: String = "boolean"): Long =
        db.trackDao().insert(
            TrackEntity(
                serverId = serverId,
                name = "Track",
                type = type,
                sortOrder = 0,
                private = false,
            ),
        )

    @Test fun toggleBoolean_createsDirtyOn_whenAbsent() = runTest {
        val trackId = seedTrack()

        repo.toggleBoolean(trackId, date)

        val row = db.tickDao().find(trackId, date.toString())!!
        assertEquals(1, row.value)
        assertTrue(row.dirty)
        assertEquals(false, row.deleted)
        assertEquals(1, scheduler.pushNowCalls)
    }

    @Test fun toggleBoolean_synced_marksForRemoval() = runTest {
        val trackId = seedTrack()
        // Synced "on" row (serverId set, not dirty).
        db.tickDao().upsert(
            TickEntity(serverId = 99, trackLocalId = trackId, date = date.toString(), value = 1),
        )

        repo.toggleBoolean(trackId, date)

        val row = db.tickDao().find(trackId, date.toString())!!
        assertEquals(0, row.value)
        assertTrue(row.deleted)
        assertTrue(row.dirty)
    }

    @Test fun toggleBoolean_pushedButNotYetPulled_marksForRemoval() = runTest {
        // A push never fills in serverId (only a pull does), so a null one doesn't mean "never
        // reached the server". Deleting the row here used to leave the server's tick in place.
        val trackId = seedTrack()
        db.tickDao().upsert(TickEntity(serverId = null, trackLocalId = trackId, date = date.toString(), value = 1))

        repo.toggleBoolean(trackId, date)

        val row = db.tickDao().find(trackId, date.toString())!!
        assertEquals(0, row.value)
        assertTrue(row.deleted)
        assertTrue(row.dirty)
    }

    @Test fun toggleBoolean_tickThenUntickBeforeThePush_leavesARemovalToPush() = runTest {
        val trackId = seedTrack()
        repo.toggleBoolean(trackId, date)

        repo.toggleBoolean(trackId, date)

        val row = db.tickDao().find(trackId, date.toString())!!
        assertTrue(row.deleted)
        assertTrue(row.dirty)
        assertEquals(2, scheduler.pushNowCalls)
    }

    @Test fun toggleBoolean_pendingRemoval_undoes() = runTest {
        val trackId = seedTrack()
        db.tickDao().upsert(
            TickEntity(
                serverId = 99,
                trackLocalId = trackId,
                date = date.toString(),
                value = 0,
                dirty = true,
                deleted = true,
            ),
        )

        repo.toggleBoolean(trackId, date)

        val row = db.tickDao().find(trackId, date.toString())!!
        assertEquals(1, row.value)
        assertEquals(false, row.deleted)
        assertTrue(row.dirty)
    }

    @Test fun adjustCounter_increments() = runTest {
        val trackId = seedTrack(type = "counter")

        repo.adjustCounter(trackId, date, +1)
        repo.adjustCounter(trackId, date, +1)
        repo.adjustCounter(trackId, date, +1)

        val row = db.tickDao().find(trackId, date.toString())!!
        assertEquals(3, row.value)
        assertTrue(row.dirty)
    }

    @Test fun adjustCounter_clampsAtZero() = runTest {
        val trackId = seedTrack(type = "counter")

        repo.adjustCounter(trackId, date, -1) // no-op
        assertNull(db.tickDao().find(trackId, date.toString()))
        assertEquals(0, scheduler.pushNowCalls)
    }

    @Test fun adjustCounter_decrementToZero_synced_marksDeleted() = runTest {
        val trackId = seedTrack(type = "counter")
        db.tickDao().upsert(
            TickEntity(serverId = 7, trackLocalId = trackId, date = date.toString(), value = 1),
        )

        repo.adjustCounter(trackId, date, -1)

        val row = db.tickDao().find(trackId, date.toString())!!
        assertEquals(0, row.value)
        assertTrue(row.deleted)
        assertTrue(row.dirty)
    }

    @Test fun adjustCounter_decrementToZero_pushedButNotYetPulled_marksDeleted() = runTest {
        val trackId = seedTrack(type = "counter")
        db.tickDao().upsert(TickEntity(serverId = null, trackLocalId = trackId, date = date.toString(), value = 1))

        repo.adjustCounter(trackId, date, -1)

        val row = db.tickDao().find(trackId, date.toString())!!
        assertEquals(0, row.value)
        assertTrue(row.deleted)
        assertTrue(row.dirty)
    }

    @Test fun adjustCounter_recordsThePendingChange() = runTest {
        val trackId = seedTrack(type = "counter")
        db.tickDao().upsert(TickEntity(serverId = 7, trackLocalId = trackId, date = date.toString(), value = 2))

        repo.adjustCounter(trackId, date, +1)
        repo.adjustCounter(trackId, date, +1)
        repo.adjustCounter(trackId, date, -1)

        val row = db.tickDao().find(trackId, date.toString())!!
        assertEquals(3, row.value)
        assertEquals(1, row.pendingDelta)
        assertTrue(row.dirty)
    }

    @Test fun adjustCounter_tapsThatCancelOut_leaveTheRowClean_andScheduleOnce() = runTest {
        val trackId = seedTrack(type = "counter")
        db.tickDao().upsert(TickEntity(serverId = 7, trackLocalId = trackId, date = date.toString(), value = 2))

        repo.adjustCounter(trackId, date, +1)
        repo.adjustCounter(trackId, date, -1)

        val row = db.tickDao().find(trackId, date.toString())!!
        assertEquals(2, row.value)
        assertNull(row.pendingDelta)
        assertEquals(false, row.dirty)
        assertEquals(2, scheduler.pushNowCalls)
    }

    @Test fun adjustCounter_rowQueuedBeforeTheUpdate_staysAbsolute() = runTest {
        val trackId = seedTrack(type = "counter")
        db.tickDao().upsert(TickEntity(serverId = 7, trackLocalId = trackId, date = date.toString(), value = 4, dirty = true))

        repo.adjustCounter(trackId, date, +1)

        val row = db.tickDao().find(trackId, date.toString())!!
        assertEquals(5, row.value)
        assertNull(row.pendingDelta)
        assertTrue(row.dirty)
    }

    @Test fun observeRange_emitsCurrentAndUpdates() = runTest {
        val trackId = seedTrack(type = "counter")

        repo.observeRange(date, date).test {
            assertTrue(awaitItem().isEmpty())
            repo.adjustCounter(trackId, date, +2)
            val updated = awaitItem()
            assertEquals(1, updated.size)
            assertEquals(2, updated.values.first().value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun observeHasUnsent_flipsAcrossWrites() = runTest {
        val trackId = seedTrack(type = "counter")

        repo.observeHasUnsent().test {
            assertEquals(false, awaitItem())
            repo.adjustCounter(trackId, date, +1)
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun toggleBoolean_outsideRange_isHidden_butFindable() = runTest {
        val trackId = seedTrack()
        repo.toggleBoolean(trackId, date)

        val outOfRange = repo
        // sanity: range observation excludes the row
        val before = LocalDate.parse("2026-04-29")
        val after = LocalDate.parse("2026-04-29")
        outOfRange.observeRange(before, after).test {
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        // but the row exists
        assertNotNull(db.tickDao().find(trackId, date.toString()))
    }
}
