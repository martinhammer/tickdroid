package com.martinhammer.tickdroid.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackColor
import com.martinhammer.tickdroid.domain.TrackPrefs

/**
 * 40dp circle in the track's colour, showing its emoji or two-letter abbreviation. [muted] (an
 * archived track) draws it neutral instead: `surfaceContainerHighest`, letters in
 * `onSurfaceVariant`, an emoji at 60% alpha, and the track's own colour isn't shown.
 */
@Composable
internal fun TrackBadge(track: Track, prefs: TrackPrefs, muted: Boolean = false) {
    val customColor = TrackColor.fromKey(prefs.colorKey).takeUnless { muted }
    val container = when {
        muted -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> customColor?.container ?: MaterialTheme.colorScheme.primaryContainer
    }
    val onContainer = when {
        muted -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> customColor?.onContainer ?: MaterialTheme.colorScheme.onPrimaryContainer
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
                modifier = if (muted) Modifier.alpha(0.6f) else Modifier,
            )
        } else {
            Text(
                text = track.name.take(2).uppercase(),
                color = onContainer,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
