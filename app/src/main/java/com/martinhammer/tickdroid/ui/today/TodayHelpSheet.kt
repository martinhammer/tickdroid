package com.martinhammer.tickdroid.ui.today

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** "About Today", opened from Today's "?". Laid out as `TracksHelpSheet`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodayHelpSheet(notes: Boolean, stats: Boolean, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("About Today", style = MaterialTheme.typography.headlineSmall)
            todayHelpParagraphs(notes, stats).forEach { paragraph ->
                Spacer(Modifier.height(12.dp))
                Text(paragraph, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The sheet's paragraphs. The streak one only with `stats`, the notes one only with `notes`. */
internal fun todayHelpParagraphs(notes: Boolean, stats: Boolean): List<String> = listOfNotNull(
    "Today shows your tracks for one day. Swipe, or use ‹ and ›, to move to another day.",
    // The journal's sentence, unchanged.
    "Tap a cell to toggle a yes/no value or increment a counter; long-press to decrement a counter value.",
    "The squares show a week of ticks, newest on the left, with the day you're looking at outlined.",
    if (stats) "The text after them is the track's streak or break up to today." else null,
    if (notes) {
        "Tap a track's name to add or change its note for the day. Notes are shared with " +
            "Tickbuddy on your Nextcloud."
    } else {
        null
    },
    "Days you can change are set in App settings.",
)
