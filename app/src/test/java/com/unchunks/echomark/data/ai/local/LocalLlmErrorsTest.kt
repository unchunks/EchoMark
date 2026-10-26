package com.unchunks.echomark.data.ai.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 端末内 AI の失敗の見分け方。 */
class LocalLlmErrorsTest {

    @Test
    fun 入力が文脈長を超えたMediaPipeの失敗を見分ける() {
        val tooLong = RuntimeException(
            "INVALID_ARGUMENT: Input is too long for the model to process: current_step(0) + input_size(4210) was not less than maxTokens(4096)."
        )
        assertTrue(isInputTooLong(tooLong))
        // 包まれていても見分ける
        assertTrue(isInputTooLong(IllegalStateException("生成に失敗", tooLong)))
        assertFalse(isInputTooLong(RuntimeException("Failed to initialize session")))
    }
}
