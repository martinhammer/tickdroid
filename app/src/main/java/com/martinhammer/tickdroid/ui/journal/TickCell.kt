package com.martinhammer.tickdroid.ui.journal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.martinhammer.tickdroid.domain.Tick
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackColor
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.ui.common.updateRequiredDescription
import com.martinhammer.tickdroid.ui.common.updateRequiredToast

/**
 * One track's day: the journal's cell in both layouts, and Today's control (at 48dp). Tap ticks
 * a yes/no day or adds 1 to a counter, long-press takes 1 off; a locked day and a track of an
 * unknown type only toast. [hasNote] draws a dot in the top end corner (the day has a note).
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
) {
    val ticked = tick != null && tick.value > 0
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
    val tapModifier = if (unknownType) {
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
            is TrackType.Unknown -> Modifier // handled above
        }
    } else {
        Modifier.combinedClickable(
            onClick = {
                android.widget.Toast.makeText(
                    context,
                    "This day is locked. Select editable days in App settings.",
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
            // The day has a note: a small dot in the top end corner, in the cell's content colour.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(NoteDotInset)
                    .size(NoteDotSize)
                    .clip(CircleShape)
                    .background(onContainer.copy(alpha = NoteDotAlpha))
                    .testTag("noteDot"),
            )
        }
    }
}

private val NoteDotSize = 6.dp
private val NoteDotInset = 4.dp
private const val NoteDotAlpha = 0.8f
