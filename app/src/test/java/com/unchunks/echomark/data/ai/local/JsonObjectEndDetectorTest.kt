package com.unchunks.echomark.data.ai.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonObjectEndDetectorTest {

    /** [chunks] を順に渡し、閉じた位置まで切り出した文字列を返す(閉じなければ null)。 */
    private fun detect(vararg chunks: String): String? {
        val detector = JsonObjectEndDetector()
        val text = StringBuilder()
        for (chunk in chunks) {
            text.append(chunk)
            if (detector.feed(chunk)) return text.substring(0, detector.endIndex)
        }
        return null
    }

    @Test
    fun 増分に分かれていてもオブジェクトの閉じた位置を見つける() {
        assertEquals(
            """{"summary": "要約", "tags": ["a"]}""",
            detect("""{"sum""", """mary": "要約", "ta""", """gs": ["a"]}""", "\n繰り返し繰り返し")
        )
    }

    @Test
    fun 前置きやコードブロックの後のオブジェクトも見つける() {
        assertEquals(
            "はい。\n```json\n{\"a\": {\"b\": 1}}",
            detect("はい。\n```json\n{\"a\": {", "\"b\": 1}}", "\n```")
        )
    }

    @Test
    fun 文字列の中の括弧とエスケープされた引用符は数えない() {
        val json = """{"summary": "a } b { \" } c", "tags": []}"""
        assertEquals(json, detect(json, " 続き"))
    }

    @Test
    fun オブジェクトの外の引用符や閉じ括弧は無視する() {
        assertEquals("""「"引用"」} {"a": 1}""", detect("""「"引用"」} {"a": 1}"""))
    }

    @Test
    fun 閉じるまでは見つからない() {
        val detector = JsonObjectEndDetector()
        assertFalse(detector.feed("{\"summary\": \"途中\""))
        assertFalse(detector.feed(", \"tags\": [\"a\""))
        assertEquals(-1, detector.endIndex)
        assertTrue(detector.feed("]}"))
    }
}
