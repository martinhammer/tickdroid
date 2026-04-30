package com.martinhammer.tickdroid.ui.journal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.martinhammer.tickdroid.data.prefs.EditableDays
import com.martinhammer.tickdroid.data.prefs.GridDensity
import com.martinhammer.tickdroid.data.repository.TickKey
import com.martinhammer.tickdroid.data.sync.PushStatus
import com.martinhammer.tickdroid.data.sync.SyncStatus
import com.martinhammer.tickdroid.domain.Tick
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackColor
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.ui.common.CompactHeightThresholdDp
import com.martinhammer.tickdroid.ui.common.desaturatedEmoji
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private val DayLabelWidth = 92.dp
private val CellGap = 6.dp
private val RightPad = 16.dp
private val MinCellSize = 28.dp
private val MaxCellSize = 64.dp

// Cap the screen width used for cell sizing so landscape doesn't blow cells out to MaxCellSize
// (which would erase the half-cell peek and make the density setting a no-op). Beyond this
// width the grid stays at its portrait-equivalent size and trailing whitespace fills the rest.
private val GridSizingMaxWidth = 480.dp

/**
 * Cell width sized so that exactly N cells are fully visible plus a half-cell peek,
 * where N is [GridDensity.visibleTracks]. When fewer tracks exist than fit, the row
 * simply has trailing whitespace instead of a peek; cells stay the same size.
 */
