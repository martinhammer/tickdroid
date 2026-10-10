package com.martinhammer.tickdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.martinhammer.tickdroid.data.network.NetworkMonitor
import com.martinhammer.tickdroid.data.repository.TrackPrefsRepository
import com.martinhammer.tickdroid.data.repository.TrackRepository
import com.martinhammer.tickdroid.data.sync.TrackChange
import com.martinhammer.tickdroid.data.sync.TrackEditor
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackType
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class TrackRowState(
    val track: Track,
    val prefs: TrackPrefs,
)

data class TracksSettingsUiState(
    val rows: List<TrackRowState> = emptyList(),
    val loaded: Boolean = false,
    /** A create is on its way: the sheet's "Add track" shows a spinner. */
    val adding: Boolean = false,
)

sealed interface TracksSettingsEvent {
    /** The sheet closes and the list scrolls to the new track. */
    data class Created(val serverId: Long) : TracksSettingsEvent
    data class Toast(val text: String) : TracksSettingsEvent
}

@HiltViewModel
class TracksSettingsViewModel @Inject constructor(
    trackRepository: TrackRepository,
    prefsRepository: TrackPrefsRepository,
    networkMonitor: NetworkMonitor,
    private val trackEditor: TrackEditor,
) : ViewModel() {

    /** Room's rows, in Room's (i.e. the server's last known) order. */
    private val roomRows: StateFlow<List<TrackRowState>?> = combine(
        trackRepository.observeTracks(),
        prefsRepository.observeAll(),
    ) { tracks, prefsByServerId ->
        tracks.map { track ->
            val prefs = track.serverId?.let { prefsByServerId[it] } ?: TrackPrefs()
            TrackRowState(track = track, prefs = prefs)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)

    /**
     * The order the user dragged to (local ids), shown in place of Room's until the server has
     * confirmed it and Room has caught up. Null when nothing is pending.
     */
    private val pendingOrder = MutableStateFlow<List<Long>?>(null)
    private var reordersInFlight = 0

    private val adding = MutableStateFlow(false)

    val state: StateFlow<TracksSettingsUiState> = combine(roomRows, pendingOrder, adding) { rows, pending, adding ->
        TracksSettingsUiState(
            rows = applyOrder(rows.orEmpty(), pending),
            loaded = rows != null,
            adding = adding,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TracksSettingsUiState(),
    )

    val online: StateFlow<Boolean> = networkMonitor.isOnline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = true)

    private val _events = Channel<TracksSettingsEvent>(Channel.BUFFERED)
    val events: Flow<TracksSettingsEvent> = _events.receiveAsFlow()

    fun create(name: String, type: TrackType, private: Boolean) {
        if (adding.value || !online.value || cleanTrackName(name) == null) return
        adding.value = true
        viewModelScope.launch {
            when (val result = trackEditor.create(name, type, private)) {
                is TrackChange.Created -> _events.send(TracksSettingsEvent.Created(result.serverId))
                is TrackChange.CreatedNotPrivate -> {
                    _events.send(TracksSettingsEvent.Created(result.serverId))
                    _events.send(TracksSettingsEvent.Toast(CREATED_NOT_PRIVATE))
                }
                is TrackChange.Failed -> _events.send(TracksSettingsEvent.Toast(result.message))
                TrackChange.Done, TrackChange.SignedOut -> Unit
            }
            adding.value = false
        }
    }

    /** During a drag: moves the row keyed [fromLocalId] to where [toLocalId] is. */
    fun move(fromLocalId: Long, toLocalId: Long) {
        val ids = state.value.rows.map { it.track.localId }
        val from = ids.indexOf(fromLocalId)
        val to = ids.indexOf(toLocalId)
        if (from < 0 || to < 0) return
        pendingOrder.value = ids.moved(from, to)
    }

    /** On drop: sends the full order if it differs from Room's. */
    fun commitOrder() {
        val pending = pendingOrder.value ?: return
        val rows = roomRows.value.orEmpty()
        if (pending == rows.map { it.track.localId }) {
            if (reordersInFlight == 0) pendingOrder.value = null
            return
        }
        if (!online.value) {
            // The connection dropped mid-drag: snap back. The screen shows the offline toast.
            if (reordersInFlight == 0) pendingOrder.value = null
            return
        }
        val serverIdByLocal = rows.associate { it.track.localId to it.track.serverId }
        val serverIds = pending.mapNotNull { serverIdByLocal[it] }
        reordersInFlight++
        viewModelScope.launch {
            val result = trackEditor.reorder(serverIds)
            reordersInFlight--
            when (result) {
                is TrackChange.Failed -> {
                    pendingOrder.value = null
                    _events.send(TracksSettingsEvent.Toast(result.message))
                }
                TrackChange.SignedOut -> pendingOrder.value = null
                else -> if (reordersInFlight == 0) {
                    // Several drops in a row each send the full order; only the last one's
                    // answer lets go. Wait (briefly) for Room's re-read to reach the rows, so the
                    // list never jumps back to the old order for a frame.
                    val sent = pendingOrder.value
                    withTimeoutOrNull(ROOM_CATCH_UP_MS) {
                        roomRows.first { rows -> rows?.map { it.track.localId } == sent }
                    }
                    if (reordersInFlight == 0) pendingOrder.value = null
                }
            }
        }
    }

    /** Screen-reader "Move up": one place up, saved at once like a drop. */
    fun moveUp(localId: Long) = moveBy(localId, -1)

    /** Screen-reader "Move down": one place down, saved at once like a drop. */
    fun moveDown(localId: Long) = moveBy(localId, +1)

    private fun moveBy(localId: Long, delta: Int) {
        if (!online.value) return
        val ids = state.value.rows.map { it.track.localId }
        val from = ids.indexOf(localId)
        val to = from + delta
        if (from < 0 || to !in ids.indices) return
        pendingOrder.value = ids.moved(from, to)
        commitOrder()
    }

    private companion object {
        const val CREATED_NOT_PRIVATE = "Track created, but it couldn't be made private. Open it to try again."
        const val ROOM_CATCH_UP_MS = 1_000L
    }
}
