package com.unchunks.echomark.data.ai

import com.unchunks.components.llm.ApiProvider
import com.unchunks.components.llm.LlmException
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.SummaryLanguage
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 要約の翻訳(#24)。「要約」の設定の AI に JSON で返させ、翻訳を取り出す。 */
class LlmSummaryTranslatorTest {

    private class TranslatingLlm(var response: String) : LlmProvider {
        val prompts = mutableListOf<String>()

        override suspend fun analyze(input: AnalysisInput, existingTags: List<String>, scope: AnalysisScope): BookmarkAnalysis =
            error("not used")

        override suspend fun chat(userMessage: String, context: List<String>, history: List<ChatMessage>): String = error("not used")

        override suspend fun complete(prompt: String): String {
            prompts += prompt
            return response
        }
    }

    private val settings = FakeAppSettingsRepository()
    private val llm = TranslatingLlm("""{"translation": "Only the changed parts are recomposed."}""")
    private val translator = LlmSummaryTranslator(
        LlmProviderResolver(LocalLlmProviders { llm }, { llm }, settings, FakeApiKeyRepository(mapOf(ApiProvider.CLAUDE to "sk-ant-test")))
    )

    @Test
    fun 翻訳先の言語を指示に入れて_応答から翻訳を取り出す() = runBlocking {
        val translated = translator.translate("変わった部分だけが再コンポーズされる。", SummaryLanguage.ENGLISH)

        assertEquals("Only the changed parts are recomposed.", translated)
        val prompt = llm.prompts.single()
        assertTrue(prompt, prompt.contains("次の文章を英語に翻訳し、JSONのみを出力してください。"))
        assertTrue(prompt, prompt.endsWith("変わった部分だけが再コンポーズされる。"))
    }

    @Test
    fun 前後に余計な文字があってもJSONから取り出す() {
        val response = "```json\n{\"translation\": \" Hello \"}\n```"

        assertEquals("Hello", LlmSummaryTranslator.parseTranslation(response))
    }

    @Test
    fun 取り出せない応答はnull_翻訳できなければ失敗() {
        assertNull(LlmSummaryTranslator.parseTranslation("翻訳です"))
        assertNull(LlmSummaryTranslator.parseTranslation("""{"translation": ""}"""))
        assertNull(LlmSummaryTranslator.parseTranslation("""{"other": "x"}"""))
        assertNull(LlmSummaryTranslator.parseTranslation("{broken"))
        llm.response = "訳せませんでした"
        try {
            runBlocking { translator.translate("要約", SummaryLanguage.KOREAN) }
            fail("例外が投げられるはず")
        } catch (e: IllegalStateException) {
            // OK
        }
    }

    @Test
    fun 要約の設定のAIで翻訳する() = runBlocking {
        // 要約をキーの無いクラウド API にすると、翻訳もその設定で解決され、キー未設定で失敗する
        settings.setLlmBackend(AiTask.SUMMARY, LlmBackend.API)
        settings.setApiProvider(AiTask.SUMMARY, ApiProvider.GEMINI)

        try {
            translator.translate("要約", SummaryLanguage.ENGLISH)
            fail("例外が投げられるはず")
        } catch (e: LlmException.ApiKeyMissing) {
            assertEquals(ApiProvider.GEMINI, e.provider)
        }
    }
}
