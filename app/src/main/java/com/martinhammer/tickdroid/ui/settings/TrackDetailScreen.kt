package com.martinhammer.tickdroid.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackColor
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.ui.common.MaxContentWidth
import com.martinhammer.tickdroid.ui.common.desaturatedEmoji
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailScreen(
    onBack: () -> Unit,
    viewModel: TrackDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val pickerState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val offlineToast = { Toast.makeText(context, OfflineMessage, Toast.LENGTH_SHORT).show() }
    val toast = { text: String -> Toast.makeText(context, text, Toast.LENGTH_LONG).show() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                TrackDetailEvent.RenameDone -> showRename = false
                // Only while this screen is the current one: if the user already pressed Back
                // during the delete, a second pop would take them off the list as well.
                TrackDetailEvent.Deleted -> if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onBack()
                is TrackDetailEvent.DeleteFailed -> {
                    showDelete = false
                    toast(event.text)
                }
                is TrackDetailEvent.Toast -> toast(event.text)
            }
        }
    }

    val track = state.track
    // The three server controls: offline they're greyed out (with the toast on tap); an
    // unsynced track or a change in flight disables them outright.
    val serverEditable = track?.serverId != null && !state.deleting
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TrackTitle(
                        name = track?.name ?: "Track",
                        showRename = track != null,
                        online = online,
                        renameEnabled = serverEditable,
                        onRename = { showRename = true },
                        onOfflineTap = offlineToast,
                    )
                },
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(
                    WindowInsets.navigationBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal)
                )
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            when {
                !state.loaded -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
                track == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Track not found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> TrackDetailContent(
                    track = track,
                    prefs = state.prefs,
                    online = online,
                    serverEditable = serverEditable,
                    pendingPrivate = state.pendingPrivate,
                    onPrivateChange = { if (online) viewModel.setPrivate(it) else offlineToast() },
                    onColorKeyChange = viewModel::setColorKey,
                    onEmojiChange = viewModel::setEmoji,
                    onMoreEmojis = { showPicker = true },
                    onDelete = { showDelete = true },
                    onOfflineTap = offlineToast,
                )
            }
        }
    }

    if (showRename && track != null) {
        RenameTrackDialog(
            currentName = track.name,
            saving = state.renaming,
            onSave = { if (online) viewModel.rename(it) else offlineToast() },
            onDismiss = { showRename = false },
        )
    }
    if (showDelete && track != null) {
        DeleteTrackDialog(
            name = track.name,
            deleting = state.deleting,
            onConfirm = { if (online) viewModel.delete() else offlineToast() },
            onDismiss = { showDelete = false },
        )
    }
    if (showPicker) {
        val close = { scope.launch { pickerState.hide() }.invokeOnCompletion { showPicker = false } }
        EmojiPickerSheet(
            sheetState = pickerState,
            onPick = {
                viewModel.setEmoji(it)
                close()
            },
            onDismiss = { showPicker = false },
        )
    }
    if (showHelp) {
        TracksHelpSheet(onDismiss = { showHelp = false })
    }
}

/**
 * The track's name with the rename pencil right after it: the name gives way (ellipsized), so the
 * pencil follows a short name and stays visible after a long one.
 */
