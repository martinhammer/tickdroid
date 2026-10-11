package com.martinhammer.tickdroid.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal val IconCircleSize = 48.dp
internal val IconCircleGap = 12.dp

/**
 * One scrolling row of 48dp circles: the track's two letters ("no icon"), "⋯" (the full emoji
 * picker), a custom emoji if one is set and isn't a habit, then the ten habit emojis. Edge fades
 * show there's more; opens scrolled to the selection.
 */
@Composable
internal fun TrackIconRow(
    trackName: String,
    current: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
    onMore: () -> Unit,
) {
    // The custom circle follows the latest non-habit icon, and stays when the user then picks a
    // habit or the letters, so the row never shifts under their finger.
    // Only ever read straight after this write, so it needn't be snapshot state.
    val custom = remember { arrayOfNulls<String>(1) }
    if (current != null && HABIT_EMOJIS.none { sameEmoji(it, current) }) custom[0] = current
    val choices = iconChoices(custom[0])
    val selected = selectedIndex(choices, current)
    val state = rememberLazyListState()
    OpeningScroll(state = state, selected = selected, itemSize = IconCircleSize, gap = IconCircleGap)

    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(IconCircleGap),
        modifier = Modifier.fillMaxWidth().edgeFade(state).testTag("iconRow"),
    ) {
        itemsIndexed(choices, key = { _, choice -> choice.toString() }) { index, choice ->
            when (choice) {
                IconChoice.Letters -> IconCircle(
                    isSelected = index == selected,
                    enabled = enabled,
                    description = "No icon",
                    onClick = { onSelect(null) },
                ) {
                    Text(
                        text = trackName.take(2).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconChoice.More -> IconCircle(
                    isSelected = false,
                    enabled = enabled,
                    description = "All emojis",
                    onClick = onMore,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreHoriz,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                is IconChoice.Emoji -> IconCircle(
                    isSelected = index == selected,
                    enabled = enabled,
                    description = choice.emoji,
                    onClick = { onSelect(choice.emoji) },
                ) {
                    Text(
                        text = choice.emoji,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun IconCircle(
    isSelected: Boolean,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val ringColor = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(IconCircleSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .let { if (isSelected) it.border(2.dp, ringColor, CircleShape) else it }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                selected = isSelected
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
