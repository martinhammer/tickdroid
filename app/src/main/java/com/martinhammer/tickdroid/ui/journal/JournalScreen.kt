package com.martinhammer.tickdroid.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.martinhammer.tickdroid.data.prefs.GridDensity
import com.martinhammer.tickdroid.data.repository.TickKey
import com.martinhammer.tickdroid.data.sync.SyncStatus
import com.martinhammer.tickdroid.domain.Tick
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackType
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private val DayLabelWidth = 84.dp
private val CellGap = 6.dp
private val RightPad = 16.dp
private val MinCellSize = 28.dp
private val MaxCellSize = 64.dp

/**
 * Cell width sized so that exactly N cells are fully visible plus a half-cell peek,
 * where N is [GridDensity.visibleTracks]. When fewer tracks exist than fit, the row
 * simply has trailing whitespace instead of a peek; cells stay the same size.
 */
@Composable
private fun computeCellSize(density: GridDensity): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val gridWidth = screenWidth - DayLabelWidth - RightPad
    val n = density.visibleTracks
    val raw = (gridWidth - CellGap * n) / (n + 0.5f)
    return raw.coerceIn(MinCellSize, MaxCellSize)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    onOpenAccount: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onOpenTracksSettings: () -> Unit = {},
    viewModel: JournalViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Tickdroid") },
                actions = {
                    SyncIndicator(state.syncStatus)
                    OverflowMenu(
                        onOpenAccount = onOpenAccount,
                        onOpenAppSettings = onOpenAppSettings,
                        onOpenTracksSettings = onOpenTracksSettings,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.syncStatus is SyncStatus.Syncing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            JournalGrid(
                state = state,
                onLoadOlder = { viewModel.loadOlder() },
            )
        }
    }
}

@Composable
private fun JournalGrid(
    state: JournalUiState,
    onLoadOlder: () -> Unit,
) {
    val tracks = state.tracks
    if (tracks.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            if (!state.loaded) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            } else {
                val message = if (state.hasHiddenPrivateTracks) {
                    "All tracks are private. Enable \"Show private tracks\" in settings to show them."
                } else {
                    "No tracks defined yet. Create and manage tracks in Tickbuddy on your Nextcloud server."
                }
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(32.dp),
                )
            }
        }
        return
    }

    val days = remember(state.window) {
        generateSequence(state.window.today) { it.minusDays(1) }
            .takeWhile { !it.isBefore(state.window.oldestVisible) }
            .toList()
    }

    val gridScroll = rememberScrollState()
    val listState = rememberLazyListState()

    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= info.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearBottom) {
        if (nearBottom) onLoadOlder()
    }

    val cellSize = computeCellSize(state.density)

    Column(Modifier.fillMaxSize()) {
        TrackHeader(tracks, gridScroll, cellSize)
        HorizontalDivider()
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(items = days, key = { it.toEpochDay() }) { day ->
                DayRow(
                    day = day,
                    today = state.window.today,
                    tracks = tracks,
                    ticks = state.ticks,
                    gridScroll = gridScroll,
                    cellSize = cellSize,
                )
            }
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun TrackHeader(
    tracks: List<Track>,
    gridScroll: androidx.compose.foundation.ScrollState,
    cellSize: Dp,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(end = RightPad)) {
            Spacer(Modifier.width(DayLabelWidth))
            Row(
                modifier = Modifier.horizontalScroll(gridScroll),
                horizontalArrangement = Arrangement.spacedBy(CellGap),
            ) {
                tracks.forEach { track ->
                    Box(
                        modifier = Modifier
                            .width(cellSize)
                            .height(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = track.name.take(2).uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayRow(
    day: LocalDate,
    today: LocalDate,
    tracks: List<Track>,
    ticks: Map<TickKey, Tick>,
    gridScroll: androidx.compose.foundation.ScrollState,
    cellSize: Dp,
) {
    val isWeekend = remember(day) {
        val cal = android.icu.util.Calendar.getInstance(Locale.getDefault())
        cal.time = Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant())
        cal.isWeekend
    }
    val rowBg = if (isWeekend) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .padding(vertical = CellGap / 2)
            .padding(end = RightPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DayLabel(day, today)
        Row(
            modifier = Modifier.horizontalScroll(gridScroll),
            horizontalArrangement = Arrangement.spacedBy(CellGap),
        ) {
            tracks.forEach { track ->
                val tick = ticks[TickKey(track.localId, day)]
                TickCell(track = track, tick = tick, cellSize = cellSize)
            }
        }
    }
}

@Composable
private fun DayLabel(day: LocalDate, today: LocalDate) {
    val dow = day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
        .uppercase(Locale.getDefault())
    val context = LocalContext.current
    val dateFormat = remember(context) { android.text.format.DateFormat.getDateFormat(context) }
    val sub = when (day) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> dateFormat.format(Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant()))
    }
    Column(modifier = Modifier.width(DayLabelWidth).padding(start = 12.dp)) {
        Text(dow, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TickCell(track: Track, tick: Tick?, cellSize: Dp) {
    val ticked = tick != null && tick.value > 0
    val container = when {
        track.type == TrackType.COUNTER && ticked -> MaterialTheme.colorScheme.tertiaryContainer
        ticked -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val onContainer = when {
        track.type == TrackType.COUNTER && ticked -> MaterialTheme.colorScheme.onTertiaryContainer
        ticked -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(cellSize)
            .clip(RoundedCornerShape(12.dp))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        if (track.type == TrackType.COUNTER && ticked) {
            Text(
                text = tick!!.value.toString(),
                color = onContainer,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
            )
        } else if (ticked) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(cellSize * 0.6f),
            )
        }
    }
}

@Composable
private fun SyncIndicator(status: SyncStatus) {
    when (status) {
        SyncStatus.Syncing -> CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp).padding(end = 12.dp),
        )
        is SyncStatus.Error -> Text(
            text = "!",
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(end = 16.dp),
        )
        SyncStatus.Idle -> Unit
    }
}

@Composable
private fun OverflowMenu(
    onOpenAccount: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenTracksSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Account") },
                onClick = {
                    expanded = false
                    onOpenAccount()
                },
            )
            DropdownMenuItem(
                text = { Text("App settings") },
                onClick = {
                    expanded = false
                    onOpenAppSettings()
                },
            )
            DropdownMenuItem(
                text = { Text("Tracks settings") },
                onClick = {
                    expanded = false
                    onOpenTracksSettings()
                },
            )
        }
    }
}
