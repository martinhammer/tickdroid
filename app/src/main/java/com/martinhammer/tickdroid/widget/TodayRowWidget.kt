package com.martinhammer.tickdroid.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import java.time.LocalDate

/** Today row (4×1 by default): the Days down header row, as a widget. */
class TodayRowWidget : GlanceAppWidget() {
    // Reads the exact size to decide how many track columns fit.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) =
        provideWidgetContent(context, id) { inputs, config, appWidgetId ->
            TodayRowContent(inputs.listModel(config), inputs.badge, appWidgetId)
        }
}

class TodayRowWidgetReceiver : TickdroidWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayRowWidget()
}

// RemoteViews containers take at most 10 children (Glance drops the rest), so track columns go
// in nested rows of this many.
private const val ColumnsPerGroup = 8

// Below this width the signed-out button doesn't fit beside its label; the whole widget still
// opens the app.
private const val SignedOutButtonMinWidthDp = 220f

@Composable
internal fun TodayRowContent(model: ListModel, badge: SyncBadge, appWidgetId: Int) {
    val size = LocalSize.current
    val root = GlanceModifier.widgetRoot().padding(horizontal = 8.dp)
    when (model) {
        ListModel.SignedOut -> Row(
            modifier = root.clickable(openAppAction()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Signed out",
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
            )
            if (size.width.value >= SignedOutButtonMinWidthDp) {
                Spacer(modifier = GlanceModifier.width(12.dp))
                Button(text = "Open Tickdroid", onClick = openAppAction())
            }
        }
        is ListModel.Content -> Row(modifier = root, verticalAlignment = Alignment.CenterVertically) {
            if (model.rows.isEmpty()) {
                NoTracksInline(appWidgetId, GlanceModifier.defaultWeight())
            } else {
                val metrics = rowMetrics(size.height.value)
                // Tracks that don't fit are left out from the end, with no marker.
                model.rows.take(rowCapacity(size.width.value)).chunked(ColumnsPerGroup).forEach { group ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        group.forEach { TrackColumn(it, model.day, metrics) }
                    }
                }
                // Spare width goes between the last track and the controls, which stay pinned
                // to the end edge.
                Spacer(modifier = GlanceModifier.defaultWeight())
            }
            SyncSlotButton(badge = badge, target = 32.dp)
            EditButton(appWidgetId = appWidgetId, target = 32.dp)
        }
    }
}

@Composable
private fun TrackColumn(row: WidgetRow, day: LocalDate, metrics: RowMetrics) {
    // The whole column (label and cell) is the tap target.
    Column(
        modifier = GlanceModifier.width(RowColumnDp.dp)
            .clickable(tickAction(row, day))
            .semantics {
                contentDescription = cellDescription(row)
                testTag = "widgetColumn-${row.serverId}"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = GlanceModifier.fillMaxWidth().height(metrics.labelDp.dp),
            contentAlignment = Alignment.Center,
        ) {
            ShortLabel(row)
        }
        Spacer(modifier = GlanceModifier.height(metrics.gapDp.dp))
        WidgetTickCell(row = row, size = metrics.cellDp.dp)
    }
}

@Composable
private fun NoTracksInline(appWidgetId: Int, modifier: GlanceModifier) {
    val context = LocalContext.current
    Row(modifier = modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "No tracks to show",
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
        )
        Text(
            text = "Choose tracks",
            maxLines = 1,
            modifier = GlanceModifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clickable(actionStartActivity(configIntent(context, appWidgetId))),
            style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        )
    }
}
