package com.unchunks.echomark.domain.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 読み上げに1回で渡す単位への分け方。 */
class SpeechTextTest {

    @Test
    fun 文の終わりで区切り_上限まで詰める() {
        val text = SpeechText(listOf(SpeechPiece.Plain("一つ目。二つ目。三つ目。")))
        assertEquals(listOf("一つ目。二つ目。", "三つ目。"), text.chunks(maxChars = 8).map { it.plain })
        assertEquals(listOf("一つ目。二つ目。三つ目。"), text.chunks(maxChars = 100).map { it.plain })
    }

    @Test
    fun 読みに置き換えた部分は途中で切らない() {
        val reading = SpeechPiece.Reading("EchoMark", "エコーマーク", ReadingOrigin.BUILT_IN)
        val text = SpeechText(listOf(SpeechPiece.Plain("これは"), reading, SpeechPiece.Plain("です。次の文。")))
        val chunks = text.chunks(maxChars = 12)
        assertTrue(chunks.first().pieces.contains(reading))
        assertEquals("これはエコーマークです。次の文。", chunks.joinToString("") { it.plain })
    }

    @Test
    fun 長すぎる1文は読点で区切り_それも無ければ文字数で切る() {
        val text = SpeechText(listOf(SpeechPiece.Plain("あいうえお、かきくけこ、さしすせそ。")))
        val chunks = text.chunks(maxChars = 7).map { it.plain }
        assertTrue(chunks.all { it.length <= 7 })
        assertEquals("あいうえお、かきくけこ、さしすせそ。", chunks.joinToString(""))

        val long = SpeechText(listOf(SpeechPiece.Plain("あ".repeat(25))))
        assertEquals(listOf(10, 10, 5), long.chunks(maxChars = 10).map { it.plain.length })
    }

    @Test
    fun 空の部分は出さない() {
        assertEquals(emptyList<SpeechText>(), SpeechText(listOf(SpeechPiece.Plain(""))).chunks(10))
    }
}
