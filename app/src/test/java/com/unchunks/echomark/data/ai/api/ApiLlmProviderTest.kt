package com.unchunks.echomark.data.ai.api

import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.provider.LlmException
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeLlmProvider
import com.unchunks.echomark.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ApiLlmProviderTest {

    private lateinit var server: MockWebServer
    private val settings = FakeAppSettingsRepository(backend = LlmBackend.API, provider = ApiProvider.GEMINI)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun provider(keys: FakeApiKeyRepository): ApiLlmProvider {
        val dispatchers = TestDispatcherProvider(Dispatchers.IO)
        return ApiLlmProvider(
            claude = ClaudeApiClient(dispatchers, server.url("/").toString(), maxRetries = 0),
            gemini = GeminiApiClient(OkHttpClient(), dispatchers, server.url("/")),
            openAi = OpenAiApiClient(OkHttpClient(), dispatchers, server.url("/")),
            appSettings = settings,
            apiKeyRepository = keys
        )
    }

    private fun geminiText(text: String) = MockResponse.Builder()
        .addHeader("Content-Type", "application/json")
        .body("""{"candidates":[{"content":{"parts":[{"text":${JSONObject.quote(text)}}]},"finishReason":"STOP"}]}""")
        .build()

    @Test
    fun キー未設定ならApiKeyMissing() = runBlocking {
        try {
            provider(FakeApiKeyRepository()).analyze(AnalysisInput(title = "", text = "本文", kind = ContentKind.WEB_PAGE), emptyList())
            fail("例外が投げられるはず")
        } catch (e: LlmException.ApiKeyMissing) {
            assertEquals(ApiProvider.GEMINI, e.provider)
        }
    }

    @Test
    fun 選択中の提供元とモデルで要約しJSONを解析する() = runBlocking {
        settings.setApiModel(ApiProvider.GEMINI, "gemini-3.5-flash-lite")
        server.enqueue(geminiText("""{"summary":"要約","tags":["a","b"],"category":"技術"}"""))

        val analysis = provider(FakeApiKeyRepository(mapOf(ApiProvider.GEMINI to "AIzaKeyForTest0000")))
            .analyze(AnalysisInput(title = "", text = "本文", kind = ContentKind.WEB_PAGE), listOf("Kotlin", "読書"))

        assertEquals("要約", analysis.summary)
        assertEquals(listOf("a", "b"), analysis.tags)
        assertEquals("技術", analysis.category)
        val request = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.5-flash-lite:generateContent", request.url.encodedPath)
        // 既存のタグをシステム指示で伝え、合うものを使い回させる
        val system = JSONObject(request.body!!.utf8())
            .getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(system, system.contains("""["Kotlin", "読書"]"""))
    }

    private fun geminiRequestTexts(): Pair<String, String> {
        val body = JSONObject(server.takeRequest().body!!.utf8())
        val system = body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        val user = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        return system to user
    }

    @Test
    fun 上限を超える本文は部分ごとに要約してからまとめる() = runBlocking {
        server.enqueue(geminiText("""{"notes":"前半の要点"}"""))
        server.enqueue(geminiText("""{"notes":"後半の要点"}"""))
        server.enqueue(geminiText("""{"summary":"全体の要約","tags":["動画"],"category":"学習"}"""))
        // 1段落 1,000 文字 × 25(約 25,000 文字。1回の上限 20,000 文字を超える)
        val text = (1..25).joinToString("\n\n") { "段落$it" + "あ".repeat(990) }

        val analysis = provider(FakeApiKeyRepository(mapOf(ApiProvider.GEMINI to "AIzaKeyForTest0000")))
            .analyze(AnalysisInput(title = "長い動画", text = text, kind = ContentKind.VIDEO), emptyList())

        assertEquals("全体の要約", analysis.summary)
        assertEquals(3, server.requestCount)
        val (partSystem, partUser) = geminiRequestTexts()
        assertTrue(partSystem, partSystem.contains("全体の一部"))
        assertTrue(partUser, partUser.startsWith("タイトル: 長い動画\n[部分 1/2]\n段落1"))
        val (_, secondUser) = geminiRequestTexts()
        assertTrue(secondUser.contains("[部分 2/2]"))
        val (finalSystem, finalUser) = geminiRequestTexts()
        // 長い動画は要約を長めにする
        assertTrue(finalSystem, finalSystem.contains("200文字以内"))
        assertTrue(finalUser, finalUser.contains("[部分 1/2]\n前半の要点\n\n[部分 2/2]\n後半の要点"))
    }

    @Test
    fun 上限以下の本文は1回で要約する() = runBlocking {
        server.enqueue(geminiText("""{"summary":"要約","tags":[],"category":"メモ"}"""))

        provider(FakeApiKeyRepository(mapOf(ApiProvider.GEMINI to "AIzaKeyForTest0000")))
            .analyze(AnalysisInput(title = "", text = "あ".repeat(20_000), kind = ContentKind.MEMO), emptyList())

        assertEquals(1, server.requestCount)
    }

    @Test
    fun チャットは文脈をシステム指示に_履歴と質問をメッセージにする() = runBlocking {
        server.enqueue(geminiText("回答"))
        val history = listOf(
            ChatMessage(conversationId = 1, role = ChatRole.USER, content = "前の質問", createdAt = 0),
            ChatMessage(conversationId = 1, role = ChatRole.ASSISTANT, content = "前の回答", createdAt = 1)
        )

        val answer = provider(FakeApiKeyRepository(mapOf(ApiProvider.GEMINI to "AIzaKeyForTest0000")))
            .chat("今の質問", listOf("[1] タイトル: 要約"), history)

        assertEquals("回答", answer)
        val body = JSONObject(server.takeRequest().body!!.utf8())
        val system = body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(system.contains("[1] タイトル: 要約"))
        val contents = body.getJSONArray("contents")
        assertEquals(3, contents.length())
        assertEquals(
            "今の質問",
            contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getString("text")
        )
    }

    @Test
    fun 接続テストは入力中のキーを優先する() = runBlocking {
        server.enqueue(geminiText("OK"))

        provider(FakeApiKeyRepository(mapOf(ApiProvider.GEMINI to "AIzaSavedKey000000")))
            .testConnection(ApiProvider.GEMINI, "gemini-3.8-flash", "  AIzaTypedKey000000 ")

        assertEquals("AIzaTypedKey000000", server.takeRequest().headers["x-goog-api-key"])
    }

    @Test
    fun ResolverはAPI選択かつキー未設定ならApiKeyMissing() = runBlocking {
        val local = FakeLlmProvider()
        val api = FakeLlmProvider()
        val keys = FakeApiKeyRepository()
        val resolver = LlmProviderResolver(local, api, settings, keys)

        try {
            resolver.resolve()
            fail("例外が投げられるはず")
        } catch (e: LlmException.ApiKeyMissing) {
            assertEquals(ApiProvider.GEMINI, e.provider)
        }

        keys.setKey(ApiProvider.GEMINI, "AIzaKey")
        assertSame(api, resolver.resolve())
        settings.setLlmBackend(LlmBackend.LOCAL)
        assertSame(local, resolver.resolve())
    }
}
