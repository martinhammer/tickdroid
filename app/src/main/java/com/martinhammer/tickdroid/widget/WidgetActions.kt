package com.martinhammer.tickdroid.widget

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.ui.common.updateRequiredToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

internal val KeyTrackServerId = ActionParameters.Key<Long>("trackServerId")
/** The day the widget was drawn for, so a tap on a stale widget can be refused. */
internal val KeyEpochDay = ActionParameters.Key<Long>("epochDay")
internal val KeyDelta = ActionParameters.Key<Int>("delta")
/** For [ReadOnlyTapAction]'s toast. */
internal val KeyTrackName = ActionParameters.Key<String>("trackName")

/**
 * The tap action for a cell: toggle a yes/no track, or add 1 to a counter. A Number or Choice
 * track opens [ValueEntryActivity], a small window over the home screen, started directly by the
 * widget (an [ActionCallback] that started it would be a trampoline, which Android 12 blocks). A
 * track of a type this Tickdroid doesn't know is read-only: its tap only says an update is needed.
 */
internal fun tickAction(context: Context, row: WidgetRow, day: LocalDate): Action {
    val ids = arrayOf(KeyTrackServerId to row.serverId, KeyEpochDay to day.toEpochDay())
    return when (row.type) {
        TrackType.YesNo -> actionRunCallback<ToggleTickAction>(actionParametersOf(*ids))
        TrackType.Counter -> actionRunCallback<AdjustCounterAction>(actionParametersOf(*ids, KeyDelta to 1))
        TrackType.Number, TrackType.Choice -> actionStartActivity(valueEntryIntent(context, row.serverId, day))
        is TrackType.Unknown -> actionRunCallback<ReadOnlyTapAction>(actionParametersOf(KeyTrackName to row.name))
    }
}

/** Opens [ValueEntryActivity] for a track and the day the widget was drawn for. */
internal fun valueEntryIntent(context: Context, serverId: Long, day: LocalDate): Intent =
    Intent(context, ValueEntryActivity::class.java)
        .putExtra(EXTRA_ENTRY_TRACK_SERVER_ID, serverId)
        .putExtra(EXTRA_ENTRY_EPOCH_DAY, day.toEpochDay())
        .setData(valueEntryUri(serverId, day.toEpochDay()).toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

class ToggleTickAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        handleTap(context, parameters, delta = null)
    }
}

class AdjustCounterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        handleTap(context, parameters, delta = parameters[KeyDelta] ?: 1)
    }
}

/** A tap on a track of a type this Tickdroid doesn't know. Writes nothing, so no stale-day check. */
class ReadOnlyTapAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val name = parameters[KeyTrackName] ?: return
        withContext(Dispatchers.Main) {
            Toast.makeText(context, updateRequiredToast(name), Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * The sync slot, as the refresh icon and as the offline symbol. Runs the existing one-shot
 * PushWorker (push unsent ticks, then pull the last 30 days) rather than a pull from here:
 * the work outlives this broadcast, waits for a network, and repeated taps coalesce.
 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        context.widgetEntryPoint().syncScheduler().schedulePushNow()
    }
}

private suspend fun handleTap(context: Context, parameters: ActionParameters, delta: Int?) {
    val serverId = parameters[KeyTrackServerId] ?: return
    val shownDay = parameters[KeyEpochDay]?.let(LocalDate::ofEpochDay) ?: return
    val entryPoint = context.widgetEntryPoint()
    val outcome = entryPoint.widgetTickHandler().onTap(serverId, shownDay, delta)
    if (outcome == WidgetTickHandler.Outcome.StaleDay) entryPoint.widgetRefresher().onDayChanged()
    // Whatever the outcome, redraw now rather than waiting for the refresher, so the tapped
    // widget and any other showing the same track catch up at once.
    updateAllWidgets(context)
    if (outcome == WidgetTickHandler.Outcome.StaleDay) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, StaleDayMessage, Toast.LENGTH_SHORT).show()
        }
    }
}
