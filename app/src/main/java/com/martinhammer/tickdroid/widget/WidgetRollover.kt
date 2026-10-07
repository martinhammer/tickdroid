package com.martinhammer.tickdroid.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * One minute past the next local midnight, in epoch millis. `atStartOfDay(zone)` handles days
 * that don't start at 00:00 because of a DST change.
 */
internal fun nextRolloverMillis(now: ZonedDateTime): Long =
    now.toLocalDate().plusDays(1).atStartOfDay(now.zone).plusMinutes(1).toInstant().toEpochMilli()

/**
 * A widget is a static picture; nothing redraws it unless we do, so a new day needs its own
 * trigger. One inexact, non-wakeup alarm: Android may deliver it late while the phone sleeps
 * (it arrives once the screen comes on), and that is fine because a tap on a widget still
 * showing yesterday is refused (WidgetTickHandler). No exact-alarm permission, and never a
 * battery-optimisation exemption request.
 */
internal object WidgetRollover {
    const val ACTION_ROLLOVER = "com.martinhammer.tickdroid.widget.ROLLOVER"

    fun hasAnyWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return WidgetKinds.all.any { (receiver, _) ->
            manager.getAppWidgetIds(ComponentName(context, receiver)).isNotEmpty()
        }
    }

    /** Arm the alarm while any widget is placed; cancel it once none are. */
    fun syncAlarm(context: Context) {
        if (hasAnyWidgets(context)) schedule(context) else cancel(context)
    }

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC,
            nextRolloverMillis(ZonedDateTime.now()),
            pendingIntent(context),
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WidgetRolloverReceiver::class.java).setAction(ACTION_ROLLOVER),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Midnight, clock changes and time-zone changes all mean the same thing here: re-read today,
 * redraw, and re-arm (a pending alarm was computed for the old zone's midnight). Android resets
 * the process's default time zone before delivering TIMEZONE_CHANGED, so `LocalDate.now()`
 * already returns the new local date.
 */
class WidgetRolloverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                context.widgetEntryPoint().widgetRefresher().onDayChanged()
                updateAllWidgets(context)
                WidgetRollover.syncAlarm(context)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            WidgetRollover.ACTION_ROLLOVER,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
