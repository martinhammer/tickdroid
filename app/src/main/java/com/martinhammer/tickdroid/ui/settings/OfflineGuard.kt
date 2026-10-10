package com.martinhammer.tickdroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Toast text for a tap on a track control that's greyed out because the device is offline. */
internal const val OfflineMessage = "You're offline. Changes to tracks need a connection."

/**
 * Draws [content] disabled while offline, and turns a tap on it into [onBlockedTap] (the offline
 * toast). A disabled Compose control ignores taps, so the tap is caught by an overlay on top.
 * TalkBack still announces the control as disabled.
 */
@Composable
internal fun OfflineGuard(
    online: Boolean,
    onBlockedTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (enabled: Boolean) -> Unit,
) {
    Box(modifier) {
        content(online)
        if (!online) Box(Modifier.matchParentSize().clickable(onClickLabel = null, onClick = onBlockedTap))
    }
}
