package com.unchunks.echomark.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 埋め込みの入力がモデルのトークン数の上限を超えたときに縮める長さ。 */
class EmbeddingInputLimitTest {

    /** 実機(EmbeddingGemma の MediaPipe 版)で出たエラー */
    private fun tooLong(tokens: Int, maxSeqLen: Int = 512) =
        "internal: CalculatorGraph::Run() failed: \nCalculator::Process() for node \"..GeckoPreprocessorCalculator\" failed: " +
            "RET_CHECK failure (third_party/mediapipe/calculators/tensor/gecko_preprocessor_calculator.cc:150) " +
            "token_ids.size() + 2 <= max_seq_len_ (${tokens + 2} vs. $maxSeqLen) Input text is too long: $tokens tokens. " +
            "size() + 2 <= max_seq_len_ ($maxSeqLen) is required."

    @Test
    fun 超えたトークン数の割合で末尾を削り_上限より少し短くする() {
        val text = "あ".repeat(1_250)

        val shorter = shortenedToFitTokenLimit(text, tooLong(tokens = 687))!!

        // 1,250 × 510 / 687 × 0.9 ≒ 835
        assertEquals(835, shorter.length)
        assertTrue(text.startsWith(shorter))
    }

    @Test
    fun エラーに書かれた上限を使う() {
        val shorter = shortenedToFitTokenLimit("a".repeat(1_000), tooLong(tokens = 1_000, maxSeqLen = 258))!!

        // 1,000 × 256 / 1,000 × 0.9 ≒ 230
        assertEquals(230, shorter.length)
    }

    @Test
    fun 見積もりが縮まないときも1文字以上短くする() {
        assertEquals(9, shortenedToFitTokenLimit("a".repeat(10), tooLong(tokens = 11, maxSeqLen = 1_000))!!.length)
    }

    @Test
    fun サロゲートペアの途中で切らない() {
        val text = "😀".repeat(100)

        // 200 × 110 / 200 × 0.9 = 99 文字目はペアの前半なので、98 文字で切る
        val shorter = shortenedToFitTokenLimit(text, tooLong(tokens = 200, maxSeqLen = 112))!!

        assertEquals("😀".repeat(49), shorter)
    }

    @Test
    fun 長すぎる以外のエラーや_縮められないときはnull() {
        assertNull(shortenedToFitTokenLimit("本文", null))
        assertNull(shortenedToFitTokenLimit("本文", "internal: model not found"))
        assertNull(shortenedToFitTokenLimit("あ", tooLong(tokens = 600)))
    }
}
