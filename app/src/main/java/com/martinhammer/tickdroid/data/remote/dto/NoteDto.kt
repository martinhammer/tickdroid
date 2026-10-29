package com.martinhammer.tickdroid.data.remote.dto

import kotlinx.serialization.Serializable

/** One entry of `GET /api/notes`. The server has no note ids: track and date are the key. */
@Serializable
data class NoteDto(
    val trackId: Long,
    val date: String,
    val text: String,
)

/** The answer to `notes/set` and `notes/append`: the stored note, "" if none. */
@Serializable
data class NoteTextResponse(val text: String)
