package com.unchunks.echomark.domain.bookmark.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitedAccessTest {

    @Test
    fun 有料とログインの印は本文の先頭の行になり_ボット対策は印を入れない() {
        assertTrue(LimitedAccess.note(PageAccessLimit.PAYWALL).startsWith(LimitedAccess.MARKER))
        assertTrue(LimitedAccess.note(PageAccessLimit.LOGIN_REQUIRED).startsWith(LimitedAccess.MARKER))
        assertEquals("", LimitedAccess.note(PageAccessLimit.BLOCKED))
    }

    @Test
    fun 印のある本文は一部のみ取得として扱う() {
        assertTrue(LimitedAccess.isLimited(LimitedAccess.note(PageAccessLimit.PAYWALL) + "\n\n冒頭の本文"))
        assertFalse(LimitedAccess.isLimited("普通の本文"))
    }

    @Test
    fun 一部のみの本文からの要約には注記を付け_二重には付けない() {
        val source = LimitedAccess.note(PageAccessLimit.PAYWALL) + "\n\n冒頭の本文"

        val annotated = LimitedAccess.annotateSummary("記事の導入部の要約。", source)

        assertEquals("記事の導入部の要約。(一部のみ)", annotated)
        assertEquals(annotated, LimitedAccess.annotateSummary(annotated, source))
    }

    @Test
    fun 全文の要約_空の要約には注記を付けない() {
        assertEquals("要約", LimitedAccess.annotateSummary("要約", "普通の本文"))
        assertEquals("", LimitedAccess.annotateSummary("", LimitedAccess.MARKER))
    }
}
