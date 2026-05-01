package com.martinhammer.tickdroid.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.turbine.test
import com.martinhammer.tickdroid.data.local.TickdroidDatabase
import com.martinhammer.tickdroid.data.local.TrackEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrackRepositoryTest {

    private lateinit var db: TickdroidDatabase
    private lateinit var repo: TrackRepository

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, TickdroidDatabase::class.java).build()
        repo = TrackRepository(db.trackDao())
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun observeTracks_emitsByExplicitSortOrder() = runTest {
        // Insert in deliberately reversed order to exercise ORDER BY sortOrder.
        db.trackDao().insert(track(name = "C", sortOrder = 2))
        db.trackDao().insert(track(name = "A", sortOrder = 0))
        db.trackDao().insert(track(name = "B", sortOrder = 1))

        repo.observeTracks().test {
            val list = awaitItem()
            assertEquals(listOf("A", "B", "C"), list.map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun observeTracks_propagatesPrivateFlag() = runTest {
        db.trackDao().insert(track(name = "Public", private = false))
        db.trackDao().insert(track(name = "Private", private = true, sortOrder = 1))

        repo.observeTracks().test {
            val list = awaitItem()
            assertEquals(2, list.size)
            assertTrue(list.first { it.name == "Private" }.private)
            assertTrue(!list.first { it.name == "Public" }.private)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun observeTracks_emitsUpdates() = runTest {
        repo.observeTracks().test {
            assertTrue(awaitItem().isEmpty())
            db.trackDao().insert(track(name = "X"))
            assertEquals(listOf("X"), awaitItem().map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun track(
        name: String,
        sortOrder: Int = 0,
        private: Boolean = false,
        serverId: Long? = null,
    ) = TrackEntity(
        serverId = serverId,
        name = name,
        type = "boolean",
        sortOrder = sortOrder,
        private = private,
    )
}
