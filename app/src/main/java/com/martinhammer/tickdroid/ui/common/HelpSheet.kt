package com.martinhammer.tickdroid.ui.common

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

/**
 * "How to use Tickdroid", opened from "?" on the Journal and on Today. The two sheets share
 * their title and every sentence but the notes one, so they live here together and stay aligned.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HelpSheet(paragraphs: List<String>, onDismiss: () -> Unit) {
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
            Text(HelpTitle, style = MaterialTheme.typography.headlineSmall)
            paragraphs.forEach { paragraph ->
                Spacer(Modifier.height(12.dp))
                Text(paragraph, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

internal const val HelpTitle = "How to use Tickdroid"

private const val TracksSentence =
    "Add and manage tracks in Tracks settings. They're shared with Tickbuddy on your Nextcloud."
private const val CellsSentence =
    "Tap a cell to toggle a yes/no value or increment a counter; long-press to decrement a counter value."
private const val SettingsSentence =
    "Editable days, show/hide private tracks, and UI options are configured in App settings."
private const val ColoursSentence =
    "Custom track colours and heading icons can be configured in Tracks settings."

/** The Journal's paragraphs; the notes one only when the server keeps notes. */
internal fun journalHelpParagraphs(notes: Boolean): List<String> = listOfNotNull(
    TracksSentence,
    CellsSentence,
    if (notes) "Tap a day to open it on Today, where you can read and write its notes." else null,
    SettingsSentence,
    ColoursSentence,
)

/** Today's paragraphs: the Journal's, with Today's own notes sentence. */
internal fun todayHelpParagraphs(notes: Boolean): List<String> = listOfNotNull(
    TracksSentence,
    CellsSentence,
    if (notes) "Tap a track's name to add or change its note for the day." else null,
    SettingsSentence,
    ColoursSentence,
)
