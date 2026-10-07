package com.martinhammer.tickdroid.widget

import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.auth.AuthState
import com.martinhammer.tickdroid.data.repository.TickRepository
import com.martinhammer.tickdroid.data.repository.TrackRepository
import com.martinhammer.tickdroid.data.time.Clock
import com.martinhammer.tickdroid.domain.TrackType
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A tap on a widget cell. Goes through the same [TickRepository] write path as a journal tap
 * (transaction, dirty bit, one-shot push), so widget taps work offline and sync identically.
 */
@Singleton
class WidgetTickHandler @Inject constructor(
    private val trackRepository: TrackRepository,
    private val tickRepository: TickRepository,
    private val authRepository: AuthRepository,
    private val clock: Clock,
) {
    enum class Outcome { Written, StaleDay, TrackGone, SignedOut, Ignored }

    /**
     * [shownDay] is the day the widget was drawn for. Anything but today is refused: a widget
     * left showing yesterday (a late midnight alarm, a time-zone change) must never put a tick
     * on the wrong day. [delta] null toggles a yes/no track; otherwise it adjusts a counter.
     */
    suspend fun onTap(trackServerId: Long, shownDay: LocalDate, delta: Int?): Outcome {
        if (authRepository.state.value !is AuthState.SignedIn) return Outcome.SignedOut
        if (shownDay != clock.today()) return Outcome.StaleDay
        val track = trackRepository.findByServerId(trackServerId) ?: return Outcome.TrackGone
        if (track.private) return Outcome.TrackGone
        when {
            delta == null && track.type == TrackType.BOOLEAN ->
                tickRepository.toggleBoolean(track.localId, shownDay)
            delta != null && track.type == TrackType.COUNTER ->
                tickRepository.adjustCounter(track.localId, shownDay, delta)
            // The track's type changed under the widget; the redraw will fix the action.
            else -> return Outcome.Ignored
        }
        return Outcome.Written
    }
}
