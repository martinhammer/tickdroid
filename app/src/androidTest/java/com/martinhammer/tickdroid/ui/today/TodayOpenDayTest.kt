package com.martinhammer.tickdroid.ui.today

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import com.martinhammer.tickdroid.data.network.NetworkMonitor
import com.martinhammer.tickdroid.data.prefs.EditableDays
import com.martinhammer.tickdroid.data.prefs.UiPreferences
import com.martinhammer.tickdroid.data.repository.NoteRepository
import com.martinhammer.tickdroid.data.repository.TickKey
import com.martinhammer.tickdroid.data.repository.TickRepository
import com.martinhammer.tickdroid.data.repository.TrackPrefsRepository
import com.martinhammer.tickdroid.data.repository.TrackRepository
import com.martinhammer.tickdroid.data.repository.TrackRunRepository
import com.martinhammer.tickdroid.data.sync.PushStatus
import com.martinhammer.tickdroid.data.sync.ServerFeatures
import com.martinhammer.tickdroid.data.sync.SyncManager
import com.martinhammer.tickdroid.data.sync.SyncStatus
import com.martinhammer.tickdroid.data.time.Clock
import com.martinhammer.tickdroid.domain.Tick
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackRun
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.ui.theme.TickdroidTheme
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * A day sent to Today by navigation (a Journal day label, the bar's Today item) arrives when
 * Today comes back into the composition, with its pager restored on the page it was left on.
 * The restored page must not win over the day that was asked for.
 */
class TodayOpenDayTest {

    @get:Rule val rule = createComposeRule()

    private val today = LocalDate.of(2026, 10, 29)
    private val track = Track(localId = 1L, serverId = 10L, name = "Exercise", type = TrackType.YesNo, sortOrder = 0, private = false)

    private fun viewModel(): TodayViewModel {
        val clock = object : Clock {
            override fun today(): LocalDate = this@TodayOpenDayTest.today
            override fun nowMillis(): Long = 0L
        }
        val syncManager = mockk<SyncManager> {
            every { status } returns MutableStateFlow<SyncStatus>(SyncStatus.Idle)
            every { pushStatus } returns MutableStateFlow<PushStatus>(PushStatus.Idle)
            every { schedulePull(any(), any()) } just runs
        }
        return TodayViewModel(
            SavedStateHandle(),
            mockk<TrackRepository> { every { observeTracks() } returns flowOf(listOf(track)) },
            mockk<TickRepository>(relaxUnitFun = true) {
                every { observeRange(any(), any()) } returns flowOf(emptyMap<TickKey, Tick>())
                every { observeHasUnsent() } returns flowOf(false)
            },
            mockk<NoteRepository>(relaxUnitFun = true) {
                every { observeRange(any(), any()) } returns flowOf(emptyMap<TickKey, String>())
            },
            mockk<TrackRunRepository> { every { observeRuns() } returns flowOf(emptyMap<Long, TrackRun>()) },
            mockk<TrackPrefsRepository> { every { observeAll() } returns flowOf(emptyMap<Long, TrackPrefs>()) },
            syncManager,
            mockk<ServerFeatures> {
                every { notes } returns MutableStateFlow(true)
                every { stats } returns MutableStateFlow(true)
            },
            mockk<NetworkMonitor> { every { isOnline } returns MutableStateFlow(true) },
            mockk<UiPreferences> {
                every { showPrivate } returns MutableStateFlow(false)
                every { editableDays } returns MutableStateFlow(EditableDays.Default)
            },
            clock,
        )
    }

    private class Host {
        var shown by mutableStateOf(true)
        var openDay by mutableStateOf<Long?>(null)
        var fromJournal = false
    }

    /** Today as the NavHost hosts it: its saveable state kept while another screen shows. */
    private fun host(vm: TodayViewModel): Host {
        val host = Host()
        rule.setContent {
            TickdroidTheme {
                val holder = rememberSaveableStateHolder()
                if (host.shown) {
                    holder.SaveableStateProvider("today") {
                        TodayScreen(
                            openDay = host.openDay,
                            openFromJournal = host.fromJournal,
                            onOpenDayHandled = { host.openDay = null },
                            onOpenJournal = {},
                            onOpenAccount = {},
                            onOpenAppSettings = {},
                            onOpenTracksSettings = {},
                            onOpenAbout = {},
                            viewModel = vm,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        return host
    }

    /** Away to the Journal, then back with [day] as the navigation result. */
    private fun Host.comeBackWith(day: LocalDate, fromJournal: Boolean) {
        shown = false
        rule.waitForIdle()
        this.fromJournal = fromJournal
        openDay = day.toEpochDay()
        shown = true
        rule.waitForIdle()
    }

    @Test fun journalDayLabel_showsThatDay() {
        val vm = viewModel()
        val host = host(vm)
        assertEquals(today, vm.state.value.day)

        host.comeBackWith(today.minusDays(5), fromJournal = true)

        assertEquals(today.minusDays(5), vm.state.value.day)
        assertEquals(true, vm.state.value.fromJournal)
    }

    @Test fun barToday_afterLeavingTodayOnAnotherDay_showsToday() {
        val vm = viewModel()
        val host = host(vm)
        vm.showDay(today.minusDays(3))
        rule.waitForIdle()

        host.comeBackWith(today, fromJournal = false)

        assertEquals(today, vm.state.value.day)
    }
}
