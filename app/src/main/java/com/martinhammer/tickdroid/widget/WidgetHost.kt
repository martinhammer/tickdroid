package com.martinhammer.tickdroid.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Shared provideGlance body: wait for the refresher's first inputs, then draw [content] from
 * whatever it holds. The inputs are collected inside the composition (see
 * [WidgetRefresher.inputs] for why a one-off snapshot isn't enough); the collector lives only as
 * long as Glance's session, which closes itself shortly after the last update.
 */
internal suspend fun GlanceAppWidget.provideWidgetContent(
    context: Context,
    id: GlanceId,
    content: @Composable (inputs: WidgetInputs, config: WidgetConfig?, appWidgetId: Int) -> Unit,
) {
    val entryPoint = context.widgetEntryPoint()
    val refresher = entryPoint.widgetRefresher()
    val store = entryPoint.widgetConfigStore()
    refresher.onDayChanged()
    val initial = refresher.inputs.filterNotNull().first()
    val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
    provideContent {
        val latest by refresher.inputs.collectAsState()
        val inputs = latest ?: initial
        val config = remember(inputs.configRevision) { store.get(appWidgetId) }
        GlanceTheme {
            content(inputs, config, appWidgetId)
        }
    }
}

/** Common receiver duties: forget a removed widget's choices and keep the midnight alarm armed. */
abstract class TickdroidWidgetReceiver : GlanceAppWidgetReceiver() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Also sent after boot and app updates, which clear alarms.
        WidgetRollover.schedule(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRollover.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // The last widget of this kind went; cancel only if no other kind is left either.
        WidgetRollover.syncAlarm(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        context.widgetEntryPoint().widgetConfigStore().remove(appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }
}
