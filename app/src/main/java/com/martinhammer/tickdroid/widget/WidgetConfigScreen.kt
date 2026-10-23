package com.martinhammer.tickdroid.widget

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.martinhammer.tickdroid.ui.common.MaxContentWidth
import com.martinhammer.tickdroid.ui.common.TrackBadge
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WidgetConfigScreen(
    kind: WidgetKind,
    viewModel: WidgetConfigViewModel,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
    onSave: (WidgetConfig) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isSelection = kind != WidgetKind.SingleTrack
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(kind.title) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                },
            )
        },
        bottomBar = {
            if (state.loaded && state.signedIn) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Button(
                        onClick = {
                            if (isSelection) onSave(viewModel.selectionConfig())
                            else viewModel.buttonConfig()?.let(onSave)
                        },
                        // Single track: a widget saved without a track would only show "Choose track".
                        enabled = isSelection || state.chosenServerId != null,
                        modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
                    ) {
                        Text(if (viewModel.isReconfigure) "Save" else "Add widget")
                    }
                }
            }
        },
    ) { padding ->
        val body = Modifier
            .fillMaxSize()
            .padding(padding)
            .windowInsetsPadding(
                WindowInsets.navigationBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal)
            )
        when {
            !state.loaded -> Box(modifier = body, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
            !state.signedIn -> SignedOutMessage(modifier = body, onOpenApp = onOpenApp)
            !isSelection -> TrackChoiceList(
                modifier = body,
                state = state,
                onBackground = viewModel::setBackground,
                onChoose = viewModel::choose,
            )
            else -> TrackSwitchList(
                modifier = body,
                kind = kind,
                state = state,
                onLabels = viewModel::setLabels,
                onBackground = viewModel::setBackground,
                onShown = viewModel::setShown,
            )
        }
    }
}

@Composable
private fun SignedOutMessage(modifier: Modifier, onOpenApp: () -> Unit) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Sign in to Tickdroid first, then add the widget.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenApp) { Text("Open Tickdroid") }
    }
}

@Composable
private fun NoTracksMessage() {
    Text(
        "No tracks yet. Add tracks in Tickdroid's Tracks settings.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(32.dp),
    )
}

