package com.unchunks.echomark.data.extract.transcribe

import com.unchunks.echomark.domain.provider.LlmException
import com.unchunks.echomark.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

class CloudTranscriptionClientsTest {

    private lateinit var server: MockWebServer
    private val wav = byteArrayOf(1, 2, 3, 4)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
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

    private fun openAi() = OpenAiTranscriptionClient(OkHttpClient(), TestDispatcherProvider(Dispatchers.IO), server.url("/"))
    private fun gemini() = GeminiTranscriptionClient(OkHttpClient(), TestDispatcherProvider(Dispatchers.IO), server.url("/"))

    @Test
    fun OpenAIはmultipartでモデルとWAVを送りtextを返す() = runBlocking {
        server.enqueue(json("""{"text":"こんにちは","usage":{"type":"duration","seconds":3}}"""))

        val text = openAi().transcribe(wav, "sk-proj-testkey123456")

        assertEquals("こんにちは", text)
        val recorded = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", recorded.url.encodedPath)
        assertEquals("Bearer sk-proj-testkey123456", recorded.headers["Authorization"])
        assertTrue(recorded.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        val body = recorded.body!!.utf8()
        assertTrue(body.contains("name=\"model\""))
        assertTrue(body.contains(OpenAiTranscriptionClient.MODEL))
        assertTrue(body.contains("name=\"file\"; filename=\"audio.wav\""))
        assertTrue(body.contains("Content-Type: audio/wav"))
    }

    @Test
    fun OpenAIのキーの誤りはInvalidApiKeyでキーを含まない() = runBlocking {
        server.enqueue(json("""{"error":{"message":"Incorrect API key provided: sk-proj-testkey123456"}}""", code = 401))

        try {
            openAi().transcribe(wav, "sk-proj-testkey123456")
            fail()
        } catch (e: LlmException.InvalidApiKey) {
            assertFalse(e.toString().contains("testkey123456"))
        }
    }

    @Test
    fun Geminiは音声をinlineDataで送り_思考を除いたテキストを返す() = runBlocking {
        server.enqueue(
            json(
                """{"candidates":[{"content":{"parts":[{"text":"考え中","thought":true},{"text":" 文字起こし \n"}]},
                    "finishReason":"STOP"}]}"""
            )
        )

        val text = gemini().transcribe(wav, "AIzaTestKey1234567890", "gemini-3.8-flash")

        assertEquals("文字起こし", text)
        val recorded = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", recorded.url.encodedPath)
        assertEquals("AIzaTestKey1234567890", recorded.headers["x-goog-api-key"])
        assertEquals(null, recorded.url.queryParameter("key"))
        val parts = JSONObject(recorded.body!!.utf8())
            .getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertTrue(parts.getJSONObject(0).getString("text").contains("文字に起こして"))
        val inline = parts.getJSONObject(1).getJSONObject("inlineData")
        assertEquals("audio/wav", inline.getString("mimeType"))
        assertArrayEquals(wav, Base64.getDecoder().decode(inline.getString("data")))
    }

    @Test
    fun Geminiで話し声が無ければ空文字() = runBlocking {
        server.enqueue(json("""{"candidates":[{"content":{"parts":[{"text":"(発話なし)"}]},"finishReason":"STOP"}]}"""))

        assertEquals("", gemini().transcribe(wav, "AIzaTestKey1234567890", "gemini-3.8-flash"))
    }

    @Test
    fun Geminiの安全フィルタはRefused() = runBlocking {
        server.enqueue(json("""{"candidates":[{"content":{"parts":[]},"finishReason":"SAFETY"}]}"""))

        try {
            gemini().transcribe(wav, "AIzaTestKey1234567890", "gemini-3.8-flash")
            fail()
        } catch (e: LlmException.Refused) {
            assertEquals("SAFETY", e.reason)
        }
    }

    @Test
    fun Geminiのレート制限はRateLimited() = runBlocking {
        server.enqueue(json("""{"error":{"code":429,"message":"quota"}}""", code = 429))

        try {
            gemini().transcribe(wav, "AIzaTestKey1234567890", "gemini-3.8-flash")
            fail()
        } catch (e: LlmException.RateLimited) {
            // 期待どおり
        }
    }
}
