package com.martinhammer.tickdroid.ui.journal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.icu.text.CompactDecimalFormat
import com.martinhammer.tickdroid.domain.CellLabel
import com.martinhammer.tickdroid.domain.Tick
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackColor
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.domain.optionLabel
import com.martinhammer.tickdroid.ui.common.EmptyValueMark
import com.martinhammer.tickdroid.ui.common.awaitingSyncToast
import com.martinhammer.tickdroid.ui.common.choiceLabel
import com.martinhammer.tickdroid.ui.common.choiceShortLabel
import com.martinhammer.tickdroid.ui.common.valueDescription
import com.martinhammer.tickdroid.ui.common.updateRequiredDescription
import com.martinhammer.tickdroid.ui.common.updateRequiredToast

/**
 * One track's day: the journal's cell in both layouts, and Today's control (at 48dp). Tap ticks
 * a yes/no day or adds 1 to a counter, long-press takes 1 off; on a Number or Choice track a tap
 * opens the card or the sheet ([onOpenEntry]). A locked day, a track of an unknown type and a
 * Number or Choice track still waiting for its first sync only toast. [hasNote] draws a dot in
 * the top end corner (the day has a note).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TickCell(
    track: Track,
    tick: Tick?,
    prefs: TrackPrefs?,
    cellSize: Dp,
    editable: Boolean,
    onToggleBoolean: () -> Unit,
    onAdjustCounter: (delta: Int) -> Unit,
    hasNote: Boolean = false,
    onOpenEntry: () -> Unit = {},
) {
    // A Number or Choice day has an entry whenever it has a row: 0 and negative Numbers count.
    // While the track waits for its first sync its cells are empty, as on a locked day.
    val entersValue = track.entersValue
    val waiting = entersValue && track.awaitingSync
    val ticked = when {
        waiting -> false
        entersValue -> tick != null
        else -> tick != null && tick.value > 0
    }
    val customColor = TrackColor.fromKey(prefs?.colorKey)
    val container = when {
        ticked && customColor != null -> customColor.container
        ticked -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val onContainer = when {
        ticked && customColor != null -> customColor.onContainer
        ticked -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val unknownType = track.type is TrackType.Unknown
    val locale = LocalConfiguration.current.locales[0]
    val toast = { text: String ->
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }
    val tapModifier = if (waiting) {
        Modifier
            .combinedClickable(onClick = { toast(awaitingSyncToast(track.name)) })
            .semantics { contentDescription = valueDescription(track, null, locale) }
    } else if (entersValue) {
        Modifier
            .combinedClickable(
                onClick = {
                    if (editable) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onOpenEntry()
                    } else {
                        toast(LockedDayMessage)
                    }
                },
            )
            .semantics { contentDescription = valueDescription(track, tick?.value, locale) }
    } else if (unknownType) {
        // A type from a newer Tickbuddy: read-only on every day, editable or locked, so the
        // locked-day toast never promises an edit that can't happen.
        Modifier
            .combinedClickable(
                onClick = {
                    android.widget.Toast.makeText(
                        context,
                        updateRequiredToast(track.name),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
            )
            .semantics { contentDescription = updateRequiredDescription(track.name) }
    } else if (editable) {
        when (track.type) {
            TrackType.YesNo -> Modifier.combinedClickable(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggleBoolean()
                },
            )
            TrackType.Counter -> Modifier.combinedClickable(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onAdjustCounter(1)
                },
                onLongClick = {
                    if (ticked) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onAdjustCounter(-1)
                    }
                },
            )
            // Handled above.
            TrackType.Number, TrackType.Choice, is TrackType.Unknown -> Modifier
        }
    } else {
        Modifier.combinedClickable(
            onClick = {
                android.widget.Toast.makeText(
                    context,
                    LockedDayMessage,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }

    Box(
        modifier = Modifier
            .size(cellSize)
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .then(tapModifier),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // Its value means something only a newer Tickdroid knows: just "there is one".
            unknownType && ticked -> {
                Text(
                    text = "?",
                    color = onContainer,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            unknownType -> Unit // no "tap" hint: taps don't write
            waiting -> Unit // as a locked day: no hint
            track.type == TrackType.Number && ticked -> NumberCellValue(tick!!.value, track.places, cellSize, onContainer)
            track.type == TrackType.Choice && ticked -> ChoiceCellValue(track, prefs, tick!!.value, cellSize, onContainer)
            editable && track.type == TrackType.Number -> {
                // Faint "…": a value goes here.
                Text(
                    text = EmptyValueMark,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    fontSize = (cellSize.value * 0.4f).sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            editable && track.type == TrackType.Choice -> {
                // Faint ▾: a tap opens a list to pick from.
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.size(cellSize * 0.5f),
                )
            }
            track.type == TrackType.Counter && ticked -> {
                Text(
                    text = tick!!.value.toString(),
                    color = onContainer,
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            ticked -> {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = onContainer,
                    modifier = Modifier.size(cellSize * 0.6f),
                )
            }
            editable && track.type == TrackType.Counter -> {
                // Faint affordance hinting "tap to add" on editable empty counter cells.
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.size(cellSize * 0.5f),
                )
            }
            editable && track.type == TrackType.YesNo -> {
                // Faint interpunct hinting "tap to tick" on editable empty boolean cells.
                Text(
                    text = "·",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    fontSize = (cellSize.value * 0.5f).sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (hasNote) {
            // The day has a note: a small dot in the top end corner, in the cell's content colour,
            // over the content with a ring in the cell's own colour. The ✓ scales with the cell
            // (60%) and the counter's number doesn't, so in small cells either can reach the
            // dot's corner; the ring cuts a clean gap there at every cell size (decision row 18).
            // The box is the ring's size, padded less by the same amount, so the dot's own edge
            // is NoteDotInset from the corner. 4dp in cells under 32dp, else 6dp ([noteDotSizeDp]).
            val dotSize = noteDotSizeDp(cellSize.value).dp
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(NoteDotInset - NoteDotRing)
                    .size(dotSize + NoteDotRing * 2)
                    .drawBehind {
                        drawCircle(container, radius = (dotSize / 2 + NoteDotRing).toPx())
                        drawCircle(onContainer.copy(alpha = NoteDotAlpha), radius = (dotSize / 2).toPx())
                    }
                    .testTag("noteDot"),
            )
        }
    }
}

/** A Number's value, stepped down in size (and then shortened) until it fits ([numberCellText]). */
@Composable
private fun NumberCellValue(milli: Int, decimalPlaces: Int, cellSize: Dp, color: Color) {
    val locale = LocalConfiguration.current.locales[0]
    val fontScale = LocalDensity.current.fontScale
    val compact = remember(locale) { compactFormatter(locale) }
    val fitted = remember(milli, decimalPlaces, cellSize, fontScale, locale) {
        numberCellText(milli, decimalPlaces, cellSize.value, fontScale, locale, compact)
    }
    Text(
        text = fitted.text,
        color = color,
        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
        fontSize = fitted.sizeSp.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * A Choice day: the option's short label (its emoji, or two letters), or with the track's
 * "Journal cells" set to Place number, the place in a filled circle about 72% of the cell.
 */
@Composable
private fun ChoiceCellValue(track: Track, prefs: TrackPrefs?, place: Int, cellSize: Dp, color: Color) {
    if (prefs?.cellLabel == CellLabel.Place || track.optionLabel(place) == null) {
        val fontScale = LocalDensity.current.fontScale
        val circle = cellSize * PlaceCircleFraction
        val fitted = remember(place, circle, fontScale) { placeCellText(place, circle.value, fontScale) }
        Box(
            modifier = Modifier
                .size(circle)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.16f))
                .testTag("placeCircle"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = fitted.text,
                color = color,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                fontSize = fitted.sizeSp.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                // At High density the circle (~20dp) is shorter than titleMedium's 24sp line;
                // clamped to it, the line is laid out from the top and the digit sits low.
                // Unbounded, the whole line box is centred on the circle instead.
                modifier = Modifier.wrapContentSize(unbounded = true),
            )
        }
    } else {
        val label = remember(track.options, place) { choiceShortLabel(choiceLabel(track, place)) }
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The phone's short form of a number ("1.2K"), for a value too wide for its cell. */
internal fun compactFormatter(locale: java.util.Locale): (java.math.BigDecimal) -> String {
    val format = CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT)
    return { value -> format.format(value) }
}

/** The toast for a tap on a locked day: a cell here, and the name or note on Today. */
internal const val LockedDayMessage = "This day is locked. Select editable days in App settings."

private val NoteDotInset = 4.dp
private val NoteDotRing = 1.5.dp
private const val NoteDotAlpha = 0.8f
