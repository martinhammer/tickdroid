package com.martinhammer.tickdroid.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.martinhammer.tickdroid.data.prefs.GridDensity
import com.martinhammer.tickdroid.data.prefs.UiPreferences
import com.martinhammer.tickdroid.data.repository.TickKey
import com.martinhammer.tickdroid.data.repository.TickRepository
import com.martinhammer.tickdroid.data.repository.TrackRepository
import com.martinhammer.tickdroid.data.sync.SyncManager
import com.martinhammer.tickdroid.data.sync.SyncStatus
import com.martinhammer.tickdroid.domain.Tick
import com.martinhammer.tickdroid.domain.Track
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
    val window: DateWindow = DateWindow(LocalDate.now().minusDays(29), LocalDate.now()),
    val syncStatus: SyncStatus = SyncStatus.Idle,
    val density: GridDensity = GridDensity.Default,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class JournalViewModel @Inject constructor(
    private val trackRepository: TrackRepository,
    private val tickRepository: TickRepository,
    private val syncManager: SyncManager,
    uiPreferences: UiPreferences,
) : ViewModel() {

    private val today = LocalDate.now()
    private val _window = MutableStateFlow(DateWindow(today.minusDays(29), today))
    val window: StateFlow<DateWindow> = _window.asStateFlow()

    private val ticks = _window.flatMapLatest { tickRepository.observeRange(it.oldestVisible, it.today) }

    private val prefs = combine(uiPreferences.showPrivate, uiPreferences.gridDensity) { sp, d -> sp to d }

    val state: StateFlow<JournalUiState> = combine(
        trackRepository.observeTracks(),
        ticks,
        _window,
        syncManager.status,
        prefs,
    ) { tracks, ticks, window, sync, (showPrivate, density) ->
        val visible = if (showPrivate) tracks else tracks.filterNot { it.private }
        JournalUiState(tracks = visible, ticks = ticks, window = window, syncStatus = sync, density = density)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = JournalUiState(window = _window.value),
    )

    init {
        refresh()
    }

    fun refresh() {
        val w = _window.value
        viewModelScope.launch { syncManager.pull(w.oldestVisible, w.today) }
    }

    /** Extend the window 30 more days into the past and pull the newly visible chunk. */
    fun loadOlder() {
        val current = _window.value
        val newOldest = current.oldestVisible.minusDays(30)
        _window.value = current.copy(oldestVisible = newOldest)
        viewModelScope.launch {
            syncManager.pull(newOldest, current.oldestVisible.minusDays(1))
        }
    }
}
