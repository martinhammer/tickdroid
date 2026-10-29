package com.martinhammer.tickdroid.ui.today

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.martinhammer.tickdroid.data.repository.TickKey
import com.martinhammer.tickdroid.data.sync.SyncStatus
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.ui.MainNavigationBar
import com.martinhammer.tickdroid.ui.MainTab
import com.martinhammer.tickdroid.ui.common.CompactHeightThresholdDp
import com.martinhammer.tickdroid.ui.journal.ChipClearance
import com.martinhammer.tickdroid.ui.journal.JournalEmptyState
import com.martinhammer.tickdroid.ui.journal.JumpToTodayChip
import com.martinhammer.tickdroid.ui.journal.OverflowMenu
import com.martinhammer.tickdroid.ui.journal.SyncIndicator
import com.martinhammer.tickdroid.ui.journal.SyncIssueChip
import java.time.LocalDate
import kotlin.math.abs

/** Beyond this many days away, the pager jumps instead of animating through every page. */
private const val AnimateDays = 3

/**
 * Today: one row per visible track for one day, moved between by swipe, ‹ › and the Today chip.
 *
 * [openDay] is a navigation result (an epoch day) from the bar's Today item or a Journal day
 * label ([openFromJournal]); it is applied once and then [onOpenDayHandled] clears it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    openDay: Long?,
    openFromJournal: Boolean,
    onOpenDayHandled: () -> Unit,
    onOpenJournal: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenTracksSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val compactHeight = LocalConfiguration.current.screenHeightDp < CompactHeightThresholdDp
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val locale = LocalConfiguration.current.locales[0]
    var showHelp by rememberSaveable { mutableStateOf(false) }

    // Refresh on resume, as the journal: today rolls over, and changes from elsewhere arrive.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    LaunchedEffect(openDay) {
        if (openDay != null) {
            viewModel.openFromNavigation(LocalDate.ofEpochDay(openDay), openFromJournal)
            onOpenDayHandled()
        }
    }

    // After a Journal day label, Back returns to the Journal as it was left.
    BackHandler(enabled = state.fromJournal) {
        viewModel.clearFromJournal()
        onOpenJournal()
    }

    // Pages are epoch days, oldest to newest left to right, so a swipe right goes back a day
    // with no reverseLayout (and RTL mirrors it). At midnight the count grows by one and every
    // page keeps its date.
    val pagerState = rememberPagerState(initialPage = state.day.toEpochDay().toInt()) {
        state.today.toEpochDay().toInt() + 1
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { viewModel.showDay(LocalDate.ofEpochDay(it.toLong())) }
    }
    LaunchedEffect(state.day) {
        val target = state.day.toEpochDay().toInt()
        if (pagerState.currentPage == target && !pagerState.isScrollInProgress) return@LaunchedEffect
        if (abs(pagerState.currentPage - target) > AnimateDays) {
            pagerState.scrollToPage(target)
        } else {
            pagerState.animateScrollToPage(target)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = if (compactHeight) Modifier else Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                // The title follows the settled day (the view model's), not the page under the
                // finger, so it doesn't flicker mid-swipe.
                val title: @Composable () -> Unit = {
                    Column {
                        Text(
                            dayTitle(state.day, state.today, locale),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("todayTitle"),
                        )
                        Text(
                            dayLine(state.day, state.today, locale),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("todayLine"),
                        )
                    }
                }
                val actions: @Composable RowScope.() -> Unit = {
                    SyncIssueChip(state.syncIssue)
                    SyncIndicator(state.syncStatus)
                    IconButton(onClick = { viewModel.previousDay() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
                    }
                    IconButton(onClick = { viewModel.nextDay() }, enabled = state.day.isBefore(state.today)) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
                    }
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "Help")
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
                    LargeTopAppBar(title = title, actions = actions, scrollBehavior = scrollBehavior)
                }
            },
            bottomBar = {
                MainNavigationBar(
                    selected = MainTab.Today,
                    onToday = {
                        viewModel.clearFromJournal()
                        viewModel.showToday()
                    },
                    onJournal = {
                        viewModel.clearFromJournal()
                        onOpenJournal()
                    },
                )
            },
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = state.syncStatus is SyncStatus.Syncing,
                onRefresh = { viewModel.refresh() },
                // As the journal: the Scaffold padding already covers the system bars, the
                // cutout and the bottom bar, so mark them consumed.
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding),
            ) {
                if (state.tracks.isEmpty()) {
                    JournalEmptyState(loaded = state.loaded, hasHiddenPrivateTracks = state.hasHiddenPrivateTracks)
                } else {
                    HorizontalPager(
                        state = pagerState,
                        // One card at a time: while it is open the days don't swipe.
                        userScrollEnabled = state.editing == null,
                        key = { it },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("todayPager"),
                    ) { page ->
                        TodayPage(
                            state = state,
                            day = LocalDate.ofEpochDay(page.toLong()),
                            onToggleBoolean = viewModel::toggleBoolean,
                            onAdjustCounter = viewModel::adjustCounter,
                            onOpenNote = viewModel::openNote,
                        )
                    }
                }
                JumpToTodayChip(
                    visible = state.loaded && state.day != state.today,
                    onClick = { viewModel.showToday() },
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }

        val editing = state.editing
        val editedTrack = editing?.let { target -> state.tracks.firstOrNull { it.localId == target.trackLocalId } }
        if (editing != null && editedTrack != null) {
            NoteCardLayer(
                track = editedTrack,
                prefs = editedTrack.serverId?.let { state.trackPrefs[it] } ?: TrackPrefs(),
                date = editing.date,
                today = state.today,
                initialText = state.notes[TickKey(editing.trackLocalId, editing.date)] ?: "",
                onSave = { text -> viewModel.saveNote(editing.trackLocalId, editing.date, text) },
                onClose = { viewModel.closeNote() },
            )
        } else if (editing != null && state.loaded) {
            // The track went (deleted elsewhere, or made hidden): nothing to write to.
            LaunchedEffect(editing) { viewModel.closeNote() }
        }
    }

    if (showHelp) {
        TodayHelpSheet(notes = state.notesSupported, stats = state.statsSupported, onDismiss = { showHelp = false })
    }
}

@Composable
private fun TodayPage(
    state: TodayUiState,
    day: LocalDate,
    onToggleBoolean: (trackLocalId: Long, date: LocalDate) -> Unit,
    onAdjustCounter: (trackLocalId: Long, date: LocalDate, delta: Int) -> Unit,
    onOpenNote: (trackLocalId: Long) -> Unit,
) {
    val editable = state.isEditable(day)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = ChipClearance),
    ) {
        items(items = state.tracks, key = { it.localId }) { track ->
            TodayRow(
                track = track,
                prefs = track.serverId?.let { state.trackPrefs[it] } ?: TrackPrefs(),
                day = day,
                today = state.today,
                ticks = state.ticks,
                note = state.notes[TickKey(track.localId, day)],
                run = state.runs[track.localId],
                showRun = state.statsSupported,
                notesSupported = state.notesSupported,
                editable = editable,
                onToggleBoolean = { onToggleBoolean(track.localId, day) },
                onAdjustCounter = { delta -> onAdjustCounter(track.localId, day, delta) },
                onOpenNote = { onOpenNote(track.localId) },
            )
        }
    }
}