@Composable
private fun computeCellSize(density: GridDensity): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val effectiveWidth = if (screenWidth < GridSizingMaxWidth) screenWidth else GridSizingMaxWidth
    val gridWidth = effectiveWidth - DayLabelWidth - RightPad
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
    onOpenAbout: () -> Unit = {},
    viewModel: JournalViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val compactHeight = LocalConfiguration.current.screenHeightDp < CompactHeightThresholdDp
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showHelp by remember { mutableStateOf(false) }

    // Refresh on resume so today rolls over (and pull catches changes from other devices).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold(
        modifier = if (compactHeight) Modifier else Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            val title: @Composable () -> Unit = { Text("Tickdroid") }
            val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                SyncErrorChip(pull = state.syncStatus, push = state.pushStatus)
                SyncIndicator(state.syncStatus)
                IconButton(onClick = { showHelp = true }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = "Help",
                    )
                }
                OverflowMenu(
                    onOpenAccount = onOpenAccount,
                    onOpenAppSettings = onOpenAppSettings,
                    onOpenTracksSettings = onOpenTracksSettings,
                    onOpenAbout = onOpenAbout,
                )
            }
            if (compactHeight) {
                TopAppBar(title = title, actions = actions)
            } else {
                LargeTopAppBar(
                    title = title,
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                )
            }
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
                onToggleBoolean = viewModel::toggleBoolean,
                onAdjustCounter = viewModel::adjustCounter,
            )
        }
    }

    if (showHelp) {
        HelpSheet(onDismiss = { showHelp = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpSheet(onDismiss: () -> Unit) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("How to use Tickdroid", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(
                "Tracks are defined in Tickbuddy on Nextcloud.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Tap a cell to toggle a yes/no track or increment a counter; long-press to decrement a counter cell.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Editable days, show/hide private tracks, and UI options are configured in App settings.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Custom track colors and heading icons can be configured in Tracks settings.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun JournalGrid(
    state: JournalUiState,
    onLoadOlder: () -> Unit,
    onToggleBoolean: (trackLocalId: Long, date: LocalDate) -> Unit,
    onAdjustCounter: (trackLocalId: Long, date: LocalDate, delta: Int) -> Unit,
) {
    val tracks = state.tracks
    if (tracks.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.navigationBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal)
                )
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
    val prefsMap = state.trackPrefs

    Column(Modifier.fillMaxSize()) {
        TrackHeader(tracks, prefsMap, gridScroll, cellSize)
        HorizontalDivider()
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(items = days, key = { it.toEpochDay() }) { day ->
                DayRow(
                    day = day,
                    today = state.window.today,
                    tracks = tracks,
                    prefsMap = prefsMap,
                    ticks = state.ticks,
                    gridScroll = gridScroll,
                    cellSize = cellSize,
                    editableDays = state.editableDays,
                    onToggleBoolean = onToggleBoolean,
                    onAdjustCounter = onAdjustCounter,
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
    prefsMap: Map<Long, TrackPrefs>,
    gridScroll: androidx.compose.foundation.ScrollState,
    cellSize: Dp,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.navigationBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal)
                )
                .padding(end = RightPad)
        ) {
            Spacer(Modifier.width(DayLabelWidth))
            Row(
                modifier = Modifier.horizontalScroll(gridScroll),
                horizontalArrangement = Arrangement.spacedBy(CellGap),
            ) {
                tracks.forEach { track ->
                    val prefs = track.serverId?.let { prefsMap[it] }
                    Box(
                        modifier = Modifier
                            .width(cellSize)
                            .height(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (prefs?.emoji != null) {
                            Text(
                                text = prefs.emoji,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.desaturatedEmoji(),
                            )
                        } else {
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
}

@Composable
private fun DayRow(
    day: LocalDate,
    today: LocalDate,
    tracks: List<Track>,
    prefsMap: Map<Long, TrackPrefs>,
    ticks: Map<TickKey, Tick>,
    gridScroll: androidx.compose.foundation.ScrollState,
    cellSize: Dp,
    editableDays: EditableDays,
    onToggleBoolean: (trackLocalId: Long, date: LocalDate) -> Unit,
    onAdjustCounter: (trackLocalId: Long, date: LocalDate, delta: Int) -> Unit,
) {
    val editable = editableDays.isEditable(day, today)
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
            .windowInsetsPadding(
                WindowInsets.navigationBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal)
            )
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
                val prefs = track.serverId?.let { prefsMap[it] }
                TickCell(
                    track = track,
                    tick = tick,
                    prefs = prefs,
                    cellSize = cellSize,
                    editable = editable,
                    onToggleBoolean = { onToggleBoolean(track.localId, day) },
                    onAdjustCounter = { delta -> onAdjustCounter(track.localId, day, delta) },
                )
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
        Text(
            text = dow,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = sub,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TickCell(
    track: Track,
    tick: Tick?,
    prefs: TrackPrefs?,
    cellSize: Dp,
    editable: Boolean,
    onToggleBoolean: () -> Unit,
    onAdjustCounter: (delta: Int) -> Unit,
) {
    val ticked = tick != null && tick.value > 0
    val customColor = TrackColor.fromKey(prefs?.colorKey)
    val container = when {
        ticked && customColor != null -> customColor.container
        track.type == TrackType.COUNTER && ticked -> MaterialTheme.colorScheme.tertiaryContainer
        ticked -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val onContainer = when {
        ticked && customColor != null -> customColor.onContainer
        track.type == TrackType.COUNTER && ticked -> MaterialTheme.colorScheme.onTertiaryContainer
        ticked -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val tapModifier = if (editable) {
        when (track.type) {
            TrackType.BOOLEAN -> Modifier.combinedClickable(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggleBoolean()
                },
            )
            TrackType.COUNTER -> Modifier.combinedClickable(
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
            track.type == TrackType.COUNTER && ticked -> {
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
            editable && track.type == TrackType.COUNTER -> {
                // Faint affordance hinting "tap to add" on editable empty counter cells.
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.size(cellSize * 0.5f),
                )
            }
            editable && track.type == TrackType.BOOLEAN -> {
                // Faint interpunct hinting "tap to tick" on editable empty boolean cells.
                Text(
                    text = "·",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    fontSize = (cellSize.value * 0.5f).sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun SyncIndicator(status: SyncStatus) {
    if (status is SyncStatus.Syncing) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp).padding(end = 12.dp),
        )
    }
}

@Composable
private fun SyncErrorChip(pull: SyncStatus, push: PushStatus) {
    val hasError = pull is SyncStatus.Error || push is PushStatus.Error
    if (!hasError) return
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text("Sync error") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = MaterialTheme.colorScheme.errorContainer,
            disabledLabelColor = MaterialTheme.colorScheme.onErrorContainer,
            disabledLeadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        border = null,
        modifier = Modifier.padding(end = 8.dp),
    )
}

@Composable
private fun OverflowMenu(
    onOpenAccount: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenTracksSettings: () -> Unit,
    onOpenAbout: () -> Unit,
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
            DropdownMenuItem(
                text = { Text("About") },
                onClick = {
                    expanded = false
                    onOpenAbout()
                },
            )
        }
    }
}