/** Single track: the background, then tap a track to pick it; Save saves. */
@Composable
private fun TrackChoiceList(
    modifier: Modifier,
    state: WidgetConfigUiState,
    onBackground: (Int) -> Unit,
    onChoose: (Long) -> Unit,
) {
    val tracks = state.tracks
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        item(key = "background") { BackgroundSetting(state.background, onBackground) }
        item(key = "hint") {
            SetupHint("Choose the track for this widget. $PrivateTracksHint")
        }
        if (tracks.isEmpty()) {
            item(key = "empty") { NoTracksMessage() }
        }
        items(items = tracks, key = { it.track.localId }) { item ->
            val serverId = item.track.serverId!!
            TrackRow(item = item, onClick = { onChoose(serverId) }) {
                if (serverId == state.chosenServerId) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Current choice",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** Today list and Today row: a switch per track, plus the list's label setting and the background. */
@Composable
private fun TrackSwitchList(
    modifier: Modifier,
    kind: WidgetKind,
    state: WidgetConfigUiState,
    onLabels: (TrackLabels) -> Unit,
    onBackground: (Int) -> Unit,
    onShown: (Long, Boolean) -> Unit,
) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        if (kind == WidgetKind.TodayList) {
            item(key = "labels") {
                SegmentedSetting(
                    title = "Track labels",
                    // Display text only: the enum name is what WidgetConfigStore saves.
                    options = listOf(TrackLabels.Full to "Full", TrackLabels.Short to "Icon"),
                    selected = state.labels,
                    onSelect = onLabels,
                )
            }
        }
        item(key = "background") { BackgroundSetting(state.background, onBackground) }
        item(key = "hint") {
            SetupHint(
                if (kind == WidgetKind.TodayRow) {
                    "$SelectionHint Tracks that don't fit the widget's width are left out."
                } else {
                    SelectionHint
                }
            )
        }
        if (state.tracks.isEmpty()) {
            item(key = "empty") { NoTracksMessage() }
        }
        items(items = state.tracks, key = { it.track.localId }) { item ->
            val serverId = item.track.serverId!!
            TrackRow(item = item, onClick = { onShown(serverId, !item.shown) }) {
                Switch(checked = item.shown, onCheckedChange = { onShown(serverId, it) })
            }
        }
    }
}

/** The background choices, as stored percentages ([WidgetConfig.background]) and their display text. */
private val BackgroundChoices = listOf(100 to "Solid", 50 to "Translucent", 0 to "Transparent")

@Composable
internal fun BackgroundSetting(percent: Int, onSelect: (Int) -> Unit) {
    SegmentedSetting(
        title = "Background",
        options = BackgroundChoices,
        // The choice nearest the stored value, so one a later slider wrote still shows sensibly.
        selected = BackgroundChoices.minBy { abs(it.first - percent) }.first,
        onSelect = onSelect,
    )
}

/** The smallest a segment label shrinks to: labelSmall, Material's smallest type role. */
internal val SegmentLabelMinFontSize = 11.sp

/** A titled segmented button row, as in App settings. [options] pairs each value with its text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SegmentedSetting(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
        ) {
            options.forEachIndexed { index, (value, text) ->
                val isSelected = selected == value
                SegmentedButton(
                    selected = isSelected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    SegmentLabel(text, besideCheckMark = isSelected)
                }
            }
        }
    }
}

/**
 * A segment's label: one line that shrinks only when the word doesn't fit, down to
 * [SegmentLabelMinFontSize], and ellipsizes past that ("Translucent" and "Transparent" barely
 * fit). LocalTextStyle here is the button's labelLarge.
 *
 * SegmentedButton (material3 1.4) measures its label at the segment's full width and only then
 * puts the check mark before it, so a selected label that fills the width runs under the
 * button's edge, and autoSize, which only sees that width, never shrinks it. So beside the mark
 * the label measures itself narrower by the mark and its gap. Covered by SegmentLabelTest.
 */
@Composable
private fun SegmentLabel(text: String, besideCheckMark: Boolean) {
    val room = if (besideCheckMark) SegmentedButtonDefaults.IconSize + CheckMarkGap else 0.dp
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(
            minFontSize = SegmentLabelMinFontSize,
            maxFontSize = LocalTextStyle.current.fontSize,
        ),
        // A layout modifier, not BoxWithConstraints: the row asks its segments for intrinsic
        // sizes, which a SubcomposeLayout refuses (a crash on opening setup).
        modifier = Modifier.layout { measurable, constraints ->
            val narrowed = if (constraints.hasBoundedWidth) {
                val max = (constraints.maxWidth - room.roundToPx()).coerceAtLeast(0)
                constraints.copy(minWidth = minOf(constraints.minWidth, max), maxWidth = max)
            } else {
                constraints
            }
            val placeable = measurable.measure(narrowed)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        },
    )
}

/** The gap SegmentedButton leaves between its check mark and label (private there: `IconSpacing`). */
private val CheckMarkGap = 8.dp

private const val PrivateTracksHint = "Private tracks never appear on widgets."
private const val SelectionHint =
    "Choose the tracks to show. New tracks appear automatically. $PrivateTracksHint"

/** The hint under a setup screen's top controls, plus the line every setup screen shares. */
@Composable
private fun SetupHint(hint: String) {
    Column(
        modifier = Modifier
            .widthIn(max = MaxContentWidth)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Widgets can only increment a counter; to lower it, use the app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The Tracks settings row style: 40dp badge, name, and a trailing control. */
@Composable
private fun TrackRow(item: ConfigTrack, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TrackBadge(track = item.track, prefs = item.prefs)
        Text(
            text = item.track.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}
