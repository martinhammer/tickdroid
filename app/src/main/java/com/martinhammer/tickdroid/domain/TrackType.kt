package com.martinhammer.tickdroid.domain

/**
 * A track's type. [fromWire] never throws: a type from a newer Tickbuddy becomes [Unknown], which
 * is shown read-only everywhere and never written (see CLAUDE.md, "Unknown track types").
 *
 * A [Number] day holds a reading in whole thousandths (10.4 is 10400), a [Choice] day the place
 * (1 to n) of one of the track's options. For both, any stored value is an entry: 0 and negative
 * Numbers count, so "has an entry" is "has a row", never `value > 0`.
 */
sealed interface TrackType {
    val wireValue: String

    data object YesNo : TrackType {
        override val wireValue = "boolean"
    }

    data object Counter : TrackType {
        override val wireValue = "counter"
    }

    /** A reading typed in each day, with an optional unit (Tickbuddy 3.2.0). */
    data object Number : TrackType {
        override val wireValue = "number"
    }

    /** One option from the track's fixed list (Tickbuddy 3.2.0). */
    data object Choice : TrackType {
        override val wireValue = "choice"
    }

    /** A type from a newer Tickbuddy that this Tickdroid doesn't know. Shown read-only, never written. */
    data class Unknown(override val wireValue: String) : TrackType

    companion object {
        fun fromWire(value: String): TrackType = when (value) {
            YesNo.wireValue -> YesNo
            Counter.wireValue -> Counter
            Number.wireValue -> Number
            Choice.wireValue -> Choice
            else -> Unknown(value)
        }
    }
}
