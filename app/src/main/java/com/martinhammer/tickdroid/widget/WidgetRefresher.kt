package com.martinhammer.tickdroid.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.auth.AuthState
import com.martinhammer.tickdroid.data.network.NetworkMonitor
import com.martinhammer.tickdroid.data.repository.TickRepository
import com.martinhammer.tickdroid.data.repository.TrackPrefsRepository
import com.martinhammer.tickdroid.data.repository.TrackRepository
import com.martinhammer.tickdroid.data.sync.PushStatus
import com.martinhammer.tickdroid.data.sync.SyncManager
import com.martinhammer.tickdroid.data.sync.SyncStatus
import com.martinhammer.tickdroid.data.time.Clock
import com.martinhammer.tickdroid.ui.journal.SyncIssue
import com.martinhammer.tickdroid.ui.journal.computeSyncIssue
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single redraw hook for every widget. Watches everything a widget draws from (auth, tracks,
 * colour/emoji overrides, today's ticks, sync state, widget choices) and redraws all widgets when
 * any of it changes. That one observer covers journal taps, widget taps, PushWorker's
 * push-then-pull, pull-to-refresh, a track made private on the web and the sign-out wipe, with no
 * change to the sync layer.
 *
 * Application work on its own scope, like SyncManager's: started from TickdroidApplication.
 */
@Singleton
class WidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val trackRepository: TrackRepository,
    private val trackPrefsRepository: TrackPrefsRepository,
    private val tickRepository: TickRepository,
    private val syncManager: SyncManager,
    private val networkMonitor: NetworkMonitor,
    private val configStore: WidgetConfigStore,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    private val today = MutableStateFlow(clock.today())

    private val _inputs = MutableStateFlow<WidgetInputs?>(null)

    /**
     * The latest inputs, null until the first emission. Widgets collect this inside their
     * composition rather than loading a snapshot once: while a Glance session is still alive
     * (up to ~45 s after its last update), update() only recomposes it and does not re-run
     * provideGlance, so a snapshot would keep drawing the pre-tap state.
     *
     * That collection only reaches widgets whose session is still open. The updateAll collector
     * in [start] is the other half and is not redundant: a widget whose session has closed has
     * no collector left, and only an update() starts a new session to redraw it.
     */
    internal val inputs: StateFlow<WidgetInputs?> = _inputs.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch { WidgetRollover.syncAlarm(context) }
        scope.launch {
            val badge = combine(
                syncManager.status,
                syncManager.pushStatus,
                networkMonitor.isOnline,
                tickRepository.observeHasDirty(),
            ) { pull, push, online, dirty ->
                SyncBadge(
                    syncing = pull is SyncStatus.Syncing || push is PushStatus.Pushing,
                    // Same rule as the journal chip's "…, unsaved changes", so the two always
                    // agree. A tap while online leaves a dirty row for the second the push takes,
                    // but the issue is None then, so the symbol doesn't flash.
                    offlinePending = dirty && computeSyncIssue(online, pull, push, dirty) != SyncIssue.None,
                )
            }
            val todayTicks = today.flatMapLatest { day ->
                tickRepository.observeRange(day, day).map { day to it }
            }
            combine(
                authRepository.state.map { it is AuthState.SignedIn },
                trackRepository.observeTracks(),
                trackPrefsRepository.observeAll(),
                todayTicks,
                combine(badge, configStore.revision) { b, r -> b to r },
            ) { signedIn, tracks, prefs, (day, ticks), (badgeValue, revision) ->
                WidgetInputs(signedIn, day, tracks, prefs, ticks, badgeValue, revision)
            }
                // A StateFlow drops equal values: a pull rewrites unchanged rows and Room
                // re-emits, and equal data must not redraw.
                .collect { _inputs.value = it }
        }
        scope.launch {
            // Every change redraws every widget. Keep this even though open sessions also collect
            // [inputs]: a widget whose session has closed (most of the time) only redraws when
            // update() starts a new one; for an open session it is a cheap recompose. The first
            // emission after process start also heals a widget left stale while the process was
            // dead (PushWorker starts it every 15 minutes).
            _inputs.filterNotNull().debounce(250).collect { refreshNow() }
        }
    }

    /** Re-read "today": midnight, a clock or time-zone change, or a tap on a stale widget. */
    fun onDayChanged() {
        today.value = clock.today()
    }

    suspend fun refreshNow() = updateAllWidgets(context)
}

/** Redraw every placed widget of every kind. Does nothing when none are placed. */
internal suspend fun updateAllWidgets(context: Context) {
    try {
        SingleTrackWidget().updateAll(context)
        TodayListWidget().updateAll(context)
        TodayRowWidget().updateAll(context)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A failed redraw leaves the old picture up; the next change tries again.
        Log.w("WidgetRefresher", "widget update failed", e)
    }
}
