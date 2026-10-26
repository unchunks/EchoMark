package com.unchunks.echomark.data.ai.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 端末内 AI のチャットの出力の見張り(繰り返し・長すぎる出力で止める)。 */
class ChatOutputGuardTest {

    @Test
    fun 普通の回答はそのまま流す() {
        val guard = ChatOutputGuard()

        val streamed = listOf("Kotlin の", "コルーチンは\n", "軽量なスレッドです。").joinToString("") { guard.accept(it) }

        assertEquals("Kotlin のコルーチンは\n軽量なスレッドです。", streamed)
        assertFalse(guard.stopped)
    }

    @Test
    fun 同じ行が3回目に出てきたら止めて_その行は流さない() {
        val guard = ChatOutputGuard()
        val line = "保存した記事によると、要点は次のとおりです。\n"

        val streamed = buildString {
            append(guard.accept("はじめに\n"))
            repeat(5) { append(guard.accept(line)) }
        }

        assertTrue(guard.stopped)
        assertEquals("はじめに\n" + line + line, streamed)
        // 止めた後の出力は捨てる
        assertEquals("", guard.accept("続き"))
    }

    @Test
    fun 短い行は同じでも繰り返しとみなさない() {
        val guard = ChatOutputGuard()

        repeat(5) { guard.accept("---\n") }

        assertFalse(guard.stopped)
    }

    @Test
    fun 上限の文字数を超えたらそこまでで止める() {
        val guard = ChatOutputGuard(maxChars = 10)

        val streamed = guard.accept("あいうえお") + guard.accept("かきくけこさしすせそ")

        assertTrue(guard.stopped)
        assertEquals("あいうえおかきくけこ", streamed)
    }
}
