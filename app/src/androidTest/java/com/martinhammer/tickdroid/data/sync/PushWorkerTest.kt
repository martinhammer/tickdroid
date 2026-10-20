package com.martinhammer.tickdroid.data.sync

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.martinhammer.tickdroid.data.local.TickEntity
import com.martinhammer.tickdroid.data.local.TrackEntity
import com.martinhammer.tickdroid.data.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import java.util.concurrent.TimeUnit
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

    /** Records what the worker would toast. */
    private val notices = object : PushNotices(InstrumentationRegistry.getInstrumentation().targetContext) {
        val shown = mutableListOf<String>()
        override suspend fun show(text: String) {
            shown += text
        }
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
                    json = rig.json,
                    pushNotices = notices,
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
        val recorded = rig.takeApiRequest()
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
        val first = rig.takeApiRequest()
        assertTrue("expected getTicks, got ${first.path}", first.path!!.contains("/ticks?"))
        // Subsequent requests come from the post-drain pull (tracks + ticks). None should be /toggle.
        val second = rig.takeApiRequest()
        val third = rig.takeApiRequest()
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
        val probe = rig.takeApiRequest()
        assertTrue(probe.path!!.contains("/ticks?"))
        val toggle = rig.takeApiRequest()
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
        assertEquals(emptyList<String>(), notices.shown)
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
        assertEquals(emptyList<String>(), notices.shown)
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
        val first = rig.takeApiRequest()
        val second = rig.takeApiRequest()
        assertTrue(first.path!!.contains("/tracks") || first.path!!.contains("/ticks"))
        assertTrue(second.path!!.contains("/tracks") || second.path!!.contains("/ticks"))
        // Plus the capabilities call the pull starts with.
        assertEquals(0, rig.server.requestCount - 3)
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

    @Test fun cancelledMidPush_restoresThePreviousStatus() = runBlocking {
        // Regression: JobScheduler stops the worker when the network loses validation, which
        // cancels doWork. The status used to stay Pushing for good, hiding the journal's chip and
        // leaving the widgets on a dead "syncing" icon instead of the offline symbol.
        val trackId = seedTrack(serverId = 11L, type = "counter")
        rig.db.tickDao().upsert(
            TickEntity(serverId = null, trackLocalId = trackId, date = today.toString(), value = 1, dirty = true),
        )
        val before = PushStatus.Error(SyncErrorKind.ServerUnreachable, "earlier attempt")
        rig.syncManager.reportPushStatus(before)
        // A response that never arrives in time, so the push is in flight when we cancel.
        rig.enqueue(MockResponse().setHeadersDelay(30, TimeUnit.SECONDS))

        val worker = buildWorker()
        val job = launch(Dispatchers.IO) { worker.doWork() }
        withTimeout(5_000) { while (rig.syncManager.pushStatus.value !is PushStatus.Pushing) delay(10) }
        job.cancel()
        job.join()

        assertEquals(before, rig.syncManager.pushStatus.value)
        assertTrue("the tick must still be waiting to go up", rig.db.tickDao().getDirty().isNotEmpty())
    }

    @Test fun yesNoTickOnATrackArchivedOnTheServer_queueMovesOn_noDirtyRowLeft() = runTest {
        // Archived on the web while this tick waited. The server ignores the toggle (200 with
        // the unchanged state), so the push leaves it dirty; the pull that follows marks the
        // track archived and drops it.
        rig.archivedTracks = true
        val yesNo = seedTrack(serverId = 10L, type = "boolean")
        val counter = seedTrack(serverId = 11L, type = "counter")
        rig.db.tickDao().upsert(
            TickEntity(serverId = null, trackLocalId = yesNo, date = today.toString(), value = 1, dirty = true),
        )
        rig.db.tickDao().upsert(
            TickEntity(serverId = null, trackLocalId = counter, date = today.toString(), value = 2, dirty = true),
        )
        rig.enqueueAsset("ocs/empty.json") // probe: off on the server
        rig.enqueueAsset("ocs/toggle_to_off.json") // toggle ignored: still off
        rig.enqueueJson("""{"ocs":{"meta":{"status":"ok","statuscode":200},"data":{"value":2}}}""")
        rig.enqueueJson(
            """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":[
              {"id":10,"name":"T10","type":"boolean","sortOrder":0,"private":false,"archivedAt":"$today"},
              {"id":11,"name":"T11","type":"counter","sortOrder":1,"private":false,"archivedAt":null}]}}""",
        )
        rig.enqueueJson(
            """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":[
              {"id":61,"trackId":11,"date":"$today","value":2}]}}""",
        )

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(rig.takeApiRequest().path!!.contains("/ticks?"))
        assertTrue(rig.takeApiRequest().path!!.endsWith("/ticks/toggle"))
        assertTrue("the queue moved on to the counter", rig.takeApiRequest().path!!.endsWith("/ticks/set"))
        assertTrue(rig.db.tickDao().getDirty().isEmpty())
        assertNull(rig.db.tickDao().find(yesNo, today.toString()))
        assertEquals(2, rig.db.tickDao().find(counter, today.toString())!!.value)
        assertEquals("an older server's quiet 200 shows nothing", emptyList<String>(), notices.shown)
    }

    // --- Refused writes (Prefer: handling=strict) ---

    private fun refusal(code: Int, reason: String?, message: String = "") =
        """{"ocs":{"meta":{"status":"failure","statuscode":$code,"message":""},"data":""" +
            (if (reason == null) "[]" else """{"message":"$message","reason":"$reason"}""") + "}}"

    private fun archivedRefusal(name: String) = refusal(423, "archived", "$name is archived, so nothing was changed")

    private fun setOk(value: Int) = """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":{"value":$value}}}"""

    private suspend fun seedDirty(trackLocalId: Long, date: LocalDate = today, value: Int = 1) {
        rig.db.tickDao().upsert(
            TickEntity(serverId = null, trackLocalId = trackLocalId, date = date.toString(), value = value, dirty = true),
        )
    }

    @Test fun refusedYesNo_queueMovesOn_rowDropped_pullRuns_oneNotice() = runTest {
        val yesNo = seedTrack(serverId = 10L, type = "boolean")
        val counter = seedTrack(serverId = 11L, type = "counter")
        seedDirty(yesNo)
        seedDirty(counter, value = 2)
        rig.enqueueAsset("ocs/empty.json") // probe: off on the server
        rig.enqueueJson(archivedRefusal("T10"), code = 423) // toggle refused
        rig.enqueueJson(setOk(2))
        enqueueDailyPullAfterPush()

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(rig.takeApiRequest().path!!.contains("/ticks?"))
        val toggle = rig.takeApiRequest()
        assertTrue(toggle.path!!.endsWith("/ticks/toggle"))
        assertEquals("handling=strict", toggle.getHeader("Prefer"))
        val set = rig.takeApiRequest()
        assertTrue("the queue moved on to the counter", set.path!!.endsWith("/ticks/set"))
        assertEquals("handling=strict", set.getHeader("Prefer"))
        assertTrue("the pull ran", rig.takeApiRequest().path!!.contains("/tracks"))
        assertTrue(rig.takeApiRequest().path!!.contains("/ticks?"))
        assertNull(rig.db.tickDao().find(yesNo, today.toString()))
        assertTrue(rig.db.tickDao().getDirty().isEmpty())
        assertEquals(listOf("T10 is archived, so the change wasn't saved."), notices.shown)
    }

    @Test fun twoRefusedTicksOnOneTrack_oneNotice() = runTest {
        val track = seedTrack(serverId = 10L, type = "counter")
        seedDirty(track, date = today.minusDays(1))
        seedDirty(track)
        rig.enqueueJson(archivedRefusal("T10"), code = 423)
        rig.enqueueJson(archivedRefusal("T10"), code = 423)
        enqueueDailyPullAfterPush()

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        assertTrue(rig.db.tickDao().getDirty().isEmpty())
        assertEquals(listOf("T10 is archived, so the change wasn't saved."), notices.shown)
    }

    @Test fun refusedTicksOnTwoTracks_twoNoticesInQueueOrder() = runTest {
        val first = seedTrack(serverId = 10L, type = "counter")
        val second = seedTrack(serverId = 11L, type = "counter")
        seedDirty(first)
        seedDirty(second)
        rig.enqueueJson(archivedRefusal("T10"), code = 423)
        rig.enqueueJson(archivedRefusal("T11"), code = 423)
        enqueueDailyPullAfterPush()

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        assertEquals(
            listOf("T10 is archived, so the change wasn't saved.", "T11 is archived, so the change wasn't saved."),
            notices.shown,
        )
    }

    @Test fun refusedThen503_retries_refusedRowGone_noticeStillShown() = runTest {
        val refused = seedTrack(serverId = 10L, type = "counter")
        val failing = seedTrack(serverId = 11L, type = "counter")
        seedDirty(refused)
        seedDirty(failing)
        rig.enqueueJson(archivedRefusal("T10"), code = 423)
        rig.enqueueStatus(503)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertNull(rig.db.tickDao().find(refused, today.toString()))
        assertTrue(rig.db.tickDao().find(failing, today.toString())!!.dirty)
        assertEquals("only the two sets: no pull after a retry", 2, rig.server.requestCount)
        assertEquals(listOf("T10 is archived, so the change wasn't saved."), notices.shown)
    }

    @Test fun refusedForAnotherReason_showsTheServersMessage() = runTest {
        val track = seedTrack(serverId = 10L, type = "counter")
        seedDirty(track)
        rig.enqueueJson(refusal(423, "immutable", "T10 can't be changed"), code = 423)
        enqueueDailyPullAfterPush()

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        assertNull(rig.db.tickDao().find(track, today.toString()))
        assertEquals(listOf("T10 can't be changed"), notices.shown)
    }

    @Test fun trackDeletedElsewhere_404WithReason_dropped_trackGoneAfterPull_noNotice() = runTest {
        val deleted = seedTrack(serverId = 10L, type = "counter")
        val kept = seedTrack(serverId = 11L, type = "counter")
        seedDirty(deleted)
        seedDirty(kept, value = 2)
        rig.enqueueJson(refusal(404, "trackNotFound", "Track not found"), code = 404)
        rig.enqueueJson(setOk(2))
        rig.enqueueJson(
            """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":[
              {"id":11,"name":"T11","type":"counter","sortOrder":1,"private":false}]}}""",
        )
        rig.enqueueJson(
            """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":[
              {"id":61,"trackId":11,"date":"$today","value":2}]}}""",
        )

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(rig.takeApiRequest().path!!.endsWith("/ticks/set"))
        assertTrue("the queue moved on", rig.takeApiRequest().path!!.endsWith("/ticks/set"))
        assertNull(rig.db.tickDao().find(deleted, today.toString()))
        assertNull("the pull removed the track", rig.db.trackDao().findByServerId(10L))
        assertTrue(rig.db.tickDao().getDirty().isEmpty())
        assertEquals(emptyList<String>(), notices.shown)
    }

    @Test fun nextcloudsOwn404_noReason_retries_tickStaysQueued() = runTest {
        // What Nextcloud answers for every Tickbuddy URL while the app is disabled.
        val track = seedTrack(serverId = 10L, type = "counter")
        seedDirty(track)
        rig.enqueueJson(
            """{"ocs":{"meta":{"status":"failure","statuscode":998,"message":"Invalid query, please check the syntax. API specifications are here: http:\/\/www.open-collaboration-services.org\/ocs-v2."},"data":[]}}""",
            code = 404,
        )

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertTrue(rig.db.tickDao().find(track, today.toString())!!.dirty)
        assertEquals("only the set: no pull after a retry", 1, rig.server.requestCount)
        assertEquals(emptyList<String>(), notices.shown)
    }

    @Test fun wrongTrackType_400WithReason_dropped_queueMovesOn_noNotice() = runTest {
        val yesNo = seedTrack(serverId = 10L, type = "boolean")
        val counter = seedTrack(serverId = 11L, type = "counter")
        seedDirty(yesNo)
        seedDirty(counter, value = 2)
        rig.enqueueAsset("ocs/empty.json") // probe
        rig.enqueueJson(refusal(400, "wrongTrackType", "Not a yes/no track"), code = 400)
        rig.enqueueJson(setOk(2))
        enqueueDailyPullAfterPush()

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        rig.takeApiRequest()
        assertTrue(rig.takeApiRequest().path!!.endsWith("/ticks/toggle"))
        assertTrue("the queue moved on", rig.takeApiRequest().path!!.endsWith("/ticks/set"))
        assertNull(rig.db.tickDao().find(yesNo, today.toString()))
        assertTrue(rig.db.tickDao().getDirty().isEmpty())
        assertEquals(emptyList<String>(), notices.shown)
    }

    @Test fun http400_noReason_retries_rowStaysDirty() = runTest {
        val track = seedTrack(serverId = 10L, type = "counter")
        seedDirty(track)
        rig.enqueueJson(refusal(400, null), code = 400)

        assertEquals(ListenableWorker.Result.retry(), buildWorker().doWork())

        assertTrue(rig.db.tickDao().find(track, today.toString())!!.dirty)
        assertEquals(emptyList<String>(), notices.shown)
    }
}
