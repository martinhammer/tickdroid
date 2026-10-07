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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackColor
import com.martinhammer.tickdroid.domain.TrackPrefs

/** 40dp circle in the track's colour, showing its emoji (desaturated) or two-letter abbreviation. */
@Composable
internal fun TrackBadge(track: Track, prefs: TrackPrefs) {
    val customColor = TrackColor.fromKey(prefs.colorKey)
    val container = customColor?.container ?: MaterialTheme.colorScheme.primaryContainer
    val onContainer = customColor?.onContainer ?: MaterialTheme.colorScheme.onPrimaryContainer
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
                text = track.name.take(2).uppercase(),
                color = onContainer,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
