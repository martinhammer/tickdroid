package com.martinhammer.tickdroid.ui.journal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Bottom padding that lets the last row scroll clear of [JumpToTodayChip]. The chip's 32dp body
 * sits centred in a 48dp touch target 16dp above the bottom, so its visible top is 56dp up; 72dp
 * leaves the same 16dp gap above it.
 */
internal val ChipClearance = 72.dp

/**
 * Floating "Today" chip shown by both journal layouts once the user has scrolled away from
 * today. The caller places it at the bottom end of a box filling the pull-to-refresh area.
 *
 * Only the horizontal insets are applied here, matching the grids: the Scaffold's inner padding
 * already includes the bottom navigation-bar inset (there is no bottomBar), so adding it again
 * would double the gap.
 */
@Composable
internal fun JumpToTodayChip(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier
            .windowInsetsPadding(
                WindowInsets.navigationBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal)
            )
            .padding(end = RightPad, bottom = 16.dp),
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
    ) {
        ElevatedAssistChip(
            onClick = onClick,
            label = { Text("Today") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.CalendarToday,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
            modifier = Modifier.testTag("jumpToToday"),
        )
    }
}
