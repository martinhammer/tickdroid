package com.martinhammer.tickdroid.widget

import android.appwidget.AppWidgetManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.auth.AuthState
import com.martinhammer.tickdroid.data.repository.TrackPrefsRepository
import com.martinhammer.tickdroid.data.repository.TrackRepository
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Set on setup intents sent by a placed widget (✎, "Choose tracks", "Choose track"). */
internal const val EXTRA_FROM_WIDGET = "com.martinhammer.tickdroid.widget.FROM_WIDGET"

data class ConfigTrack(val track: Track, val prefs: TrackPrefs, val shown: Boolean)

data class WidgetConfigUiState(
    val loaded: Boolean = false,
    val signedIn: Boolean = true,
    /** Non-private tracks with a server id, in journal order. */
    val tracks: List<ConfigTrack> = emptyList(),
    val labels: TrackLabels = TrackLabels.Full,
    /** Single track: the current choice, if any. */
    val chosenServerId: Long? = null,
)

@HiltViewModel
class WidgetConfigViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    authRepository: AuthRepository,
    trackRepository: TrackRepository,
    trackPrefsRepository: TrackPrefsRepository,
    store: WidgetConfigStore,
) : ViewModel() {

    val appWidgetId: Int =
        savedStateHandle[AppWidgetManager.EXTRA_APPWIDGET_ID] ?: AppWidgetManager.INVALID_APPWIDGET_ID

    private val stored: WidgetConfig? = store.get(appWidgetId)

    /**
     * "Save" rather than "Add widget": the widget is already on the home screen. A stored setup
     * alone isn't enough to tell: a placed widget can have none (sign-out clears every widget's
     * choices but leaves the widgets), and its first edit then read "Add widget". So a launch
     * from the widget itself ([EXTRA_FROM_WIDGET]) counts too; only the launcher placing a new
     * widget sends neither.
     */
    val isReconfigure: Boolean =
        savedStateHandle.get<Boolean>(EXTRA_FROM_WIDGET) == true || stored != null

    private val selection = MutableStateFlow(stored as? WidgetConfig.TrackSelection ?: WidgetConfig.TrackSelection())

    val state: StateFlow<WidgetConfigUiState> = combine(
        authRepository.state,
        trackRepository.observeTracks(),
        trackPrefsRepository.observeAll(),
        selection,
    ) { auth, tracks, prefs, current ->
        WidgetConfigUiState(
            loaded = true,
            signedIn = auth is AuthState.SignedIn,
            // Private tracks are never offered, whatever "Show private tracks" says.
            tracks = tracks
                .filter { !it.private && it.serverId != null }
                .map { ConfigTrack(it, prefs[it.serverId] ?: TrackPrefs(), current.isShown(it)) },
            labels = current.labels,
            chosenServerId = (stored as? WidgetConfig.Button)?.trackServerId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WidgetConfigUiState())

    fun setShown(serverId: Long, shown: Boolean) {
        selection.update { it.copy(shown = it.shown + (serverId to shown)) }
    }

    fun setLabels(labels: TrackLabels) {
        selection.update { it.copy(labels = labels) }
    }

    /**
     * The list's or row's choice, with an explicit entry for every track on screen so the user's
     * choices stand as made; tracks that appear later fall to the default (shown).
     */
    fun selectionConfig(): WidgetConfig.TrackSelection {
        val current = selection.value
        val explicit = state.value.tracks.associate { it.track.serverId!! to it.shown }
        return current.copy(shown = current.shown + explicit)
    }
}
