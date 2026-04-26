package com.martinhammer.tickdroid.domain

enum class TrackType(val wireValue: String) {
    BOOLEAN("boolean"),
    COUNTER("counter");

    companion object {
        fun fromWire(value: String): TrackType =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unknown track type: $value")
    }
}
