package com.martinhammer.tickdroid.ui.settings

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.martinhammer.tickdroid.domain.TrackType
import java.time.format.DateTimeFormatter

/**
 * Archived tracks for the list: by name, in [collator]'s order (the ViewModel passes a PRIMARY
 * collator, so case and accents don't count, like the web's `localeCompare` with
 * `sensitivity: 'base'`), then by server id so equal names keep a stable order.
 */
internal fun sortArchived(rows: List<TrackRowState>, collator: Comparator<in String>): List<TrackRowState> =
    rows.sortedWith(
        compareBy<TrackRowState, String>(collator) { it.track.name }
            .thenBy { it.track.serverId ?: Long.MAX_VALUE },
    )

/** "Yes/No · Archived 14 Aug 2026". [date] is already formatted ([rememberArchivedDateFormatter]). */
internal fun archivedSubtitle(type: TrackType, date: String): String =
    "${trackTypeLabel(type)} · ${archivedLabel(date)}"

/** "Archived 14 Aug 2026": the archived dialog's text. */
internal fun archivedLabel(date: String): String = "Archived $date"

/**
 * Day, short month and year in the phone's locale, no weekday: "14 Aug 2026" in British English,
 * "Aug 14, 2026" in US English. The Android equivalent of the web's
 * `Intl.DateTimeFormat({ day: 'numeric', month: 'short', year: 'numeric' })`.
 */
@Composable
internal fun rememberArchivedDateFormatter(): DateTimeFormatter {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMMy"), locale)
    }
}
