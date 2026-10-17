package com.martinhammer.tickdroid.data.sync

import com.martinhammer.tickdroid.data.local.TickEntity
import com.martinhammer.tickdroid.data.local.TrackPrefsEntity
import com.martinhammer.tickdroid.data.time.Clock
import com.martinhammer.tickdroid.domain.TrackType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Track changes end to end: the requests [TrackEditor] sends (form-encoded, as Tickbuddy's web
 * settings send them), the re-read that follows in the same lock hold, and what lands in Room.
 */
class TrackChangesTest {

    private lateinit var rig: SyncTestRig
    private lateinit var editor: TrackEditor
    private val today = LocalDate.parse("2026-04-20")
    private val clock = object : Clock {
        override fun today() = this@TrackChangesTest.today
        override fun nowMillis() = 0L
    }

    @Before fun setUp() {
        rig = SyncTestRig().also { it.assertSignedIn() }
        editor = TrackEditor(rig.syncManager, Json { ignoreUnknownKeys = true }, clock)
    }

    @After fun tearDown() {
        rig.shutdown()
    }

    private val base = "/ocs/v2.php/apps/tickbuddy/api/tracks"

    private data class T(
        val id: Long,
        val name: String,
        val sortOrder: Int,
        val private: Boolean = false,
        val type: String = "boolean",
        val archivedAt: String? = null,
    )

    private fun trackJson(t: T) =
        """{"id":${t.id},"name":"${t.name}","type":"${t.type}","sortOrder":${t.sortOrder},"private":${t.private},""" +
            """"archivedAt":${t.archivedAt?.let { "\"$it\"" } ?: "null"}}"""

    private fun ok(data: String) = """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":$data}}"""
    private fun list(vararg tracks: T) = ok(tracks.joinToString(",", "[", "]") { trackJson(it) })
    private fun error(message: String) =
        """{"ocs":{"meta":{"status":"failure","statuscode":400,"message":""},"data":{"message":"$message"}}}"""

    private val meditate = T(10, "Meditate", 0)
    private val pages = T(11, "Pages read", 1, type = "counter")
    private val mood = T(12, "Mood", 2, private = true)

