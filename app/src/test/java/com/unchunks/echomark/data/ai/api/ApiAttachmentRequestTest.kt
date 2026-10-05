package com.unchunks.echomark.data.ai.api

import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** ファイル(画像・PDF・音声・動画)を添付したときの、各社のリクエストの形。 */
class ApiAttachmentRequestTest {

    private lateinit var server: MockWebServer
    private val dispatchers = TestDispatcherProvider(Dispatchers.IO)

    private val image = ApiAttachment(AttachmentKind.IMAGE, "image/jpeg", "SU1BR0U=", "photo.jpg")
    private val pdf = ApiAttachment(AttachmentKind.PDF, "application/pdf", "UERG", "doc.pdf")
    private val audio = ApiAttachment(AttachmentKind.AUDIO, "audio/m4a", "QVVESU8=", "voice.m4a")
    private val video = ApiAttachment(AttachmentKind.VIDEO, "video/mp4", "VklERU8=", "clip.mp4")

    private fun request(vararg attachments: ApiAttachment) = ApiRequest(
        purpose = ApiPurpose.ANALYZE,
        system = "JSON で答えて",
        messages = listOf(ApiMessage(ChatRole.USER, "本文", attachments.toList()))
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(body: String) = MockResponse.Builder()
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun recordedBody() = JSONObject(server.takeRequest().body!!.utf8())

    @Test
    fun Claudeは画像をimageブロック_PDFをdocumentブロックにしてテキストより前に置く() = runBlocking {
        server.enqueue(
            json(
                """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
                "content":[{"type":"text","text":"{}"}],"stop_reason":"end_turn","stop_sequence":null,
                "usage":{"input_tokens":1,"output_tokens":1}}"""
            )
        )
        val client = ClaudeApiClient(dispatchers, server.url("/").toString(), maxRetries = 0)

        client.complete(request(image, pdf), ApiCredentials("sk-ant-test-key", "claude-opus-5-5"))

        val content = recordedBody().getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(3, content.length())
        val imageBlock = content.getJSONObject(0)
        assertEquals("image", imageBlock.getString("type"))
        assertEquals("base64", imageBlock.getJSONObject("source").getString("type"))
        assertEquals("image/jpeg", imageBlock.getJSONObject("source").getString("media_type"))
        assertEquals("SU1BR0U=", imageBlock.getJSONObject("source").getString("data"))
        val documentBlock = content.getJSONObject(1)
        assertEquals("document", documentBlock.getString("type"))
        assertEquals("base64", documentBlock.getJSONObject("source").getString("type"))
        assertEquals("application/pdf", documentBlock.getJSONObject("source").getString("media_type"))
        assertEquals("UERG", documentBlock.getJSONObject("source").getString("data"))
        assertEquals("text", content.getJSONObject(2).getString("type"))
        assertEquals("本文", content.getJSONObject(2).getString("text"))
    }

    @Test
    fun Claudeは音声と動画を送らない() = runBlocking {
        server.enqueue(
            json(
                """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
                "content":[{"type":"text","text":"{}"}],"stop_reason":"end_turn","stop_sequence":null,
                "usage":{"input_tokens":1,"output_tokens":1}}"""
            )
        )
        val client = ClaudeApiClient(dispatchers, server.url("/").toString(), maxRetries = 0)

        client.complete(request(audio, video), ApiCredentials("sk-ant-test-key", "claude-opus-5-5"))

        val content = recordedBody().getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
    }

    @Test
    fun GeminiはすべてinlineDataにしてテキストより前に置く() = runBlocking {
        server.enqueue(json("""{"candidates":[{"content":{"parts":[{"text":"{}"}]},"finishReason":"STOP"}]}"""))
        val client = GeminiApiClient(OkHttpClient(), dispatchers, server.url("/"))

        client.complete(request(image, pdf, audio, video), ApiCredentials("AIzaKeyForTest0000", "gemini-3.5-flash"))

        val parts = recordedBody().getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(5, parts.length())
        val inline = (0 until 4).map { parts.getJSONObject(it).getJSONObject("inlineData") }
        assertEquals(
            listOf("image/jpeg", "application/pdf", "audio/m4a", "video/mp4"),
            inline.map { it.getString("mimeType") }
        )
        assertEquals(listOf("SU1BR0U=", "UERG", "QVVESU8=", "VklERU8="), inline.map { it.getString("data") })
        assertEquals("本文", parts.getJSONObject(4).getString("text"))
    }

    @Test
    fun OpenAIは画像をinput_image_PDFをinput_fileにしてテキストより前に置く() = runBlocking {
        server.enqueue(
            json(
                """{"id":"resp_1","status":"completed","output":[{"type":"message","role":"assistant",
                "content":[{"type":"output_text","text":"{}"}]}]}"""
            )
        )
        val client = OpenAiApiClient(OkHttpClient(), dispatchers, server.url("/"))

        client.complete(request(image, pdf, audio), ApiCredentials("sk-proj-testkey123456", "gpt-6.1-sol"))

        val content: JSONArray = recordedBody().getJSONArray("input").getJSONObject(0).getJSONArray("content")
        // 音声は Responses API の入力に無いため送らない
        assertEquals(3, content.length())
        assertEquals("input_image", content.getJSONObject(0).getString("type"))
        assertEquals("data:image/jpeg;base64,SU1BR0U=", content.getJSONObject(0).getString("image_url"))
        assertEquals("input_file", content.getJSONObject(1).getString("type"))
        assertEquals("doc.pdf", content.getJSONObject(1).getString("filename"))
        assertEquals("data:application/pdf;base64,UERG", content.getJSONObject(1).getString("file_data"))
        assertEquals("input_text", content.getJSONObject(2).getString("type"))
        assertEquals("本文", content.getJSONObject(2).getString("text"))
    }

    @Test
    fun 添付が無ければ従来どおりテキストだけ() = runBlocking {
        server.enqueue(
            json(
                """{"id":"resp_1","status":"completed","output":[{"type":"message","role":"assistant",
                "content":[{"type":"output_text","text":"{}"}]}]}"""
            )
        )
        val client = OpenAiApiClient(OkHttpClient(), dispatchers, server.url("/"))

        client.complete(request(), ApiCredentials("sk-proj-testkey123456", "gpt-6.1-sol"))

        assertEquals("本文", recordedBody().getJSONArray("input").getJSONObject(0).getString("content"))
    }

    @Test
    fun 添付の中身はログ用の文字列に出さない() {
        assertFalse(image.toString().contains("SU1BR0U="))
        assertFalse(ApiMessage(ChatRole.USER, "本文", listOf(image)).toString().contains("SU1BR0U="))
    }

    @Test
    fun 同じロールの連続をまとめるときは添付も引き継ぐ() {
        val turns = normalizeTurns(
            listOf(
                ApiMessage(ChatRole.USER, "", listOf(image)),
                ApiMessage(ChatRole.USER, "本文", listOf(pdf))
            )
        )

        assertEquals(1, turns.size)
        assertEquals("本文", turns[0].text)
        assertEquals(listOf(image, pdf), turns[0].attachments)
    }
}
