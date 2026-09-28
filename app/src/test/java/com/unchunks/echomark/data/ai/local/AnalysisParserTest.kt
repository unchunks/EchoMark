package com.unchunks.echomark.data.ai.local

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisParserTest {

    @Test
    fun 正常なJSONを解析する() {
        val result = AnalysisParser.parse(
            """{"summary": "要約です", "tags": ["a", "b"], "category": "技術"}""",
            "元テキスト"
        )
        assertEquals("要約です", result.summary)
        assertEquals(listOf("a", "b"), result.tags)
        assertEquals("技術", result.category)
    }

    @Test
    fun 前後に説明文やコードブロックがあってもJSONを取り出せる() {
        val response = """
            はい、分析結果です。
            ```json
            {"summary": "要約", "tags": ["x"], "category": "ニュース"}
            ```
            以上です。
        """.trimIndent()
        val result = AnalysisParser.parse(response, "元")
        assertEquals("要約", result.summary)
        assertEquals(listOf("x"), result.tags)
        assertEquals("ニュース", result.category)
    }

    @Test
    fun タグは整形され重複除去と最大件数の制限を受ける() {
        val result = AnalysisParser.parse(
            """{"summary": "s", "tags": ["#kotlin", " kotlin ", "", "b", "c", "d", "e", "f"], "category": "c"}""",
            "元"
        )
        assertEquals(listOf("kotlin", "b", "c", "d", "e"), result.tags)
        assertEquals(AnalysisParser.MAX_TAGS, result.tags.size)
    }

    @Test
    fun 要約が空なら入力テキストの先頭で代替する() {
        val source = "  " + "あ".repeat(150)
        val result = AnalysisParser.parse("""{"summary": "", "tags": [], "category": "技術"}""", source)
        assertEquals("あ".repeat(AnalysisParser.FALLBACK_SUMMARY_CHARS), result.summary)
        assertEquals("技術", result.category)
    }

    @Test
    fun カテゴリが空なら未分類になる() {
        val result = AnalysisParser.parse("""{"summary": "s", "tags": ["t"], "category": " "}""", "元")
        assertEquals("未分類", result.category)
    }

    @Test
    fun tagsやcategoryが欠けていても要約は使える() {
        val result = AnalysisParser.parse("""{"summary": "s"}""", "元")
        assertEquals("s", result.summary)
        assertEquals(emptyList<String>(), result.tags)
        assertEquals("未分類", result.category)
    }

    @Test
    fun JSONが無い出力はフォールバックになる() {
        val result = AnalysisParser.parse("すみません、分析できません", "元のテキスト")
        assertEquals("元のテキスト", result.summary)
        assertEquals(emptyList<String>(), result.tags)
        assertEquals("未分類", result.category)
    }

    @Test
    fun 壊れたJSONはフォールバックになる() {
        val result = AnalysisParser.parse("""{"summary": "途中で切れ""", "元のテキスト")
        assertEquals("元のテキスト", result.summary)
        assertEquals("未分類", result.category)
    }

    @Test
    fun 空文字の出力はフォールバックになる() {
        val result = AnalysisParser.parse("", "元")
        assertEquals("元", result.summary)
    }
}
