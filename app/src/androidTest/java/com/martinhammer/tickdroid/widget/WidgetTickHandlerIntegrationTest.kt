package com.martinhammer.tickdroid.widget

import android.content.Context
import com.martinhammer.tickdroid.data.local.TrackEntity
import com.martinhammer.tickdroid.data.repository.TickRepository
import com.martinhammer.tickdroid.data.repository.TrackRepository
import com.martinhammer.tickdroid.data.sync.SyncScheduler
import com.martinhammer.tickdroid.data.sync.SyncTestRig
import com.martinhammer.tickdroid.data.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** A widget tap goes down exactly the journal's write path: a dirty row and one push. */
class WidgetTickHandlerIntegrationTest {

    private val today = LocalDate.of(2026, 10, 5)
    private lateinit var rig: SyncTestRig
    private lateinit var scheduler: RecordingScheduler
    private lateinit var tickRepository: TickRepository
    private lateinit var handler: WidgetTickHandler

    @Before fun setUp() {
        rig = SyncTestRig()
        rig.assertSignedIn()
        scheduler = RecordingScheduler(rig.context)
        tickRepository = TickRepository(rig.db.tickDao(), rig.db, scheduler)
        val clock = object : Clock {
            override fun today() = today
            override fun nowMillis() = 0L
        }
        handler = WidgetTickHandler(TrackRepository(rig.db.trackDao()), tickRepository, rig.authRepository, clock)
    }

    @After fun tearDown() = rig.shutdown()

    @Test fun tap_leaves_a_dirty_tick_for_today_and_schedules_one_push() = runTest {
        val localId = rig.db.trackDao().insert(
            TrackEntity(serverId = 101L, name = "Exercise", type = "boolean", sortOrder = 0, private = false),
        )
        assertFalse(tickRepository.observeHasDirty().first())

        val outcome = handler.onTap(trackServerId = 101L, shownDay = today, delta = null)

        assertEquals(WidgetTickHandler.Outcome.Written, outcome)
        val row = rig.db.tickDao().find(localId, today.toString())!!
        assertEquals(1, row.value)
        assertTrue(row.dirty)
        assertTrue(tickRepository.observeHasDirty().first())
        assertEquals(1, scheduler.pushNowCalls)
    }

    @Test fun counter_tap_adds_one() = runTest {
        val localId = rig.db.trackDao().insert(
            TrackEntity(serverId = 102L, name = "Coffee", type = "counter", sortOrder = 0, private = false),
        )
        handler.onTap(102L, today, 1)
        handler.onTap(102L, today, 1)
        assertEquals(2, rig.db.tickDao().find(localId, today.toString())!!.value)
        assertEquals(2, scheduler.pushNowCalls)
    }

    private class RecordingScheduler(context: Context) : SyncScheduler(context) {
        var pushNowCalls = 0
        override fun schedulePushNow() { pushNowCalls++ }
        override fun schedulePeriodicPush() = Unit
        override fun cancelAll() = Unit
    }
}
