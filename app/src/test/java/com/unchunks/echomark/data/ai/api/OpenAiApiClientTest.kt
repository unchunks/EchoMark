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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAiApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenAiApiClient

    private val credentials = ApiCredentials("sk-proj-testkey123456", "gpt-6.1-sol")

    private val analyzeRequest = ApiRequest(
        purpose = ApiPurpose.ANALYZE,
        system = "JSON で答えて",
        messages = listOf(ApiMessage(ChatRole.USER, "本文"))
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenAiApiClient(OkHttpClient(), TestDispatcherProvider(Dispatchers.IO), server.url("/"))
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
    fun Responses_APIのリクエスト形とoutput_textの抽出() = runBlocking {
        server.enqueue(
            json(
                """{"id":"resp_1","status":"completed","output":[
                    {"type":"reasoning","summary":[]},
                    {"type":"message","role":"assistant","content":[{"type":"output_text","text":"{\"summary\":\"s\"}"}]}]}"""
            )
        )

        val text = client.complete(analyzeRequest, credentials)

        assertEquals("""{"summary":"s"}""", text)
        val recorded = server.takeRequest()
        assertEquals("/v1/responses", recorded.url.encodedPath)
        assertEquals("Bearer sk-proj-testkey123456", recorded.headers["Authorization"])
        val body = JSONObject(recorded.body!!.utf8())
        assertEquals("gpt-6.1-sol", body.getString("model"))
        assertEquals("JSON で答えて", body.getString("instructions"))
        assertFalse(body.getBoolean("store"))
        assertEquals("json_object", body.getJSONObject("text").getJSONObject("format").getString("type"))
        val input = body.getJSONArray("input")
        assertEquals("user", input.getJSONObject(0).getString("role"))
        assertEquals("本文", input.getJSONObject(0).getString("content"))
        assertFalse(body.has("stream"))
    }

    @Test
    fun 拒否はRefused() = runBlocking {
        server.enqueue(
            json(
                """{"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"できません"}]}]}"""
            )
        )

        try {
            client.complete(analyzeRequest, credentials)
            fail("例外が投げられるはず")
        } catch (e: LlmException.Refused) {
            assertEquals("できません", e.reason)
        }
    }

    @Test
    fun 認証エラーはInvalidApiKeyでキーを含まない() = runBlocking {
        server.enqueue(
            json("""{"error":{"message":"Incorrect API key provided: sk-proj-****3456","code":"invalid_api_key"}}""", 401)
        )

        try {
            client.complete(analyzeRequest, credentials)
            fail("例外が投げられるはず")
        } catch (e: LlmException.InvalidApiKey) {
            assertFalse(e.userMessage.contains("sk-proj"))
        }
    }

    @Test
    fun サーバーエラーはServerError() = runBlocking {
        server.enqueue(json("""{"error":{"message":"oops"}}""", 503))

        try {
            client.complete(analyzeRequest, credentials)
            fail("例外が投げられるはず")
        } catch (e: LlmException.ServerError) {
            assertEquals(503, e.statusCode)
            assertTrue(e.isRetryable)
        }
    }

    private fun sse(vararg events: String) = MockResponse.Builder()
        .addHeader("Content-Type", "text/event-stream")
        .body(events.joinToString("") { data ->
            val type = JSONObject(data).getString("type")
            "event: $type\ndata: $data\n\n"
        })
        .build()

    @Test
    fun ストリーミングはoutput_text_deltaを流す() = runBlocking {
        server.enqueue(
            sse(
                """{"type":"response.created","response":{"id":"resp_1"}}""",
                """{"type":"response.output_text.delta","delta":"こん"}""",
                """{"type":"response.output_text.delta","delta":"にちは"}""",
                """{"type":"response.completed","response":{"id":"resp_1","status":"completed"}}"""
            )
        )

        val chunks = client.stream(analyzeRequest.copy(purpose = ApiPurpose.CHAT), credentials).toList()

        assertEquals(listOf("こん", "にちは"), chunks)
        val body = JSONObject(server.takeRequest().body!!.utf8())
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.has("text"))
    }

    @Test
    fun ストリーム途中のerrorイベントは種類に応じた例外() = runBlocking {
        server.enqueue(
            sse(
                """{"type":"response.output_text.delta","delta":"途中"}""",
                """{"type":"error","code":"rate_limit_exceeded","message":"slow down"}"""
            )
        )

        try {
            client.stream(analyzeRequest.copy(purpose = ApiPurpose.CHAT), credentials).toList()
            fail("例外が投げられるはず")
        } catch (e: LlmException.RateLimited) {
            // OK
        }
    }
}
