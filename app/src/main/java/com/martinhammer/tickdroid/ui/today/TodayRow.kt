package com.martinhammer.tickdroid.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import com.martinhammer.tickdroid.data.repository.TickKey
import com.martinhammer.tickdroid.domain.Tick
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackRun
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.ui.common.MaxContentWidth
import com.martinhammer.tickdroid.ui.common.TrackBadge
import com.martinhammer.tickdroid.ui.journal.TickCell
import java.time.LocalDate

private val BadgeGap = 16.dp
private val ControlSize = 48.dp
private val SquareSize = 10.dp
private val SquareGap = 2.dp
private val SquareCorner = 2.dp
private val OutlineWidth = 1.5.dp
private val OutlineGap = 1.dp
/** Line 1's note icon box: the name's height, not an IconButton's 48dp. */
private val NoteIconBox = 24.dp
/**
 * The note icons' alpha over onSurfaceVariant: at full colour their solid strokes read darker
 * than the streak text in the same grey (light theme, 31 Oct).
 */
private const val NoteIconAlpha = 0.7f
/** Line 1 to line 2, and line 2 to the note line (decision row 17). */
private val LineGap = 4.dp
private val NoteLineGap = 8.dp
/** Between the text and the cell. */
private val CellGap = 12.dp

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
    onLockedTap: () -> Unit,
) {
    // Notes open only on a day that can be changed, and only when the server keeps notes. On a
    // locked day the name and the note still answer a tap, with the cell's locked-day toast.
    val canWriteNote = notesSupported && editable
    val noteTap = if (editable) onOpenNote else onLockedTap
    Column(
        Modifier
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("todayRow-${track.localId}"),
    ) {
        // Badge and cell are centred on lines 1 and 2 together (decision 4); the note hangs
        // NoteLineGap under line 2, not under the 48dp cell, so it is placed by hand.
        Layout(
            content = {
                TrackBadge(track, prefs)
                Column {
                    NameLine(
                        name = track.name,
                        showNoteIcon = canWriteNote && note == null,
                        clickable = notesSupported,
                        editable = editable,
                        hasNote = note != null,
                        onOpenNote = noteTap,
                    )
                    Spacer(Modifier.height(LineGap))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        // Only when the streak wraps under the squares: the same 4dp as from
                        // the name to the squares, so it clears the day shown's outline.
                        verticalArrangement = Arrangement.spacedBy(LineGap),
                        modifier = Modifier.testTag("todayLine2"),
                    ) {
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
                TickCell(
                    track = track,
                    tick = ticks[TickKey(track.localId, day)],
                    prefs = prefs,
                    cellSize = ControlSize,
                    editable = editable,
                    onToggleBoolean = onToggleBoolean,
                    onAdjustCounter = onAdjustCounter,
                )
                if (notesSupported && note != null) {
                    NoteLine(
                        trackName = track.name,
                        note = note,
                        key = "${track.localId}-${day.toEpochDay()}",
                        editable = editable,
                        onOpenNote = noteTap,
                    )
                }
            },
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val badge = measurables[0].measure(loose)
            val cell = measurables[2].measure(loose)
            val textX = badge.width + BadgeGap.roundToPx()
            val linesWidth = (constraints.maxWidth - textX - CellGap.roundToPx() - cell.width).coerceAtLeast(0)
            val lines = measurables[1].measure(loose.copy(maxWidth = linesWidth))
            val noteWidth = (constraints.maxWidth - textX).coerceAtLeast(0)
            val noteLine = measurables.getOrNull(3)?.measure(loose.copy(maxWidth = noteWidth))
            val pair = maxOf(badge.height, lines.height, cell.height)
            val linesY = (pair - lines.height) / 2
            val noteY = linesY + lines.height + NoteLineGap.roundToPx()
            val height = maxOf(pair, noteLine?.let { noteY + it.height } ?: 0)
            layout(constraints.maxWidth, height) {
                badge.placeRelative(0, (pair - badge.height) / 2)
                lines.placeRelative(textX, linesY)
                cell.placeRelative(constraints.maxWidth - cell.width, (pair - cell.height) / 2)
                noteLine?.placeRelative(textX, noteY)
            }
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
    editable: Boolean,
    hasNote: Boolean,
    onOpenNote: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // As tall as the name; the icon's box is no taller, so rows don't jump when it goes.
        modifier = Modifier
            .heightIn(min = NoteIconBox)
            .testTag("todayLine1"),
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
                            onClickLabel = when {
                                !editable -> null
                                hasNote -> "Edit the note"
                                else -> "Add a note"
                            },
                            onClick = onOpenNote,
                        )
                    } else {
                        Modifier
                    },
                )
                .testTag("todayName"),
        )
        if (showNoteIcon) {
            // Not an IconButton: that takes 48dp of layout, which made line 1 twice the name's
            // height. A clickable this small still gets a 48dp touch area round its centre
            // (ViewConfiguration.minimumTouchTargetSize) without taking the space.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(NoteIconBox)
                    .clickable(onClickLabel = "Add a note", role = Role.Button, onClick = onOpenNote)
                    .semantics { contentDescription = "Add a note for $name" },
            ) {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NoteIconAlpha),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The note under the name: the icon, then the text, its line breaks kept, at most
 * [NoteMaxLines] lines and then "Show more" (measured, as on the web). "Show more" works on a
 * locked day too, where a tap on the icon or the text shows the locked-day toast ([onOpenNote]
 * is then that toast) instead of opening the card.
 */
@Composable
private fun NoteLine(
    trackName: String,
    note: String,
    key: String,
    editable: Boolean,
    onOpenNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    var overflowing by remember(key, note) { mutableStateOf(false) }
    val open = Modifier.clickable(onClickLabel = if (editable) "Edit the note" else null, onClick = onOpenNote)
    Row(
        modifier
            .padding(bottom = 4.dp)
            .testTag("todayNoteLine"),
    ) {
        Icon(
            Icons.Outlined.Description,
            contentDescription = if (editable) "Edit the note for $trackName" else null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NoteIconAlpha),
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
 * it isn't ticked, solid once it is. Ticked days are shaded by level as on the web ([levelFor],
 * [LEVEL_ALPHA]) towards the track's strong colour ([squareColour]): a yes/no day at level 2
 * (45%), a counter at 1 to 4. No TalkBack description yet (cells have none either).
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
    // The app's own theme setting, not the system's: read from the scheme it produced.
    val dark = empty.luminance() < 0.5f
    val full = squareColour(prefs.colorKey, dark, MaterialTheme.colorScheme.primary)
    val outline = MaterialTheme.colorScheme.outline
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val isCounter = track.type == TrackType.Counter
    val margin = OutlineGap + OutlineWidth
    val width = SquareSize * days.size + SquareGap * (days.size - 1) + margin * 2
    Canvas(
        modifier
            .size(width, SquareSize + margin * 2)
            .clearAndSetSemantics { testTag = "todaySquares" },
    ) {
        val square = SquareSize.toPx()
        val step = square + SquareGap.toPx()
        val inset = margin.toPx()
        days.forEachIndexed { index, date ->
            val slot = if (rtl) days.size - 1 - index else index
            val left = inset + slot * step
            val value = ticks[TickKey(track.localId, date)]?.value ?: 0
            val level = levelFor(value, isCounter, maxValue)
            // Every ticked day by its level, as on the web: yes/no is level 2 (45%), counters 1 to 4.
            val color: Color = if (level == 0) empty else lerp(empty, full, LEVEL_ALPHA[level])
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
