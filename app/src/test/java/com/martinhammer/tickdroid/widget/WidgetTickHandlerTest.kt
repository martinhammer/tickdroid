package com.martinhammer.tickdroid.widget

import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.auth.AuthState
import com.martinhammer.tickdroid.data.auth.Credentials
import com.martinhammer.tickdroid.data.repository.TickRepository
import com.martinhammer.tickdroid.data.repository.TrackRepository
import com.martinhammer.tickdroid.data.time.Clock
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class WidgetTickHandlerTest {

    private val today = LocalDate.of(2026, 10, 5)
    private val authState = MutableStateFlow<AuthState>(
        AuthState.SignedIn(Credentials("https://cloud.example.com", "alice", "pw")),
    )
    private val authRepository = mockk<AuthRepository> { every { state } returns authState }
    private val trackRepository = mockk<TrackRepository>()
    private val tickRepository = mockk<TickRepository>(relaxed = true)
    private val clock = object : Clock {
        override fun today() = today
        override fun nowMillis() = 0L
    }
    private val handler = WidgetTickHandler(trackRepository, tickRepository, authRepository, clock)

    private val yesNo = Track(localId = 1, serverId = 101, name = "Exercise", type = TrackType.BOOLEAN, sortOrder = 0, private = false)
    private val counter = Track(localId = 2, serverId = 102, name = "Coffee", type = TrackType.COUNTER, sortOrder = 1, private = false)

    init {
        coEvery { trackRepository.findByServerId(any()) } returns null
        coEvery { trackRepository.findByServerId(101) } returns yesNo
        coEvery { trackRepository.findByServerId(102) } returns counter
    }

    private fun assertNoWrite() {
        coVerify(exactly = 0) { tickRepository.toggleBoolean(any(), any()) }
        coVerify(exactly = 0) { tickRepository.adjustCounter(any(), any(), any()) }
    }

    @Test fun `signed out writes nothing`() = runTest {
        authState.value = AuthState.SignedOut
        assertEquals(WidgetTickHandler.Outcome.SignedOut, handler.onTap(101, today, null))
        assertNoWrite()
    }

    @Test fun `a widget drawn for yesterday is refused`() = runTest {
        assertEquals(WidgetTickHandler.Outcome.StaleDay, handler.onTap(101, today.minusDays(1), null))
        assertNoWrite()
    }

    @Test fun `a widget a day ahead (after flying west) is refused`() = runTest {
        assertEquals(WidgetTickHandler.Outcome.StaleDay, handler.onTap(102, today.plusDays(1), 1))
        assertNoWrite()
    }

    @Test fun `an unknown server id is TrackGone`() = runTest {
        assertEquals(WidgetTickHandler.Outcome.TrackGone, handler.onTap(999, today, null))
        assertNoWrite()
    }

    @Test fun `a track made private is TrackGone`() = runTest {
        coEvery { trackRepository.findByServerId(101) } returns yesNo.copy(private = true)
        assertEquals(WidgetTickHandler.Outcome.TrackGone, handler.onTap(101, today, null))
        assertNoWrite()
    }

    @Test fun `yes-no with no delta toggles today`() = runTest {
        assertEquals(WidgetTickHandler.Outcome.Written, handler.onTap(101, today, null))
        coVerify(exactly = 1) { tickRepository.toggleBoolean(1, today) }
    }

    @Test fun `counter adjusts by the delta`() = runTest {
        assertEquals(WidgetTickHandler.Outcome.Written, handler.onTap(102, today, 1))
        assertEquals(WidgetTickHandler.Outcome.Written, handler.onTap(102, today, -1))
        coVerify(exactly = 1) { tickRepository.adjustCounter(2, today, 1) }
        coVerify(exactly = 1) { tickRepository.adjustCounter(2, today, -1) }
    }

    @Test fun `a type mismatch is Ignored and writes nothing`() = runTest {
        assertEquals(WidgetTickHandler.Outcome.Ignored, handler.onTap(101, today, 1))
        assertEquals(WidgetTickHandler.Outcome.Ignored, handler.onTap(102, today, null))
        assertNoWrite()
    }
}
