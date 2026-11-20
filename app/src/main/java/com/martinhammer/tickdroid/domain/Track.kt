package com.martinhammer.tickdroid.domain

import java.time.LocalDate

data class Track(
    val localId: Long,
    val serverId: Long?,
    val name: String,
    val type: TrackType,
    val sortOrder: Int,
    val private: Boolean,
    /** The day it was archived; null for an active track. */
    val archivedAt: LocalDate? = null,
    /** Number only: the unit, "" for none. Null on other types. */
    val unit: String? = null,
    /** Number only: 0 to 3. Null on other types. */
    val decimalPlaces: Int? = null,
    /** Number only. Null on other types. */
    val allowNegative: Boolean? = null,
    /** Choice only: the labels in place order (place 1 first). Empty on other types. */
    val options: List<String> = emptyList(),
    /** Choice only. Null on other types. */
    val orderedScale: Boolean? = null,
    /**
     * A Number or Choice track that hasn't had a successful pull since the update that taught
     * this Tickdroid its type: its local ticks were 1.5.0's markers and are gone, so it is
     * read-only until the pull brings the real values (see CLAUDE.md, "Number and Choice").
     */
    val awaitingSync: Boolean = false,
) {
    val archived: Boolean get() = archivedAt != null

    /** Number places, defaulting as the server does (1). */
    val places: Int get() = (decimalPlaces ?: 1).coerceIn(0, MAX_DECIMAL_PLACES)

    /**
     * Whether this Tickdroid can write the track's values: Yes/No and Counter, and Number and
     * Choice once they have synced. A type it doesn't know never.
     */
    val isEditableType: Boolean
        get() = when (type) {
            TrackType.YesNo, TrackType.Counter -> true
            TrackType.Number, TrackType.Choice -> !awaitingSync
            is TrackType.Unknown -> false
        }

    /** Number and Choice: a tap opens the card or the sheet instead of writing straight away. */
    val entersValue: Boolean get() = type == TrackType.Number || type == TrackType.Choice
}

/** The label for a Choice [place], or null when the track has no such option (only a bug). */
fun Track.optionLabel(place: Int): String? = options.getOrNull(place - 1)
