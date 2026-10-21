package com.martinhammer.tickdroid.ui.common

// What a track of a type this Tickdroid doesn't know (TrackType.Unknown) says, in the app and on
// the widgets. Such a track is read-only: a newer Tickdroid is needed to enter its values.

/** Shown wherever the type label ("Yes/No", "Counter") would be. */
internal const val UPDATE_REQUIRED = "Tickdroid update required"

/** The toast for a tap on one of its cells. */
internal fun updateRequiredToast(name: String): String = "$name: $UPDATE_REQUIRED."

/** The content description of one of its cells. */
internal fun updateRequiredDescription(name: String): String = "$name, $UPDATE_REQUIRED"
