package com.unchunks.echomark.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回答を読み上げ用の文にする。 */
class AnswerSpeechTest {

    @Test
    fun 引用番号と記法を除いて読む() {
        assertEquals(
            listOf("要点\n大事な点です。\n二つ目です。"),
            AnswerSpeech.partsOf("## 要点\n**大事**な点です [1]。\n\n- 二つ目です [2, 3]。")
        )
    }

    @Test
    fun コードは読まない() {
        assertEquals(listOf("次の通りです。"), AnswerSpeech.partsOf("次の通りです。\n\n```kotlin\nval a = 1\n```"))
    }

    @Test
    fun 停止の印は読まない() {
        assertEquals(listOf("途中までの回答"), AnswerSpeech.partsOf("途中までの回答\n\n(停止)"))
    }

    @Test
    fun 長い回答は文の切れ目で分ける() {
        val sentence = "あ".repeat(400) + "。"

        val parts = AnswerSpeech.partsOf(sentence.repeat(5))

        assertEquals(3, parts.size)
        assertTrue(parts.all { it.length <= AnswerSpeech.MAX_PART_CHARS })
        assertTrue(parts.all { it.endsWith("。") })
    }

    @Test
    fun 上限より長い1文も分ける() {
        val parts = AnswerSpeech.partsOf("あ".repeat(AnswerSpeech.MAX_PART_CHARS * 2 + 1))

        assertEquals(3, parts.size)
        assertTrue(parts.all { it.length <= AnswerSpeech.MAX_PART_CHARS })
    }

    @Test
    fun 読むものが無ければ空() {
        assertEquals(emptyList<String>(), AnswerSpeech.partsOf("```\ncode\n```"))
    }
}
