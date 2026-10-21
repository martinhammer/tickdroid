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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.ui.common.UPDATE_REQUIRED
import com.martinhammer.tickdroid.ui.common.MaxContentWidth
import com.martinhammer.tickdroid.ui.common.TrackBadge
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracksSettingsScreen(
    onBack: () -> Unit,
    onOpenTrack: (Long) -> Unit = {},
    /** Server id of a track the track screen has just archived, or null. */
    justArchived: Long? = null,
    onJustArchivedHandled: () -> Unit = {},
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
    // Collapsed on every visit; kept (saveable, with the list's back-stack entry) while the user
    // opens a track and comes back, or rotates. Not stored anywhere else.
    var archivedOpen by rememberSaveable { mutableStateOf(false) }
    // Server ids: the archived track whose dialog is open, and the one whose delete confirm is.
    var archivedDialogFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteConfirmFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var scrollToServerId by remember { mutableStateOf<Long?>(null) }
    val offlineToast = { Toast.makeText(context, OfflineMessage, Toast.LENGTH_SHORT).show() }
    val toast = { text: String -> Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    val dateFormatter = rememberArchivedDateFormatter()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TracksSettingsEvent.Created -> {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { showNewTrack = false }
                    scrollToServerId = event.serverId
                }
                is TracksSettingsEvent.Restored -> {
                    archivedDialogFor = null
                    scrollToServerId = event.serverId
                }
                TracksSettingsEvent.Deleted -> deleteConfirmFor = null
                is TracksSettingsEvent.RestoreFailed -> {
                    archivedDialogFor = null
                    toast(event.text)
                }
                is TracksSettingsEvent.DeleteFailed -> {
                    deleteConfirmFor = null
                    toast(event.text)
                }
                is TracksSettingsEvent.Toast -> toast(event.text)
            }
        }
    }

    // Back from the track screen after an archive: open the Archived row so the track is seen
    // arriving, and scroll to it.
    LaunchedEffect(justArchived) {
        if (justArchived != null) {
            archivedOpen = true
            scrollToServerId = justArchived
            onJustArchivedHandled()
        }
    }

    // After a create, restore or archive: once the track is in the list (for an archive, once
    // the Archived row is open and Room has it as archived), scroll to it unless it's in view.
    LaunchedEffect(scrollToServerId, state, archivedOpen) {
        val target = scrollToServerId ?: return@LaunchedEffect
        val index = listIndexOf(state, archivedOpen, target)
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
            archivedOpen = archivedOpen,
            onToggleArchived = { archivedOpen = !archivedOpen },
            onOpenArchived = { serverId -> archivedDialogFor = serverId },
            dateFormatter = dateFormatter,
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

    archivedDialogFor?.let { serverId ->
        val row = state.archived.firstOrNull { it.track.serverId == serverId }
        if (row == null) {
            // Gone while open (deleted or restored elsewhere and a pull landed, or our own
            // restore's re-read): close.
            LaunchedEffect(serverId, state.loaded) { if (state.loaded) archivedDialogFor = null }
        } else {
            ArchivedTrackDialog(
                name = row.track.name,
                dateText = row.track.archivedAt?.format(dateFormatter).orEmpty(),
                restoring = state.restoringServerId == serverId,
                onRestore = { if (online) viewModel.restore(serverId) else offlineToast() },
                onDelete = {
                    if (online) {
                        archivedDialogFor = null
                        deleteConfirmFor = serverId
                    } else {
                        offlineToast()
                    }
                },
                onDismiss = { archivedDialogFor = null },
            )
        }
    }
    deleteConfirmFor?.let { serverId ->
        val row = state.archived.firstOrNull { it.track.serverId == serverId }
        if (row == null) {
            LaunchedEffect(serverId, state.loaded) { if (state.loaded) deleteConfirmFor = null }
        } else {
            // Step 4's confirm, without "Archive instead": the track already is archived.
            DeleteTrackDialog(
                name = row.track.name,
                deleting = state.deletingServerId == serverId,
                onConfirm = { if (online) viewModel.delete(serverId) else offlineToast() },
                onDismiss = { deleteConfirmFor = null },
            )
        }
    }
    if (showHelp) {
        TracksHelpSheet(archiveSupported = state.archiveSupported, onDismiss = { showHelp = false })
    }
}

