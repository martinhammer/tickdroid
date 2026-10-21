package com.martinhammer.tickdroid.domain

/**
 * A track's type. [fromWire] never throws: a type from a newer Tickbuddy becomes [Unknown], which
 * is shown read-only everywhere and never written (see CLAUDE.md, "Unknown track types").
 */
sealed interface TrackType {
    val wireValue: String

    data object YesNo : TrackType {
        override val wireValue = "boolean"
    }

    data object Counter : TrackType {
        override val wireValue = "counter"
    }

    /** A type from a newer Tickbuddy that this Tickdroid doesn't know. Shown read-only, never written. */
    data class Unknown(override val wireValue: String) : TrackType

    companion object {
        fun fromWire(value: String): TrackType = when (value) {
            YesNo.wireValue -> YesNo
            Counter.wireValue -> Counter
            else -> Unknown(value)
        }
    }
}
