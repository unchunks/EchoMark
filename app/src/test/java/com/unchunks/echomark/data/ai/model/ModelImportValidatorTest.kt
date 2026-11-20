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
    fun 埋め込みモデルはtaskとtfliteを受け付けlitertlmは拒否() {
        val ext = ModelImportValidator.EMBEDDING_EXTENSIONS
        assertNull(ModelImportValidator.validate("embedding_gemma.task", gb, 10 * gb, ext))
        assertNull(ModelImportValidator.validate("model.TFLITE", gb, 10 * gb, ext))
        assertEquals(
            ModelImportError.UnsupportedFormat("m.litertlm", ext),
            ModelImportValidator.validate("m.litertlm", gb, 10 * gb, ext)
        )
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

    @Test
    fun 中身の形式はファイルの先頭で見分ける() {
        assertTrue(ModelImportValidator.matchesFormat(".litertlm", "LITERTLM\u0001\u0000".toByteArray(Charsets.ISO_8859_1)))
        assertTrue(ModelImportValidator.matchesFormat(".task", byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        // ZIP の .task を .litertlm に名前を変えたもの・HTML(途中で切れたダウンロードの画面など)・短すぎるファイル
        assertEquals(false, ModelImportValidator.matchesFormat(".litertlm", byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertEquals(false, ModelImportValidator.matchesFormat(".task", "<!DOCTYPE html>".toByteArray()))
        assertEquals(false, ModelImportValidator.matchesFormat(".litertlm", "LITE".toByteArray()))
        // 拡張子は大文字でもよい。形式の決まっていない拡張子は調べない
        assertTrue(ModelImportValidator.matchesFormat(".LITERTLM", "LITERTLM".toByteArray()))
        assertTrue(ModelImportValidator.matchesFormat(".tflite", ByteArray(0)))
    }

    @Test
    fun 旧形式のtaskと読み込めないときの案内() {
        val task = LocalModelInfo("local_llm.task", "gemma3-1b-it-int4.task", 1, 0)
        val litertlm = LocalModelInfo("local_llm.litertlm", "Qwen3-0.6B.litertlm", 1, 0)
        assertTrue(task.isLegacyTaskFormat)
        assertEquals(false, litertlm.isLegacyTaskFormat)
        assertTrue(LocalModelLoadFailure(task, null).userMessage.contains(".litertlm 版を取り込み直して"))
        assertTrue(LocalModelLoadFailure(litertlm, null).userMessage.contains("別のモデルを取り込み直して"))
    }
}
