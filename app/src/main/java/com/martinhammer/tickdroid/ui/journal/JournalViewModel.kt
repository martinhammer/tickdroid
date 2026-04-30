package com.martinhammer.tickdroid.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.martinhammer.tickdroid.data.prefs.EditableDays
import com.martinhammer.tickdroid.data.prefs.GridDensity
import com.martinhammer.tickdroid.data.network.NetworkMonitor
import com.martinhammer.tickdroid.data.prefs.UiPreferences
import com.martinhammer.tickdroid.data.repository.TickKey
import com.martinhammer.tickdroid.data.repository.TickRepository
import com.martinhammer.tickdroid.data.repository.TrackPrefsRepository
import com.martinhammer.tickdroid.data.repository.TrackRepository
import com.martinhammer.tickdroid.data.sync.PushStatus
import com.martinhammer.tickdroid.data.sync.SyncErrorKind
import com.martinhammer.tickdroid.data.sync.SyncManager
import com.martinhammer.tickdroid.data.sync.SyncStatus
import com.martinhammer.tickdroid.data.time.Clock
import com.martinhammer.tickdroid.domain.Tick
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Visible date window. Newest day is always today; [oldestVisible] expands as the user
 * scrolls backwards. Pulls happen for the entire window.
 */
data class DateWindow(val oldestVisible: LocalDate, val today: LocalDate)

data class JournalUiState(
    val tracks: List<Track> = emptyList(),
    val ticks: Map<TickKey, Tick> = emptyMap(),
    val window: DateWindow = DateWindow(LocalDate.MIN, LocalDate.MIN),
    val syncStatus: SyncStatus = SyncStatus.Idle,
    val pushStatus: PushStatus = PushStatus.Idle,
    val syncIssue: SyncIssue = SyncIssue.None,
    val density: GridDensity = GridDensity.Default,
    val loaded: Boolean = false,
    val hasHiddenPrivateTracks: Boolean = false,
    val trackPrefs: Map<Long, TrackPrefs> = emptyMap(),
    val editableDays: EditableDays = EditableDays.Default,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class JournalViewModel @Inject constructor(
    private val trackRepository: TrackRepository,
    private val tickRepository: TickRepository,
    private val syncManager: SyncManager,
    networkMonitor: NetworkMonitor,
    uiPreferences: UiPreferences,
    trackPrefsRepository: TrackPrefsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val initialToday = clock.today()
    private val _today = MutableStateFlow(initialToday)
    private val _oldestVisible = MutableStateFlow(initialToday.minusDays(29))

    /** Combined window. Recomposes whenever today rolls over or the user scrolls older. */
    private val windowFlow = combine(_today, _oldestVisible) { today, oldest ->
        DateWindow(oldestVisible = oldest, today = today)
    }

    private val ticks = windowFlow.flatMapLatest { tickRepository.observeRange(it.oldestVisible, it.today) }

    private data class PrefsBundle(
        val showPrivate: Boolean,
        val density: GridDensity,
        val trackPrefs: Map<Long, TrackPrefs>,
        val editableDays: EditableDays,
    )

    private val prefs = combine(
        uiPreferences.showPrivate,
        uiPreferences.gridDensity,
        trackPrefsRepository.observeAll(),
        uiPreferences.editableDays,
    ) { sp, d, tp, ed -> PrefsBundle(sp, d, tp, ed) }

    private data class SyncBundle(
        val pull: SyncStatus,
        val push: PushStatus,
        val isOnline: Boolean,
        val hasUnsaved: Boolean,
    )

    private val syncBundle = combine(
        syncManager.status,
        syncManager.pushStatus,
        networkMonitor.isOnline,
        tickRepository.observeHasDirty(),
    ) { pull, push, online, dirty -> SyncBundle(pull, push, online, dirty) }

    val state: StateFlow<JournalUiState> = combine(
        trackRepository.observeTracks(),
        ticks,
        windowFlow,
        syncBundle,
        prefs,
    ) { tracks, ticks, window, sync, prefsBundle ->
        val visible = if (prefsBundle.showPrivate) tracks else tracks.filterNot { it.private }
        JournalUiState(
            tracks = visible,
            ticks = ticks,
            window = window,
            syncStatus = sync.pull,
            pushStatus = sync.push,
            syncIssue = computeSyncIssue(sync.isOnline, sync.pull, sync.push, sync.hasUnsaved),
            density = prefsBundle.density,
            loaded = true,
            hasHiddenPrivateTracks = !prefsBundle.showPrivate && tracks.any { it.private },
            trackPrefs = prefsBundle.trackPrefs,
            editableDays = prefsBundle.editableDays,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = JournalUiState(window = DateWindow(_oldestVisible.value, _today.value)),
    )

    init {
        refresh()
    }

    /** Re-checks the wall clock (for midnight rollover) and pulls the visible window. */
    fun refresh() {
        _today.value = clock.today()
        val oldest = _oldestVisible.value
        val today = _today.value
        viewModelScope.launch { syncManager.pull(oldest, today) }
    }

    /** Extend the window 30 more days into the past and pull the newly visible chunk. */
    fun loadOlder() {
        val previousOldest = _oldestVisible.value
        val newOldest = previousOldest.minusDays(30)
        _oldestVisible.value = newOldest
        viewModelScope.launch {
            syncManager.pull(newOldest, previousOldest.minusDays(1))
        }
    }

    fun toggleBoolean(trackLocalId: Long, date: LocalDate) {
        viewModelScope.launch { tickRepository.toggleBoolean(trackLocalId, date) }
    }

    fun adjustCounter(trackLocalId: Long, date: LocalDate, delta: Int) {
        viewModelScope.launch { tickRepository.adjustCounter(trackLocalId, date, delta) }
    }
}
