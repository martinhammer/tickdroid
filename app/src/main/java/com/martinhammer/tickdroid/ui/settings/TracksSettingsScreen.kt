package com.martinhammer.tickdroid.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.ui.common.MaxContentWidth
import com.martinhammer.tickdroid.ui.common.TrackBadge
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracksSettingsScreen(
    onBack: () -> Unit,
    onOpenTrack: (Long) -> Unit = {},
    viewModel: TracksSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showNewTrack by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var scrollToServerId by remember { mutableStateOf<Long?>(null) }
    val offlineToast = { Toast.makeText(context, OfflineMessage, Toast.LENGTH_SHORT).show() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TracksSettingsEvent.Created -> {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { showNewTrack = false }
                    scrollToServerId = event.serverId
                }
                is TracksSettingsEvent.Toast -> Toast.makeText(context, event.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    // After a create: once the new track is in the list, scroll to it unless it's in view.
    LaunchedEffect(scrollToServerId, state.rows) {
        val target = scrollToServerId ?: return@LaunchedEffect
        val index = state.rows.indexOfFirst { it.track.serverId == target }
        if (index < 0) return@LaunchedEffect
        if (!listState.isFullyVisible(index)) listState.animateScrollToItem(index)
        scrollToServerId = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tracks settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "Help")
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.loaded) {
                AddTrackFab(online = online, onClick = { if (online) showNewTrack = true else offlineToast() })
            }
        },
    ) { padding ->
        TracksSettingsContent(
            state = state,
            online = online,
            listState = listState,
            onOpenTrack = onOpenTrack,
            onMove = viewModel::move,
            onCommitOrder = {
                // The connection dropped mid-drag: the list snaps back and says why.
                if (!online) offlineToast()
                viewModel.commitOrder()
            },
            onMoveUp = viewModel::moveUp,
            onMoveDown = viewModel::moveDown,
            onOfflineTap = offlineToast,
            modifier = Modifier.padding(padding),
        )
    }

    if (showNewTrack) {
        NewTrackSheet(
            sheetState = sheetState,
            adding = state.adding,
            onAdd = { name, type, private ->
                if (online) viewModel.create(name, type, private) else offlineToast()
            },
            onDismiss = { if (!state.adding) showNewTrack = false },
        )
    }
    if (showHelp) {
        TracksHelpSheet(onDismiss = { showHelp = false })
    }
}

/** True if item [index] is laid out and clear of both ends (including the FAB's padding). */
private fun LazyListState.isFullyVisible(index: Int): Boolean {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    return item.offset >= info.viewportStartOffset &&
        item.offset + item.size <= info.viewportEndOffset - info.afterContentPadding
}

/** List, drag-to-reorder and empty state, without the Scaffold. Internal for UI tests. */
@Composable
internal fun TracksSettingsContent(
    state: TracksSettingsUiState,
    online: Boolean,
    listState: LazyListState,
    onOpenTrack: (Long) -> Unit,
    onMove: (fromLocalId: Long, toLocalId: Long) -> Unit,
    onCommitOrder: () -> Unit,
    onMoveUp: (Long) -> Unit,
    onMoveDown: (Long) -> Unit,
    onOfflineTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        !state.loaded -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
        state.rows.isEmpty() -> Box(
            modifier = modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No tracks yet. Tap + to add one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        else -> {
            val reorderState = rememberReorderableLazyListState(listState) { from, to ->
                onMove(from.key as Long, to.key as Long)
            }
            LazyColumn(
                state = listState,
                modifier = modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.navigationBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Horizontal)
                    ),
                // Bottom: room for the last row to scroll clear of the FAB.
                contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
            ) {
                itemsIndexed(items = state.rows, key = { _, row -> row.track.localId }) { index, row ->
                    ReorderableItem(reorderState, key = row.track.localId) { isDragging ->
                        val id = row.track.localId
                        TrackRow(
                            row = row,
                            isDragging = isDragging,
                            online = online,
                            canMoveUp = canMoveUp(index),
                            canMoveDown = canMoveDown(index, state.rows.size),
                            handleModifier = Modifier.draggableHandle(enabled = online, onDragStopped = onCommitOrder),
                            onClick = { onOpenTrack(id) },
                            onMoveUp = { if (online) onMoveUp(id) else onOfflineTap() },
                            onMoveDown = { if (online) onMoveDown(id) else onOfflineTap() },
                            onOfflineTap = onOfflineTap,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    row: TrackRowState,
    isDragging: Boolean,
    online: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onOfflineTap: () -> Unit,
    modifier: Modifier = Modifier,
    handleModifier: Modifier = Modifier,
) {
    val track = row.track
    // Lifted over the others while dragged.
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (isDragging) 2.dp else 0.dp,
        shadowElevation = if (isDragging) 4.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = MaxContentWidth)
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .semantics {
                    // Screen-reader stand-ins for dragging; the row's click stays the default.
                    customActions = buildList {
                        if (canMoveUp) add(CustomAccessibilityAction("Move up") { onMoveUp(); true })
                        if (canMoveDown) add(CustomAccessibilityAction("Move down") { onMoveDown(); true })
                    }
                }
                .testTag("track-${track.localId}")
                .padding(start = 8.dp, end = 24.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Its own semantics boundary, hidden from screen readers (the row's actions stand in
            // for it), so its offline "disabled" doesn't merge into the row, which still opens.
            OfflineGuard(
                online = online,
                onBlockedTap = onOfflineTap,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("handle-${track.localId}")
                    .semantics(mergeDescendants = true) {
                        hideFromAccessibility()
                        if (!online) disabled()
                    },
            ) { enabled ->
                Box(handleModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.DragIndicator,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            TrackBadge(track = track, prefs = row.prefs)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(text = track.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = trackTypeLabel(track.type) + if (track.private) " · Private" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun trackTypeLabel(type: TrackType): String = when (type) {
    TrackType.BOOLEAN -> "Yes/No"
    TrackType.COUNTER -> "Counter"
}

/**
 * "Add track". `FloatingActionButton` has no `enabled`, so offline it draws Material's disabled
 * colours itself, reports disabled to accessibility, and [onClick] shows the offline toast.
 */
@Composable
internal fun AddTrackFab(online: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    FloatingActionButton(
        onClick = onClick,
        containerColor = if (online) colors.primaryContainer else colors.onSurface.copy(alpha = 0.12f),
        contentColor = if (online) colors.onPrimaryContainer else colors.onSurface.copy(alpha = 0.38f),
        elevation = if (online) FloatingActionButtonDefaults.elevation()
        else FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
        modifier = Modifier.semantics { if (!online) disabled() },
    ) {
        Icon(Icons.Filled.Add, contentDescription = "Add track")
    }
}
