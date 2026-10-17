package com.martinhammer.tickdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.martinhammer.tickdroid.data.network.NetworkMonitor
import com.martinhammer.tickdroid.data.repository.TrackPrefsRepository
import com.martinhammer.tickdroid.data.repository.TrackRepository
import com.martinhammer.tickdroid.data.sync.ServerFeatures
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Collator
import javax.inject.Inject

data class TrackRowState(
    val track: Track,
    val prefs: TrackPrefs,
)

data class TracksSettingsUiState(
    /** Active tracks, in Room's order (or the order being dragged to). */
    val rows: List<TrackRowState> = emptyList(),
    /** Archived tracks, alphabetical. Always empty when the server can't archive. */
    val archived: List<TrackRowState> = emptyList(),
    val loaded: Boolean = false,
    /** A create is on its way: the sheet's "Add track" shows a spinner. */
    val adding: Boolean = false,
    val archiveSupported: Boolean = false,
    /** The archived track whose restore is on its way; its dialog shows a spinner. */
    val restoringServerId: Long? = null,
    /** The archived track whose delete is on its way; its confirm shows a spinner. */
    val deletingServerId: Long? = null,
)

sealed interface TracksSettingsEvent {
    /** The sheet closes and the list scrolls to the new track. */
    data class Created(val serverId: Long) : TracksSettingsEvent

    /** The archived dialog closes and the list scrolls to the restored track. */
    data class Restored(val serverId: Long) : TracksSettingsEvent

    /** An archived track's delete confirm closes. */
    data object Deleted : TracksSettingsEvent

    /** The archived dialog closes and the toast shows. */
    data class RestoreFailed(val text: String) : TracksSettingsEvent

    /** An archived track's delete confirm closes and the toast shows. */
    data class DeleteFailed(val text: String) : TracksSettingsEvent
    data class Toast(val text: String) : TracksSettingsEvent
}

@HiltViewModel
class TracksSettingsViewModel @Inject constructor(
    trackRepository: TrackRepository,
    prefsRepository: TrackPrefsRepository,
    networkMonitor: NetworkMonitor,
    private val trackEditor: TrackEditor,
    serverFeatures: ServerFeatures,
) : ViewModel() {

    /** Every row in Room, archived included, in Room's (i.e. the server's last known) order. */
    private val allRows: StateFlow<List<TrackRowState>?> = combine(
        trackRepository.observeAllTracks(),
        prefsRepository.observeAll(),
    ) { tracks, prefsByServerId ->
        tracks.map { track ->
            val prefs = track.serverId?.let { prefsByServerId[it] } ?: TrackPrefs()
            TrackRowState(track = track, prefs = prefs)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)

    /**
     * Room's active rows, in Room's order. Everything about reordering works on these only:
     * archived tracks are never dragged and never sent in an order. A plain derived Flow, not its
     * own `stateIn`: only [state] is collected, so a separate StateFlow's value would stay null.
     */
    private val roomRows: Flow<List<TrackRowState>?> = allRows.map(::activeOnly)

    private fun activeOnly(rows: List<TrackRowState>?) = rows?.filterNot { it.track.archived }

    // By name in the phone's locale, ignoring case and accents (like the web).
    private val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    /**
     * The order the user dragged to (local ids), shown in place of Room's until the server has
     * confirmed it and Room has caught up. Null when nothing is pending.
     */
    private val pendingOrder = MutableStateFlow<List<Long>?>(null)
    private var reordersInFlight = 0

    private data class Busy(
        val adding: Boolean = false,
        val restoringServerId: Long? = null,
        val deletingServerId: Long? = null,
    )

    private val busy = MutableStateFlow(Busy())

    val state: StateFlow<TracksSettingsUiState> = combine(
        allRows,
        pendingOrder,
        busy,
        serverFeatures.archivedTracks,
    ) { all, pending, busy, archiveSupported ->
        val (archived, active) = all.orEmpty().partition { it.track.archived }
        TracksSettingsUiState(
            rows = applyOrder(active, pending),
            // Nothing is drawn without the flag, even if Room somehow holds an archived row.
            archived = if (archiveSupported) sortArchived(archived, collator) else emptyList(),
            loaded = all != null,
            adding = busy.adding,
            archiveSupported = archiveSupported,
            restoringServerId = busy.restoringServerId,
            deletingServerId = busy.deletingServerId,
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
        if (busy.value.adding || !online.value || cleanTrackName(name) == null) return
        busy.update { it.copy(adding = true) }
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
            busy.update { it.copy(adding = false) }
        }
    }

    /** From the archived dialog. No confirm; the server puts the track at the end of the list. */
    fun restore(serverId: Long) {
        if (!online.value || busy.value.archivedChangeRunning) return
        busy.update { it.copy(restoringServerId = serverId) }
        viewModelScope.launch {
            when (val result = trackEditor.restore(serverId)) {
                is TrackChange.Failed -> _events.send(TracksSettingsEvent.RestoreFailed(result.message))
                TrackChange.SignedOut -> Unit
                else -> {
                    // Close the dialog only once the track is back among the active rows, so the
                    // list can scroll to it.
                    awaitRoom { rows -> rows.any { it.track.serverId == serverId && !it.track.archived } }
                    _events.send(TracksSettingsEvent.Restored(serverId))
                }
            }
            busy.update { it.copy(restoringServerId = null) }
        }
    }

    /** From an archived track's delete confirm. A 404 counts as done, as in step 4. */
    fun delete(serverId: Long) {
        if (!online.value || busy.value.archivedChangeRunning) return
        busy.update { it.copy(deletingServerId = serverId) }
        viewModelScope.launch {
            when (val result = trackEditor.delete(serverId)) {
                is TrackChange.Failed -> _events.send(TracksSettingsEvent.DeleteFailed(result.message))
                TrackChange.SignedOut -> Unit
                else -> {
                    awaitRoom { rows -> rows.none { it.track.serverId == serverId } }
                    _events.send(TracksSettingsEvent.Deleted)
                }
            }
            busy.update { it.copy(deletingServerId = null) }
        }
    }

    private val Busy.archivedChangeRunning: Boolean
        get() = restoringServerId != null || deletingServerId != null

    /** Waits (briefly) for Room's re-read to reach the rows; it ran before the change returned. */
    private suspend fun awaitRoom(caughtUp: (List<TrackRowState>) -> Boolean) {
        withTimeoutOrNull(ROOM_CATCH_UP_MS) { allRows.first { it != null && caughtUp(it) } }
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
        val rows = activeOnly(allRows.value).orEmpty()
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
