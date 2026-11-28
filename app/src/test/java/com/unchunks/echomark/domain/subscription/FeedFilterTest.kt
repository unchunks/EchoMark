package com.unchunks.echomark.domain.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 購読の新着の絞り込み(含む語・除く語)。 */
class FeedFilterTest {

    @Test
    fun 空白_読点_カンマで区切り_引用符の語句はそのまま() {
        assertEquals(
            listOf("AI", "LLM", "生成AI", "large language model"),
            FeedFilter.parse(" AI、LLM,生成AI  \"large  language model\" ")
        )
    }

    @Test
    fun 全角半角大文字小文字の違いは同じ語として一度だけ() {
        assertEquals(listOf("AI"), FeedFilter.parse("AI ａｉ ai"))
    }

    @Test
    fun 語の数には上限がある() {
        assertEquals(FeedFilter.MAX_WORDS, FeedFilter.parse((1..50).joinToString(" ") { "w$it" }).size)
    }

    @Test
    fun 絞り込みが無ければすべて保存する() {
        val filter = FeedFilter()

        assertTrue(filter.isEmpty)
        assertTrue(filter.accepts("何でも"))
        assertTrue(filter.accepts(null))
    }

    @Test
    fun 含む語はどれかを含むものだけ_違いを見ない() {
        val filter = FeedFilter(include = listOf("ai", "Kotlin"))

        assertTrue(filter.accepts("生成ＡＩの新機能"))
        assertTrue(filter.accepts("KOTLIN 2.5 released"))
        assertFalse(filter.accepts("今日の天気"))
        assertFalse(filter.accepts(null))
    }

    @Test
    fun 除く語は含む語より優先する() {
        val filter = FeedFilter(include = listOf("AI"), exclude = listOf("PR", "広告"))

        assertTrue(filter.accepts("AI の論文"))
        assertFalse(filter.accepts("【PR】AI ツールの紹介"))
        assertFalse(filter.accepts("広告: 最新の AI"))
        assertTrue(FeedFilter(exclude = listOf("PR")).accepts(null))
    }

    @Test
    fun DBの形と入力の形に戻せる() {
        val words = listOf("AI", "large language model")

        assertEquals(words, FeedFilter.decode(FeedFilter.encode(words)))
        assertEquals("AI \"large language model\"", FeedFilter.format(words))
        assertEquals(words, FeedFilter.parse(FeedFilter.format(words)))
        assertEquals(emptyList<String>(), FeedFilter.decode(""))
    }
}
