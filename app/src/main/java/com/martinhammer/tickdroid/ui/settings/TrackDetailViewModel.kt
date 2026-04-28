package com.martinhammer.tickdroid.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.martinhammer.tickdroid.data.repository.TrackPrefsRepository
import com.martinhammer.tickdroid.data.repository.TrackRepository
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrackDetailUiState(
    val track: Track? = null,
    val prefs: TrackPrefs = TrackPrefs(),
    val loaded: Boolean = false,
)

@HiltViewModel
class TrackDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    trackRepository: TrackRepository,
    private val prefsRepository: TrackPrefsRepository,
) : ViewModel() {

    private val localId: Long = checkNotNull(savedStateHandle["localId"]) {
        "localId nav arg missing"
    }

    val state: StateFlow<TrackDetailUiState> = combine(
        trackRepository.observeTracks(),
        prefsRepository.observeAll(),
    ) { tracks, prefsByServerId ->
        val track = tracks.firstOrNull { it.localId == localId }
        val prefs = track?.serverId?.let { prefsByServerId[it] } ?: TrackPrefs()
        TrackDetailUiState(track = track, prefs = prefs, loaded = true)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TrackDetailUiState(),
    )

    fun setColorKey(colorKey: String?) {
        val serverId = state.value.track?.serverId ?: return
        viewModelScope.launch { prefsRepository.setColorKey(serverId, colorKey) }
    }

    fun setEmoji(emoji: String?) {
        val serverId = state.value.track?.serverId ?: return
        val cleaned = emoji?.takeIf { it.isNotBlank() }
        viewModelScope.launch { prefsRepository.setEmoji(serverId, cleaned) }
    }

    fun reset() {
        val serverId = state.value.track?.serverId ?: return
        viewModelScope.launch { prefsRepository.reset(serverId) }
    }
}
