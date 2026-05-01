package com.martinhammer.tickdroid.data.sync

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.martinhammer.tickdroid.data.local.TickEntity
import com.martinhammer.tickdroid.data.local.TrackEntity
import com.martinhammer.tickdroid.data.time.Clock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class PushWorkerTest {

    private lateinit var rig: SyncTestRig
    private val today = LocalDate.parse("2026-04-30")
    private val fixedClock = object : Clock {
        override fun today(): LocalDate = today
        override fun nowMillis(): Long = 0L
    }

    @Before fun setUp() {
        rig = SyncTestRig().also { it.assertSignedIn() }
    }

    @After fun tearDown() {
        rig.shutdown()
    }

    private fun buildWorker(): PushWorker {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return TestListenableWorkerBuilder<PushWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: android.content.Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = PushWorker(
                    appContext = appContext,
                    params = workerParameters,
                    api = rig.api,
                    database = rig.db,
                    tickDao = rig.db.tickDao(),
                    trackDao = rig.db.trackDao(),
                    authRepository = rig.authRepository,
                    syncManager = rig.syncManager,
                    clock = fixedClock,
                )
            })
            .build()
    }

    private suspend fun seedTrack(serverId: Long, type: String): Long =
        rig.db.trackDao().insert(
            TrackEntity(serverId = serverId, name = "T$serverId", type = type, sortOrder = 0, private = false),
        )

    private fun enqueueDailyPullAfterPush() {
        // PushWorker.doWork runs a follow-up pull after a successful drain.
        rig.enqueueAsset("ocs/empty.json") // tracks
        rig.enqueueAsset("ocs/empty.json") // ticks
    }

    @Test fun counter_postsSetAndClearsDirty() = runTest {
        val trackId = seedTrack(serverId = 11L, type = "counter")
        rig.db.tickDao().upsert(
            TickEntity(
                serverId = null, trackLocalId = trackId, date = today.toString(),
                value = 3, dirty = true,
            ),
        )
        // /set → returns the value the server settled on.
        rig.enqueueJson("""{"ocs":{"meta":{"status":"ok","statuscode":200},"data":{"value":3}}}""")
        enqueueDailyPullAfterPush()

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val recorded = rig.server.takeRequest()
        assertTrue(recorded.path!!.endsWith("/ticks/set"))
        val body = recorded.body.readUtf8()
        assertTrue("body=$body", body.contains("trackId=11") && body.contains("value=3"))

        // After push, no dirty rows remain. (The post-drain pull may sweep the clean row
        // since our test track has serverId on the server but our local TickEntity had
        // serverId=null; that's fine for this test — we care that the push cleared dirty.)
        assertTrue(rig.db.tickDao().getDirty().isEmpty())
    }

    @Test fun counter_zeroValue_deletesLocalRow() = runTest {
        val trackId = seedTrack(serverId = 11L, type = "counter")
        rig.db.tickDao().upsert(
            TickEntity(
                serverId = 50L, trackLocalId = trackId, date = today.toString(),
                value = 0, dirty = true, deleted = true,
            ),
        )
        // server confirms zero
        rig.enqueueJson("""{"ocs":{"meta":{"status":"ok","statuscode":200},"data":{"value":0}}}""")
        enqueueDailyPullAfterPush()

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNull(rig.db.tickDao().find(trackId, today.toString()))
    }

    @Test fun boolean_skipsToggleWhenServerAlreadyMatches() = runTest {
        val trackId = seedTrack(serverId = 10L, type = "boolean")
        rig.db.tickDao().upsert(
            TickEntity(
                serverId = null, trackLocalId = trackId, date = today.toString(),
                value = 1, dirty = true,
            ),
        )
        // Replay-safety probe: same-day GET. Server says it's already on.
        rig.enqueueJson(
            """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":[
              {"id":42,"trackId":10,"date":"$today","value":1}]}}""".trimIndent(),
        )
        enqueueDailyPullAfterPush()

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)

        // Drain phase: only the GET should have been issued (no /toggle).
        val first = rig.server.takeRequest()
        assertTrue("expected getTicks, got ${first.path}", first.path!!.contains("/ticks?"))
        // Subsequent requests come from the post-drain pull (tracks + ticks). None should be /toggle.
        val second = rig.server.takeRequest()
        val third = rig.server.takeRequest()
        assertTrue(
            "no /toggle should be issued",
            !first.path!!.contains("toggle") &&
                !second.path!!.contains("toggle") &&
                !third.path!!.contains("toggle"),
        )

        assertTrue(rig.db.tickDao().getDirty().isEmpty())
    }

    @Test fun boolean_togglesWhenServerDiffers() = runTest {
        val trackId = seedTrack(serverId = 10L, type = "boolean")
        rig.db.tickDao().upsert(
            TickEntity(
                serverId = null, trackLocalId = trackId, date = today.toString(),
                value = 1, dirty = true,
            ),
        )
        // Probe: server says off.
        rig.enqueueAsset("ocs/empty.json")
        // /toggle response: now on.
        rig.enqueueAsset("ocs/toggle_already_set.json")
        enqueueDailyPullAfterPush()

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val probe = rig.server.takeRequest()
        assertTrue(probe.path!!.contains("/ticks?"))
        val toggle = rig.server.takeRequest()
        assertTrue("expected /toggle, got ${toggle.path}", toggle.path!!.endsWith("/ticks/toggle"))

        assertTrue(rig.db.tickDao().getDirty().isEmpty())
    }

    @Test fun http401_signsOutAndFails() = runTest {
        val trackId = seedTrack(serverId = 11L, type = "counter")
        rig.db.tickDao().upsert(
            TickEntity(
                serverId = null, trackLocalId = trackId, date = today.toString(),
                value = 1, dirty = true,
            ),
        )
        rig.enqueueStatus(401)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        // AuthRepository should now be SignedOut.
        val state = rig.authRepository.state.value
        assertTrue("expected SignedOut, was $state", state is com.martinhammer.tickdroid.data.auth.AuthState.SignedOut)
    }

    @Test fun http5xx_returnsRetry() = runTest {
        val trackId = seedTrack(serverId = 11L, type = "counter")
        rig.db.tickDao().upsert(
            TickEntity(
                serverId = null, trackLocalId = trackId, date = today.toString(),
                value = 1, dirty = true,
            ),
        )
        rig.enqueueStatus(503)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        // Row should remain dirty.
        val row = rig.db.tickDao().find(trackId, today.toString())!!
        assertTrue(row.dirty)
    }

    @Test fun noDirty_succeedsWithoutNetwork() = runTest {
        // No dirty rows, but PushWorker still runs the post-drain pull.
        enqueueDailyPullAfterPush()

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        // Verify the drain did not issue any push requests — only the two pull requests.
        val first = rig.server.takeRequest()
        val second = rig.server.takeRequest()
        assertTrue(first.path!!.contains("/tracks") || first.path!!.contains("/ticks"))
        assertTrue(second.path!!.contains("/tracks") || second.path!!.contains("/ticks"))
        assertEquals(0, rig.server.requestCount - 2)
    }

    @Test fun unsyncedTrack_skipsPushButLeavesDirty() = runTest {
        // Track has no serverId yet — push must wait until pull picks it up.
        val trackId = rig.db.trackDao().insert(
            TrackEntity(serverId = null, name = "T", type = "counter", sortOrder = 0, private = false),
        )
        rig.db.tickDao().upsert(
            TickEntity(
                serverId = null, trackLocalId = trackId, date = today.toString(),
                value = 5, dirty = true,
            ),
        )
        // No push call should be issued. Only the post-drain pull.
        enqueueDailyPullAfterPush()

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val row = rig.db.tickDao().find(trackId, today.toString())!!
        assertTrue("row remains dirty for retry", row.dirty)
        assertNotNull(row)
    }
}
