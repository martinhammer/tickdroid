package com.martinhammer.tickdroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.martinhammer.tickdroid.domain.CellLabel
import com.martinhammer.tickdroid.domain.MAX_DECIMAL_PLACES
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackPrefs
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.ui.common.MaxContentWidth
import com.martinhammer.tickdroid.ui.entry.PlaceCircle
import com.martinhammer.tickdroid.widget.SegmentLabel

/** The track screen's Number and Choice controls, as callbacks (the screen maps them to the view model). */
internal class TypeSettingsActions(
    val onChangeUnit: () -> Unit = {},
    val onDecimalPlacesChange: (Int) -> Unit = {},
    val onAllowNegativeChange: (Boolean) -> Unit = {},
    val onRenameOption: (place: Int) -> Unit = {},
    val onOrderedScaleChange: (Boolean) -> Unit = {},
    val onCellLabelChange: (CellLabel) -> Unit = {},
)

/**
 * A Number's or Choice's settings under Private (decision 8, mock-up I), nothing for other types
 * or while the track waits for its first sync (its settings aren't known yet). Saved at once and
 * online only, behind [OfflineGuard] as Private, except "Journal cells", which is kept on this
 * phone and works offline, as colour and icon.
 */
@Composable
internal fun TypeSettingsSection(
    track: Track,
    prefs: TrackPrefs,
    online: Boolean,
    serverEditable: Boolean,
    pending: TypeSettingsState,
    actions: TypeSettingsActions,
    onOfflineTap: () -> Unit,
) {
    if (track.awaitingSync) return
    when (track.type) {
        TrackType.Number -> NumberSettings(track, online, serverEditable, pending, actions, onOfflineTap)
        TrackType.Choice -> ChoiceSettings(track, prefs, online, serverEditable, pending, actions, onOfflineTap)
        else -> Unit
    }
}

@Composable
private fun NumberSettings(
    track: Track,
    online: Boolean,
    serverEditable: Boolean,
    pending: TypeSettingsState,
    actions: TypeSettingsActions,
    onOfflineTap: () -> Unit,
) {
    Column(Modifier.testTag("numberSettings")) {
        // Unit: the value and a pencil at the end; the whole row opens "Change unit", as Decimal places opens its menu.
        OfflineGuard(online = online, onBlockedTap = onOfflineTap) { enabled ->
            val canChange = enabled && serverEditable
            SettingRow(
                label = "Unit",
                enabled = canChange,
                onClick = actions.onChangeUnit,
                onClickLabel = "Change unit",
                modifier = Modifier.testTag("unitRow"),
            ) {
                // Its own weighted, end-aligned row: the label is weighted too, so a weighted Text
                // here would split the free space with it and float the unit mid-row.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        track.unit.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    // Drawn plain, not as an IconButton, so it ends at the edge like Decimal places' ▾.
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = null,
                        tint = if (canChange) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        // Decimal places: a row of the same kind, its value opening a 0 to 3 menu.
        OfflineGuard(online = online, onBlockedTap = onOfflineTap) { enabled ->
            val places = pending.pendingDecimalPlaces ?: track.places
            val canChange = enabled && serverEditable && pending.pendingDecimalPlaces == null
            var expanded by remember { mutableStateOf(false) }
            SettingRow(
                label = "Decimal places",
                enabled = canChange,
                onClick = { expanded = true },
                modifier = Modifier.testTag("decimalPlacesRow"),
            ) {
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(places.toString(), style = MaterialTheme.typography.bodyLarge)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        (0..MAX_DECIMAL_PLACES).forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.toString()) },
                                onClick = {
                                    expanded = false
                                    if (option != places) actions.onDecimalPlacesChange(option)
                                },
                            )
                        }
                    }
                }
            }
        }
        OfflineGuard(online = online, onBlockedTap = onOfflineTap) { enabled ->
            ToggleRow(
                label = "Allow negative values",
                checked = pending.pendingAllowNegative ?: (track.allowNegative == true),
                onCheckedChange = actions.onAllowNegativeChange,
                enabled = enabled && serverEditable && pending.pendingAllowNegative == null,
            )
        }
    }
}

@Composable
private fun ChoiceSettings(
    track: Track,
    prefs: TrackPrefs,
    online: Boolean,
    serverEditable: Boolean,
    pending: TypeSettingsState,
    actions: TypeSettingsActions,
    onOfflineTap: () -> Unit,
) {
    Column(Modifier.testTag("choiceSettings")) {
        SectionLabel(label = "Options", subtitle = "Tap an option to rename it")
        Spacer(Modifier.height(8.dp))
        track.options.forEachIndexed { index, label ->
            val place = index + 1
            OfflineGuard(online = online, onBlockedTap = onOfflineTap) { enabled ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .widthIn(max = MaxContentWidth)
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(enabled = enabled && serverEditable, onClickLabel = "Rename option") {
                            actions.onRenameOption(place)
                        }
                        .testTag("option-$place"),
                ) {
                    PlaceCircle(place)
                    Spacer(Modifier.width(16.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OfflineGuard(online = online, onBlockedTap = onOfflineTap) { enabled ->
            ToggleRow(
                label = "Ordered scale",
                checked = pending.pendingOrderedScale ?: (track.orderedScale == true),
                onCheckedChange = actions.onOrderedScaleChange,
                enabled = enabled && serverEditable && pending.pendingOrderedScale == null,
            )
        }
        Spacer(Modifier.height(24.dp))
        SectionLabel(label = "Journal cells", subtitle = "What a day shows in the Journal and widgets")
        Spacer(Modifier.height(12.dp))
        val current = prefs.cellLabel ?: CellLabel.Short
        val choices = listOf(CellLabel.Short to "Short label", CellLabel.Place to "Place number")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth()) {
            choices.forEachIndexed { index, (value, text) ->
                SegmentedButton(
                    selected = current == value,
                    onClick = { actions.onCellLabelChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = choices.size),
                    enabled = track.serverId != null,
                    modifier = Modifier.testTag("cellLabel-${value.key}"),
                ) {
                    SegmentLabel(text, besideCheckMark = current == value)
                }
            }
        }
    }
}

/** A setting's label at the start and its value (and control) at the end, as one row. */
@Composable
private fun SettingRow(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    end: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        end()
    }
}
