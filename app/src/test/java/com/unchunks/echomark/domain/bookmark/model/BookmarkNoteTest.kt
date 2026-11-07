package com.unchunks.echomark.domain.bookmark.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkNoteTest {

    @Test
    fun 文章は前後の空白を除き_空白だけなら保存しない() {
        assertEquals("ノート", NoteText.cleanText("  ノート \n"))
        assertNull(NoteText.cleanText(""))
        assertNull(NoteText.cleanText(" \n\t "))
    }

    @Test
    fun 文章の途中の改行は残す() {
        assertEquals("一行目\n二行目", NoteText.cleanText("一行目\n二行目"))
    }

    @Test
    fun 長すぎる文章と引用は決めた長さで切る() {
        assertEquals(NoteText.MAX_TEXT, NoteText.cleanText("あ".repeat(NoteText.MAX_TEXT * 2))!!.length)
        assertEquals(NoteText.MAX_QUOTE, NoteText.cleanQuote("い".repeat(NoteText.MAX_QUOTE * 2))!!.length)
    }

    @Test
    fun 引用は空白だけならなしとして扱う() {
        assertNull(NoteText.cleanQuote(null))
        assertNull(NoteText.cleanQuote("   "))
        assertEquals("引用", NoteText.cleanQuote(" 引用 "))
    }

    @Test
    fun 直したかは更新日時が作成日時より後かで決まる() {
        assertFalse(BookmarkNote(bookmarkId = 1, text = "t", createdAt = 5).isEdited)
        assertTrue(BookmarkNote(bookmarkId = 1, text = "t", createdAt = 5, updatedAt = 6).isEdited)
    }
}
