package com.martinhammer.tickdroid.ui.journal

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.martinhammer.tickdroid.data.prefs.GridDensity
import com.martinhammer.tickdroid.data.repository.TickKey
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.ui.common.desaturatedEmoji
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle

private val DayHeaderHeight = 40.dp

/**
 * "Tracks down" journal layout: tracks are rows, days are columns. The day columns are a
 * [LazyRow] of one-slot items that scrolls one day at a time and snaps so the leftmost column
 * lines up with the name column (none is left cut off). A second LazyRow for the sticky header
 * follows the body, so the header stays outside the vertical scroll while both slide together.
 *
 * Not a HorizontalPager with one-slot pages, though that was the first version: on a slow
 * release the pager picks its target from the finger's travel in whole pages, and with pages
 * this small the touch slop (~20% of a column) makes that disagree with the actual scroll, so
 * e.g. a slow one-column drag snapped back. LazyRow snapping goes to whichever column edge is
 * actually closest.
 *
 * Columns are anchored on today (column 0), not on calendar weeks. Today is leftmost, older
 * days are to the right, so a leftward swipe goes back in time (Compose mirrors the lists
 * under RTL, which is correct there too). Column indices don't depend on how many columns fit,
 * so rotation and density changes keep the same leftmost day with no extra code.
 */
@Composable
internal fun TracksDownGrid(
    state: JournalUiState,
    onLoadOlder: () -> Unit,
    onToggleBoolean: (trackLocalId: Long, date: LocalDate) -> Unit,
    onAdjustCounter: (trackLocalId: Long, date: LocalDate, delta: Int) -> Unit,
) {
    if (state.tracks.isEmpty()) {
        JournalEmptyState(state)
        return
    }
    // Sized from the width actually available, not Configuration.screenWidthDp: with
    // targetSdk 35+ that includes the display cutout, which in landscape would overflow the
    // day columns into the name column. The insets are applied here, outside the measurement.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.navigationBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal)
            )
    ) {
        TracksDownContent(state, maxWidth.value, onLoadOlder, onToggleBoolean, onAdjustCounter)
    }
}

