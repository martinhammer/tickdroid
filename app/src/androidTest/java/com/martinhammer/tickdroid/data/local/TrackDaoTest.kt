package com.martinhammer.tickdroid.data.local

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TrackDaoTest {

    private lateinit var db: TickdroidDatabase

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, TickdroidDatabase::class.java).build()
    }

    @After fun tearDown() {
        db.close()
    }

    private suspend fun track(serverId: Long, sortOrder: Int, archivedAt: String? = null): Long =
        db.trackDao().insert(
            TrackEntity(serverId = serverId, name = "T$serverId", type = "boolean", sortOrder = sortOrder, private = false, archivedAt = archivedAt),
        )

    @Test fun observeActive_leavesArchivedOut_observeAllKeepsThem() = runTest {
        track(1, 2)
        track(2, 0, archivedAt = "2026-08-14")
        track(3, 1)

        assertEquals(listOf(3L, 1L), db.trackDao().observeActive().first().map { it.serverId })
        assertEquals(listOf(2L, 3L, 1L), db.trackDao().observeAll().first().map { it.serverId })
    }

    @Test fun deleteDirtyOfArchivedTracks_deletesOnlyDirtyTicksOfArchivedTracks() = runTest {
        val active = track(1, 0)
        val archived = track(2, 1, archivedAt = "2026-08-14")
        val dao = db.tickDao()
        dao.upsert(TickEntity(serverId = null, trackLocalId = active, date = "2026-04-01", value = 1, dirty = true))
        dao.upsert(TickEntity(serverId = 5, trackLocalId = archived, date = "2026-04-01", value = 1))
        dao.upsert(TickEntity(serverId = null, trackLocalId = archived, date = "2026-04-02", value = 1, dirty = true))

        assertEquals(1, dao.deleteDirtyOfArchivedTracks())

        assertNotNull(dao.find(active, "2026-04-01"))
        assertNotNull(dao.find(archived, "2026-04-01"))
        assertNull(dao.find(archived, "2026-04-02"))
    }
}
