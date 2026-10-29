package com.martinhammer.tickdroid.domain

/**
 * Tickbuddy's limit for a note, in Unicode code points (`NoteService::MAX_LENGTH`). The server
 * refuses a longer note rather than cutting it, and a refusal with a `reason` makes the push drop
 * the change, so the phone never sends one.
 */
const val MAX_NOTE_CHARS = 500

/**
 * Text as it arrives in the note card: `\r\n` and `\r` become `\n`; C0 control characters other
 * than tab and line break, DEL and the C1 controls are dropped (the server refuses all of them);
 * then cut to [MAX_NOTE_CHARS] code points, never splitting a surrogate pair (as [capTrackName]).
 */
fun cleanNoteInput(input: String): String {
    val unified = input.replace("\r\n", "\n").replace('\r', '\n')
    val kept = unified.filterNot(::isRefusedControl)
    if (kept.codePointCount(0, kept.length) <= MAX_NOTE_CHARS) return kept
    return kept.substring(0, kept.offsetByCodePoints(0, MAX_NOTE_CHARS))
}

/**
 * A note as stored: [cleanNoteInput], then spaces, tabs and line breaks trimmed at both ends,
 * exactly the server's trim (not Kotlin's `trim()`, which also takes other whitespace). Every
 * stored text and every comparison goes through this.
 */
fun normaliseNote(text: String): String = cleanNoteInput(text).trim(' ', '\t', '\n')

private fun isRefusedControl(c: Char): Boolean =
    (c < ' ' && c != '\n' && c != '\t') || c in '\u007F'..'\u009F'

/** What the push does with one dirty note. See [planNotePush]. */
sealed interface NotePushPlan {
    /** Nothing to send: the row becomes the server's [text]. */
    data class Settle(val text: String) : NotePushPlan

    /** `POST notes/set` with [text], replacing the server's note. */
    data class Set(val text: String) : NotePushPlan

    /** `DELETE /api/notes`. */
    data object Delete : NotePushPlan

    /** `POST notes/append` with [text], the phone's new lines. */
    data class Append(val text: String) : NotePushPlan

    /** The merged note would be over [MAX_NOTE_CHARS]: nothing is sent, the server's note stays. */
    data object TooLong : NotePushPlan
}

/**
 * How to send the phone's note [phone], begun when the server held [base], now that the server
 * holds [server] (all normalised, "" for no note). [canAppend] is the `notesAppend` flag.
 *
 * - The server already has it: settle.
 * - The server's note is unchanged since the change began, or the server can't append: the
 *   phone's version (set, or delete when empty).
 * - Changed elsewhere: only the phone's new lines are appended, so nothing on the server is
 *   removed. New lines are the phone's non-blank lines found neither in [base] nor in [server],
 *   in the phone's order, each once: a line edited on the phone goes up in its new form and the
 *   old one stays; a line removed on the phone stays. No new lines (the phone only removed or
 *   reordered, or deleted the note): settle to the server's note.
 */
fun planNotePush(phone: String, base: String, server: String, canAppend: Boolean): NotePushPlan {
    if (phone == server) return NotePushPlan.Settle(server)
    if (server == base || !canAppend) {
        return if (phone.isEmpty()) NotePushPlan.Delete else NotePushPlan.Set(phone)
    }
    val known = base.split('\n').toSet() + server.split('\n')
    val newLines = phone.split('\n').filter { it.isNotBlank() && it !in known }.distinct()
    if (newLines.isEmpty()) return NotePushPlan.Settle(server)
    val text = newLines.joinToString("\n")
    val merged = if (server.isEmpty()) text else server + "\n" + text
    if (merged.codePointCount(0, merged.length) > MAX_NOTE_CHARS) return NotePushPlan.TooLong
    return NotePushPlan.Append(text)
}
