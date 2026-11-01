package com.unchunks.echomark.domain.bookmark.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingTimeTest {

    @Test
    fun 本文が無いか短いときは目安を出さない() {
        assertNull(ReadingTime.minutes(null))
        assertNull(ReadingTime.minutes("   "))
        assertNull(ReadingTime.minutes("短いメモです。"))
        assertNull(ReadingTime.minutes("a few words only"))
    }

    @Test
    fun 日本語は500文字で1分で切り上げる() {
        assertEquals(1, ReadingTime.minutes("あ".repeat(200)))
        assertEquals(1, ReadingTime.minutes("あ".repeat(500)))
        assertEquals(2, ReadingTime.minutes("あ".repeat(501)))
        assertEquals(6, ReadingTime.minutes("あ".repeat(2_600)))
    }

    @Test
    fun 英語は230語で1分で切り上げる() {
        assertEquals(1, ReadingTime.minutes("word ".repeat(100)))
        assertEquals(1, ReadingTime.minutes("word ".repeat(230)))
        assertEquals(2, ReadingTime.minutes("word ".repeat(231)))
    }

    @Test
    fun 日本語と英語が混ざれば足し合わせ_記号や空白は数えない() {
        // 500 文字で 1 分 + 230 語で 1 分 = ちょうど 2 分
        val text = "あ".repeat(500) + "。、" + " word,".repeat(230) + "\n\n"
        assertEquals(2, ReadingTime.minutes(text))
    }

    @Test
    fun 数字も語として数える() {
        assertEquals(1, ReadingTime.minutes("2026 ".repeat(200)))
    }

    @Test
    fun ブックマークの本文から目安を出し_言い方は約N分() {
        val bookmark = Bookmark(type = BookmarkType.URL, title = "記事", content = "あ".repeat(1_200), createdAt = 0, lastAccessedAt = 0)
        assertEquals(3, bookmark.readingMinutes())
        assertEquals("約3分", ReadingTime.label(3))
        assertNull(bookmark.copy(content = null).readingMinutes())
    }
}
