package com.martinhammer.tickdroid.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.size
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.martinhammer.tickdroid.MainActivity
import com.martinhammer.tickdroid.R
import com.martinhammer.tickdroid.domain.TrackColor
import com.martinhammer.tickdroid.domain.TrackType
import androidx.glance.color.ColorProvider as DayNightColorProvider

// Shared by all three widget kinds so none of them can drift from the journal's TickCell.

/** Root modifier for every widget: the launcher's background, shape and colour. */
@Composable
internal fun GlanceModifier.widgetRoot(): GlanceModifier = this
    .fillMaxSize()
    .appWidgetBackground()
    .background(GlanceTheme.colors.widgetBackground)
    .cornerRadius(android.R.dimen.system_app_widget_background_radius)

/**
 * The journal's empty-cell colour. The journal uses `surfaceContainerHighest`, which Glance's
 * colour set doesn't have, so read it from the same dynamic scheme the app uses (light and
 * dark). Unlike GlanceTheme's own colours these are fixed at draw time, so after a wallpaper
 * change they catch up on the next redraw.
 */
@Composable
private fun emptyCellColor(): ColorProvider {
    val context = LocalContext.current
    return remember(context) {
        DayNightColorProvider(
            day = dynamicLightColorScheme(context).surfaceContainerHighest,
            night = dynamicDarkColorScheme(context).surfaceContainerHighest,
        )
    }
}

/**
 * The journal cell, as a widget draws it. [row] null draws the "?" tile of a Single track widget
 * with no track. Ticked: the track colour with ✓ or the value. Not ticked: the empty-cell colour
 * with the faint dot (yes/no) or "0" (counter).
 */
@Composable
internal fun WidgetTickCell(row: WidgetRow?, size: Dp, modifier: GlanceModifier = GlanceModifier) {
    val customColor = TrackColor.fromKey(row?.colorKey)
    val done = row?.done == true
    val container = when {
        done && customColor != null -> ColorProvider(customColor.container)
        done -> GlanceTheme.colors.primaryContainer
        else -> emptyCellColor()
    }
    val onContainer = when {
        done && customColor != null -> ColorProvider(customColor.onContainer)
        done -> GlanceTheme.colors.onPrimaryContainer
        else -> GlanceTheme.colors.onSurfaceVariant
    }
    // titleMedium, as in TickCell. RemoteViews has no SemiBold and no tabular figures; Medium is
    // the nearest weight, and a cell only ever holds one number.
    val numberSize = if (size < 36.dp) 14.sp else 16.sp
    Box(
        modifier = modifier.size(size).cornerRadius(12.dp).background(container),
        contentAlignment = Alignment.Center,
    ) {
        when {
            row == null -> CellText("?", onContainer, numberSize)
            row.type == TrackType.COUNTER -> CellText(row.value.toString(), onContainer, numberSize)
            done -> Image(
                provider = ImageProvider(R.drawable.ic_widget_check),
                contentDescription = null,
                colorFilter = ColorFilter.tint(onContainer),
                modifier = GlanceModifier.size(size * 0.6f),
            )
            else -> Image(
                provider = ImageProvider(R.drawable.ic_widget_dot),
                contentDescription = null,
                alpha = 0.35f,
                colorFilter = ColorFilter.tint(onContainer),
                modifier = GlanceModifier.size(size * 0.5f),
            )
        }
    }
}

@Composable
private fun CellText(text: String, color: ColorProvider, fontSize: TextUnit) {
    Text(
        text = text,
        maxLines = 1,
        style = TextStyle(color = color, fontSize = fontSize, fontWeight = FontWeight.Medium),
    )
}

/**
 * A track label without the name: the emoji (full colour; RemoteViews can't filter text the
 * way the journal's desaturatedEmoji modifier does) or the two-letter abbreviation, styled like
 * the Days down header.
 */
@Composable
internal fun ShortLabel(row: WidgetRow) {
    if (row.emoji != null) {
        Text(text = row.emoji, maxLines = 1, style = TextStyle(fontSize = 16.sp, textAlign = TextAlign.Center))
    } else {
        Text(
            text = abbreviation(row.name),
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/**
 * The list header's and the Today row's sync slot: a faded ⟳ with no action while a sync runs,
 * else the cloud-off symbol while unsent ticks can't go up (tap retries), else ⟳ (tap syncs).
 */
@Composable
internal fun SyncSlotButton(badge: SyncBadge, target: Dp) {
    when (badge.slot) {
        SyncSlot.Syncing -> IconTarget(
            icon = R.drawable.ic_widget_refresh,
            tint = GlanceTheme.colors.onSurfaceVariant,
            alpha = 0.38f,
            description = "Syncing",
            tag = "widgetRefresh",
            target = target,
            onClick = null,
        )
        SyncSlot.Offline -> IconTarget(
            icon = R.drawable.ic_widget_cloud_off,
            tint = GlanceTheme.colors.error,
            description = "Unsaved changes, not synced. Tap to retry",
            tag = "widgetOffline",
            target = target,
            onClick = actionRunCallback<RefreshAction>(),
        )
        SyncSlot.Refresh -> IconTarget(
            icon = R.drawable.ic_widget_refresh,
            tint = GlanceTheme.colors.onSurfaceVariant,
            description = "Refresh",
            tag = "widgetRefresh",
            target = target,
            onClick = actionRunCallback<RefreshAction>(),
        )
    }
}

/** ✎: opens setup for this widget. */
@Composable
internal fun EditButton(appWidgetId: Int, target: Dp) {
    val context = LocalContext.current
    IconTarget(
        icon = R.drawable.ic_widget_edit,
        tint = GlanceTheme.colors.onSurfaceVariant,
        description = "Edit widget",
        tag = "widgetEdit",
        target = target,
        onClick = actionStartActivity(configIntent(context, appWidgetId)),
    )
}

@Composable
private fun IconTarget(
    icon: Int,
    tint: ColorProvider,
    description: String,
    tag: String,
    target: Dp,
    onClick: Action?,
    alpha: Float = 1f,
) {
    val base = GlanceModifier.size(target).cornerRadius(target / 2)
        .semantics { contentDescription = description; testTag = tag }
    Box(
        modifier = if (onClick != null) base.clickable(onClick) else base,
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = null,
            alpha = alpha,
            colorFilter = ColorFilter.tint(tint),
            modifier = GlanceModifier.size(ControlIconSize),
        )
    }
}

/**
 * Sync-slot and edit icons on the list header and the Today row, sized to sit with the 14–16sp
 * widget text. The tap target around them stays [IconTarget]'s `target`.
 */
private val ControlIconSize = 16.dp

/**
 * Opens [WidgetConfigActivity] for [appWidgetId], from a widget that is already placed (hence
 * [EXTRA_FROM_WIDGET]). The data URI is unique per widget so the PendingIntents of two widgets
 * don't collapse into one.
 */
internal fun configIntent(context: Context, appWidgetId: Int): Intent =
    Intent(context, WidgetConfigActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        .putExtra(EXTRA_FROM_WIDGET, true)
        .setData("tickdroid-widget://config/$appWidgetId".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

internal fun openAppAction(): Action = actionStartActivity<MainActivity>()
