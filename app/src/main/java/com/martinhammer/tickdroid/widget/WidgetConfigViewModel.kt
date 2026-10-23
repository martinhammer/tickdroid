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
    /** The widget's background strength, 0..100 ([WidgetConfig.background]). */
    val background: Int = 100,
    /**
     * Single track: the current pick, if any. Null when that track isn't in [tracks] (deleted,
     * archived or made private), so a stale saved choice can't enable Save.
     */
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

    /** Today list and Today row: the per-track choices and labels. The background is [background]. */
    private val selection = MutableStateFlow(stored as? WidgetConfig.TrackSelection ?: WidgetConfig.TrackSelection())

    /** Single track: the track picked on screen, saved only by Save. */
    private val pick = MutableStateFlow((stored as? WidgetConfig.Button)?.trackServerId)

    /** Both kinds. */
    private val background = MutableStateFlow(stored.backgroundPercent())

    val state: StateFlow<WidgetConfigUiState> = combine(
        authRepository.state,
        trackRepository.observeTracks(),
        trackPrefsRepository.observeAll(),
        selection,
        combine(pick, background, ::Pair),
    ) { auth, tracks, prefs, current, (picked, backgroundPercent) ->
        // Private tracks are never offered, whatever "Show private tracks" says.
        val offered = tracks
            .filter { !it.private && it.serverId != null }
            .map { ConfigTrack(it, prefs[it.serverId] ?: TrackPrefs(), current.isShown(it)) }
        WidgetConfigUiState(
            loaded = true,
            signedIn = auth is AuthState.SignedIn,
            tracks = offered,
            labels = current.labels,
            background = backgroundPercent,
            chosenServerId = picked?.takeIf { id -> offered.any { it.track.serverId == id } },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WidgetConfigUiState())

    /** Single track: pick a track. Nothing is saved until [buttonConfig] goes to Save. */
    fun choose(serverId: Long) {
        pick.value = serverId
    }

    fun setBackground(percent: Int) {
        background.value = percent
    }

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
        return current.copy(shown = current.shown + explicit, background = background.value)
    }

    /** Single track's setup, or null while no offered track is picked (Save is disabled then). */
    fun buttonConfig(): WidgetConfig.Button? =
        state.value.chosenServerId?.let { WidgetConfig.Button(trackServerId = it, background = background.value) }
}
