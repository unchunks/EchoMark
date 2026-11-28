package com.unchunks.echomark.data.ai

import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AiTaskSetting
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeLlmProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 端末内モデルを先に読み込むかの判断(用途ごとの AI の設定から)。 */
class AiPrewarmerTest {

    private fun settings(tagging: LlmBackend, summary: LlmBackend, chat: LlmBackend = LlmBackend.LOCAL) = mapOf(
        AiTask.TAGGING to AiTaskSetting(backend = tagging),
        AiTask.SUMMARY to AiTaskSetting(backend = summary),
        AiTask.CHAT to AiTaskSetting(backend = chat)
    )

    @Test
    fun タグ付けか要約のどちらかが端末内なら使う() {
        assertTrue(usesLocalLlmForAnalysis(settings(LlmBackend.LOCAL, LlmBackend.LOCAL)))
        assertTrue(usesLocalLlmForAnalysis(settings(LlmBackend.LOCAL, LlmBackend.API)))
        assertTrue(usesLocalLlmForAnalysis(settings(LlmBackend.API, LlmBackend.LOCAL)))
    }

    @Test
    fun 両方クラウドなら使わない_チャットは関係しない() {
        assertFalse(usesLocalLlmForAnalysis(settings(LlmBackend.API, LlmBackend.API, chat = LlmBackend.LOCAL)))
    }

    @Test
    fun 設定の無い用途は既定の端末内として扱う() {
        assertTrue(usesLocalLlmForAnalysis(emptyMap()))
        assertTrue(usesLocalLlmForAnalysis(mapOf(AiTask.TAGGING to AiTaskSetting(backend = LlmBackend.API))))
    }

    @Test
    fun 端末内を使う設定のときだけ読み込みを始める() = runBlocking {
        val llm = FakeLlmProvider()
        val appSettings = FakeAppSettingsRepository(backend = LlmBackend.LOCAL)
        val prewarmer = AiPrewarmer(LocalLlmProviders { llm }, appSettings)

        assertTrue(prewarmer.prewarmLocalLlmIfUsed())
        assertEquals(1, llm.prewarmCalls)

        appSettings.setBackendForAll(LlmBackend.API)
        assertFalse(prewarmer.prewarmLocalLlmIfUsed())
        assertEquals(1, llm.prewarmCalls)
    }

    @Test
    fun 用途ごとに別の端末内モデルなら最初に動くタグ付けのモデルを読み込む() = runBlocking {
        val byModel = mutableMapOf<String?, FakeLlmProvider>()
        val appSettings = FakeAppSettingsRepository(backend = LlmBackend.LOCAL)
        val prewarmer = AiPrewarmer(LocalLlmProviders { id -> byModel.getOrPut(id) { FakeLlmProvider() } }, appSettings)
        appSettings.setLocalModel(AiTask.TAGGING, "tagger")
        appSettings.setLocalModel(AiTask.SUMMARY, "summarizer")

        assertTrue(prewarmer.prewarmLocalLlmIfUsed())
        assertEquals(1, byModel.getValue("tagger").prewarmCalls)
        assertFalse("summarizer" in byModel)
    }

    @Test
    fun 先に読み込むモデルはタグ付けが端末内ならそのモデル_そうでなければ要約のモデル() {
        val local = AiTaskSetting(backend = LlmBackend.LOCAL, localModelId = "a")
        val api = AiTaskSetting(backend = LlmBackend.API, localModelId = "a")
        val summary = AiTaskSetting(backend = LlmBackend.LOCAL, localModelId = "b")
        assertEquals("a", analysisLocalModelToPrewarm(mapOf(AiTask.TAGGING to local, AiTask.SUMMARY to summary)))
        assertEquals("b", analysisLocalModelToPrewarm(mapOf(AiTask.TAGGING to api, AiTask.SUMMARY to summary)))
        assertEquals(null, analysisLocalModelToPrewarm(emptyMap()))
    }

    @Test
    fun 要約だけ_タグだけの再処理ではその用途のモデルだけを見る() {
        val tagging = AiTaskSetting(backend = LlmBackend.LOCAL, localModelId = "a")
        val summary = AiTaskSetting(backend = LlmBackend.LOCAL, localModelId = "b")
        val settings = mapOf(AiTask.TAGGING to tagging, AiTask.SUMMARY to summary)
        assertEquals("b", analysisLocalModelToPrewarm(settings, AnalysisScope.SUMMARY))
        assertEquals("a", analysisLocalModelToPrewarm(settings, AnalysisScope.TAGS))

        // 片方だけ端末内なら、クラウド API の用途の再処理では端末内 AI を使わない
        val mixed = settings(tagging = LlmBackend.LOCAL, summary = LlmBackend.API)
        assertTrue(usesLocalLlmForAnalysis(mixed, AnalysisScope.TAGS))
        assertFalse(usesLocalLlmForAnalysis(mixed, AnalysisScope.SUMMARY))
    }
}
