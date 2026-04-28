package com.martinhammer.tickdroid.ui.settings

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
import javax.inject.Inject

data class TrackRowState(
    val track: Track,
    val prefs: TrackPrefs,
)

data class TracksSettingsUiState(
    val rows: List<TrackRowState> = emptyList(),
    val loaded: Boolean = false,
)

@HiltViewModel
class TracksSettingsViewModel @Inject constructor(
    trackRepository: TrackRepository,
    private val prefsRepository: TrackPrefsRepository,
) : ViewModel() {

    val state: StateFlow<TracksSettingsUiState> = combine(
        trackRepository.observeTracks(),
        prefsRepository.observeAll(),
    ) { tracks, prefsByServerId ->
        val rows = tracks.map { track ->
            val prefs = track.serverId?.let { prefsByServerId[it] } ?: TrackPrefs()
            TrackRowState(track = track, prefs = prefs)
        }
        TracksSettingsUiState(rows = rows, loaded = true)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TracksSettingsUiState(),
    )
}