    /** Seeds Room with tracks 10, 11, 12 and their April ticks, then forgets those requests. */
    private suspend fun seed() {
        rig.enqueueAsset("ocs/tracks_ok.json")
        rig.enqueueAsset("ocs/ticks_ok.json")
        rig.syncManager.pull(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-30"))
        repeat(2) { rig.takeApiRequest() }
    }

    private fun RecordedRequest.form(): List<Pair<String, String>> =
        body.readUtf8().split("&").filter { it.isNotEmpty() }.map {
            val (k, v) = it.split("=", limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    private fun take(): RecordedRequest = rig.takeApiRequest()

    // --- Create ---

    @Test fun create_postsNameAndType_thenRereads_trackLandsAtTheEnd() = runTest {
        seed()
        val water = T(20, "Water", 3)
        rig.enqueueJson(ok(trackJson(water)), code = 201)
        rig.enqueueJson(list(meditate, pages, mood, water))

        val result = editor.create("Water", TrackType.BOOLEAN, private = false)

        assertEquals(TrackChange.Created(20), result)
        val post = take()
        assertEquals("POST", post.method)
        assertEquals(base, post.path)
        assertEquals(listOf("name" to "Water", "type" to "boolean"), post.form())
        val reread = take()
        assertEquals("GET", reread.method)
        assertEquals(base, reread.path)
        assertEquals(listOf(10L, 11L, 12L, 20L), rig.db.trackDao().getAll().map { it.serverId })
    }

    @Test fun createPrivate_postsThenPutsPrivateThenRereads() = runTest {
        val water = T(20, "Water", 1)
        rig.enqueueJson(ok(trackJson(water)), code = 201)
        rig.enqueueJson(ok(trackJson(water.copy(private = true))))
        rig.enqueueJson(list(water.copy(private = true)))

        val result = editor.create("Water", TrackType.COUNTER, private = true)

        assertEquals(TrackChange.Created(20), result)
        assertEquals(listOf("name" to "Water", "type" to "counter"), take().form())
        val put = take()
        assertEquals("PUT", put.method)
        assertEquals("$base/20", put.path)
        assertEquals(listOf("private" to "true"), put.form())
        assertEquals("GET", take().method)
        assertTrue(rig.db.trackDao().findByServerId(20)!!.private)
    }

    @Test fun createPrivate_putFails_keepsThePublicTrack() = runTest {
        val water = T(20, "Water", 1)
        rig.enqueueJson(ok(trackJson(water)), code = 201)
        rig.enqueueStatus(500)
        rig.enqueueJson(list(water))

        val result = editor.create("Water", TrackType.BOOLEAN, private = true)

        assertEquals(TrackChange.CreatedNotPrivate(20), result)
        assertFalse(rig.db.trackDao().findByServerId(20)!!.private)
    }

    // --- Rename / private ---

    @Test fun rename_sendsOnlyName_localIdUnchanged() = runTest {
        seed()
        val before = rig.db.trackDao().findByServerId(10)!!
        rig.enqueueJson(ok(trackJson(meditate.copy(name = "Breathe"))))
        rig.enqueueJson(list(meditate.copy(name = "Breathe"), pages, mood))

        assertEquals(TrackChange.Done, editor.rename(10, " Breathe "))

        val put = take()
        assertEquals("PUT", put.method)
        assertEquals("$base/10", put.path)
        assertEquals(listOf("name" to "Breathe"), put.form())
        val after = rig.db.trackDao().findByServerId(10)!!
        assertEquals("Breathe", after.name)
        assertEquals(before.localId, after.localId)
    }

    @Test fun setPrivateFalse_sendsPrivateFalse() = runTest {
        seed()
        val before = rig.db.trackDao().findByServerId(12)!!
        rig.enqueueJson(ok(trackJson(mood.copy(private = false))))
        rig.enqueueJson(list(meditate, pages, mood.copy(private = false)))

        assertEquals(TrackChange.Done, editor.setPrivate(12, false))

        assertEquals(listOf("private" to "false"), take().form())
        val after = rig.db.trackDao().findByServerId(12)!!
        assertFalse(after.private)
        assertEquals(before.localId, after.localId)
    }

    // --- Reorder ---

    @Test fun reorder_readsServerFirst_sendsRepeatedIds_appendsServerOnlyTrack() = runTest {
        seed()
        val web = T(13, "Added on the web", 3)
        rig.enqueueJson(list(meditate, pages, mood, web)) // reorder's own read
        rig.enqueueJson(ok("[]"))
        rig.enqueueJson(list(mood.copy(sortOrder = 1), meditate.copy(sortOrder = 2), pages.copy(sortOrder = 3), web.copy(sortOrder = 4)))

        assertEquals(TrackChange.Done, editor.reorder(listOf(12, 10, 11)))

        assertEquals("GET", take().method)
        val put = take()
        assertEquals("PUT", put.method)
        assertEquals("$base/reorder", put.path)
        assertEquals(
            listOf("trackIds[]" to "12", "trackIds[]" to "10", "trackIds[]" to "11", "trackIds[]" to "13"),
            put.form(),
        )
        assertEquals("GET", take().method)
        val bySort = rig.db.trackDao().getAll().sortedBy { it.sortOrder }.map { it.serverId }
        assertEquals(listOf(12L, 10L, 11L, 13L), bySort)
    }

    // --- Delete ---

    @Test fun delete_removesTrackTicksAndPrefs() = runTest {
        seed()
        val track = rig.db.trackDao().findByServerId(11)!!
        rig.db.tickDao().upsert(TickEntity(serverId = null, trackLocalId = track.localId, date = "2026-04-15", value = 4, dirty = true))
        rig.db.trackPrefsDao().upsert(TrackPrefsEntity(serverId = 11, colorKey = "blue"))
        rig.enqueueStatus(204)
        rig.enqueueJson(list(meditate, mood))

        assertEquals(TrackChange.Done, editor.delete(11))

        val delete = take()
        assertEquals("DELETE", delete.method)
        assertEquals("$base/11", delete.path)
        assertEquals("GET", take().method)
        assertNull(rig.db.trackDao().findByServerId(11))
        val ticks = rig.db.tickDao().getRange("2026-04-01", "2026-04-30")
        assertTrue(ticks.none { it.trackLocalId == track.localId })
        assertNull(rig.db.trackPrefsDao().findByServerId(11))
    }

    @Test fun delete_404CountsAsSuccess() = runTest {
        seed()
        rig.enqueueJson(error("Track not found"), code = 404)
        rig.enqueueJson(list(meditate, mood))

        assertEquals(TrackChange.Done, editor.delete(11))
        assertNull(rig.db.trackDao().findByServerId(11))
    }

    // --- Archive / restore ---

    /** [seed] on a server with the `archivedTracks` flag: the pull reads it first. */
    private suspend fun seedWithArchive() {
        rig.archivedTracks = true
        seed()
        assertTrue(rig.serverFeatures.archivedTracks.value)
    }

    @Test fun archive_sendsArchivedAndTodaysDate_thenRereadsWithArchived() = runTest {
        seedWithArchive()
        val before = rig.db.trackDao().findByServerId(11)!!
        rig.db.trackPrefsDao().upsert(TrackPrefsEntity(serverId = 11, colorKey = "blue", emoji = "📚"))
        val ticksBefore = rig.db.tickDao().getRange("2026-04-01", "2026-04-30").filter { it.trackLocalId == before.localId }
        val archived = pages.copy(archivedAt = today.toString())
        rig.enqueueJson(ok(trackJson(archived)))
        rig.enqueueJson(list(meditate, archived, mood))

        assertEquals(TrackChange.Done, editor.archive(11))

        val put = take()
        assertEquals("PUT", put.method)
        assertEquals("$base/11", put.path)
        assertEquals(listOf("archived" to "true", "archivedAt" to "2026-04-20"), put.form())
        val reread = take()
        assertEquals("GET", reread.method)
        assertEquals("$base?includeArchived=true", reread.path)
        val after = rig.db.trackDao().findByServerId(11)!!
        assertEquals("2026-04-20", after.archivedAt)
        assertEquals(before.localId, after.localId)
        assertEquals("blue", rig.db.trackPrefsDao().findByServerId(11)?.colorKey)
        assertEquals(
            ticksBefore,
            rig.db.tickDao().getRange("2026-04-01", "2026-04-30").filter { it.trackLocalId == before.localId },
        )
    }

    @Test fun restore_sendsOnlyArchivedFalse_trackIsActiveAtTheEnd() = runTest {
        rig.archivedTracks = true
        rig.enqueueJson(list(meditate, pages.copy(archivedAt = "2026-03-02"), mood))
        rig.enqueueAsset("ocs/empty.json")
        rig.syncManager.pull(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-30"))
        repeat(2) { take() }
        val restored = pages.copy(sortOrder = 3)
        rig.enqueueJson(ok(trackJson(restored)))
        rig.enqueueJson(list(meditate, mood, restored))

        assertEquals(TrackChange.Done, editor.restore(11))

        assertEquals(listOf("archived" to "false"), take().form())
        assertEquals("$base?includeArchived=true", take().path)
        assertNull(rig.db.trackDao().findByServerId(11)!!.archivedAt)
        assertEquals(listOf(10L, 12L, 11L), rig.db.trackDao().getAll().map { it.serverId })
    }

    @Test fun reorder_withAnArchivedTrack_readsActiveOnly_sendsActiveIds() = runTest {
        rig.archivedTracks = true
        rig.enqueueJson(list(meditate, pages, mood, T(13, "Smoking", 3, archivedAt = "2026-03-02")))
        rig.enqueueAsset("ocs/empty.json")
        rig.syncManager.pull(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-30"))
        repeat(2) { take() }
        rig.enqueueJson(list(meditate, pages, mood)) // reorder's own read: active only
        rig.enqueueJson(ok("[]"))
        rig.enqueueJson(list(mood.copy(sortOrder = 1), meditate.copy(sortOrder = 2), pages.copy(sortOrder = 3), T(13, "Smoking", 4, archivedAt = "2026-03-02")))

        assertEquals(TrackChange.Done, editor.reorder(listOf(12, 10, 11)))

        assertEquals(base, take().path)
        assertEquals(
            listOf("trackIds[]" to "12", "trackIds[]" to "10", "trackIds[]" to "11"),
            take().form(),
        )
        assertEquals("$base?includeArchived=true", take().path)
    }

    @Test fun archive_dropsADirtyTickWaitingForThatTrack() = runTest {
        seedWithArchive()
        val track = rig.db.trackDao().findByServerId(10)!!
        rig.db.tickDao().upsert(TickEntity(serverId = null, trackLocalId = track.localId, date = today.toString(), value = 1, dirty = true))
        val other = rig.db.trackDao().findByServerId(11)!!
        rig.db.tickDao().upsert(TickEntity(serverId = null, trackLocalId = other.localId, date = today.toString(), value = 2, dirty = true))
        val archived = meditate.copy(archivedAt = today.toString())
        rig.enqueueJson(ok(trackJson(archived)))
        rig.enqueueJson(list(archived, pages, mood))

        assertEquals(TrackChange.Done, editor.archive(10))

        assertNull(rig.db.tickDao().find(track.localId, today.toString()))
        assertTrue("other tracks' unsent ticks stay", rig.db.tickDao().find(other.localId, today.toString())!!.dirty)
    }

    // --- Errors ---

    @Test fun serverRefusal_givesTheServersMessage() = runTest {
        rig.enqueueJson(error("Maximum of 99 tracks reached"), code = 403)
        rig.enqueueJson(list())
        assertEquals(
            TrackChange.Failed("Maximum of 99 tracks reached"),
            editor.create("Water", TrackType.BOOLEAN, private = false),
        )

        rig.enqueueJson(error("Invalid track type: x"), code = 400)
        rig.enqueueJson(list())
        assertEquals(
            TrackChange.Failed("Invalid track type: x"),
            editor.create("Water", TrackType.BOOLEAN, private = false),
        )
    }

    @Test fun droppedConnection_givesTheConnectionMessage() = runTest {
        // Twice, in case OkHttp retries the request on a fresh connection.
        repeat(2) { rig.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        rig.enqueueJson(list())

        assertEquals(
            TrackChange.Failed("Couldn't save. Check your connection and try again."),
            editor.rename(10, "Breathe"),
        )
    }

    @Test fun failedReread_afterSuccessfulChange_isStillDone() = runTest {
        seed()
        rig.enqueueJson(ok(trackJson(meditate.copy(name = "Breathe"))))
        rig.enqueueStatus(500)

        assertEquals(TrackChange.Done, editor.rename(10, "Breathe"))
        // Room is stale until the next pull.
        assertEquals("Meditate", rig.db.trackDao().findByServerId(10)!!.name)
    }

    // --- Lock ---

    @Test fun change_waitsForAPullInProgress() = runBlocking {
        val pullInside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val changeEntered = AtomicBoolean(false)
        rig.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                pullInside.countDown()
                release.await()
                return MockResponse().setResponseCode(200).setBody(ok("[]"))
            }
        }

        val pull = CoroutineScope(Dispatchers.IO).async {
            rig.syncManager.pull(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-30"))
        }
        pullInside.await()
        val change = CoroutineScope(Dispatchers.IO).async {
            rig.syncManager.changeTracks { changeEntered.set(true) }
        }
        Thread.sleep(200)
        val enteredEarly = changeEntered.get()
        release.countDown()
        pull.await()
        change.await()

        assertFalse("change ran while a pull held the lock", enteredEarly)
        assertTrue(changeEntered.get())
    }

    @Test fun pull_waitsForAChangeAndItsReread() = runBlocking {
        val changeInside = CountDownLatch(1)
        val release = CountDownLatch(1)
        rig.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(200).setBody(ok("[]"))
        }

        val change = CoroutineScope(Dispatchers.IO).async {
            rig.syncManager.changeTracks {
                changeInside.countDown()
                release.await()
            }
        }
        changeInside.await()
        val pull = CoroutineScope(Dispatchers.IO).async {
            rig.syncManager.pull(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-30"))
        }
        Thread.sleep(200)
        val requestsWhileChanging = rig.server.requestCount
        release.countDown()
        change.await()
        pull.await()

        assertEquals("pull reached the server while a change held the lock", 0, requestsWhileChanging)
        // The change's re-read, then the pull's tracks and ticks.
        val paths = List(3) { take().path!! }
        assertEquals(base, paths[0])
        assertEquals(base, paths[1])
        assertTrue(paths[2].startsWith("/ocs/v2.php/apps/tickbuddy/api/ticks"))
    }
}
