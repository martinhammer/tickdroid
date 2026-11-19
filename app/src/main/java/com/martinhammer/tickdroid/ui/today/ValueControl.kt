package com.martinhammer.tickdroid.ui.today

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.martinhammer.tickdroid.domain.Tick
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackColor
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.domain.formatNumber
import com.martinhammer.tickdroid.ui.common.EmptyValueMark
import com.martinhammer.tickdroid.ui.common.awaitingSyncToast
import com.martinhammer.tickdroid.ui.common.choiceLabel
import com.martinhammer.tickdroid.ui.common.valueDescription
import com.martinhammer.tickdroid.ui.journal.LockedDayMessage
import com.martinhammer.tickdroid.ui.journal.emptyControlShowsUnit
import com.martinhammer.tickdroid.ui.journal.todayNumberFit

/** Today's control for a Number or Choice row: wider than the 48dp cell, as tall (open decision 9). */
internal val ValueControlWidth = 112.dp
private val ValueControlHeight = 48.dp
private val ValueControlPadding = 8.dp

/**
 * A Number or Choice day on Today (decision 5): the Number's full value with grouping and its
 * unit, or the Choice's full label with ▾; "… km" and "… ▾" when empty (T15). A tap opens the
 * card or the sheet ([onOpen]) on an editable day; a locked day toasts as a cell does, and so
 * does a track still waiting for its first sync (T20), which shows nothing.
 */
@Composable
internal fun ValueControl(
    track: Track,
    prefs: TrackPrefs,
    tick: Tick?,
    editable: Boolean,
    onOpen: () -> Unit,
) {
    val waiting = track.awaitingSync
    val hasEntry = tick != null && !waiting
    val customColor = TrackColor.fromKey(prefs.colorKey)
    val container = when {
        hasEntry && customColor != null -> customColor.container
        hasEntry -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val onContainer = when {
        hasEntry && customColor != null -> customColor.onContainer
        hasEntry -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val locale = LocalConfiguration.current.locales[0]
    Box(
        modifier = Modifier
            .size(ValueControlWidth, ValueControlHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .clickable(role = Role.Button) {
                when {
                    waiting -> Toast.makeText(context, awaitingSyncToast(track.name), Toast.LENGTH_SHORT).show()
                    editable -> {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onOpen()
                    }
                    else -> Toast.makeText(context, LockedDayMessage, Toast.LENGTH_SHORT).show()
                }
            }
            .semantics { contentDescription = valueDescription(track, tick?.value?.takeIf { hasEntry }, locale) }
            .padding(horizontal = ValueControlPadding)
            .testTag("valueControl-${track.localId}"),
        contentAlignment = Alignment.Center,
    ) {
        if (waiting) return@Box
        val inner = ValueControlWidth - ValueControlPadding * 2
        when (track.type) {
            TrackType.Number -> NumberControlContent(track, tick?.takeIf { hasEntry }?.value, onContainer, inner)
            else -> ChoiceControlContent(track, tick?.takeIf { hasEntry }?.value, onContainer)
        }
    }
}

@Composable
private fun NumberControlContent(track: Track, milli: Int?, color: Color, inner: Dp) {
    val locale = LocalConfiguration.current.locales[0]
    val fontScale = LocalDensity.current.fontScale
    val unit = track.unit.orEmpty()
    val unitStyle = MaterialTheme.typography.bodySmall
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (milli == null) {
            val showUnit = remember(unit, fontScale) { emptyControlShowsUnit(unit, inner.value, fontScale) }
            Text(EmptyValueMark, color = color, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (showUnit) {
                Spacer(Modifier.width(4.dp))
                Text(unit, color = color, style = unitStyle, maxLines = 1)
            }
            return@Row
        }
        val value = remember(milli, track.places, locale) { formatNumber(milli, track.places, locale, grouping = true) }
        val fit = remember(value, unit, fontScale) { todayNumberFit(value, unit, inner.value, fontScale) }
        Text(
            value,
            color = color,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            fontSize = fit.sizeSp.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
        if (fit.showUnit) {
            Spacer(Modifier.width(4.dp))
            Text(unit, color = color.copy(alpha = 0.8f), style = unitStyle, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun ChoiceControlContent(track: Track, place: Int?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = place?.let { choiceLabel(track, it) } ?: EmptyValueMark,
            color = color,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            Icons.Filled.ArrowDropDown,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
    }
}
