package com.martinhammer.tickdroid.ui.today

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.martinhammer.tickdroid.data.network.NetworkMonitor
import com.martinhammer.tickdroid.data.prefs.EditableDays
import com.martinhammer.tickdroid.data.prefs.UiPreferences
import com.martinhammer.tickdroid.data.repository.NoteRepository
import com.martinhammer.tickdroid.data.repository.TickKey
import com.martinhammer.tickdroid.data.repository.TickRepository
import com.martinhammer.tickdroid.data.repository.TrackPrefsRepository
import com.martinhammer.tickdroid.data.repository.TrackRepository
import com.martinhammer.tickdroid.data.repository.TrackRunRepository
import com.martinhammer.tickdroid.data.sync.ServerFeatures
import com.martinhammer.tickdroid.data.sync.SyncManager
import com.martinhammer.tickdroid.data.sync.SyncStatus
import com.martinhammer.tickdroid.data.time.Clock
import com.martinhammer.tickdroid.domain.Tick
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackRun
import com.martinhammer.tickdroid.ui.journal.SyncIssue
import com.martinhammer.tickdroid.ui.journal.computeSyncIssue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** The note being written: a track and a day. */
data class NoteTarget(val trackLocalId: Long, val date: LocalDate)

data class TodayUiState(
    val tracks: List<Track> = emptyList(),
    val hasHiddenPrivateTracks: Boolean = false,
    val trackPrefs: Map<Long, TrackPrefs> = emptyMap(),
    /** Ticks of the loaded window (see [TodayViewModel.ensureLoaded]). */
    val ticks: Map<TickKey, Tick> = emptyMap(),
    /** Notes of the loaded window; empty without the server's `notes` flag. */
    val notes: Map<TickKey, String> = emptyMap(),
    /** By track local id; empty without the server's `stats` flag. */
    val runs: Map<Long, TrackRun> = emptyMap(),
    val editableDays: EditableDays = EditableDays.Default,
    val today: LocalDate = LocalDate.MIN,
    /** The day shown. */
    val day: LocalDate = LocalDate.MIN,
    val syncIssue: SyncIssue = SyncIssue.None,
    val syncStatus: SyncStatus = SyncStatus.Idle,
    val notesSupported: Boolean = false,
    val statsSupported: Boolean = false,
    /** A day label in the Journal opened this day: Back returns to the Journal. */
    val fromJournal: Boolean = false,
    val editing: NoteTarget? = null,
    val loaded: Boolean = false,
) {
    fun isEditable(date: LocalDate): Boolean = editableDays.isEditable(date, today)
}

