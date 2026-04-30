package com.martinhammer.tickdroid.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import com.martinhammer.tickdroid.data.prefs.EditableDays
import com.martinhammer.tickdroid.data.prefs.GridDensity
import com.martinhammer.tickdroid.data.prefs.ThemeMode
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackColor
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.ui.common.MaxContentWidth
import com.martinhammer.tickdroid.ui.common.desaturatedEmoji
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    SettingsScaffold(title = "Account", onBack = onBack) {
        Field(label = "Server", value = viewModel.account.serverUrl)
        Spacer(Modifier.height(16.dp))
        Field(label = "Username", value = viewModel.account.login)
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { viewModel.signOut() },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
        ) {
            Text("Log out")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val showPrivate by viewModel.showPrivate.collectAsStateWithLifecycle()
    val density by viewModel.gridDensity.collectAsStateWithLifecycle()
    val theme by viewModel.themeMode.collectAsStateWithLifecycle()
    val editableDays by viewModel.editableDays.collectAsStateWithLifecycle()
    SettingsScaffold(title = "App settings", onBack = onBack) {
        ToggleRow(
            label = "Show private tracks",
            checked = showPrivate,
            onCheckedChange = viewModel::setShowPrivate,
        )
        Spacer(Modifier.height(24.dp))
        EditableDaysSelector(
            current = editableDays,
            onSelect = viewModel::setEditableDays,
        )
        Spacer(Modifier.height(24.dp))
        DensitySelector(
            current = density,
            onSelect = viewModel::setGridDensity,
        )
        Spacer(Modifier.height(24.dp))
        ThemeSelector(
            current = theme,
            onSelect = viewModel::setThemeMode,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DensitySelector(current: GridDensity, onSelect: (GridDensity) -> Unit) {
    Column {
        Text(
            text = "Grid density",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))
        val options = GridDensity.values()
        SingleChoiceSegmentedButtonRow(modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = current == option,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(option.displayLabel())
                }
            }
        }
    }
}

private fun GridDensity.displayLabel(): String = when (this) {
    GridDensity.LOW -> "Low"
    GridDensity.MEDIUM -> "Medium"
    GridDensity.HIGH -> "High"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditableDaysSelector(current: EditableDays, onSelect: (EditableDays) -> Unit) {
    Column {
        Text(
            text = "Editable days",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))
        val options = EditableDays.values()
        SingleChoiceSegmentedButtonRow(modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = current == option,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(option.displayLabel())
                }
            }
        }
    }
}

private fun EditableDays.displayLabel(): String = when (this) {
    EditableDays.ACTIVE_DAY -> "Today"
    EditableDays.ACTIVE_AND_PREVIOUS -> "+1 day"
    EditableDays.ONE_WEEK -> "1 week"
    EditableDays.ALL_DAYS -> "All"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSelector(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Column {
        Text(
            text = "Theme",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))
        val options = ThemeMode.values()
        SingleChoiceSegmentedButtonRow(modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = current == option,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(option.displayLabel())
                }
            }
        }
    }
}

private fun ThemeMode.displayLabel(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracksSettingsScreen(
    onBack: () -> Unit,
    onOpenTrack: (Long) -> Unit = {},
    viewModel: TracksSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tracks settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when {
            !state.loaded -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
            state.rows.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No tracks defined yet. Create and manage tracks in Tickbuddy on your Nextcloud server.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .windowInsetsPadding(
                        WindowInsets.navigationBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Horizontal)
                        ),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(items = state.rows, key = { it.track.localId }) { row ->
                    TrackPrefsRow(row = row, onClick = { onOpenTrack(row.track.localId) })
                }
            }
        }
    }
}

@Composable
private fun TrackPrefsRow(row: TrackRowState, onClick: () -> Unit) {
    val track = row.track
    val prefs = row.prefs
    Row(
        modifier = Modifier
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TrackBadge(track = track, prefs = prefs)
        Text(
            text = track.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrackBadge(track: Track, prefs: TrackPrefs) {
    val customColor = TrackColor.fromKey(prefs.colorKey)
    val container = customColor?.container ?: if (track.type == TrackType.COUNTER) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val onContainer = customColor?.onContainer ?: if (track.type == TrackType.COUNTER) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        if (prefs.emoji != null) {
            Text(
                text = prefs.emoji,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.desaturatedEmoji(),
            )
        } else {
            Text(
                text = track.name.take(2).uppercase(Locale.getDefault()),
                color = onContainer,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value.ifEmpty { "—" },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
