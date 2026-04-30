package com.martinhammer.tickdroid.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontWeight
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackColor
import com.martinhammer.tickdroid.ui.common.MaxContentWidth
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.ui.common.desaturatedEmoji
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailScreen(
    onBack: () -> Unit,
    viewModel: TrackDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.track?.name ?: "Track") },
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
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            when {
                !state.loaded -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
                state.track == null -> Box(
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
                    track = state.track!!,
                    prefs = state.prefs,
                    onColorKeyChange = viewModel::setColorKey,
                    onEmojiChange = viewModel::setEmoji,
                    onReset = viewModel::reset,
                )
            }
        }
    }
}

@Composable
private fun TrackDetailContent(
    track: Track,
    prefs: TrackPrefs,
    onColorKeyChange: (String?) -> Unit,
    onEmojiChange: (String?) -> Unit,
    onReset: () -> Unit,
) {
    val unsynced = track.serverId == null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding(),
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

        SectionLabel(label = "Custom color")
        Spacer(Modifier.height(8.dp))
        ColorPicker(
            selected = TrackColor.fromKey(prefs.colorKey),
            enabled = !unsynced,
            defaultColor = defaultColorForType(track.type),
            onSelect = { onColorKeyChange(it?.key) },
        )
        Spacer(Modifier.height(24.dp))

        SectionLabel(label = "Icon / Title", subtitle = "Use the emoji keyboard to select an icon or leave empty for the 2-letter abbreviation.")
        Spacer(Modifier.height(8.dp))
        EmojiField(
            current = prefs.emoji,
            enabled = !unsynced,
            onChange = onEmojiChange,
        )
        Spacer(Modifier.height(32.dp))

        OutlinedButton(
            onClick = onReset,
            enabled = !unsynced && (prefs.colorKey != null || prefs.emoji != null),
            modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
        ) {
            Text("Reset to defaults")
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
        val description = buildList {
            add(if (track.type == TrackType.COUNTER) "Counter" else "Yes/No")
            if (track.private) add("Private")
        }.joinToString(" · ")
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PreviewBadge(track: Track, prefs: TrackPrefs) {
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
                text = track.name.take(2).uppercase(Locale.getDefault()),
                color = onContainer,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SectionLabel(label: String, subtitle: String? = null) {
    Column {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun defaultColorForType(type: TrackType): String =
    if (type == TrackType.COUNTER) "Counter default" else "Yes/No default"

@Composable
private fun ColorPicker(
    selected: TrackColor?,
    enabled: Boolean,
    defaultColor: String,
    onSelect: (TrackColor?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            DefaultSwatch(
                label = defaultColor,
                isSelected = selected == null,
                enabled = enabled,
                onClick = { onSelect(null) },
            )
        }
        items(items = TrackColor.values().toList(), key = { it.key }) { color ->
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

@Composable
private fun EmojiField(
    current: String?,
    enabled: Boolean,
    onChange: (String?) -> Unit,
) {
    var text by remember(current) { mutableStateOf(current.orEmpty()) }

    LaunchedEffect(text) {
        val cleaned = text.firstGraphemeOrNull()
        if (cleaned != current) onChange(cleaned)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.firstGraphemeOrNull().orEmpty()
        },
        enabled = enabled,
        singleLine = true,
        placeholder = { Text("Tap, then pick an emoji") },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
    )
}

/** Returns the first user-perceived character (grapheme cluster) or null if blank. */
private fun String.firstGraphemeOrNull(): String? {
    if (isEmpty()) return null
    val iterator = java.text.BreakIterator.getCharacterInstance()
    iterator.setText(this)
    val end = iterator.next()
    if (end == java.text.BreakIterator.DONE || end <= 0) return null
    val candidate = substring(0, end)
    return candidate.takeIf { it.isNotBlank() }
}