@Composable
private fun TracksDownContent(
    state: JournalUiState,
    widthDp: Float,
    onLoadOlder: () -> Unit,
    onToggleBoolean: (trackLocalId: Long, date: LocalDate) -> Unit,
    onAdjustCounter: (trackLocalId: Long, date: LocalDate, delta: Int) -> Unit,
) {
    val tracks = state.tracks
    val metrics = remember(widthDp, state.density) {
        tracksDownMetrics(widthDp, state.density.tracksDownDays)
    }
    val n = metrics.daysVisible
    val slot = metrics.slotDp.dp
    val cellSize = metrics.cellDp.dp
    // High density is about fitting more in: names stay on one line (ellipsized) so rows stay
    // one cell tall. Other densities let a long name wrap to a second line.
    val nameLines = if (state.density == GridDensity.HIGH) 1 else 2
    val today = state.window.today
    val count = columnCount(state.window)

    val bodyListState = rememberLazyListState()
    val headerListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // requestScrollToItem, not scrollToItem: the body updates its position during measure, so
    // this collector can resume mid-layout, and the suspending scroll forces a remeasure
    // ("performMeasureAndLayout called during measure layout"). The request form just records
    // the position for the header's next measure.
    LaunchedEffect(bodyListState, headerListState) {
        snapshotFlow { bodyListState.firstVisibleItemIndex to bodyListState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> headerListState.requestScrollToItem(index, offset) }
    }

    // Leftmost visible column (exact once snapped). derivedStateOf so the readers below
    // recompose once per column crossed, not on every scroll frame.
    val firstColumn by remember { derivedStateOf { bodyListState.firstVisibleItemIndex } }

    // Mirrors Days down's nearBottom rule. Keyed on count too: after a long fling one 30-day
    // extension may leave nearEnd still true, and an unchanged key alone wouldn't re-run it.
    // New columns are appended at the old end, so the current index does not shift.
    val nearEnd by remember(n, count) {
        derivedStateOf { bodyListState.firstVisibleItemIndex + n >= count - 7 }
    }
    LaunchedEffect(nearEnd, count) {
        if (nearEnd) onLoadOlder()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = DayHeaderHeight)
                        .padding(end = RightPad),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RangeLabel(
                        days = daysInView(firstColumn, n, today),
                        today = today,
                        modifier = Modifier.weight(1f),
                    )
                    LazyRow(
                        state = headerListState,
                        modifier = Modifier.width(slot * n).height(DayHeaderHeight),
                        userScrollEnabled = false,
                    ) {
                        items(count) { column ->
                            DayHeader(day = dayForColumn(column, today), today = today, slot = slot)
                        }
                    }
                }
            }
            HorizontalDivider()
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = ChipClearance)
            ) {
                // All rows share one height: one cell row, or the tallest label if any name
                // wraps to two lines, so the grid stays even. The labels have to be measured
                // before the day columns to know that height, and a plain Row measures the
                // fixed-width columns *before* the weighted name column, hence SubcomposeLayout.
                SubcomposeLayout(
                    Modifier
                        .fillMaxWidth()
                        .padding(end = RightPad)
                ) { constraints ->
                    val columnsWidth = (slot * n).roundToPx()
                    val labelWidth = (constraints.maxWidth - columnsWidth).coerceAtLeast(0)
                    val labels = subcompose(TracksDownSlot.Labels) {
                        tracks.forEach { track ->
                            TrackLabel(track, track.serverId?.let { state.trackPrefs[it] }, cellSize + CellGap, nameLines)
                        }
                    }.map { it.measure(Constraints.fixedWidth(labelWidth)) }
                    val rowHeightPx = labels.maxOf { it.height }
                    val rowHeight = rowHeightPx.toDp()
                    val columns = subcompose(TracksDownSlot.Columns) {
                        TracksDownColumns(
                            state = state,
                            listState = bodyListState,
                            daysVisible = n,
                            slot = slot,
                            cellSize = cellSize,
                            rowHeight = rowHeight,
                            onToggleBoolean = onToggleBoolean,
                            onAdjustCounter = onAdjustCounter,
                        )
                    }.single().measure(Constraints.fixedWidth(columnsWidth))
                    // placeRelative mirrors under RTL, like Row would. Shorter labels are
                    // centred in the shared row height, matching the centred cells.
                    layout(constraints.maxWidth, rowHeightPx * labels.size) {
                        labels.forEachIndexed { index, label ->
                            label.placeRelative(0, index * rowHeightPx + (rowHeightPx - label.height) / 2)
                        }
                        columns.placeRelative(labelWidth, 0)
                    }
                }
            }
        }
        val awayFromToday by remember { derivedStateOf { bodyListState.firstVisibleItemIndex > 0 } }
        JumpToTodayChip(
            visible = awayFromToday,
            onClick = { scope.launch { bodyListState.animateScrollToItem(0) } },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

private enum class TracksDownSlot { Labels, Columns }

/**
 * One item per day column. A fling travels freely and then snaps so the leftmost column lines
 * up with the name column; a slow drag settles on the closest column edge.
 */
@Composable
private fun TracksDownColumns(
    state: JournalUiState,
    listState: LazyListState,
    daysVisible: Int,
    slot: Dp,
    cellSize: Dp,
    rowHeight: Dp,
    onToggleBoolean: (trackLocalId: Long, date: LocalDate) -> Unit,
    onAdjustCounter: (trackLocalId: Long, date: LocalDate, delta: Int) -> Unit,
) {
    val today = state.window.today
    LazyRow(
        state = listState,
        modifier = Modifier
            .width(slot * daysVisible)
            .height(rowHeight * state.tracks.size)
            .testTag("tracksDownGrid"),
        flingBehavior = rememberSnapFlingBehavior(listState, SnapPosition.Start),
    ) {
        items(columnCount(state.window)) { column ->
            val day = dayForColumn(column, today)
            val weekend = rememberIsWeekend(day)
            Column(
                Modifier.background(
                    if (weekend) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent
                )
            ) {
                state.tracks.forEach { track ->
                    Box(
                        modifier = Modifier
                            .width(slot)
                            .height(rowHeight)
                            .testTag("cell-${track.localId}-${day.toEpochDay()}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        TickCell(
                            track = track,
                            tick = state.ticks[TickKey(track.localId, day)],
                            prefs = track.serverId?.let { state.trackPrefs[it] },
                            cellSize = cellSize,
                            editable = state.editableDays.isEditable(day, today),
                            onToggleBoolean = { onToggleBoolean(track.localId, day) },
                            onAdjustCounter = { delta -> onAdjustCounter(track.localId, day, delta) },
                        )
                    }
                }
            }
        }
    }
}

/** Date range of the days in view, e.g. "Sep 26 – Oct 2"; the year only when it isn't the current one. */
@Composable
private fun RangeLabel(
    days: List<LocalDate>,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val start = days.minOrNull()!!
    val end = days.maxOrNull()!!
    val label = remember(start, end, today, context) {
        val zone = ZoneId.systemDefault()
        val startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillisExclusive = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
        if (start.year == today.year && end.year == today.year) flags = flags or DateUtils.FORMAT_NO_YEAR
        DateUtils.formatDateRange(context, startMillis, endMillisExclusive, flags)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .testTag("rangeLabel")
            .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun DayHeader(day: LocalDate, today: LocalDate, slot: Dp) {
    val locale = LocalConfiguration.current.locales[0]
    val weekend = rememberIsWeekend(day)
    val isToday = day == today
    val color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .width(slot)
            .height(DayHeaderHeight)
            .background(if (weekend) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // "Today" replaces the weekday letter; the day number stays so the row reads evenly.
        Text(
            text = if (isToday) "Today" else day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isToday) FontWeight.SemiBold else null,
            color = if (isToday) color else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = day.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * At least one cell row tall; a long name wraps to a second line (then ellipsizes) and the
 * label grows to fit. The grid then gives every row the tallest label's height. The vertical
 * padding only matters for two-line labels: a single bodyLarge line plus padding still fits
 * the minimum row.
 */
@Composable
private fun TrackLabel(track: Track, prefs: TrackPrefs?, minRowHeight: Dp, maxLines: Int) {
    Row(
        modifier = Modifier
            .testTag("label-${track.localId}")
            .fillMaxWidth()
            .heightIn(min = minRowHeight)
            .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (prefs?.emoji != null) {
            Text(
                text = prefs.emoji,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.desaturatedEmoji(),
            )
            Spacer(Modifier.width(6.dp))
        }
        TrackName(track.name, maxLines)
    }
}

/**
 * Up to [maxLines] (1 or 2) lines of whole words (see [splitLabel]); a word too wide for a line
 * ellipsizes instead of wrapping mid-word. Semantics carry the full name as one text node.
 */
@Composable
private fun TrackName(name: String, maxLines: Int) {
    val style = MaterialTheme.typography.bodyLarge
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(
        Modifier.clearAndSetSemantics { text = AnnotatedString(name) },
    ) {
        val maxWidth = constraints.maxWidth
        val lines = remember(name, style, maxWidth, measurer) {
            if (maxLines == 1) {
                listOf(name.trim().split(Regex("\\s+")).joinToString(" "))
            } else {
                splitLabel(name) { candidate ->
                    measurer.measure(candidate, style, softWrap = false, maxLines = 1).size.width <= maxWidth
                }
            }
        }
        Column {
            lines.forEach { line ->
                Text(
                    text = line,
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
