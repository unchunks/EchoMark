package com.unchunks.echomark.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 長い本文の分け方(境目の選び方・長さのそろえ方)と、部分の間引き方。 */
class TextChunkerTest {

    @Test
    fun 上限以下ならそのまま1つ() {
        assertEquals(listOf("短い本文"), TextChunker.split("  短い本文\n", maxChars = 100))
        assertEquals(emptyList<String>(), TextChunker.split(" \n ", maxChars = 100))
    }

    @Test
    fun 段落の境目を優先して切る() {
        val first = "あ".repeat(60) + "。" + "い".repeat(20)
        val second = "う".repeat(70)
        val text = "$first\n\n$second"

        val parts = TextChunker.split(text, maxChars = 100)

        assertEquals(listOf(first, second), parts)
    }

    @Test
    fun 段落が無ければ文の終わりで切る() {
        val sentence = "これは文です。"
        val text = sentence.repeat(30) // 210 文字

        val parts = TextChunker.split(text, maxChars = 100)

        parts.forEach {
            assertTrue(it, it.length <= 100)
            assertTrue(it, it.endsWith("。"))
        }
        assertEquals(text, parts.joinToString(""))
    }

    @Test
    fun 英文は小数点では切らずピリオドと空白の後で切る() {
        val text = "Version 1.5 is out. " + "word ".repeat(30)

        val parts = TextChunker.split(text, maxChars = 100)

        assertTrue(parts.toString(), parts.all { it.length <= 100 })
        assertTrue(parts.none { it.endsWith("1.") })
    }

    @Test
    fun 境目が無ければ上限で切る() {
        val text = "あ".repeat(250)

        val parts = TextChunker.split(text, maxChars = 100)

        assertTrue(parts.all { it.length <= 100 })
        assertEquals(text, parts.joinToString(""))
    }

    @Test
    fun 部分の長さをそろえる() {
        // 101 文字を「100 + 1」ではなく、ほぼ半分ずつに分ける
        val text = "あ".repeat(101)

        val parts = TextChunker.split(text, maxChars = 100)

        assertEquals(2, parts.size)
        assertTrue(parts.toString(), parts.all { it.length in 40..60 })
    }

    @Test
    fun 間引くときは先頭と末尾を含めて均等に選ぶ() {
        assertEquals(listOf(0, 1, 2), TextChunker.selectEvenly(total = 3, max = 6))
        assertEquals(listOf(0, 3, 6, 9), TextChunker.selectEvenly(total = 10, max = 4))
        assertEquals(listOf(0), TextChunker.selectEvenly(total = 10, max = 1))
        val selected = TextChunker.selectEvenly(total = 7, max = 6)
        assertEquals(6, selected.size)
        assertEquals(0, selected.first())
        assertEquals(6, selected.last())
    }
}
