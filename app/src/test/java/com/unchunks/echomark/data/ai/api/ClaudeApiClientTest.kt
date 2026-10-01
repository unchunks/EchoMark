package com.unchunks.echomark.data.ai.api

import com.anthropic.client.AnthropicClient
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.provider.LlmException
import com.unchunks.echomark.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ClaudeApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ClaudeApiClient

    private val credentials = ApiCredentials("sk-ant-test-key", "claude-opus-5-5")

    private val analyzeRequest = ApiRequest(
        purpose = ApiPurpose.ANALYZE,
        system = "システム指示",
        messages = listOf(ApiMessage(ChatRole.USER, "本文"))
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // テストでは SDK の自動リトライを切る
        client = ClaudeApiClient(TestDispatcherProvider(Dispatchers.IO), server.url("/").toString(), maxRetries = 0)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun messageJson(text: String, stopReason: String = "end_turn", stopDetails: String = "null") = """
        {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
         "content":[{"type":"text","text":${JSONObject.quote(text)}}],
         "stop_reason":"$stopReason","stop_sequence":null,"stop_details":$stopDetails,
         "usage":{"input_tokens":10,"output_tokens":5}}
    """.trimIndent()

    @Test
    fun リクエストにキー_バージョン_effort_fallbacksが入る() = runBlocking {
        server.enqueue(jsonResponse(messageJson("""{"summary":"要約"}""")))

        val text = client.complete(analyzeRequest, credentials)

        assertEquals("""{"summary":"要約"}""", text)
        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.url.encodedPath)
        assertEquals("sk-ant-test-key", recorded.headers["x-api-key"])
        assertTrue(recorded.headers["anthropic-version"]!!.isNotBlank())
        assertTrue(recorded.headers["anthropic-beta"]!!.contains("server-side-fallback-2026-07-01"))
        val body = JSONObject(recorded.body!!.utf8())
        assertEquals("claude-opus-5-5", body.getString("model"))
        assertEquals("システム指示", body.getString("system"))
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        assertEquals("default", body.getString("fallbacks"))
        // Opus 5.5 は thinking を無効化できないため、thinking は送らない
        assertFalse(body.has("thinking"))
        val messages = body.getJSONArray("messages")
        assertEquals("user", messages.getJSONObject(0).getString("role"))
    }

    @Test
    fun チャットはeffortがmedium() = runBlocking {
        server.enqueue(jsonResponse(messageJson("回答")))

        client.complete(analyzeRequest.copy(purpose = ApiPurpose.CHAT), credentials)

        val body = JSONObject(server.takeRequest().body!!.utf8())
        assertEquals("medium", body.getJSONObject("output_config").getString("effort"))
    }

    @Test
    fun Haikuにはeffortとfallbacksを送らない() = runBlocking {
        server.enqueue(jsonResponse(messageJson("回答")))

        client.complete(analyzeRequest, ApiCredentials("sk-ant-test-key", "claude-haiku-4-5"))

        val recorded = server.takeRequest()
        val body = JSONObject(recorded.body!!.utf8())
        assertFalse(body.has("output_config"))
        assertFalse(body.has("fallbacks"))
        assertTrue(recorded.headers["anthropic-beta"].orEmpty().isEmpty())
    }

    @Test
    fun stop_reasonがrefusalならRefused() = runBlocking {
        server.enqueue(
            jsonResponse(
                messageJson("", stopReason = "refusal", stopDetails = """{"type":"refusal","category":"cyber","explanation":null}""")
            )
        )

        try {
            client.complete(analyzeRequest, credentials)
            fail("例外が投げられるはず")
        } catch (e: LlmException.Refused) {
            // OK
        }
    }

    @Test
    fun HTTPエラーは種類ごとの例外になりキーを含まない() = runBlocking {
        val cases = listOf(
            401 to LlmException.InvalidApiKey::class,
            403 to LlmException.InvalidApiKey::class,
            429 to LlmException.RateLimited::class,
            500 to LlmException.ServerError::class,
            404 to LlmException.BadRequest::class
        )
        for ((code, expected) in cases) {
            server.enqueue(
                jsonResponse(
                    """{"type":"error","error":{"type":"x","message":"bad key sk-ant-test-key"}}""",
                    code
                )
            )
            try {
                client.complete(analyzeRequest, credentials)
                fail("HTTP $code で例外が投げられるはず")
            } catch (e: LlmException) {
                assertTrue("HTTP $code -> ${e::class}", expected.isInstance(e))
                assertFalse(e.message!!.contains("sk-ant-test-key"))
                assertFalse(e.userMessage.contains("sk-ant-test-key"))
            }
        }
    }

    @Test
    fun ネットワークに繋がらなければNetwork() = runBlocking {
        server.close()
        try {
            client.complete(analyzeRequest, credentials)
            fail("例外が投げられるはず")
        } catch (e: LlmException.Network) {
            // OK
        }
    }

    private fun sse(vararg events: Pair<String, String>) = MockResponse.Builder()
        .addHeader("Content-Type", "text/event-stream")
        .body(events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" })
        .build()

    private val streamStart = "message_start" to
        """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}}"""
    private val blockStart = "content_block_start" to
        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""

    private fun textDelta(text: String) = "content_block_delta" to
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":${JSONObject.quote(text)}}}"""

    private fun messageDelta(stopReason: String) = "message_delta" to
        """{"type":"message_delta","delta":{"stop_reason":"$stopReason","stop_sequence":null},"usage":{"output_tokens":3}}"""

    @Test
    fun ストリーミングはtext_deltaを順に流す() = runBlocking {
        server.enqueue(
            sse(
                streamStart, blockStart, textDelta("こんに"), textDelta("ちは"),
                "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                messageDelta("end_turn"),
                "message_stop" to """{"type":"message_stop"}"""
            )
        )

        val chunks = client.stream(analyzeRequest.copy(purpose = ApiPurpose.CHAT), credentials).toList()

        assertEquals(listOf("こんに", "ちは"), chunks)
        val body = JSONObject(server.takeRequest().body!!.utf8())
        assertTrue(body.getBoolean("stream"))
    }

    @Test
    fun ストリーミング途中の拒否はRefused() = runBlocking {
        server.enqueue(
            sse(
                streamStart, blockStart, textDelta("途中"), messageDelta("refusal"),
                "message_stop" to """{"type":"message_stop"}"""
            )
        )

        val received = mutableListOf<String>()
        try {
            client.stream(analyzeRequest.copy(purpose = ApiPurpose.CHAT), credentials).collect { received += it }
            fail("例外が投げられるはず")
        } catch (e: LlmException.Refused) {
            assertEquals(listOf("途中"), received)
        }
    }

    /** 作ったクライアントを記録するクライアント工場(閉じたかどうかも見る)。 */
    private inner class RecordingFactory : (String) -> AnthropicClient {
        val created = mutableListOf<Pair<String, TrackingClient>>()
        override fun invoke(apiKey: String): AnthropicClient = synchronized(this) {
            TrackingClient(ClaudeApiClient.newClient(apiKey, server.url("/").toString(), maxRetries = 0))
                .also { created += apiKey to it }
        }
    }

    private class TrackingClient(private val delegate: AnthropicClient) : AnthropicClient by delegate {
        @Volatile var closed = false
        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private fun clientWith(factory: RecordingFactory) =
        ClaudeApiClient(TestDispatcherProvider(Dispatchers.IO), server.url("/").toString(), 0, factory)

    @Test
    fun 接続テストは使い回すクライアントを置き換えず_使い終わったら閉じる() = runBlocking {
        val factory = RecordingFactory()
        val client = clientWith(factory)
        repeat(3) { server.enqueue(jsonResponse(messageJson("OK"))) }

        client.complete(analyzeRequest, credentials)
        client.complete(
            analyzeRequest.copy(purpose = ApiPurpose.CONNECTION_TEST),
            ApiCredentials("sk-ant-other-key", "claude-opus-5-5")
        )
        client.complete(analyzeRequest, credentials)

        assertEquals(listOf("sk-ant-test-key", "sk-ant-other-key"), factory.created.map { it.first })
        assertFalse(factory.created[0].second.closed)
        assertTrue(factory.created[1].second.closed)
    }

    @Test
    fun キーが変わっても使用中かもしれない古いクライアントは閉じない() = runBlocking {
        val factory = RecordingFactory()
        val client = clientWith(factory)
        repeat(2) { server.enqueue(jsonResponse(messageJson("OK"))) }

        client.complete(analyzeRequest, credentials)
        client.complete(analyzeRequest, ApiCredentials("sk-ant-new-key", "claude-opus-5-5"))

        assertEquals(2, factory.created.size)
        assertTrue(factory.created.none { it.second.closed })
    }

    @Test
    fun 同時に呼んでもクライアントは1つだけ作る() = runBlocking {
        val factory = RecordingFactory()
        val client = clientWith(factory)
        repeat(16) { server.enqueue(jsonResponse(messageJson("OK"))) }

        coroutineScope {
            repeat(16) { launch(Dispatchers.IO) { client.complete(analyzeRequest, credentials) } }
        }

        assertEquals(1, factory.created.size)
    }
}
