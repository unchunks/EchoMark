package com.unchunks.echomark.domain.model

import com.unchunks.echomark.data.ai.AiPrompts
import com.unchunks.echomark.data.ai.local.AnalysisParser
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.bookmark.model.SummaryProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** カテゴリ名の扱いと、標準のカテゴリの説明。 */
class CategoryNamesTest {

    @Test
    fun 名前は前後の空白を除き_連続した空白は1つにする() {
        assertEquals("仕事 の メモ", CategoryNames.clean("  仕事　 の  メモ \n"))
    }

    @Test
    fun キーは全角半角と大文字小文字を区別しない() {
        assertEquals(CategoryNames.key("Tech"), CategoryNames.key(" ＴＥＣＨ "))
    }

    @Test
    fun AIの答えは選べるカテゴリの名前にそろえ_当てはまらなければ整えた答えのまま() {
        val defined = listOf("Tech", "仕事")

        assertEquals("Tech", CategoryNames.resolve(" ＴＥＣＨ", defined))
        assertEquals("仕事", CategoryNames.resolve("仕事", defined))
        assertEquals("旅行", CategoryNames.resolve("  旅行 ", defined))
    }

    @Test
    fun 標準のカテゴリは名前が重ならず_全部に説明がある() {
        val keys = CategoryNames.BUILT_IN.map { CategoryNames.key(it.name) }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(CategoryNames.BUILT_IN.all { it.description.isNotBlank() })
        // どの名前も、AI が返せる長さ・自分のカテゴリの上限に収まる
        assertTrue(CategoryNames.BUILT_IN.all { it.name.length <= CategoryNames.MAX_NAME_CHARS })
    }

    @Test
    fun AIの分類の例にしているカテゴリは全部標準のカテゴリにある() {
        val examples = ContentKind.entries.flatMap { kind ->
            SummaryProfile.entries.flatMap { profile -> AiPrompts.categoryExamples(kind, profile) }
        }.toSet()

        examples.forEach { assertNotNull("「$it」に説明が無い", CategoryNames.builtInDescription(it)) }
    }

    @Test
    fun 未分類の名前はAIの解析の既定と同じ() {
        assertEquals(AnalysisParser.DEFAULT_CATEGORY, CategoryNames.UNCATEGORIZED)
    }

    @Test
    fun 標準でない名前に標準の説明は無い() {
        assertNull(CategoryNames.builtInDescription("旅行の計画"))
        assertEquals(CategoryNames.builtInDescription("技術"), CategoryNames.builtInDescription("技術 "))
    }
}
