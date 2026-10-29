package com.martinhammer.tickdroid.data.sync

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.martinhammer.tickdroid.data.local.NoteEntity
import com.martinhammer.tickdroid.data.local.TickEntity
import com.martinhammer.tickdroid.data.local.TrackEntity
import com.martinhammer.tickdroid.data.repository.TickRepository
import com.martinhammer.tickdroid.data.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.time.LocalDate
import java.util.UUID

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

    /** Taps go through the real write path; the push they'd schedule is run by hand. */
    private val repo by lazy {
        val scheduler = object : SyncScheduler(rig.context) {
            override fun schedulePushNow() {}
            override fun schedulePeriodicPush() {}
            override fun cancelAll() {}
        }
        TickRepository(rig.db.tickDao(), rig.db, scheduler)
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
                    noteDao = rig.db.noteDao(),
                    trackDao = rig.db.trackDao(),
                    authRepository = rig.authRepository,
                    syncManager = rig.syncManager,
                    clock = fixedClock,
                    json = rig.json,
                    pushNotices = notices,
                    serverFeatures = rig.serverFeatures,
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

    @Test fun dirtyTickOnAnUnknownTypeTrack_droppedUnsent_queueMovesOn() = runTest {
        // Only a bug can queue one (taps on such a track never write); the guard drops it.
        val future = seedTrack(serverId = 14L, type = "future")
        val counter = seedTrack(serverId = 11L, type = "counter")
        seedDirty(future, value = 3)
        seedDirty(counter, value = 2)
        rig.enqueueJson(setOk(2))
        // The pull after the drain still knows both tracks, so the dropped row can't be a cascade.
        rig.enqueueJson(
            """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":[
              {"id":14,"name":"T14","type":"future","sortOrder":0,"private":false},
              {"id":11,"name":"T11","type":"counter","sortOrder":1,"private":false}]}}""",
        )
        rig.enqueueAsset("ocs/empty.json") // ticks

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        assertTrue("nothing sent for the unknown type", rig.takeApiRequest().path!!.endsWith("/ticks/set"))
        assertTrue(rig.takeApiRequest().path!!.contains("/tracks"))
        assertTrue(rig.takeApiRequest().path!!.contains("/ticks?"))
        assertNotNull(rig.db.trackDao().findByServerId(14))
        assertNull(rig.db.tickDao().find(future, today.toString()))
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
    // --- set for yes/no, increment for counters ---

    /** A Tickbuddy 2.3.0: `set` for yes/no, `increment` with `Idempotency-Key` for counters. */
    private fun serverWithIncrement() {
        rig.booleanSet = true
        rig.counterIncrement = true
        rig.idempotencyKey = true
        rig.seedServerFeatures()
    }

    private fun RecordedRequest.form(): Map<String, String> =
        body.readUtf8().split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    /** The quoted UUID of an `Idempotency-Key` header (a Structured Fields string). */
    private fun RecordedRequest.idempotencyKey(): String {
        val header = checkNotNull(getHeader("Idempotency-Key")) { "no Idempotency-Key" }
        assertTrue("quoted: $header", header.length > 2 && header.startsWith('"') && header.endsWith('"'))
        return header.removeSurrounding("\"").also { UUID.fromString(it) }
    }

    /** The pull after a drain, for a server that still has the track (so it isn't swept). */
    private fun enqueuePull(serverId: Long, type: String, ticks: String = "") {
        rig.enqueueJson(
            """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":[
              {"id":$serverId,"name":"T$serverId","type":"$type","sortOrder":0,"private":false}]}}""",
        )
        rig.enqueueJson("""{"ocs":{"meta":{"status":"ok","statuscode":200},"data":[$ticks]}}""")
    }

    private fun serverTick(serverId: Long, value: Int) = """{"id":50,"trackId":$serverId,"date":"$today","value":$value}"""

    @Test fun counter_withIncrement_sendsTheDelta() = runTest {
        serverWithIncrement()
        val track = seedTrack(serverId = 11L, type = "counter")
        rig.db.tickDao().upsert(TickEntity(serverId = 50L, trackLocalId = track, date = today.toString(), value = 2))
        repo.adjustCounter(track, today, +1)
        rig.enqueueJson(setOk(3))
        enqueuePull(11L, "counter", serverTick(11L, 3))

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        val request = rig.takeApiRequest()
        assertTrue("expected /increment, got ${request.path}", request.path!!.endsWith("/ticks/increment"))
        request.idempotencyKey()
        assertEquals(mapOf("trackId" to "11", "date" to today.toString(), "delta" to "1"), request.form())
        val row = rig.db.tickDao().find(track, today.toString())!!
        assertEquals(3, row.value)
        assertFalse(row.dirty)
    }

    @Test fun tapDuringPush_isKept() = runBlocking {
        val track = seedTrack(serverId = 11L, type = "counter")
        rig.db.tickDao().upsert(TickEntity(serverId = 50L, trackLocalId = track, date = today.toString(), value = 2))
        repo.adjustCounter(track, today, +1)

        val worker = buildWorker()
        val run = launch(Dispatchers.IO) { assertEquals(ListenableWorker.Result.success(), worker.doWork()) }
        val first = rig.takeApiRequest() // in flight: its answer isn't queued yet
        assertEquals("3", first.form()["value"])
        repo.adjustCounter(track, today, +1)
        rig.enqueueJson(setOk(3))
        enqueuePull(11L, "counter", serverTick(11L, 3))
        run.join()

        val row = rig.db.tickDao().find(track, today.toString())!!
        assertEquals("the server's 3 plus the tap", 4, row.value)
        assertTrue("the tap is still to go up", row.dirty)

        rig.enqueueJson(setOk(4))
        enqueuePull(11L, "counter", serverTick(11L, 4))
        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        rig.takeApiRequest(); rig.takeApiRequest() // the first run's pull
        assertEquals("4", rig.takeApiRequest().form()["value"])
        val settled = rig.db.tickDao().find(track, today.toString())!!
        assertEquals(4, settled.value)
        assertFalse(settled.dirty)
    }

    @Test fun untickDuringYesNoSet_isKept() = runBlocking {
        rig.booleanSet = true
        rig.seedServerFeatures()
        val track = seedTrack(serverId = 10L, type = "boolean")
        repo.toggleBoolean(track, today)

        val worker = buildWorker()
        val run = launch(Dispatchers.IO) { assertEquals(ListenableWorker.Result.success(), worker.doWork()) }
        val first = rig.takeApiRequest()
        assertTrue("expected /set, got ${first.path}", first.path!!.endsWith("/ticks/set"))
        assertEquals("1", first.form()["value"])
        repo.toggleBoolean(track, today)
        rig.enqueueJson(setOk(1))
        enqueuePull(10L, "boolean", serverTick(10L, 1))
        run.join()

        val row = rig.db.tickDao().find(track, today.toString())!!
        assertTrue("the untick is kept", row.deleted)
        assertTrue(row.dirty)

        rig.enqueueJson(setOk(0))
        enqueuePull(10L, "boolean")
        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        rig.takeApiRequest(); rig.takeApiRequest()
        assertEquals("0", rig.takeApiRequest().form()["value"])
        assertNull(rig.db.tickDao().find(track, today.toString()))
    }
    @Test fun tapDuringIncrement_isKept_andGoesUpWithANewKey() = runBlocking {
        serverWithIncrement()
        val track = seedTrack(serverId = 11L, type = "counter")
        rig.db.tickDao().upsert(TickEntity(serverId = 50L, trackLocalId = track, date = today.toString(), value = 2))
        repo.adjustCounter(track, today, +1)

        val worker = buildWorker()
        val run = launch(Dispatchers.IO) { assertEquals(ListenableWorker.Result.success(), worker.doWork()) }
        val first = rig.takeApiRequest()
        repo.adjustCounter(track, today, +1)
        rig.enqueueJson(setOk(3))
        enqueuePull(11L, "counter", serverTick(11L, 3))
        run.join()

        val row = rig.db.tickDao().find(track, today.toString())!!
        assertEquals(4, row.value)
        assertEquals(1, row.pendingDelta)
        assertTrue(row.dirty)
        assertNull("the first request is settled", row.sentKey)

        rig.enqueueJson(setOk(4))
        enqueuePull(11L, "counter", serverTick(11L, 4))
        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        rig.takeApiRequest(); rig.takeApiRequest()
        val second = rig.takeApiRequest()
        assertEquals("1", second.form()["delta"])
        assertNotEquals(first.idempotencyKey(), second.idempotencyKey())
        assertFalse(rig.db.tickDao().find(track, today.toString())!!.dirty)
    }

    @Test fun yesNo_withBooleanSet_setsWithoutReadingFirst() = runTest {
        rig.booleanSet = true
        rig.seedServerFeatures()
        val ticked = seedTrack(serverId = 10L, type = "boolean")
        val unticked = seedTrack(serverId = 12L, type = "boolean")
        seedDirty(ticked)
        rig.db.tickDao().upsert(
            TickEntity(serverId = 70L, trackLocalId = unticked, date = today.toString(), value = 0, dirty = true, deleted = true),
        )
        rig.enqueueJson(setOk(1))
        rig.enqueueJson(setOk(0))
        enqueueDailyPullAfterPush()

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        val on = rig.takeApiRequest()
        assertTrue("no read first: ${on.path}", on.path!!.endsWith("/ticks/set"))
        assertEquals(mapOf("trackId" to "10", "date" to today.toString(), "value" to "1"), on.form())
        val off = rig.takeApiRequest()
        assertTrue(off.path!!.endsWith("/ticks/set"))
        assertEquals("0", off.form()["value"])
        assertTrue("then the pull", rig.takeApiRequest().path!!.contains("/tracks"))
        assertTrue(rig.db.tickDao().getDirty().isEmpty())
    }

    @Test fun yesNo_setAnsweredTrue_settlesTheRow() = runTest {
        // A 2.3.0 pre-release answered true / false.
        rig.booleanSet = true
        rig.seedServerFeatures()
        val track = seedTrack(serverId = 10L, type = "boolean")
        seedDirty(track)
        rig.enqueueJson("""{"ocs":{"meta":{"status":"ok","statuscode":200},"data":{"value":true}}}""")
        enqueuePull(10L, "boolean", serverTick(10L, 1))

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        val row = rig.db.tickDao().find(track, today.toString())!!
        assertEquals(1, row.value)
        assertFalse(row.dirty)
    }

    @Test fun counter_pendingZero_noRequest_rowSettled() = runTest {
        serverWithIncrement()
        val track = seedTrack(serverId = 11L, type = "counter")
        repo.adjustCounter(track, today, +1)
        repo.adjustCounter(track, today, -1) // never reached the server
        enqueueDailyPullAfterPush()

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        assertTrue("only the pull", rig.takeApiRequest().path!!.contains("/tracks"))
        assertNull(rig.db.tickDao().find(track, today.toString()))
    }

    @Test fun counter_queuedBeforeTheUpdate_goesUpAbsolute_evenWithIncrement() = runTest {
        serverWithIncrement()
        val track = seedTrack(serverId = 11L, type = "counter")
        seedDirty(track, value = 5) // pendingDelta null, as MIGRATION_3_4 leaves it
        rig.enqueueJson(setOk(5))
        enqueuePull(11L, "counter", serverTick(11L, 5))

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        val request = rig.takeApiRequest()
        assertTrue(request.path!!.endsWith("/ticks/set"))
        assertEquals("5", request.form()["value"])
        assertNull(request.getHeader("Idempotency-Key"))
        val row = rig.db.tickDao().find(track, today.toString())!!
        assertFalse(row.dirty)
        assertNull(row.pendingDelta)
    }

    @Test fun counterIncrementWithoutIdempotencyKey_goesUpAbsolute() = runTest {
        rig.booleanSet = true
        rig.counterIncrement = true
        rig.seedServerFeatures()
        val track = seedTrack(serverId = 11L, type = "counter")
        rig.db.tickDao().upsert(TickEntity(serverId = 50L, trackLocalId = track, date = today.toString(), value = 2))
        repo.adjustCounter(track, today, +1)
        rig.enqueueJson(setOk(3))
        enqueuePull(11L, "counter", serverTick(11L, 3))

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        val request = rig.takeApiRequest()
        assertTrue(request.path!!.endsWith("/ticks/set"))
        assertEquals("3", request.form()["value"])
        assertFalse(rig.db.tickDao().find(track, today.toString())!!.dirty)
    }

    @Test fun increment_answerIncludesAnotherDevicesChange() = runTest {
        serverWithIncrement()
        val track = seedTrack(serverId = 11L, type = "counter")
        rig.db.tickDao().upsert(TickEntity(serverId = 50L, trackLocalId = track, date = today.toString(), value = 2))
        repo.adjustCounter(track, today, +1)
        rig.enqueueJson(setOk(4)) // a script added 1 meanwhile
        enqueuePull(11L, "counter", serverTick(11L, 4))

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        val row = rig.db.tickDao().find(track, today.toString())!!
        assertEquals(4, row.value)
        assertFalse(row.dirty)
    }

    /** One run whose increment the server applied but whose answer never arrived. */
    private suspend fun lostAnswer(answer: MockResponse) {
        rig.enqueue(answer)
        assertEquals(ListenableWorker.Result.retry(), buildWorker().doWork())
    }

    private suspend fun incrementWithLostAnswer_resendsTheSameRequest(answer: MockResponse) {
        serverWithIncrement()
        val track = seedTrack(serverId = 11L, type = "counter")
        rig.db.tickDao().upsert(TickEntity(serverId = 50L, trackLocalId = track, date = today.toString(), value = 2))
        repo.adjustCounter(track, today, +2)

        lostAnswer(answer)

        val first = rig.takeApiRequest()
        val kept = rig.db.tickDao().find(track, today.toString())!!
        assertEquals(first.idempotencyKey(), kept.sentKey)
        assertEquals(2, kept.sentDelta)
        assertTrue(kept.dirty)

        repo.adjustCounter(track, today, +1)
        rig.enqueueJson(setOk(4)) // the repeat, answered as the first request was
        enqueuePull(11L, "counter", serverTick(11L, 4))
        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        val repeat = rig.takeApiRequest()
        assertEquals("the same key", first.idempotencyKey(), repeat.idempotencyKey())
        assertEquals("the same delta, not the tap since", "2", repeat.form()["delta"])
        val afterRepeat = rig.db.tickDao().find(track, today.toString())!!
        assertEquals(5, afterRepeat.value)
        assertEquals(1, afterRepeat.pendingDelta)
        assertNull(afterRepeat.sentKey)

        rig.takeApiRequest(); rig.takeApiRequest() // the pull
        rig.enqueueJson(setOk(5))
        enqueuePull(11L, "counter", serverTick(11L, 5))
        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        val next = rig.takeApiRequest()
        assertEquals("1", next.form()["delta"])
        assertNotEquals(first.idempotencyKey(), next.idempotencyKey())
        val settled = rig.db.tickDao().find(track, today.toString())!!
        assertEquals(5, settled.value)
        assertFalse(settled.dirty)
    }

    @Test fun incrementWithAnswerCutOff_resendsTheSameKeyAndDelta() = runTest {
        // The server applied it; the connection dropped while its answer came back.
        incrementWithLostAnswer_resendsTheSameRequest(
            MockResponse().setBody(setOk(4) + " ".repeat(4096)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
    }

    @Test fun incrementAnswered502WithoutReason_resendsTheSameKeyAndDelta() = runTest {
        // A proxy's answer: the increment may or may not have reached Tickbuddy.
        incrementWithLostAnswer_resendsTheSameRequest(MockResponse().setResponseCode(502).setBody("Bad Gateway"))
    }

    @Test fun incrementKeyIsInRoomBeforeTheRequestLeaves_keptWhenCancelled() = runBlocking {
        serverWithIncrement()
        val track = seedTrack(serverId = 11L, type = "counter")
        repo.adjustCounter(track, today, +1)
        rig.enqueue(MockResponse().setHeadersDelay(30, TimeUnit.SECONDS))

        val worker = buildWorker()
        val job = launch(Dispatchers.IO) { worker.doWork() }
        val request = rig.takeApiRequest()
        val inFlight = rig.db.tickDao().find(track, today.toString())!!
        assertEquals(request.idempotencyKey(), inFlight.sentKey)
        assertEquals(1, inFlight.sentDelta)
        job.cancel()
        job.join()

        val after = rig.db.tickDao().find(track, today.toString())!!
        assertEquals(inFlight.sentKey, after.sentKey)
        assertEquals(1, after.sentDelta)
        assertTrue(after.dirty)
    }

    @Test fun increment422KeyReused_dropped_queueMovesOn() = runTest {
        serverWithIncrement()
        val first = seedTrack(serverId = 10L, type = "counter")
        val second = seedTrack(serverId = 11L, type = "counter")
        repo.adjustCounter(first, today, +1)
        repo.adjustCounter(second, today, +1)
        rig.enqueueJson(refusal(422, "idempotencyKeyReused", "This Idempotency-Key was already used for another request"), code = 422)
        rig.enqueueJson(setOk(1))
        enqueueDailyPullAfterPush()

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        assertTrue(rig.takeApiRequest().path!!.endsWith("/ticks/increment"))
        assertTrue("the queue moved on", rig.takeApiRequest().path!!.endsWith("/ticks/increment"))
        assertTrue(rig.db.tickDao().getDirty().isEmpty())
        assertEquals(emptyList<String>(), notices.shown)
    }

    @Test fun increment423_dropped_oneNotice() = runTest {
        serverWithIncrement()
        val track = seedTrack(serverId = 10L, type = "counter")
        repo.adjustCounter(track, today.minusDays(1), +1)
        repo.adjustCounter(track, today, +1)
        rig.enqueueJson(archivedRefusal("T10"), code = 423)
        rig.enqueueJson(archivedRefusal("T10"), code = 423)
        enqueueDailyPullAfterPush()

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        val increment = rig.takeApiRequest()
        assertTrue(increment.path!!.endsWith("/ticks/increment"))
        assertEquals("handling=strict", increment.getHeader("Prefer"))
        assertTrue(rig.db.tickDao().getDirty().isEmpty())
        assertEquals(listOf("T10 is archived, so the change wasn't saved."), notices.shown)
    }
    // Notes (capability `notes`): read the server's note, then set / delete / append / nothing.

    private fun notesServer(append: Boolean = true) {
        rig.notes = true
        rig.notesAppend = append
        rig.seedServerFeatures()
    }

    private suspend fun seedNote(trackLocalId: Long, text: String, base: String?): Long =
        rig.db.noteDao().upsert(
            NoteEntity(trackLocalId = trackLocalId, date = today.toString(), text = text, dirty = true, baseText = base),
        )

    private fun quoted(text: String) = Json.encodeToString(String.serializer(), text)

    private fun serverNote(serverId: Long, text: String) =
        """{"trackId":$serverId,"date":"$today","text":${quoted(text)}}"""

    private fun notesList(vararg notes: String) =
        """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":[${notes.joinToString(",")}]}}"""

    private fun noteOk(text: String) = """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":{"text":${quoted(text)}}}}"""

    /** The pull after a push, with notes: tracks, ticks, then notes. */
    private fun enqueueNotesPull(serverId: Long = 10L, vararg notes: String) {
        enqueuePull(serverId, "boolean")
        rig.enqueueJson(notesList(*notes))
    }

    private fun RecordedRequest.text(): String = URLDecoder.decode(form().getValue("text"), "UTF-8")

    private suspend fun note(trackLocalId: Long) = rig.db.noteDao().find(trackLocalId, today.toString())

    @Test fun note_serverUnchanged_setsThePhonesText() = runTest {
        notesServer()
        val track = seedTrack(serverId = 10L, type = "boolean")
        seedNote(track, "5k along the river", base = "")
        rig.enqueueJson(notesList())
        rig.enqueueJson(noteOk("5k along the river"))
        enqueueNotesPull(10L, serverNote(10L, "5k along the river"))

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        val read = rig.takeApiRequest()
        assertTrue(read.path!!, read.path!!.contains("/notes?from=$today&to=$today&includeAllTypes=true"))
        val set = rig.takeApiRequest()
        assertTrue(set.path!!.endsWith("/notes/set"))
        val form = set.form() // the body can only be read once
        assertEquals("10", form["trackId"])
        assertEquals("5k along the river", URLDecoder.decode(form.getValue("text"), "UTF-8"))
        val row = note(track)!!
        assertEquals("5k along the river", row.text)
        assertFalse(row.dirty)
        assertNull(row.baseText)
    }

    @Test fun note_deletedOnPhone_serverUnchanged_sendsDelete() = runTest {
        notesServer()
        val track = seedTrack(serverId = 10L, type = "boolean")
        seedNote(track, "", base = "old")
        rig.enqueueJson(notesList(serverNote(10L, "old")))
        rig.enqueueStatus(204)
        enqueueNotesPull()

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        rig.takeApiRequest()
        val delete = rig.takeApiRequest()
        assertEquals("DELETE", delete.method)
        assertTrue(delete.path!!, delete.path!!.contains("/notes?trackId=10&date=$today"))
        assertNull(note(track))
    }

    @Test fun note_changedElsewhere_appendsOnlyTheNewLines() = runTest {
        notesServer()
        val track = seedTrack(serverId = 10L, type = "boolean")
        seedNote(track, "a\nmine", base = "a")
        rig.enqueueJson(notesList(serverNote(10L, "a\ntheirs")))
        rig.enqueueJson(noteOk("a\ntheirs\nmine"))
        enqueueNotesPull(10L, serverNote(10L, "a\ntheirs\nmine"))

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        rig.takeApiRequest()
        val append = rig.takeApiRequest()
        assertTrue(append.path!!.endsWith("/notes/append"))
        assertEquals("mine", append.text())
        val row = note(track)!!
        assertEquals("a\ntheirs\nmine", row.text)
        assertFalse(row.dirty)
    }

    @Test fun note_changedElsewhere_phoneOnlyRemovedALine_writesNothing() = runTest {
        notesServer()
        val track = seedTrack(serverId = 10L, type = "boolean")
        seedNote(track, "a", base = "a\nb")
        rig.enqueueJson(notesList(serverNote(10L, "a\nb\nc")))
        enqueueNotesPull(10L, serverNote(10L, "a\nb\nc"))

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        rig.takeApiRequest() // the read
        assertTrue("straight to the pull", rig.takeApiRequest().path!!.contains("/tracks"))
        val row = note(track)!!
        assertEquals("a\nb\nc", row.text)
        assertFalse(row.dirty)
    }

    @Test fun note_mergeOver500_writesNothing_serversNoteBack_notice() = runTest {
        notesServer()
        val track = seedTrack(serverId = 10L, type = "boolean")
        val theirs = "t".repeat(498)
        seedNote(track, "mine", base = "")
        rig.enqueueJson(notesList(serverNote(10L, theirs)))
        enqueueNotesPull(10L, serverNote(10L, theirs))

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        rig.takeApiRequest()
        assertTrue("no write", rig.takeApiRequest().path!!.contains("/tracks"))
        val row = note(track)!!
        assertEquals(theirs, row.text)
        assertFalse(row.dirty)
        assertEquals(
            listOf("The note for T10 would be longer than 500 characters, so the change wasn't saved."),
            notices.shown,
        )
    }

    @Test fun note_changedElsewhere_withoutNotesAppend_setsThePhonesVersion() = runTest {
        notesServer(append = false)
        val track = seedTrack(serverId = 10L, type = "boolean")
        seedNote(track, "mine", base = "a")
        rig.enqueueJson(notesList(serverNote(10L, "theirs")))
        rig.enqueueJson(noteOk("mine"))
        enqueueNotesPull(10L, serverNote(10L, "mine"))

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        rig.takeApiRequest()
        val set = rig.takeApiRequest()
        assertTrue(set.path!!.endsWith("/notes/set"))
        assertEquals("mine", set.text())
    }

    @Test fun note_serverAlreadyHasIt_writesNothing() = runTest {
        notesServer()
        val track = seedTrack(serverId = 10L, type = "boolean")
        seedNote(track, "same", base = "")
        rig.enqueueJson(notesList(serverNote(10L, "same")))
        enqueueNotesPull(10L, serverNote(10L, "same"))

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        rig.takeApiRequest()
        assertTrue(rig.takeApiRequest().path!!.contains("/tracks"))
        assertFalse(note(track)!!.dirty)
    }

    @Test fun note_editedDuringSet_staysDirty_baseIsTheAnswer_nextRunSetsAgain() = runBlocking {
        notesServer()
        val track = seedTrack(serverId = 10L, type = "boolean")
        val id = seedNote(track, "first", base = "")
        rig.enqueueJson(notesList())

        val run = launch(Dispatchers.IO) { assertEquals(ListenableWorker.Result.success(), buildWorker().doWork()) }
        rig.takeApiRequest() // the read
        assertEquals("first", rig.takeApiRequest().text()) // in flight: its answer isn't queued yet
        rig.db.noteDao().update(rig.db.noteDao().findById(id)!!.copy(text = "second"))
        rig.enqueueJson(noteOk("first"))
        enqueueNotesPull(10L, serverNote(10L, "first"))
        run.join()

        val row = note(track)!!
        assertEquals("second", row.text)
        assertTrue(row.dirty)
        assertEquals("first", row.baseText)

        rig.enqueueJson(notesList(serverNote(10L, "first")))
        rig.enqueueJson(noteOk("second"))
        enqueueNotesPull(10L, serverNote(10L, "second"))
        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())
        repeat(3) { rig.takeApiRequest() } // the first run's pull
        rig.takeApiRequest() // the read
        val again = rig.takeApiRequest()
        assertTrue(again.path!!.endsWith("/notes/set"))
        assertEquals("second", again.text())
        assertFalse(note(track)!!.dirty)
    }

    @Test fun note_editedDuringAppend_baseKept_nextRunAppendsAgain() = runBlocking {
        notesServer()
        val track = seedTrack(serverId = 10L, type = "boolean")
        val id = seedNote(track, "a\nmine", base = "a")
        rig.enqueueJson(notesList(serverNote(10L, "a\ntheirs")))

        val run = launch(Dispatchers.IO) { assertEquals(ListenableWorker.Result.success(), buildWorker().doWork()) }
        rig.takeApiRequest()
        assertEquals("mine", rig.takeApiRequest().text())
        rig.db.noteDao().update(rig.db.noteDao().findById(id)!!.copy(text = "a\nmine\nmore"))
        rig.enqueueJson(noteOk("a\ntheirs\nmine"))
        enqueueNotesPull(10L, serverNote(10L, "a\ntheirs\nmine"))
        run.join()

        val row = note(track)!!
        assertEquals("a\nmine\nmore", row.text)
        assertTrue(row.dirty)
        assertEquals("a", row.baseText)

        rig.enqueueJson(notesList(serverNote(10L, "a\ntheirs\nmine")))
        rig.enqueueJson(noteOk("a\ntheirs\nmine\nmore"))
        enqueueNotesPull(10L, serverNote(10L, "a\ntheirs\nmine\nmore"))
        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())
        repeat(3) { rig.takeApiRequest() }
        rig.takeApiRequest()
        val append = rig.takeApiRequest()
        assertTrue(append.path!!.endsWith("/notes/append"))
        assertEquals("more", append.text())
        assertEquals("a\ntheirs\nmine\nmore", note(track)!!.text)
    }

    @Test fun note_refused423_dropped_oneNoticeSharedWithATick() = runTest {
        notesServer()
        rig.booleanSet = true
        rig.seedServerFeatures()
        val track = seedTrack(serverId = 10L, type = "boolean")
        seedDirty(track)
        seedNote(track, "mine", base = "")
        rig.enqueueJson(archivedRefusal("T10"), code = 423) // the tick's set
        rig.enqueueJson(notesList())
        rig.enqueueJson(archivedRefusal("T10"), code = 423) // the note's set
        enqueueNotesPull()

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        assertNull(note(track))
        assertTrue(rig.db.tickDao().getDirty().isEmpty())
        assertEquals(listOf("T10 is archived, so the change wasn't saved."), notices.shown)
    }

    @Test fun note_404WithReason_droppedSilently_withoutReason_retried() = runTest {
        notesServer()
        val track = seedTrack(serverId = 10L, type = "boolean")
        seedNote(track, "mine", base = "")
        rig.enqueueJson(notesList())
        rig.enqueueJson(refusal(404, "trackNotFound", "Track not found"), code = 404)
        enqueueNotesPull()
        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())
        assertNull(note(track))
        assertEquals(emptyList<String>(), notices.shown)

        seedNote(track, "again", base = "")
        rig.enqueueStatus(404) // Nextcloud's own: Tickbuddy disabled
        assertEquals(ListenableWorker.Result.retry(), buildWorker().doWork())
        assertTrue(note(track)!!.dirty)
    }

    @Test fun note_409OnAppend_retried_nextRunAppends() = runTest {
        notesServer()
        val track = seedTrack(serverId = 10L, type = "boolean")
        seedNote(track, "a\nmine", base = "a")
        rig.enqueueJson(notesList(serverNote(10L, "a\ntheirs")))
        rig.enqueueJson(refusal(409, "noteConflict", "The note changed while appending; try again"), code = 409)
        assertEquals(ListenableWorker.Result.retry(), buildWorker().doWork())
        assertTrue(note(track)!!.dirty)

        rig.enqueueJson(notesList(serverNote(10L, "a\ntheirs")))
        rig.enqueueJson(noteOk("a\ntheirs\nmine"))
        enqueueNotesPull(10L, serverNote(10L, "a\ntheirs\nmine"))
        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())
        assertFalse(note(track)!!.dirty)
    }

    @Test fun ticksAndNotes_ticksFirst_thenNotes_thenOnePull() = runTest {
        notesServer()
        rig.booleanSet = true
        rig.seedServerFeatures()
        val track = seedTrack(serverId = 10L, type = "boolean")
        seedNote(track, "mine", base = "")
        seedDirty(track)
        rig.enqueueJson(setOk(1))
        rig.enqueueJson(notesList())
        rig.enqueueJson(noteOk("mine"))
        enqueueNotesPull(10L, serverNote(10L, "mine"))

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        val paths = List(6) { rig.takeApiRequest().path!!.substringBefore('?') }
        assertEquals(
            listOf("/ticks/set", "/notes", "/notes/set", "/tracks", "/ticks", "/notes"),
            paths.map { it.substringAfter("/api") },
        )
    }

    @Test fun withoutNotesFlag_noNotesRequests_dirtyNoteStays() = runTest {
        val track = seedTrack(serverId = 10L, type = "boolean")
        seedNote(track, "mine", base = "")
        enqueuePull(10L, "boolean")

        assertEquals(ListenableWorker.Result.success(), buildWorker().doWork())

        assertTrue(rig.takeApiRequest().path!!.contains("/tracks"))
        assertTrue(rig.takeApiRequest().path!!.contains("/ticks"))
        assertTrue(note(track)!!.dirty)
        assertEquals(3, rig.server.requestCount) // with the capabilities call
    }
}