@Composable
internal fun TrackTitle(
    name: String,
    showRename: Boolean,
    online: Boolean,
    renameEnabled: Boolean,
    onRename: () -> Unit,
    onOfflineTap: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (showRename) {
            OfflineGuard(online = online, onBlockedTap = onOfflineTap) { enabled ->
                IconButton(
                    onClick = onRename,
                    enabled = enabled && renameEnabled,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "Rename track",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun TrackDetailContent(
    track: Track,
    prefs: TrackPrefs,
    online: Boolean,
    serverEditable: Boolean,
    pendingPrivate: Boolean?,
    onPrivateChange: (Boolean) -> Unit,
    onColorKeyChange: (String?) -> Unit,
    onEmojiChange: (String?) -> Unit,
    onMoreEmojis: () -> Unit,
    onDelete: () -> Unit,
    onOfflineTap: () -> Unit,
) {
    val unsynced = track.serverId == null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        TrackPreviewHeader(track = track, prefs = prefs)
        Spacer(Modifier.height(24.dp))

        if (unsynced) {
            Text(
                text = "This track hasn't synced with the server yet. Customizations are disabled until it does.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(16.dp))
        }

        // Saves straight away: the switch moves at once and stays disabled until the server
        // answers; on failure it moves back (Room still has the old value).
        OfflineGuard(online = online, onBlockedTap = onOfflineTap) { enabled ->
            ToggleRow(
                label = "Private",
                checked = pendingPrivate ?: track.private,
                onCheckedChange = onPrivateChange,
                enabled = enabled && serverEditable && pendingPrivate == null,
            )
        }
        Spacer(Modifier.height(24.dp))

        SectionLabel(label = "Custom color")
        Spacer(Modifier.height(8.dp))
        ColorPicker(
            selected = TrackColor.fromKey(prefs.colorKey),
            enabled = !unsynced,
            defaultColor = "Default",
            onSelect = { onColorKeyChange(it?.key) },
        )
        Spacer(Modifier.height(24.dp))

        SectionLabel(label = "Icon")
        Spacer(Modifier.height(8.dp))
        TrackIconRow(
            trackName = track.name,
            current = prefs.emoji,
            enabled = !unsynced,
            onSelect = onEmojiChange,
            onMore = onMoreEmojis,
        )
        Spacer(Modifier.height(32.dp))

        // Styled like Account's "Log out".
        OfflineGuard(
            online = online,
            onBlockedTap = onOfflineTap,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = MaxContentWidth)
                .fillMaxWidth(),
        ) { enabled ->
            Button(
                onClick = onDelete,
                enabled = enabled && serverEditable,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Delete track")
            }
        }
    }
}

@Composable
private fun TrackPreviewHeader(track: Track, prefs: TrackPrefs) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PreviewBadge(track = track, prefs = prefs)
        Text(
            text = trackTypeLabel(track.type),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PreviewBadge(track: Track, prefs: TrackPrefs) {
    val customColor = TrackColor.fromKey(prefs.colorKey)
    val container = customColor?.container ?: MaterialTheme.colorScheme.primaryContainer
    val onContainer = customColor?.onContainer ?: MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        if (prefs.emoji != null) {
            Text(
                text = prefs.emoji,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.desaturatedEmoji(),
            )
        } else {
            Text(
                text = track.name.take(2).uppercase(),
                color = onContainer,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ColorPicker(
    selected: TrackColor?,
    enabled: Boolean,
    defaultColor: String,
    onSelect: (TrackColor?) -> Unit,
) {
    val state = rememberLazyListState()
    val colors = TrackColor.values().toList()
    OpeningScroll(
        state = state,
        selected = selected?.let { colors.indexOf(it) + 1 } ?: 0,
        itemSize = IconCircleSize,
        gap = IconCircleGap,
    )
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().edgeFade(state),
        horizontalArrangement = Arrangement.spacedBy(IconCircleGap),
    ) {
        item {
            DefaultSwatch(
                label = defaultColor,
                isSelected = selected == null,
                enabled = enabled,
                onClick = { onSelect(null) },
            )
        }
        items(items = colors, key = { it.key }) { color ->
            ColorSwatch(
                color = color,
                isSelected = selected == color,
                enabled = enabled,
                onClick = { onSelect(color) },
            )
        }
    }
}

@Composable
private fun ColorSwatch(
    color: TrackColor,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val ringColor = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(color.container)
            .let { if (isSelected) it.border(2.dp, ringColor, CircleShape) else it }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = color.onContainer,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun DefaultSwatch(
    label: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val ringColor = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .let { if (isSelected) it.border(2.dp, ringColor, CircleShape) else it }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Text(
                text = "—",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
