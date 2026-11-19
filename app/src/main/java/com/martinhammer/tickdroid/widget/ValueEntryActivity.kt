package com.martinhammer.tickdroid.widget

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.auth.AuthState
import com.martinhammer.tickdroid.data.prefs.ThemeMode
import com.martinhammer.tickdroid.data.prefs.UiPreferences
import com.martinhammer.tickdroid.data.repository.TickRepository
import com.martinhammer.tickdroid.data.repository.TrackPrefsRepository
import com.martinhammer.tickdroid.data.repository.TrackRepository
import com.martinhammer.tickdroid.data.time.Clock
import com.martinhammer.tickdroid.domain.Tick
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.ui.common.awaitingSyncToast
import com.martinhammer.tickdroid.ui.entry.ValueEntryLayer
import com.martinhammer.tickdroid.ui.theme.TickdroidTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

/** [valueEntryIntent]'s extras: the track's server id and the epoch day the widget was drawn for. */
internal const val EXTRA_ENTRY_TRACK_SERVER_ID = "com.martinhammer.tickdroid.ENTRY_TRACK_SERVER_ID"
internal const val EXTRA_ENTRY_EPOCH_DAY = "com.martinhammer.tickdroid.ENTRY_EPOCH_DAY"

/**
 * The small window a widget's Number or Choice cell opens over the home screen: the same card or
 * sheet as in the app, closing back to the home screen once saved (decision 6, open decision 10).
 * Translucent, in its own task (`taskAffinity=""`, `excludeFromRecents`), started directly by
 * the widget. Saving goes through [TickRepository.setValue], offline too.
 */
@AndroidEntryPoint
class ValueEntryActivity : ComponentActivity() {

    @Inject lateinit var uiPreferences: UiPreferences
    @Inject lateinit var widgetRefresher: WidgetRefresher

    private val viewModel: ValueEntryViewModel by viewModels()
    private var saveJob: Job? = null
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by uiPreferences.themeMode.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val state by viewModel.state.collectAsStateWithLifecycle()
            TickdroidTheme(darkTheme = darkTheme) {
                when (val s = state) {
                    EntryWindowState.Loading -> Unit
                    is EntryWindowState.Closed -> LaunchedEffect(s) { close(s.toast, s.staleDay) }
                    is EntryWindowState.Showing -> Box(Modifier.fillMaxSize()) {
                        // The sheet brings its own scrim; the card is drawn over this one.
                        if (s.track.type == TrackType.Number) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = ScrimAlpha)))
                        }
                        ValueEntryLayer(
                            track = s.track,
                            prefs = s.prefs,
                            date = s.day,
                            today = s.day,
                            current = s.tick,
                            onSave = ::save,
                            onClose = { close(toast = null, staleDay = false) },
                        )
                    }
                }
            }
        }
    }

    /** Not viewModelScope: the window finishes straight after, and the value must still be written. */
    private fun save(value: Int?) {
        val previous = saveJob
        saveJob = lifecycleScope.launch {
            withContext(NonCancellable) {
                previous?.join()
                viewModel.save(value)
            }
        }
    }

    /**
     * Redraws every widget (so the tapped one shows the new value at once, as `handleTap` does),
     * shows [toast] if any, and goes back to the home screen. A widget drawn for an earlier day is
     * told the day changed first, as a tap on a stale yes/no cell is.
     */
    private fun close(toast: String?, staleDay: Boolean) {
        if (closing) return
        closing = true
        if (toast != null) Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            withContext(NonCancellable) {
                saveJob?.join()
                if (staleDay) widgetRefresher.onDayChanged()
                updateAllWidgets(applicationContext)
            }
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private companion object {
        const val ScrimAlpha = 0.32f
    }
}

sealed interface EntryWindowState {
    data object Loading : EntryWindowState

    /** Nothing to enter: close at once, with [toast] if there's something to say. */
    data class Closed(val toast: String? = null, val staleDay: Boolean = false) : EntryWindowState

    data class Showing(val track: Track, val prefs: TrackPrefs, val tick: Tick?, val day: LocalDate) : EntryWindowState
}

/**
 * The window's track and day. Refused before anything shows, as [WidgetTickHandler.onTap]
 * refuses a tap: signed out, or the track gone, private or archived (closes and redraws); a
 * widget drawn for an earlier day ("New day: tap again to tick"); a track waiting for its first
 * sync (T20). A day change while the window is open still saves to the day it was opened for.
 */
@HiltViewModel
class ValueEntryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    trackRepository: TrackRepository,
    private val tickRepository: TickRepository,
    prefsRepository: TrackPrefsRepository,
    authRepository: AuthRepository,
    clock: Clock,
) : ViewModel() {

    private val serverId: Long? = savedStateHandle.get<Long>(EXTRA_ENTRY_TRACK_SERVER_ID)
    private val day: LocalDate? = savedStateHandle.get<Long>(EXTRA_ENTRY_EPOCH_DAY)?.let(LocalDate::ofEpochDay)

    /** The track's local id once found, for [save]. */
    private var trackLocalId: Long? = null

    val state: StateFlow<EntryWindowState> = when {
        serverId == null || day == null -> flowOf(EntryWindowState.Closed())
        authRepository.state.value !is AuthState.SignedIn -> flowOf(EntryWindowState.Closed())
        day != clock.today() -> flowOf(EntryWindowState.Closed(toast = StaleDayMessage, staleDay = true))
        // Active tracks only, so an archived one is gone here.
        else -> combine(
            trackRepository.observeTracks(),
            tickRepository.observeRange(day, day),
            prefsRepository.observeAll(),
        ) { tracks, ticks, prefs ->
            val track = tracks.firstOrNull { it.serverId == serverId }
            when {
                track == null || track.private || !track.entersValue -> EntryWindowState.Closed()
                track.awaitingSync -> EntryWindowState.Closed(toast = awaitingSyncToast(track.name))
                else -> {
                    trackLocalId = track.localId
                    EntryWindowState.Showing(
                        track = track,
                        prefs = prefs[serverId] ?: TrackPrefs(),
                        tick = ticks.values.firstOrNull { it.trackLocalId == track.localId },
                        day = day,
                    )
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EntryWindowState.Loading)

    suspend fun save(value: Int?) {
        val localId = trackLocalId ?: return
        tickRepository.setValue(localId, day ?: return, value)
    }
}

/** The toast for a tap on a widget still showing an earlier day. */
internal const val StaleDayMessage = "New day: tap again to tick"
