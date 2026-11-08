package com.unchunks.echomark.domain.model

import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.domain.repository.AiTaskSetting
import com.unchunks.echomark.domain.repository.LlmBackend
import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryVersionTest {

    @Test
    fun 言語と書き方の呼び名() {
        assertEquals("日本語・文章", SummaryStyle().label)
        assertEquals("English・箇条書き", SummaryStyle(SummaryLanguage.ENGLISH, SummaryFormat.BULLETS).label)
        assertEquals("한국어・1 行", SummaryStyle(SummaryLanguage.KOREAN, SummaryFormat.ONE_LINE).label)
    }

    @Test
    fun 端末内のAIは選んだモデルのidつき_既定なら端末内AIだけ() {
        assertEquals("端末内 AI", AiTaskSetting(backend = LlmBackend.LOCAL).generatorLabel())
        assertEquals("端末内 AI(qwen-3)", AiTaskSetting(backend = LlmBackend.LOCAL, localModelId = "qwen-3").generatorLabel())
    }

    @Test
    fun クラウドAPIは提供元とモデル() {
        val setting = AiTaskSetting(
            backend = LlmBackend.API,
            apiProvider = ApiProvider.GEMINI,
            apiModels = mapOf(ApiProvider.GEMINI to "gemini-3.5-flash-lite")
        )

        assertEquals("Gemini (Google) gemini-3.5-flash-lite", setting.generatorLabel())
        // モデルを選んでいなければ、提供元の既定のモデル
        assertEquals(
            "Claude (Anthropic) ${ApiProvider.CLAUDE.defaultModel}",
            AiTaskSetting(backend = LlmBackend.API).generatorLabel()
        )
    }

    @Test
    fun 作った元の呼び名() {
        assertEquals("AI", SummarySource.AI.label)
        assertEquals("手で編集", SummarySource.MANUAL.label)
    }
}
