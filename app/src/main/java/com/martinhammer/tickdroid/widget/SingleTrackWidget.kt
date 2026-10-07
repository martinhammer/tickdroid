package com.martinhammer.tickdroid.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.martinhammer.tickdroid.R

/** Single track (1×1): one track's cell for today, labelled with its emoji or abbreviation; a tap toggles it or adds 1. */
class SingleTrackWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) =
        provideWidgetContent(context, id) { inputs, config, appWidgetId ->
            SingleTrackContent(inputs.buttonModel(config), inputs.badge, appWidgetId)
        }
}

class SingleTrackWidgetReceiver : TickdroidWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SingleTrackWidget()
}

@Composable
internal fun SingleTrackContent(model: ButtonModel, badge: SyncBadge, appWidgetId: Int) {
    val context = LocalContext.current
    // Both no-track states draw the same "?" tile; only the label and the tap differ. With a
    // track, the label is the Today row's (emoji or abbreviation), so stateText is null.
    val row = (model as? ButtonModel.Content)?.row
    val (stateText, action, description) = when (model) {
        ButtonModel.SignedOut -> Triple("Signed out", openAppAction(), "Signed out. Open Tickdroid")
        ButtonModel.ChooseTrack -> Triple(
            "Choose track",
            actionStartActivity(configIntent(context, appWidgetId)),
            "Choose a track",
        )
        is ButtonModel.Content -> Triple(
            null,
            tickAction(model.row, model.day),
            cellDescription(model.row),
        )
    }
    Box(
        modifier = GlanceModifier.widgetRoot()
            .clickable(action)
            .semantics { contentDescription = description; testTag = "widgetButton" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 4dp of the 48dp box sits below the 40dp tile: the gap before the label.
            Box(modifier = GlanceModifier.size(48.dp), contentAlignment = Alignment.Center) {
                WidgetTickCell(row = row, size = 40.dp)
                // The list's and row's sync-slot rule: hidden while a sync runs, so all three
                // kinds agree on when the symbol shows.
                if (badge.slot == SyncSlot.Offline) {
                    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                        OfflineBadge()
                    }
                }
            }
            if (row != null) {
                // Drawn like a Today row label, but without the row's fixed 24dp slot, which
                // left no room to spare in a small 1×1 cell. TalkBack still gets the full name
                // from the widget's content description.
                ShortLabel(row)
            } else {
                Text(
                    text = stateText.orEmpty(),
                    maxLines = 1,
                    modifier = GlanceModifier.padding(horizontal = 4.dp),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}

/** Corner badge while unsent ticks can't go up. Not tappable here: the tap still ticks. */
@Composable
private fun OfflineBadge() {
    Box(
        modifier = GlanceModifier.size(16.dp)
            .cornerRadius(8.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .semantics { contentDescription = "Unsaved changes, not synced"; testTag = "widgetOffline" },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_cloud_off),
            contentDescription = null,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.error),
            modifier = GlanceModifier.size(12.dp),
        )
    }
}
