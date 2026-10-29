package com.martinhammer.tickdroid.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteTextTest {

    @Test fun `line breaks become newlines`() {
        assertEquals("a\nb\nc", cleanNoteInput("a\r\nb\rc"))
    }

    @Test fun `control characters go, tab and newline stay`() {
        assertEquals("ab\tc\nd", cleanNoteInput("a\u001Bb\tc\u0000\n\u007Fd\u0085"))
        assertEquals("x", cleanNoteInput("\u0080x\u009F"))
    }

    @Test fun `cut to 500 code points, never splitting an emoji`() {
        assertEquals(500, cleanNoteInput("a".repeat(501)).length)
        val text = "a".repeat(499) + "😀" + "b"
        val cut = cleanNoteInput(text)
        assertEquals("a".repeat(499) + "😀", cut)
        assertEquals(500, cut.codePointCount(0, cut.length))
    }

    @Test fun `normaliseNote trims spaces, tabs and line breaks at both ends only`() {
        assertEquals("a\n\nb", normaliseNote(" \t\na\n\nb \n\t"))
        assertEquals("", normaliseNote("\n \n"))
        // Not Kotlin's trim(): other whitespace stays, as on the server.
        assertEquals(" a", normaliseNote(" a"))
    }

    // planNotePush, one case per row of the spec's table and then the edges.

    @Test fun `the server already has the phone's text - settle`() {
        assertEquals(NotePushPlan.Settle("a"), planNotePush(phone = "a", base = "", server = "a", canAppend = true))
    }

    @Test fun `server unchanged - the phone's version`() {
        assertEquals(NotePushPlan.Set("a\nb"), planNotePush(phone = "a\nb", base = "a", server = "a", canAppend = true))
        // A new note.
        assertEquals(NotePushPlan.Set("x"), planNotePush(phone = "x", base = "", server = "", canAppend = true))
    }

    @Test fun `phone emptied, server unchanged - delete`() {
        assertEquals(NotePushPlan.Delete, planNotePush(phone = "", base = "a", server = "a", canAppend = true))
    }

    @Test fun `changed elsewhere with new lines - append only those`() {
        assertEquals(
            NotePushPlan.Append("mine"),
            planNotePush(phone = "a\nmine", base = "a", server = "a\ntheirs", canAppend = true),
        )
    }

    @Test fun `changed elsewhere, phone only removed or reordered lines - settle to the server's`() {
        assertEquals(
            NotePushPlan.Settle("a\nb\ntheirs"),
            planNotePush(phone = "b", base = "a\nb", server = "a\nb\ntheirs", canAppend = true),
        )
        assertEquals(
            NotePushPlan.Settle("x\ntheirs"),
            planNotePush(phone = "b\na", base = "a\nb", server = "x\ntheirs", canAppend = true),
        )
    }

    @Test fun `phone deleted, server changed - settle, nothing removed`() {
        assertEquals(NotePushPlan.Settle("a\ntheirs"), planNotePush(phone = "", base = "a", server = "a\ntheirs", canAppend = true))
    }

    @Test fun `a line changed on the phone goes up in its new form, the old one stays`() {
        assertEquals(
            NotePushPlan.Append("run 6k"),
            planNotePush(phone = "run 6k", base = "run 5k", server = "run 5k\nstretch", canAppend = true),
        )
    }

    @Test fun `blank lines, lines the server has, and repeats aren't sent twice`() {
        assertEquals(
            NotePushPlan.Append("new"),
            planNotePush(phone = "new\n \ntheirs\nnew", base = "", server = "theirs", canAppend = true),
        )
    }

    @Test fun `no notesAppend - the phone's version even when changed elsewhere`() {
        assertEquals(NotePushPlan.Set("mine"), planNotePush(phone = "mine", base = "a", server = "theirs", canAppend = false))
        assertEquals(NotePushPlan.Delete, planNotePush(phone = "", base = "a", server = "theirs", canAppend = false))
    }

    @Test fun `a merge of exactly 500 is appended, 501 is too long`() {
        val server = "s".repeat(496)
        // server + "\n" + "abc" = 500
        assertEquals(NotePushPlan.Append("abc"), planNotePush(phone = "abc", base = "", server = server, canAppend = true))
        assertEquals(NotePushPlan.TooLong, planNotePush(phone = "abcd", base = "", server = server, canAppend = true))
    }

    @Test fun `an emoji counts once`() {
        val server = "s".repeat(496)
        assertEquals(NotePushPlan.Append("ab😀"), planNotePush(phone = "ab😀", base = "", server = server, canAppend = true))
    }
}
