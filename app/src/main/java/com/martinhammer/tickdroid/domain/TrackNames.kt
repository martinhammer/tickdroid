package com.martinhammer.tickdroid.domain

/**
 * Tickbuddy stores names in a `VARCHAR(255)`, which MySQL and PostgreSQL count in code points
 * (an emoji is one character, not two UTF-16 units). The server doesn't check the limit itself.
 */
const val MAX_TRACK_NAME_CHARS = 255

/** The trimmed name, or null when it is blank or longer than [MAX_TRACK_NAME_CHARS] code points. */
fun cleanTrackName(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.codePointCount(0, trimmed.length) > MAX_TRACK_NAME_CHARS) return null
    return trimmed
}

/** Cuts [input] to its first [MAX_TRACK_NAME_CHARS] code points, never splitting a surrogate pair. */
fun capTrackName(input: String): String {
    if (input.codePointCount(0, input.length) <= MAX_TRACK_NAME_CHARS) return input
    return input.substring(0, input.offsetByCodePoints(0, MAX_TRACK_NAME_CHARS))
}
