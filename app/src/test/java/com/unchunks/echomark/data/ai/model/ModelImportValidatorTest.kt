package com.unchunks.echomark.data.ai.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelImportValidatorTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun taskとlitertlmを受け付ける() {
        assertNull(ModelImportValidator.validate("Gemma3-1B-IT_int4.task", gb, 10 * gb))
        assertNull(ModelImportValidator.validate("gemma-3n-E2B.LITERTLM", gb, 10 * gb))
        assertEquals(".litertlm", ModelImportValidator.supportedExtension("model.LiteRTLM"))
    }

    @Test
    fun 非対応の拡張子は拒否() {
        assertTrue(ModelImportValidator.validate("model.bin", gb, 10 * gb) is ModelImportError.UnsupportedFormat)
        assertTrue(ModelImportValidator.validate("model.task.zip", gb, 10 * gb) is ModelImportError.UnsupportedFormat)
    }

    @Test
    fun 空き容量が足りなければ拒否() {
        val error = ModelImportValidator.validate("model.task", 2 * gb, 2 * gb)
        assertTrue(error is ModelImportError.InsufficientStorage)
    }

    @Test
    fun 空ファイルは拒否_サイズ不明は容量チェックを後回し() {
        assertEquals(ModelImportError.EmptyFile, ModelImportValidator.validate("model.task", 0L, 10 * gb))
        assertNull(ModelImportValidator.validate("model.task", -1L, 0L))
    }

    @Test
    fun Gemma判定は表示名で行う() {
        assertTrue(LocalModelInfo("local_llm.task", "Gemma3-1B-IT.task", 1, 0).isGemma)
        assertEquals(false, LocalModelInfo("local_llm.task", "qwen2.5.task", 1, 0).isGemma)
    }
}