/** True if item [index] is laid out and clear of both ends (including the FAB's padding). */
private fun LazyListState.isFullyVisible(index: Int): Boolean {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    return item.offset >= info.viewportStartOffset &&
        item.offset + item.size <= info.viewportEndOffset - info.afterContentPadding
}

/**
 * Where the track with [serverId] sits in [TracksSettingsContent]'s list, or -1 if it isn't drawn
 * (an archived track while the Archived row is collapsed). Mirrors the list's item order: the
 * empty text (only when there are no active tracks), the active rows, the Archived row, then the
 * archived rows while open.
 */
internal fun listIndexOf(state: TracksSettingsUiState, archivedOpen: Boolean, serverId: Long): Int {
    val active = state.rows.indexOfFirst { it.track.serverId == serverId }
    if (active >= 0) return active
    if (!archivedOpen) return -1
    val archived = state.archived.indexOfFirst { it.track.serverId == serverId }
    if (archived < 0) return -1
    val emptyText = if (state.rows.isEmpty()) 1 else 0
    return emptyText + state.rows.size + 1 + archived
}

/** List, drag-to-reorder, the Archived section and empty state, without the Scaffold. Internal for UI tests. */
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
    archivedOpen: Boolean = false,
    onToggleArchived: () -> Unit = {},
    onOpenArchived: (serverId: Long) -> Unit = {},
    dateFormatter: DateTimeFormatter = rememberArchivedDateFormatter(),
) {
    when {
        !state.loaded -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
        state.rows.isEmpty() && state.archived.isEmpty() -> Box(
            modifier = modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            EmptyTracksText()
        }
        else -> {
            val reorderState = rememberReorderableLazyListState(listState) { from, to ->
                // Two key types share the list (active rows: Long local ids; the Archived row and
                // archived rows: Strings), so never cast blindly. move() ignores ids that aren't
                // active rows as well.
                val fromId = from.key as? Long ?: return@rememberReorderableLazyListState
                val toId = to.key as? Long ?: return@rememberReorderableLazyListState
                onMove(fromId, toId)
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
                if (state.rows.isEmpty()) {
                    // Every track archived: today's empty text, with the Archived row under it.
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            EmptyTracksText()
                        }
                    }
                }
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
                // Not ReorderableItems, so they can't be dragged. Hidden when nothing is archived.
                if (state.archived.isNotEmpty()) {
                    item(key = "archived-header") {
                        ArchivedHeader(count = state.archived.size, open = archivedOpen, onToggle = onToggleArchived)
                    }
                    if (archivedOpen) {
                        items(items = state.archived, key = { "archived-${it.track.localId}" }) { row ->
                            ArchivedTrackRow(
                                row = row,
                                dateText = row.track.archivedAt?.format(dateFormatter).orEmpty(),
                                onClick = {
                                    val serverId = row.track.serverId
                                    when {
                                        !online -> onOfflineTap()
                                        serverId != null -> onOpenArchived(serverId)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTracksText() {
    Text(
        "No tracks yet. Tap + to add one.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** "Archived (n)": opens and closes the archived rows. Works offline: it only changes the view. */
@Composable
private fun ArchivedHeader(count: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(top = 8.dp)
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                onClickLabel = if (open) "Hide archived tracks" else "Show archived tracks",
                onClick = onToggle,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "Archived, $count ${if (count == 1) "track" else "tracks"}"
                stateDescription = if (open) "Expanded" else "Collapsed"
            }
            .testTag("archived-header")
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Archived ($count)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** An archived track: greyed, no handle, no chevron. A tap opens the archived dialog. */
@Composable
private fun ArchivedTrackRow(
    row: TrackRowState,
    dateText: String,
    onClick: () -> Unit,
) {
    val track = row.track
    Row(
        modifier = Modifier
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .clickable(onClickLabel = "Restore or delete", onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .testTag("archived-${track.localId}")
            .padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackBadge(track = track, prefs = row.prefs, muted = true)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = archivedSubtitle(track.type, dateText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    TrackType.YesNo -> "Yes/No"
    TrackType.Counter -> "Counter"
    is TrackType.Unknown -> UPDATE_REQUIRED
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
