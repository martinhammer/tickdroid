package com.martinhammer.tickdroid.widget

import android.content.Context
import android.text.format.DateFormat
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
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Today list (2 columns and up): today's tracks, each with its cell at the row end. */
class TodayListWidget : GlanceAppWidget() {
    // The list just fills whatever space it gets; the header picks its parts from the width.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) =
        provideWidgetContent(context, id) { inputs, config, appWidgetId ->
            TodayListContent(inputs.listModel(config), inputs.today, inputs.badge, appWidgetId)
        }
}

class TodayListWidgetReceiver : TickdroidWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayListWidget()
}

@Composable
internal fun TodayListContent(model: ListModel, today: LocalDate, badge: SyncBadge, appWidgetId: Int) {
    val parts = headerParts(LocalSize.current.width.value)
    Column(modifier = GlanceModifier.widgetRoot()) {
        ListHeader(
            today = today,
            parts = parts,
            badge = badge,
            appWidgetId = appWidgetId,
            // Signed out there's nothing to sync or edit.
            showControls = model is ListModel.Content,
        )
        when {
            model is ListModel.SignedOut -> SignedOutBody(GlanceModifier.fillMaxSize())
            model is ListModel.Content && model.rows.isEmpty() -> NoTracksBody(appWidgetId)
            model is ListModel.Content -> LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(model.rows, itemId = { it.serverId }) { row ->
                    ListRow(row = row, day = model.day, labels = model.labels)
                }
            }
        }
    }
}

@Composable
private fun ListHeader(
    today: LocalDate,
    parts: HeaderParts,
    badge: SyncBadge,
    appWidgetId: Int,
    showControls: Boolean,
) {
    val target = parts.iconTargetDp.dp
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (parts.showTitle) {
            Text(
                text = "Tickdroid",
                maxLines = 1,
                modifier = GlanceModifier.clickable(openAppAction()),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
        Spacer(modifier = GlanceModifier.defaultWeight())
        if (parts.date != HeaderDate.None) {
            Text(
                text = headerDate(today, parts.date),
                maxLines = 1,
                // Narrow, with the title gone, the date is what opens the app.
                modifier = GlanceModifier.clickable(openAppAction()).padding(horizontal = 4.dp),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
            )
        }
        if (showControls) {
            SyncSlotButton(badge = badge, target = target)
            EditButton(appWidgetId = appWidgetId, target = target)
        }
    }
}

/** Locale-formatted header date: "Sun, Oct 5" (long) or "Sun 5" (short). */
private fun headerDate(day: LocalDate, kind: HeaderDate): String {
    val locale = Locale.getDefault()
    val skeleton = if (kind == HeaderDate.Long) "EEEdMMM" else "EEEd"
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(day)
}

@Composable
private fun ListRow(row: WidgetRow, day: LocalDate, labels: TrackLabels) {
    // The whole row is the tap target, for every track type.
    Row(
        modifier = GlanceModifier.fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 16.dp)
            .clickable(tickAction(row, day))
            .semantics {
                contentDescription = cellDescription(row, tapHint = true)
                testTag = "widgetRow-${row.serverId}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (labels) {
            // Like Tracks down's name column: emoji, 6dp, the name.
            TrackLabels.Full -> Row(
                modifier = GlanceModifier.defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.emoji != null) {
                    Text(text = row.emoji, maxLines = 1, style = TextStyle(fontSize = 16.sp))
                    Spacer(modifier = GlanceModifier.width(6.dp))
                }
                Text(
                    text = row.name,
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp),
                )
            }
            // Like the Days down header, centred in the space before the cell.
            TrackLabels.Short -> Box(
                modifier = GlanceModifier.defaultWeight(),
                contentAlignment = Alignment.Center,
            ) {
                ShortLabel(row)
            }
        }
        Spacer(modifier = GlanceModifier.width(8.dp))
        WidgetTickCell(row = row, size = 36.dp)
    }
}

@Composable
private fun NoTracksBody(appWidgetId: Int) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "No tracks to show",
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            ),
        )
        Text(
            text = "Choose tracks",
            modifier = GlanceModifier
                .padding(8.dp)
                .clickable(actionStartActivity(configIntent(context, appWidgetId))),
            style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        )
    }
}

/** "Signed out" and an "Open Tickdroid" button, as on the setup screen while signed out. */
@Composable
private fun SignedOutBody(modifier: GlanceModifier) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Signed out",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
        )
        Spacer(modifier = GlanceModifier.height(8.dp))
        Button(text = "Open Tickdroid", onClick = openAppAction())
    }
}
