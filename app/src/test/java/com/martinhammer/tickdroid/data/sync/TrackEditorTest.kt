package com.martinhammer.tickdroid.data.sync

import com.martinhammer.tickdroid.data.remote.OcsBody
import com.martinhammer.tickdroid.data.remote.OcsEnvelope
import com.martinhammer.tickdroid.data.remote.OcsMeta
import com.martinhammer.tickdroid.data.remote.TickbuddyApi
import com.martinhammer.tickdroid.data.remote.dto.TrackDto
import com.martinhammer.tickdroid.domain.TrackType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class TrackEditorTest {

    private val api = mockk<TickbuddyApi>()
    private val syncManager = mockk<SyncManager>()
    private val editor = TrackEditor(syncManager, Json { ignoreUnknownKeys = true })

    @Before fun setUp() {
        // Run each change's block straight against the mocked API, as changeTracks does.
        coEvery { syncManager.changeTracks<TrackChange>(any()) } coAnswers {
            firstArg<suspend (TickbuddyApi) -> TrackChange>().invoke(api)
        }
    }

    private fun <T> ok(data: T) = OcsEnvelope(OcsBody(OcsMeta("ok", 200), data))

    private fun dto(id: Long, sortOrder: Int = 1, private: Boolean = false) =
        TrackDto(id = id, name = "T$id", type = "boolean", sortOrder = sortOrder, private = private)

    private fun httpError(code: Int, body: String = ""): HttpException =
        HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    private fun ocsError(message: String) =
        """{"ocs":{"meta":{"status":"failure","statuscode":400,"message":""},"data":{"message":"$message"}}}"""

    // --- Error table (§5.4) ---

    @Test fun `SSLException maps to the certificate message, before IOException`() = runTest {
        coEvery { api.updateTrack(1, name = "New") } throws SSLHandshakeException("PKIX path building failed")
        assertEquals(
            TrackChange.Failed("Couldn't save. The server's certificate isn't trusted."),
            editor.rename(1, "New"),
        )
    }

    @Test fun `IOException maps to the connection message`() = runTest {
        coEvery { api.updateTrack(1, name = "New") } throws SocketTimeoutException("timeout")
        assertEquals(
            TrackChange.Failed("Couldn't save. Check your connection and try again."),
            editor.rename(1, "New"),
        )
    }

    @Test fun `401 maps to SignedOut`() = runTest {
        coEvery { api.updateTrack(1, isPrivate = true) } throws httpError(401)
        assertEquals(TrackChange.SignedOut, editor.setPrivate(1, true))
    }

    @Test fun `server message is shown as is`() = runTest {
        coEvery { api.createTrack("Water", "boolean") } throws httpError(403, ocsError("Maximum of 99 tracks reached"))
        assertEquals(
            TrackChange.Failed("Maximum of 99 tracks reached"),
            editor.create("Water", TrackType.BOOLEAN, private = false),
        )
    }

    @Test fun `HTTP error without a message shows the status code`() = runTest {
        coEvery { api.updateTrack(1, name = "New") } throws httpError(500, "<html>oops</html>")
        assertEquals(
            TrackChange.Failed("Couldn't save. Tickbuddy returned an error (HTTP 500)."),
            editor.rename(1, "New"),
        )
    }

    @Test fun `404 on rename shows the server's message`() = runTest {
        coEvery { api.updateTrack(1, name = "New") } throws httpError(404, ocsError("Track not found"))
        assertEquals(TrackChange.Failed("Track not found"), editor.rename(1, "New"))
    }

    // --- Create ---

    @Test fun `create sends the trimmed name and wire type`() = runTest {
        coEvery { api.createTrack("Coffee", "counter") } returns ok(dto(7))
        assertEquals(TrackChange.Created(7), editor.create("  Coffee ", TrackType.COUNTER, private = false))
        coVerify(exactly = 0) { api.updateTrack(any(), any(), any()) }
    }

    @Test fun `create with private makes a second call`() = runTest {
        coEvery { api.createTrack("Coffee", "boolean") } returns ok(dto(7))
        coEvery { api.updateTrack(7, isPrivate = true) } returns ok(dto(7, private = true))
        assertEquals(TrackChange.Created(7), editor.create("Coffee", TrackType.BOOLEAN, private = true))
        coVerify { api.updateTrack(7, null, true) }
    }

    @Test fun `create with private and a failing second call gives CreatedNotPrivate`() = runTest {
        coEvery { api.createTrack("Coffee", "boolean") } returns ok(dto(7))
        coEvery { api.updateTrack(7, isPrivate = true) } throws IOException("reset")
        assertEquals(
            TrackChange.CreatedNotPrivate(7),
            editor.create("Coffee", TrackType.BOOLEAN, private = true),
        )
    }

    @Test fun `blank name never reaches the server`() = runTest {
        assertEquals(TrackChange.Failed("Track name cannot be empty"), editor.create("  ", TrackType.BOOLEAN, false))
        assertEquals(TrackChange.Failed("Track name cannot be empty"), editor.rename(1, ""))
        coVerify(exactly = 0) { syncManager.changeTracks<TrackChange>(any()) }
    }

    // --- Delete ---

    @Test fun `delete 204 is Done`() = runTest {
        coEvery { api.deleteTrack(3) } returns Response.success(204, Unit)
        assertEquals(TrackChange.Done, editor.delete(3))
    }

    @Test fun `delete 404 counts as Done`() = runTest {
        coEvery { api.deleteTrack(3) } returns Response.error(404, "".toResponseBody())
        assertEquals(TrackChange.Done, editor.delete(3))
    }

    @Test fun `delete 500 is Failed`() = runTest {
        coEvery { api.deleteTrack(3) } returns Response.error(500, "".toResponseBody())
        assertEquals(
            TrackChange.Failed("Couldn't save. Tickbuddy returned an error (HTTP 500)."),
            editor.delete(3),
        )
    }

    // --- Reorder ---

    @Test fun `reorder sends mergeOrder's list`() = runTest {
        // Server has 1, 2, 3 plus 4 added elsewhere; the phone still knows 5, deleted elsewhere.
        coEvery { api.getTracks() } returns ok(listOf(dto(2, 2), dto(1, 1), dto(4, 4), dto(3, 3)))
        coEvery { api.reorderTracks(any()) } returns ok(emptyList())
        assertEquals(TrackChange.Done, editor.reorder(listOf(3, 5, 1, 2)))
        coVerify { api.reorderTracks(listOf(3L, 1L, 2L, 4L)) }
    }
}