/**
 * Today: one day's tracks, ticks, squares, streaks and notes. Mirrors `JournalViewModel`: the
 * same sources, the same writes, pulls through [SyncManager.schedulePull] (never
 * `viewModelScope`), and today recomputed on every [refresh].
 *
 * The day shown, whether a Journal day label opened it, and the note being written live in the
 * [SavedStateHandle], so they survive rotation and Android restoring the app; a fresh start shows
 * today.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    trackRepository: TrackRepository,
    private val tickRepository: TickRepository,
    private val noteRepository: NoteRepository,
    trackRunRepository: TrackRunRepository,
    trackPrefsRepository: TrackPrefsRepository,
    private val syncManager: SyncManager,
    private val serverFeatures: ServerFeatures,
    networkMonitor: NetworkMonitor,
    private val uiPreferences: UiPreferences,
    private val clock: Clock,
) : ViewModel() {

    private val _today = MutableStateFlow(clock.today())

    private val _day = MutableStateFlow(
        savedStateHandle.get<Long>(KEY_DAY)?.let(LocalDate::ofEpochDay)?.coerceAtMost(_today.value) ?: _today.value,
    )

    private val _fromJournal = MutableStateFlow(savedStateHandle.get<Boolean>(KEY_FROM_JOURNAL) ?: false)

    private val _editing = MutableStateFlow(
        savedStateHandle.get<Long>(KEY_EDIT_TRACK)?.let { track ->
            savedStateHandle.get<Long>(KEY_EDIT_DAY)?.let { NoteTarget(track, LocalDate.ofEpochDay(it)) }
        },
    )

    /** The oldest day whose ticks and notes are loaded; the window always ends today. */
    private val _oldestLoaded = MutableStateFlow(_today.value.minusDays(WINDOW_DAYS - 1))

    private val window = combine(_oldestLoaded, _today) { oldest, today -> oldest to today }

    private val ticks = window.flatMapLatest { (from, to) -> tickRepository.observeRange(from, to) }

    private val notes = serverFeatures.notes.flatMapLatest { supported ->
        if (supported) window.flatMapLatest { (from, to) -> noteRepository.observeRange(from, to) } else flowOf(emptyMap())
    }

    private val runs = combine(serverFeatures.stats, trackRunRepository.observeRuns()) { supported, runs ->
        if (supported) runs else emptyMap()
    }

    private data class Content(
        val tracks: List<Track>,
        val ticks: Map<TickKey, Tick>,
        val notes: Map<TickKey, String>,
        val runs: Map<Long, TrackRun>,
        val trackPrefs: Map<Long, TrackPrefs>,
    )

    private val content = combine(
        trackRepository.observeTracks(),
        ticks,
        notes,
        runs,
        trackPrefsRepository.observeAll(),
    ) { tracks, ticks, notes, runs, prefs -> Content(tracks, ticks, notes, runs, prefs) }

    private data class Settings(
        val showPrivate: Boolean,
        val editableDays: EditableDays,
        val notesSupported: Boolean,
        val statsSupported: Boolean,
    )

    private val settings = combine(
        uiPreferences.showPrivate,
        uiPreferences.editableDays,
        serverFeatures.notes,
        serverFeatures.stats,
    ) { showPrivate, editable, notes, stats -> Settings(showPrivate, editable, notes, stats) }

    // The journal's four inputs, so the chip and the widgets' symbol agree on both screens.
    private val sync = combine(
        syncManager.status,
        syncManager.pushStatus,
        networkMonitor.isOnline,
        tickRepository.observeHasUnsent(),
    ) { pull, push, online, unsent -> pull to computeSyncIssue(online, pull, push, unsent) }

    private data class Position(
        val today: LocalDate,
        val day: LocalDate,
        val fromJournal: Boolean,
        val editing: NoteTarget?,
    )

    private val position = combine(_today, _day, _fromJournal, _editing) { today, day, fromJournal, editing ->
        Position(today, day, fromJournal, editing)
    }

    val state: StateFlow<TodayUiState> = combine(content, settings, sync, position) { c, s, (pull, issue), p ->
        TodayUiState(
            tracks = if (s.showPrivate) c.tracks else c.tracks.filterNot { it.private },
            hasHiddenPrivateTracks = !s.showPrivate && c.tracks.any { it.private },
            trackPrefs = c.trackPrefs,
            ticks = c.ticks,
            notes = c.notes,
            runs = c.runs,
            editableDays = s.editableDays,
            today = p.today,
            day = p.day,
            syncIssue = issue,
            syncStatus = pull,
            notesSupported = s.notesSupported,
            statsSupported = s.statsSupported,
            fromJournal = p.fromJournal,
            editing = p.editing,
            loaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TodayUiState(today = _today.value, day = _day.value, editing = _editing.value),
    )

    init {
        // A restored day may be further back than the first window; refresh pulls all of it.
        extendWindow(_day.value)
        refresh()
    }

    /**
     * Re-checks the wall clock and pulls the loaded days. After midnight, a Today showing the old
     * today moves to the new day; one showing any other day stays on it.
     */
    fun refresh() {
        val oldToday = _today.value
        val newToday = clock.today()
        _today.value = newToday
        if (newToday != oldToday && _day.value == oldToday) setDay(newToday)
        // Not viewModelScope: see SyncManager.schedulePull.
        syncManager.schedulePull(_oldestLoaded.value, newToday)
    }

    /** Shows [date] (never after today) and loads what its squares need. */
    fun showDay(date: LocalDate) {
        val day = date.coerceAtMost(_today.value)
        if (day != _day.value) setDay(day)
        ensureLoaded(day)
    }

    fun previousDay() = showDay(_day.value.minusDays(1))

    fun nextDay() = showDay(_day.value.plusDays(1))

    fun showToday() = showDay(_today.value)

    /**
     * Opened from navigation: the bar's Today item ([fromJournal] false) or a Journal day label
     * (true), which makes Back return to the Journal.
     */
    fun openFromNavigation(date: LocalDate, fromJournal: Boolean) {
        setFromJournal(fromJournal)
        showDay(date)
    }

    /** The Back-to-Journal exception ends: Back was used, or a bar item tapped. */
    fun clearFromJournal() = setFromJournal(false)

    /**
     * Loads, if needed, the days [date]'s squares need and the day before it (so a swipe back
     * lands on a loaded page): moves the window's start to 29 days before the oldest of them and
     * pulls only the new part.
     */
    fun ensureLoaded(date: LocalDate) {
        val oldest = _oldestLoaded.value
        val newOldest = extendWindow(date) ?: return
        syncManager.schedulePull(newOldest, oldest.minusDays(1))
    }

    /** Moves the window's start back for [date] if needed; the new start, or null if unchanged. */
    private fun extendWindow(date: LocalDate): LocalDate? {
        val needed = squareWindow(_today.value, date.minusDays(1)).last()
        if (!needed.isBefore(_oldestLoaded.value)) return null
        return needed.minusDays(WINDOW_DAYS - 1).also { _oldestLoaded.value = it }
    }

    fun toggleBoolean(trackLocalId: Long, date: LocalDate) {
        viewModelScope.launch { tickRepository.toggleBoolean(trackLocalId, date) }
    }

    fun adjustCounter(trackLocalId: Long, date: LocalDate, delta: Int) {
        viewModelScope.launch { tickRepository.adjustCounter(trackLocalId, date, delta) }
    }

    /** Opens the note card for [trackLocalId] on the day shown: only on an editable day, with `notes`. */
    fun openNote(trackLocalId: Long) {
        val day = _day.value
        if (!serverFeatures.notes.value) return
        if (!uiPreferences.editableDays.value.isEditable(day, _today.value)) return
        setEditing(NoteTarget(trackLocalId, day))
    }

    fun closeNote() = setEditing(null)

    /** The repository is uncancellable and saves only a change, so leaving mid-save is safe. */
    fun saveNote(trackLocalId: Long, date: LocalDate, text: String) {
        viewModelScope.launch { noteRepository.saveNote(trackLocalId, date, text) }
    }

    private fun setDay(day: LocalDate) {
        _day.value = day
        savedStateHandle[KEY_DAY] = day.toEpochDay()
    }

    private fun setFromJournal(value: Boolean) {
        _fromJournal.value = value
        savedStateHandle[KEY_FROM_JOURNAL] = value
    }

    private fun setEditing(target: NoteTarget?) {
        _editing.value = target
        savedStateHandle[KEY_EDIT_TRACK] = target?.trackLocalId
        savedStateHandle[KEY_EDIT_DAY] = target?.date?.toEpochDay()
    }

    private companion object {
        const val WINDOW_DAYS = 30L
        const val KEY_DAY = "day"
        const val KEY_FROM_JOURNAL = "fromJournal"
        const val KEY_EDIT_TRACK = "editTrack"
        const val KEY_EDIT_DAY = "editDay"
    }
}

private fun LocalDate.coerceAtMost(max: LocalDate): LocalDate = if (isAfter(max)) max else this
