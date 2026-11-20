package com.martinhammer.tickdroid.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class TrackDto(
    val id: Long,
    val name: String,
    val type: String,
    val sortOrder: Int,
    val private: Boolean,
    /** YYYY-MM-DD or null; absent on servers before archiving (Tickbuddy step 5a). */
    val archivedAt: String? = null,
    // Number and Choice settings (Tickbuddy 3.2.0): null on other types, absent on older servers.
    /** Number: the unit, "" for none. */
    val unit: String? = null,
    /** Number: 0 to 3. */
    val decimalPlaces: Int? = null,
    /** Number. */
    val allowNegative: Boolean? = null,
    /** Choice: every option, in place order. */
    val options: List<TrackOptionDto>? = null,
    /** Choice. */
    val orderedScale: Boolean? = null,
)

/** One option of a Choice track: [value] is its place, 1 to n. */
@Serializable
data class TrackOptionDto(
    val value: Int,
    val label: String,
)
