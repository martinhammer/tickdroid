package com.martinhammer.tickdroid.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.martinhammer.tickdroid.data.network.NetworkMonitor
import com.martinhammer.tickdroid.data.repository.TrackPrefsRepository
import com.martinhammer.tickdroid.data.repository.TrackRepository
import com.martinhammer.tickdroid.data.sync.TrackChange
import com.martinhammer.tickdroid.data.sync.TrackEditor
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.cleanTrackName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class TrackDetailUiState(
    val track: Track? = null,
    val prefs: TrackPrefs = TrackPrefs(),
    val loaded: Boolean = false,
    /** The Private switch's position while a change is out; null when none is. */
    val pendingPrivate: Boolean? = null,
    val renaming: Boolean = false,
    val deleting: Boolean = false,
)

sealed interface TrackDetailEvent {
    /** The rename dialog closes. */
    data object RenameDone : TrackDetailEvent

    /** The screen goes back to the list. */
    data object Deleted : TrackDetailEvent

    /** The delete dialog closes and the toast shows. */
    data class DeleteFailed(val text: String) : TrackDetailEvent
    data class Toast(val text: String) : TrackDetailEvent
}

@HiltViewModel
class TrackDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    trackRepository: TrackRepository,
    private val prefsRepository: TrackPrefsRepository,
    networkMonitor: NetworkMonitor,
    private val trackEditor: TrackEditor,
) : ViewModel() {

    private val localId: Long = checkNotNull(savedStateHandle["localId"]) {
        "localId nav arg missing"
    }

    private data class Busy(
        val pendingPrivate: Boolean? = null,
        val renaming: Boolean = false,
        val deleting: Boolean = false,
        val deleted: Boolean = false,
    )

    private val busy = MutableStateFlow(Busy())

    // The last track (and its prefs) seen in Room. While this screen's own delete runs, and
    // after it until the back navigation, the screen keeps showing them: the re-read removes
    // the row (and sweeps its prefs) before the screen leaves, and must not flash "Track not
    // found." or a colourless badge.
    private var lastTrack: Track? = null
    private var lastPrefs: TrackPrefs = TrackPrefs()

    val state: StateFlow<TrackDetailUiState> = combine(
        trackRepository.observeTracks(),
        prefsRepository.observeAll(),
        busy,
    ) { tracks, prefsByServerId, busy ->
        val live = tracks.firstOrNull { it.localId == localId }
        val keepLast = live == null && (busy.deleting || busy.deleted)
        val track = if (keepLast) lastTrack else live
        val prefs = if (keepLast) lastPrefs else live?.serverId?.let { prefsByServerId[it] } ?: TrackPrefs()
        if (live != null) {
            lastTrack = live
            lastPrefs = prefs
        }
        TrackDetailUiState(
            track = track,
            prefs = prefs,
            loaded = true,
            pendingPrivate = busy.pendingPrivate,
            renaming = busy.renaming,
            deleting = busy.deleting || busy.deleted,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TrackDetailUiState(),
    )

    val online: StateFlow<Boolean> = networkMonitor.isOnline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = true)

    private val _events = Channel<TrackDetailEvent>(Channel.BUFFERED)
    val events: Flow<TrackDetailEvent> = _events.receiveAsFlow()

    fun setColorKey(colorKey: String?) {
        val serverId = state.value.track?.serverId ?: return
        viewModelScope.launch { prefsRepository.setColorKey(serverId, colorKey) }
    }

    fun setEmoji(emoji: String?) {
        val serverId = state.value.track?.serverId ?: return
        val cleaned = emoji?.takeIf { it.isNotBlank() }
        viewModelScope.launch { prefsRepository.setEmoji(serverId, cleaned) }
    }

    fun rename(name: String) {
        val track = state.value.track ?: return
        val serverId = track.serverId ?: return
        val clean = cleanTrackName(name) ?: return
        if (busy.value.renaming) return
        if (clean == track.name) {
            // Unchanged after trimming: close without a server call, as the web does.
            _events.trySend(TrackDetailEvent.RenameDone)
            return
        }
        if (!online.value) return
        busy.update { it.copy(renaming = true) }
        viewModelScope.launch {
            when (val result = trackEditor.rename(serverId, clean)) {
                is TrackChange.Failed -> _events.send(TrackDetailEvent.Toast(result.message))
                TrackChange.SignedOut -> Unit
                else -> {
                    awaitRoom { it.track?.name == clean }
                    _events.send(TrackDetailEvent.RenameDone)
                }
            }
            busy.update { it.copy(renaming = false) }
        }
    }

    fun setPrivate(private: Boolean) {
        val serverId = state.value.track?.serverId ?: return
        if (busy.value.pendingPrivate != null || !online.value) return
        busy.update { it.copy(pendingPrivate = private) }
        viewModelScope.launch {
            when (val result = trackEditor.setPrivate(serverId, private)) {
                is TrackChange.Failed -> _events.send(TrackDetailEvent.Toast(result.message))
                TrackChange.SignedOut -> Unit
                else -> awaitRoom { it.track?.private == private }
            }
            // On failure the switch moves back, since Room still has the old value.
            busy.update { it.copy(pendingPrivate = null) }
        }
    }

    fun delete() {
        val serverId = state.value.track?.serverId ?: return
        if (busy.value.deleting || !online.value) return
        busy.update { it.copy(deleting = true) }
        viewModelScope.launch {
            when (val result = trackEditor.delete(serverId)) {
                is TrackChange.Failed -> {
                    busy.update { it.copy(deleting = false) }
                    _events.send(TrackDetailEvent.DeleteFailed(result.message))
                }
                TrackChange.SignedOut -> busy.update { it.copy(deleting = false) }
                else -> {
                    busy.update { it.copy(deleting = false, deleted = true) }
                    _events.send(TrackDetailEvent.Deleted)
                }
            }
        }
    }

    /**
     * After a successful change, waits (briefly) until Room's re-read has reached [state], so a
     * control doesn't flick back to the old value for a frame. The re-read itself already ran
     * before the change returned; only the Flow emission can lag.
     */
    private suspend fun awaitRoom(caughtUp: (TrackDetailUiState) -> Boolean) {
        withTimeoutOrNull(ROOM_CATCH_UP_MS) { state.first(caughtUp) }
    }

    private companion object {
        const val ROOM_CATCH_UP_MS = 1_000L
    }
}
