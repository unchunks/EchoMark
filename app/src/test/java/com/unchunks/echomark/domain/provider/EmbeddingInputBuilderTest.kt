package com.unchunks.echomark.domain.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingInputBuilderTest {

    private val v1 = EmbeddingModelProfile.GEMMA_V1

    @Test
    fun タイトル_要約_本文の先頭の順につなぐ() {
        assertEquals("題\n要約\n本文", EmbeddingInputBuilder.build("題", "要約", "本文", v1))
        assertEquals("題\n本文", EmbeddingInputBuilder.build("題", null, "本文", v1))
        assertEquals("題\n要約", EmbeddingInputBuilder.build("題", "要約", "", v1))
    }

    @Test
    fun 空白だけの項目は含めない() {
        assertEquals("本文", EmbeddingInputBuilder.build(" ", "  ", "本文", v1))
        assertEquals("", EmbeddingInputBuilder.build("", null, null, v1))
    }

    @Test
    fun 本文はプロファイルの上限までしか含めない() {
        assertEquals(1_000, EmbeddingInputBuilder.build("", null, "あ".repeat(5_000), v1).length)
        val wide = v1.copy(maxContentChars = 3_000, maxInputTokens = 8192)
        assertEquals(3_000, EmbeddingInputBuilder.build("", null, "あ".repeat(5_000), wide).length)
    }

    @Test
    fun 全体は文字数の予算に収まる() {
        val small = v1.copy(maxInputTokens = 400, maxContentChars = 10_000) // 予算 352 文字
        val budget = EmbeddingInputBuilder.charBudget(small)
        assertEquals(400 - EmbeddingInputBuilder.RESERVED_TOKENS, budget)

        val text = EmbeddingInputBuilder.build("題".repeat(50), "要".repeat(1_000), "本".repeat(1_000), small)

        assertTrue(text.length <= budget)
    }

    @Test
    fun 長い要約でも本文の先頭が残り_タイトルは切らない() {
        val small = v1.copy(maxInputTokens = 448, maxContentChars = 10_000) // 予算 400 文字
        val text = EmbeddingInputBuilder.build("題名", "要".repeat(1_000), "本".repeat(1_000), small)
        val lines = text.split("\n")

        assertEquals("題名", lines[0])
        // 要約は予算の半分(200 文字)まで
        assertEquals(200, lines[1].length)
        // 残り(400 - 2 - 1 - 200 - 1 = 196 文字)が本文
        assertEquals(196, lines[2].length)
        assertEquals(400, text.length)
    }

    @Test
    fun 極端に長いタイトルは上限で切る() {
        val text = EmbeddingInputBuilder.build("題".repeat(5_000), "要約", "本文", v1)

        assertEquals("題".repeat(EmbeddingInputBuilder.MAX_TITLE_CHARS) + "\n要約\n本文", text)
    }

    @Test
    fun サロゲートペアの途中では切らない() {
        val emoji = "😀" // 😀(2 char)
        val small = v1.copy(maxInputTokens = 48 + 256, maxContentChars = 10_000) // 予算は下限の 256 文字
        // 本文の枠(256 - 1 - 1 = 254 でちょうど絵文字の途中)
        val text = EmbeddingInputBuilder.build("題", null, "あ" + emoji.repeat(300), small)

        assertTrue(text.length <= 256)
        assertTrue(!text.last().isHighSurrogate())
    }

    @Test
    fun 予算はトークン上限から予約分を引いて文字数へ換算する() {
        assertEquals(2048 - EmbeddingInputBuilder.RESERVED_TOKENS, EmbeddingInputBuilder.charBudget(v1))
        val ratio = v1.copy(charsPerToken = 1.5)
        assertEquals(((2048 - EmbeddingInputBuilder.RESERVED_TOKENS) * 1.5).toInt(), EmbeddingInputBuilder.charBudget(ratio))
    }

    @Test
    fun 予算が小さすぎる設定でも下限の文字数は確保する() {
        val tiny = v1.copy(maxInputTokens = 10)
        assertTrue(EmbeddingInputBuilder.charBudget(tiny) >= 256)
    }
}
