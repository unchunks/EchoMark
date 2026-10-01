package com.unchunks.echomark.data.ai.api

import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.provider.LlmException
import com.unchunks.echomark.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GeminiApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GeminiApiClient

    private val credentials = ApiCredentials("AIzaTestKey1234567890", "gemini-3.8-flash")

    private val chatRequest = ApiRequest(
        purpose = ApiPurpose.CHAT,
        system = "システム指示",
        messages = listOf(
            ApiMessage(ChatRole.USER, "前の質問"),
            ApiMessage(ChatRole.ASSISTANT, "前の回答"),
            ApiMessage(ChatRole.USER, "今の質問")
        )
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GeminiApiClient(OkHttpClient(), TestDispatcherProvider(Dispatchers.IO), server.url("/"))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    @Test
    fun generateContentのリクエスト形とテキスト抽出() = runBlocking {
        server.enqueue(
            json(
                """{"candidates":[{"content":{"role":"model","parts":[
                    {"text":"考え中","thought":true},{"text":"回答です"}]},"finishReason":"STOP"}]}"""
            )
        )

        val text = client.complete(chatRequest, credentials)

        // 思考の要約(thought=true)は除く
        assertEquals("回答です", text)
        val recorded = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", recorded.url.encodedPath)
        assertEquals("AIzaTestKey1234567890", recorded.headers["x-goog-api-key"])
        // キーを URL に載せない
        assertNull(recorded.url.queryParameter("key"))
        val body = JSONObject(recorded.body!!.utf8())
        assertEquals(
            "システム指示",
            body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        )
        val contents = body.getJSONArray("contents")
        assertEquals(3, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        assertFalse(body.getJSONObject("generationConfig").has("responseMimeType"))
    }

    @Test
    fun 要約はJSON出力を指定する() = runBlocking {
        server.enqueue(json("""{"candidates":[{"content":{"parts":[{"text":"{}"}]},"finishReason":"STOP"}]}"""))

        client.complete(chatRequest.copy(purpose = ApiPurpose.ANALYZE), credentials)

        val body = JSONObject(server.takeRequest().body!!.utf8())
        assertEquals("application/json", body.getJSONObject("generationConfig").getString("responseMimeType"))
    }

    @Test
    fun プロンプトがブロックされたらRefused() = runBlocking {
        server.enqueue(json("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))

        try {
            client.complete(chatRequest, credentials)
            fail("例外が投げられるはず")
        } catch (e: LlmException.Refused) {
            assertEquals("SAFETY", e.reason)
        }
    }

    @Test
    fun キー不正の400はInvalidApiKey() = runBlocking {
        server.enqueue(
            json(
                """{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT",
                    "details":[{"reason":"API_KEY_INVALID"}]}}""",
                400
            )
        )

        try {
            client.complete(chatRequest, credentials)
            fail("例外が投げられるはず")
        } catch (e: LlmException.InvalidApiKey) {
            // OK
        }
    }

    @Test
    fun 存在しないモデルはBadRequestで詳細にキーを含まない() = runBlocking {
        server.enqueue(
            json("""{"error":{"code":404,"message":"models/x is not found (key AIzaTestKey1234567890)"}}""", 404)
        )

        try {
            client.complete(chatRequest, credentials)
            fail("例外が投げられるはず")
        } catch (e: LlmException.BadRequest) {
            assertTrue(e.userMessage.contains("not found"))
            assertFalse(e.userMessage.contains("AIzaTestKey1234567890"))
        }
    }

    @Test
    fun streamGenerateContentはSSEのチャンクを流す() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body(
                    "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"こん\"}]}}]}\n\n" +
                        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"にちは\"}]},\"finishReason\":\"STOP\"}]}\n\n"
                )
                .build()
        )

        val chunks = client.stream(chatRequest, credentials).toList()

        assertEquals(listOf("こん", "にちは"), chunks)
        val recorded = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.8-flash:streamGenerateContent", recorded.url.encodedPath)
        assertEquals("sse", recorded.url.queryParameter("alt"))
    }

    @Test
    fun ストリーム途中の安全フィルタはRefused() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body(
                    "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"途中\"}]}}]}\n\n" +
                        "data: {\"candidates\":[{\"finishReason\":\"SAFETY\"}]}\n\n"
                )
                .build()
        )

        try {
            client.stream(chatRequest, credentials).toList()
            fail("例外が投げられるはず")
        } catch (e: LlmException.Refused) {
            // OK
        }
    }

    @Test
    fun レート制限は429でRateLimited() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(429).addHeader("Retry-After", "30").body("""{"error":{"code":429}}""").build()
        )

        try {
            client.stream(chatRequest, credentials).toList()
            fail("例外が投げられるはず")
        } catch (e: LlmException.RateLimited) {
            assertEquals(30L, e.retryAfterSeconds)
        }
    }
}
