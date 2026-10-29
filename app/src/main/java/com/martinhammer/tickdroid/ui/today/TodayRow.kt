package com.martinhammer.tickdroid.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.martinhammer.tickdroid.data.repository.TickKey
import com.martinhammer.tickdroid.domain.Tick
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackColor
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackRun
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.ui.common.MaxContentWidth
import com.martinhammer.tickdroid.ui.common.TrackBadge
import com.martinhammer.tickdroid.ui.journal.TickCell
import java.time.LocalDate

private val BadgeSize = 40.dp
private val BadgeGap = 16.dp
private val ControlSize = 48.dp
private val SquareSize = 10.dp
private val SquareGap = 2.dp
private val SquareCorner = 2.dp
private val OutlineWidth = 1.5.dp
private val OutlineGap = 1.dp

/** How many lines a note shows before "Show more". */
private const val NoteMaxLines = 3

/**
 * One track on Today: badge, name (with the note icon on an open day without a note), the seven
 * squares and the streak, then the journal's cell at 48dp. The day's note, if any, is a third
 * line under the name's column; the row grows for it, while the badge and the cell stay centred
 * on the first two lines.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TodayRow(
    track: Track,
    prefs: TrackPrefs,
    day: LocalDate,
    today: LocalDate,
    ticks: Map<TickKey, Tick>,
    note: String?,
    run: TrackRun?,
    showRun: Boolean,
    notesSupported: Boolean,
    editable: Boolean,
    onToggleBoolean: () -> Unit,
    onAdjustCounter: (delta: Int) -> Unit,
    onOpenNote: () -> Unit,
) {
    // Notes open only on a day that can be changed, and only when the server keeps notes.
    val canWriteNote = notesSupported && editable
    Column(
        Modifier
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("todayRow-${track.localId}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrackBadge(track, prefs)
            Spacer(Modifier.width(BadgeGap))
            Column(Modifier.weight(1f)) {
                NameLine(
                    name = track.name,
                    showNoteIcon = canWriteNote && note == null,
                    clickable = canWriteNote,
                    hasNote = note != null,
                    onOpenNote = onOpenNote,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Squares(
                        track = track,
                        prefs = prefs,
                        days = squareWindow(today, day),
                        shown = day,
                        ticks = ticks,
                        maxValue = run?.maxValue ?: 0,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    val text = if (showRun) runText(run) else null
                    if (text != null) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .clearAndSetSemantics { testTag = "todayRun" },
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            TickCell(
                track = track,
                tick = ticks[TickKey(track.localId, day)],
                prefs = prefs,
                cellSize = ControlSize,
                editable = editable,
                onToggleBoolean = onToggleBoolean,
                onAdjustCounter = onAdjustCounter,
            )
        }
        if (notesSupported && note != null) {
            NoteLine(
                trackName = track.name,
                note = note,
                key = "${track.localId}-${day.toEpochDay()}",
                clickable = canWriteNote,
                onOpenNote = onOpenNote,
                modifier = Modifier.padding(start = BadgeSize + BadgeGap),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** The name, one line with an ellipsis, and straight after it the note icon (as `TrackTitle`). */
@Composable
private fun NameLine(
    name: String,
    showNoteIcon: Boolean,
    clickable: Boolean,
    hasNote: Boolean,
    onOpenNote: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // One height whether or not the icon shows, so rows don't jump.
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .then(
                    if (clickable) {
                        Modifier.clickable(
                            onClickLabel = if (hasNote) "Edit the note" else "Add a note",
                            onClick = onOpenNote,
                        )
                    } else {
                        Modifier
                    },
                )
                .testTag("todayName"),
        )
        if (showNoteIcon) {
            IconButton(
                onClick = onOpenNote,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = "Add a note for $name",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The note under the name: the icon, then the text, its line breaks kept, at most
 * [NoteMaxLines] lines and then "Show more" (measured, as on the web). "Show more" works on a
 * locked day too; the icon and the text open the card only when [clickable].
 */
@Composable
private fun NoteLine(
    trackName: String,
    note: String,
    key: String,
    clickable: Boolean,
    onOpenNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    var overflowing by remember(key, note) { mutableStateOf(false) }
    val open = if (clickable) Modifier.clickable(onClickLabel = "Edit the note", onClick = onOpenNote) else Modifier
    Row(modifier.padding(bottom = 4.dp)) {
        Icon(
            Icons.Outlined.Description,
            contentDescription = if (clickable) "Edit the note for $trackName" else null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp)
                .then(open),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else NoteMaxLines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
                modifier = open.testTag("todayNote"),
            )
            if (expanded || overflowing) {
                Text(
                    text = if (expanded) "Show less" else "Show more",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * Seven 10dp squares, newest on the left (mirrored in RTL), with [shown] outlined: dashed while
 * it isn't ticked, solid once it is. Ticked days in the track's colour; a counter's shaded by
 * level as on the web ([levelFor], [LEVEL_ALPHA]), a ticked yes/no day at full colour, as its
 * cell. No TalkBack description yet (cells have none either).
 */
@Composable
private fun Squares(
    track: Track,
    prefs: TrackPrefs,
    days: List<LocalDate>,
    shown: LocalDate,
    ticks: Map<TickKey, Tick>,
    maxValue: Int,
    modifier: Modifier = Modifier,
) {
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val full = TrackColor.fromKey(prefs.colorKey)?.container ?: MaterialTheme.colorScheme.primaryContainer
    val outline = MaterialTheme.colorScheme.outline
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val isCounter = track.type == TrackType.Counter
    val margin = OutlineGap + OutlineWidth
    val width = SquareSize * days.size + SquareGap * (days.size - 1) + margin * 2
    Canvas(
        modifier
            .size(width, SquareSize + margin * 2)
            .clearAndSetSemantics {}
            .testTag("todaySquares"),
    ) {
        val square = SquareSize.toPx()
        val step = square + SquareGap.toPx()
        val inset = margin.toPx()
        days.forEachIndexed { index, date ->
            val slot = if (rtl) days.size - 1 - index else index
            val left = inset + slot * step
            val value = ticks[TickKey(track.localId, date)]?.value ?: 0
            val level = levelFor(value, isCounter, maxValue)
            val color: Color = when {
                level == 0 -> empty
                isCounter -> lerp(empty, full, LEVEL_ALPHA[level])
                else -> full
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(left, inset),
                size = Size(square, square),
                cornerRadius = CornerRadius(SquareCorner.toPx()),
            )
            if (date == shown) {
                val out = OutlineGap.toPx() + OutlineWidth.toPx() / 2
                val stroke = OutlineWidth.toPx()
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(left - out, inset - out),
                    size = Size(square + out * 2, square + out * 2),
                    cornerRadius = CornerRadius(SquareCorner.toPx() + out),
                    style = Stroke(
                        width = stroke,
                        pathEffect = if (value > 0) null else PathEffect.dashPathEffect(floatArrayOf(stroke * 1.5f, stroke)),
                    ),
                )
            }
        }
    }
}
