package com.unchunks.echomark.data.ai.api

import com.unchunks.components.llm.ApiProvider
import com.unchunks.components.llm.ChatRole
import com.unchunks.components.llm.LlmException
import com.unchunks.components.llm.cloud.ApiAttachment
import com.unchunks.components.llm.cloud.AttachmentKind
import com.unchunks.components.llm.cloud.AttachmentPolicy
import com.unchunks.components.llm.cloud.ClaudeApiClient
import com.unchunks.components.llm.cloud.GeminiApiClient
import com.unchunks.components.llm.cloud.OpenAiApiClient
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.ai.LocalLlmProviders
import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.model.AnalysisAttachment
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.provider.NothingToAnalyzeException
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeLlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** [task] の設定で呼び出す [LlmProvider]。 */
    private fun provider(keys: FakeApiKeyRepository, task: AiTask = AiTask.SUMMARY): LlmProvider =
        apiLlmProvider(keys).forTask(task)

    private fun apiLlmProvider(keys: FakeApiKeyRepository): ApiLlmProvider {
        val dispatchers = TestDispatcherProvider(Dispatchers.IO)
        return ApiLlmProvider(
            claude = ClaudeApiClient(dispatchers, server.url("/").toString(), maxRetries = 0),
            gemini = GeminiApiClient(OkHttpClient(), dispatchers, server.url("/")),
            openAi = OpenAiApiClient(OkHttpClient(), dispatchers, server.url("/")),
            appSettings = settings,
            apiKeyRepository = keys,
            attachmentLoader = loader
        )
    }

    /** 読み込みを記録し、固定の中身を返す添付の読み込み。 */
    private class FakeAttachmentLoader : AttachmentLoader {
        val loaded = mutableListOf<Pair<AnalysisAttachment, AttachmentKind>>()
        override suspend fun load(attachment: AnalysisAttachment, kind: AttachmentKind): ApiAttachment {
            loaded += attachment to kind
            val mimeType = AttachmentPolicy.apiMimeType(kind, attachment.mimeType)!!
            return ApiAttachment(kind, mimeType, "QkFTRTY0", "file")
        }
    }

    private val loader = FakeAttachmentLoader()

    private fun geminiText(text: String) = MockResponse.Builder()
        .addHeader("Content-Type", "application/json")
        .body("""{"candidates":[{"content":{"parts":[{"text":${JSONObject.quote(text)}}]},"finishReason":"STOP"}]}""")
        .build()

    /** Claude の SSE 応答(text_delta 1 つ)。 */
    private fun claudeSse(text: String): MockResponse {
        val events = listOf(
            "message_start" to """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":${JSONObject.quote(text)}}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":3}}""",
            "message_stop" to """{"type":"message_stop"}"""
        )
        return MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")
            .body(events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" })
            .build()
    }

    /** 要約(JSON を返させる呼び出し)はストリーミングで受けるため、SSE で返す。[chunks] は増分のテキスト。 */
    private fun geminiSse(vararg chunks: String) = MockResponse.Builder()
        .addHeader("Content-Type", "text/event-stream")
        .body(
            chunks.joinToString("") { text ->
                """data: {"candidates":[{"content":{"parts":[{"text":${JSONObject.quote(text)}}]}}]}""" + "\n\n"
            }
        )
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
        settings.setApiModel(AiTask.SUMMARY, ApiProvider.GEMINI, "gemini-3.5-flash-lite")
        server.enqueue(geminiSse("""{"summary":"要約","tags":["a","b"],"category":"技術"}"""))

        val analysis = provider(FakeApiKeyRepository(mapOf(ApiProvider.GEMINI to "AIzaKeyForTest0000")))
            .analyze(AnalysisInput(title = "", text = "本文", kind = ContentKind.WEB_PAGE), listOf("Kotlin", "読書"))

        assertEquals("要約", analysis.summary)
        assertEquals(listOf("a", "b"), analysis.tags)
        assertEquals("技術", analysis.category)
        val request = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.5-flash-lite:streamGenerateContent", request.url.encodedPath)
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

    /** 部分要約(「全体の一部」の指示)には部分の番号で、最終の要約にはそれ以外で応答する。並行する呼び出しの到着順に依らない。 */
    private fun respondByPrompt(finalJson: String, partNotes: (Int) -> String = { "要点$it" }) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body!!.utf8())
                if (!body.systemText().contains("全体の一部")) return geminiSse(finalJson)
                val number = Regex("\\[部分 (\\d+)/").find(body.userText())!!.groupValues[1].toInt()
                return geminiSse("""{"notes":"${partNotes(number)}"}""")
            }
        }
    }

    private fun takeRequestBodies(count: Int): List<JSONObject> = List(count) { JSONObject(server.takeRequest().body!!.utf8()) }

    private fun JSONObject.systemText() =
        getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")

    private fun JSONObject.userText() =
        getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")

    @Test
    fun 文脈長が分からないモデルは従来の上限を超える本文を部分ごとに要約してからまとめる() = runBlocking {
        settings.setApiModel(AiTask.SUMMARY, ApiProvider.GEMINI, "my-custom-model")
        respondByPrompt("""{"summary":"全体の要約","tags":["動画"],"category":"学習"}""") { if (it == 1) "前半の要点" else "後半の要点" }
        // 1段落 1,000 文字 × 25(約 25,000 文字。従来の上限 20,000 文字を超える)
        val text = (1..25).joinToString("\n\n") { "段落$it" + "あ".repeat(990) }

        val analysis = provider(geminiKeys)
            .analyze(AnalysisInput(title = "長い動画", text = text, kind = ContentKind.VIDEO), emptyList())

        assertEquals("全体の要約", analysis.summary)
        assertEquals(3, server.requestCount)
        val bodies = takeRequestBodies(3)
        val parts = bodies.filter { it.systemText().contains("全体の一部") }
        assertEquals(2, parts.size)
        // 部分は並行して呼ばれるため、到着順ではなく番号で確かめる
        val first = parts.single { it.userText().contains("[部分 1/2]") }
        assertTrue(first.userText(), first.userText().startsWith("タイトル: 長い動画\n[部分 1/2]\n段落1"))
        assertTrue(parts.single { it.userText().contains("[部分 2/2]") }.userText().isNotEmpty())
        val final = bodies.single { !it.systemText().contains("全体の一部") }
        // 長い動画は要約を長めにする
        assertTrue(final.systemText(), final.systemText().contains("200文字以内"))
        // メモは部分の順に並ぶ
        assertTrue(final.userText(), final.userText().contains("[部分 1/2]\n前半の要点\n\n[部分 2/2]\n後半の要点"))
    }

    @Test
    fun 現行モデルなら従来の上限を超える本文も1回で要約する() = runBlocking {
        server.enqueue(geminiSse("""{"summary":"要約","tags":[],"category":"ニュース"}"""))
        // 100,000 文字(従来は 5 部分に分けて 6 回の呼び出しだった)
        val text = "あ".repeat(100_000)

        val analysis = provider(geminiKeys)
            .analyze(AnalysisInput(title = "", text = text, kind = ContentKind.WEB_PAGE), emptyList())

        assertEquals("要約", analysis.summary)
        assertEquals(1, server.requestCount)
        // 本文は切り詰めずに渡す
        assertTrue(takeRequestBodies(1).single().userText().length > 100_000)
    }

    @Test
    fun 一回で渡せる上限を超える本文は並行して部分要約しメモの順にまとめる() = runBlocking {
        respondByPrompt("""{"summary":"全体の要約","tags":[],"category":"学習"}""")
        // 160,000 文字: 上限(150,000 文字)を超えるため、約 4 万文字ずつ 4 部分に分ける
        val text = (1..160).joinToString("\n\n") { "段落$it" + "あ".repeat(990) }

        provider(geminiKeys).analyze(AnalysisInput(title = "", text = text, kind = ContentKind.WEB_PAGE), emptyList())

        assertEquals(5, server.requestCount)
        val final = takeRequestBodies(5).single { !it.systemText().contains("全体の一部") }
        assertTrue(
            final.userText(),
            final.userText().contains("[部分 1/4]\n要点1\n\n[部分 2/4]\n要点2\n\n[部分 3/4]\n要点3\n\n[部分 4/4]\n要点4")
        )
    }

    @Test
    fun 一回で渡した本文が文脈長を超えて拒否されたら従来の単位で分けて要約し直す() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body!!.utf8())
                return when {
                    // 本文全体は(スキーマの有無に関係なく)文脈長超過
                    body.userText().length > 50_000 -> MockResponse.Builder().code(400)
                        .body("""{"error":{"message":"input token count exceeds the maximum"}}""").build()
                    body.systemText().contains("全体の一部") -> geminiSse("""{"notes":"要点"}""")
                    else -> geminiSse("""{"summary":"分けて要約","tags":[],"category":"ニュース"}""")
                }
            }
        }
        val text = (1..60).joinToString("\n\n") { "段落$it" + "あ".repeat(990) } // 約 60,000 文字

        val analysis = provider(geminiKeys)
            .analyze(AnalysisInput(title = "", text = text, kind = ContentKind.WEB_PAGE), emptyList())

        assertEquals("分けて要約", analysis.summary)
        // 本文全体 2 回(スキーマ付きで拒否 → スキーマを外しても拒否) + 3 部分 + まとめ 1 回
        assertEquals(6, server.requestCount)
        // スキーマのせいではなかったため、分けた後の呼び出しにもスキーマを付ける
        val bodies = takeRequestBodies(6)
        assertTrue(bodies.last().getJSONObject("generationConfig").has("responseJsonSchema"))
    }

    @Test
    fun 要約はJSONスキーマで出力を縛る() = runBlocking {
        server.enqueue(geminiSse("""{"tags":["Kotlin"],"category":"技術"}"""))

        provider(geminiKeys, AiTask.TAGGING)
            .analyze(AnalysisInput(title = "", text = "Kotlin の本文", kind = ContentKind.WEB_PAGE), emptyList(), AnalysisScope.TAGS)

        val schema = JSONObject(server.takeRequest().body!!.utf8())
            .getJSONObject("generationConfig").getJSONObject("responseJsonSchema")
        assertEquals(setOf("tags", "category"), schema.getJSONObject("properties").keys().asSequence().toSet())
        assertFalse(schema.getBoolean("additionalProperties"))
    }

    @Test
    fun 形の崩れた出力は直す指示を付けて作り直させる() = runBlocking {
        server.enqueue(geminiSse("""{"summary":"","tags":["a"],"category":"技術"}"""))
        server.enqueue(geminiSse("""{"summary":"直した要約","tags":["a"],"category":"技術"}"""))

        val analysis = provider(geminiKeys)
            .analyze(AnalysisInput(title = "", text = "本文 a", kind = ContentKind.WEB_PAGE), emptyList())

        assertEquals("直した要約", analysis.summary)
        val (first, repair) = takeRequestBodies(2)
        assertEquals(1, first.getJSONArray("contents").length())
        // 2回目は、前の出力(model)と直す指示(user)を会話の続きとして渡す
        val contents = repair.getJSONArray("contents")
        assertEquals(3, contents.length())
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        val instruction = contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(instruction, instruction.contains("summary が空です"))
    }

    @Test
    fun 作り直しても直らなければ回数の上限で止めて緩く解析する() = runBlocking {
        repeat(3) { server.enqueue(geminiSse("要約はできませんでした")) }

        val analysis = provider(geminiKeys)
            .analyze(AnalysisInput(title = "", text = "本文の先頭", kind = ContentKind.WEB_PAGE), emptyList())

        // 最初の 1 回 + 作り直し 2 回で止め、本文の先頭を要約の代わりにする
        assertEquals(3, server.requestCount)
        assertEquals("本文の先頭", analysis.summary)
    }

    @Test
    fun スキーマを付けたリクエストが拒否されたら外してやり直し以後は付けない() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body!!.utf8())
                return if (body.getJSONObject("generationConfig").has("responseJsonSchema")) {
                    MockResponse.Builder().code(400).body("""{"error":{"message":"Invalid JSON schema"}}""").build()
                } else {
                    geminiSse("""{"summary":"要約","tags":[],"category":"技術"}""")
                }
            }
        }
        val provider = provider(geminiKeys)
        val input = AnalysisInput(title = "", text = "本文", kind = ContentKind.WEB_PAGE)

        assertEquals("要約", provider.analyze(input, emptyList()).summary)
        assertEquals("要約", provider.analyze(input, emptyList()).summary)

        // 1回目はスキーマ付き(拒否)とスキーマ無し、2回目は最初からスキーマ無し
        assertEquals(3, server.requestCount)
    }

    @Test
    fun 上限以下の本文は1回で要約する() = runBlocking {
        server.enqueue(geminiSse("""{"summary":"要約","tags":[],"category":"メモ"}"""))

        provider(FakeApiKeyRepository(mapOf(ApiProvider.GEMINI to "AIzaKeyForTest0000")))
            .analyze(AnalysisInput(title = "", text = "あ".repeat(20_000), kind = ContentKind.MEMO), emptyList())

        assertEquals(1, server.requestCount)
    }

    @Test
    fun 要約はストリーミングで受け_JSONが閉じたらその後ろは使わない() = runBlocking {
        server.enqueue(
            geminiSse("""{"summary":"要約","ta""", """gs":["a"],"category":"技術"}""", """余計な続き{"summary":"別"}""")
        )

        val analysis = provider(geminiKeys)
            .analyze(AnalysisInput(title = "", text = "本文", kind = ContentKind.WEB_PAGE), emptyList())

        assertEquals("要約", analysis.summary)
        assertEquals(listOf("a"), analysis.tags)
        assertEquals("技術", analysis.category)
        val request = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.8-flash:streamGenerateContent", request.url.encodedPath)
        assertEquals("sse", request.url.queryParameter("alt"))
    }

    @Test
    fun ストリーミング途中の安全フィルタはRefusedのまま伝わる() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body("""data: {"candidates":[{"content":{"parts":[{"text":"{\"summ"}]},"finishReason":"SAFETY"}]}""" + "\n\n")
                .build()
        )

        try {
            provider(geminiKeys).analyze(AnalysisInput(title = "", text = "本文", kind = ContentKind.WEB_PAGE), emptyList())
            fail("例外が投げられるはず")
        } catch (e: LlmException.Refused) {
            assertEquals("SAFETY", e.reason)
        }
    }

    @Test
    fun ストリームが空ならUnexpected() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body("").build())

        try {
            provider(geminiKeys).analyze(AnalysisInput(title = "", text = "本文", kind = ContentKind.WEB_PAGE), emptyList())
            fail("例外が投げられるはず")
        } catch (e: LlmException.Unexpected) {
            // 空の要約を保存せず、再試行させる
        }
    }

    private val geminiKeys get() = FakeApiKeyRepository(mapOf(ApiProvider.GEMINI to "AIzaKeyForTest0000"))
    private val imageFile = AnalysisAttachment(path = "/data/files/photo.png", mimeType = "image/png", sizeBytes = 2_000_000)

    @Test
    fun 画像は設定がオンならファイルも送り_種類に合う指示にする() = runBlocking {
        server.enqueue(geminiSse("""{"summary":"レシート","tags":[],"category":"レシート"}"""))

        provider(geminiKeys).analyze(
            AnalysisInput(title = "photo.png", text = "合計 1,200円", kind = ContentKind.IMAGE, attachment = imageFile),
            emptyList()
        )

        assertEquals(listOf(imageFile to AttachmentKind.IMAGE), loader.loaded)
        val body = JSONObject(server.takeRequest().body!!.utf8())
        val system = body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(system, system.contains("保存内容(画像)"))
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals("image/jpeg", parts.getJSONObject(0).getJSONObject("inlineData").getString("mimeType"))
        val text = parts.getJSONObject(1).getString("text")
        assertTrue(text, text.contains("添付: 保存した画像のファイルそのもの"))
        // 画像内の文字(OCR の結果)も手がかりとして渡す
        assertTrue(text, text.contains("合計 1,200円"))
    }

    @Test
    fun 設定がオフならファイルは送らずテキストだけで要約する() = runBlocking {
        settings.setSendFilesToCloud(false)
        server.enqueue(geminiSse("""{"summary":"s","tags":[],"category":"写真"}"""))

        provider(geminiKeys).analyze(
            AnalysisInput(title = "photo.png", text = "読み取った文字", kind = ContentKind.IMAGE, attachment = imageFile),
            emptyList()
        )

        assertTrue(loader.loaded.isEmpty())
        val parts = JSONObject(server.takeRequest().body!!.utf8())
            .getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(1, parts.length())
        assertFalse(parts.getJSONObject(0).getString("text").contains("添付:"))
    }

    @Test
    fun 本文が空でファイルも送れないときは要約しない() = runBlocking {
        settings.setSendFilesToCloud(false)

        try {
            provider(geminiKeys).analyze(
                AnalysisInput(title = "IMG_0001.png", text = "", kind = ContentKind.IMAGE, attachment = imageFile),
                emptyList()
            )
            fail("例外が投げられるはず")
        } catch (e: NothingToAnalyzeException) {
            // ファイル名だけの要約は作らない
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun 本文が空でもファイルを送れるなら要約する() = runBlocking {
        server.enqueue(geminiSse("""{"summary":"夕焼けの写真","tags":[],"category":"写真"}"""))

        val analysis = provider(geminiKeys).analyze(
            AnalysisInput(title = "IMG_0001.png", text = "", kind = ContentKind.IMAGE, attachment = imageFile),
            emptyList()
        )

        assertEquals("夕焼けの写真", analysis.summary)
    }

    @Test
    fun 提供元が受け付けない種類のファイルは読み込まない() = runBlocking {
        settings.setApiProvider(AiTask.SUMMARY, ApiProvider.CLAUDE)
        server.enqueue(claudeSse("""{"summary":"s","tags":[],"category":"音声"}"""))
        val audio = AnalysisAttachment(path = "/data/files/voice.m4a", mimeType = "audio/mp4", sizeBytes = 1_000_000)

        provider(FakeApiKeyRepository(mapOf(ApiProvider.CLAUDE to "sk-ant-test-key")))
            .analyze(AnalysisInput(title = "", text = "文字起こし", kind = ContentKind.AUDIO, attachment = audio), emptyList())

        // Claude には音声の入力が無いため、文字起こしのテキストだけで要約する
        assertTrue(loader.loaded.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun PDFや動画をそのまま送るときはテキストを短くし_分割要約はしない() = runBlocking {
        server.enqueue(geminiSse("""{"summary":"s","tags":[],"category":"論文"}"""))
        val pdf = AnalysisAttachment(path = "/data/files/paper.pdf", mimeType = "application/pdf", sizeBytes = 3_000_000)

        provider(geminiKeys).analyze(
            AnalysisInput(title = "paper.pdf", text = "あ".repeat(30_000), kind = ContentKind.DOCUMENT, attachment = pdf),
            emptyList()
        )

        assertEquals(1, server.requestCount)
        val body = JSONObject(server.takeRequest().body!!.utf8())
        val system = body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(system, system.contains("200文字以内"))
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals("application/pdf", parts.getJSONObject(0).getJSONObject("inlineData").getString("mimeType"))
        val text = parts.getJSONObject(1).getString("text")
        assertTrue(text.length < 4_200)
    }

    @Test
    fun チャットは文脈をシステム指示に_履歴と質問をメッセージにする() = runBlocking {
        server.enqueue(geminiText("回答"))
        val history = listOf(
            ChatMessage(conversationId = 1, role = ChatRole.USER, content = "前の質問", createdAt = 0),
            ChatMessage(conversationId = 1, role = ChatRole.ASSISTANT, content = "前の回答", createdAt = 1)
        )

        val answer = provider(FakeApiKeyRepository(mapOf(ApiProvider.GEMINI to "AIzaKeyForTest0000")), AiTask.CHAT)
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

        apiLlmProvider(FakeApiKeyRepository(mapOf(ApiProvider.GEMINI to "AIzaSavedKey000000")))
            .testConnection(ApiProvider.GEMINI, "gemini-3.8-flash", "  AIzaTypedKey000000 ")

        assertEquals("AIzaTypedKey000000", server.takeRequest().headers["x-goog-api-key"])
    }

    @Test
    fun 用途ごとに選んだ提供元とモデルで呼び出す() = runBlocking {
        settings.setApiModel(AiTask.SUMMARY, ApiProvider.GEMINI, "gemini-3.5-flash-lite")
        settings.setApiModel(AiTask.CHAT, ApiProvider.GEMINI, "gemini-3.1-pro-preview")
        server.enqueue(geminiText("回答"))

        provider(FakeApiKeyRepository(mapOf(ApiProvider.GEMINI to "AIzaKeyForTest0000")), AiTask.CHAT)
            .chat("質問", emptyList(), emptyList())

        assertEquals("/v1beta/models/gemini-3.1-pro-preview:generateContent", server.takeRequest().url.encodedPath)
    }

    @Test
    fun タグだけを作るときはsummaryを出力させない() = runBlocking {
        server.enqueue(geminiSse("""{"tags":["Kotlin"],"category":"技術"}"""))

        val analysis = provider(FakeApiKeyRepository(mapOf(ApiProvider.GEMINI to "AIzaKeyForTest0000")), AiTask.TAGGING)
            .analyze(AnalysisInput(title = "", text = "本文", kind = ContentKind.WEB_PAGE), listOf("Kotlin"), AnalysisScope.TAGS)

        assertEquals(listOf("Kotlin"), analysis.tags)
        assertEquals("技術", analysis.category)
        val (system, _) = geminiRequestTexts()
        assertFalse(system, system.contains("summary"))
        assertTrue(system, system.contains("\"tags\""))
    }

    @Test
    fun ResolverはAPI選択かつキー未設定ならApiKeyMissing() = runBlocking {
        val local = FakeLlmProvider()
        val api = FakeLlmProvider()
        val keys = FakeApiKeyRepository()
        val resolver = LlmProviderResolver(LocalLlmProviders { local }, { api }, settings, keys)

        try {
            resolver.resolve(AiTask.SUMMARY)
            fail("例外が投げられるはず")
        } catch (e: LlmException.ApiKeyMissing) {
            assertEquals(ApiProvider.GEMINI, e.provider)
        }

        keys.setKey(ApiProvider.GEMINI, "AIzaKey")
        assertSame(api, resolver.resolve(AiTask.SUMMARY))
        settings.setLlmBackend(AiTask.SUMMARY, LlmBackend.LOCAL)
        assertSame(local, resolver.resolve(AiTask.SUMMARY))
    }

    @Test
    fun Resolverは端末内なら用途に選んだモデルで動かす() = runBlocking {
        val byModel = mutableMapOf<String?, FakeLlmProvider>()
        val resolver = LlmProviderResolver(
            LocalLlmProviders { id -> byModel.getOrPut(id) { FakeLlmProvider() } },
            { FakeLlmProvider() },
            settings,
            FakeApiKeyRepository()
        )
        AiTask.entries.forEach { settings.setLlmBackend(it, LlmBackend.LOCAL) }
        settings.setLocalModel(AiTask.CHAT, "qwen")

        assertSame(resolver.resolve(AiTask.CHAT), byModel.getValue("qwen"))
        assertSame(resolver.resolve(AiTask.SUMMARY), byModel.getValue(null))
    }

    @Test
    fun Resolverは用途ごとの実行場所と提供元で選ぶ() = runBlocking {
        val local = FakeLlmProvider()
        val apiByTask = AiTask.entries.associateWith { FakeLlmProvider() }
        val keys = FakeApiKeyRepository(mapOf(ApiProvider.GEMINI to "AIzaKey"))
        val resolver = LlmProviderResolver(LocalLlmProviders { local }, { apiByTask.getValue(it) }, settings, keys)
        settings.setLlmBackend(AiTask.TAGGING, LlmBackend.LOCAL)
        settings.setApiProvider(AiTask.CHAT, ApiProvider.OPENAI)

        assertSame(local, resolver.resolve(AiTask.TAGGING))
        assertSame(apiByTask.getValue(AiTask.SUMMARY), resolver.resolve(AiTask.SUMMARY))
        // チャットに選んだ OpenAI のキーは無い
        try {
            resolver.resolve(AiTask.CHAT)
            fail("例外が投げられるはず")
        } catch (e: LlmException.ApiKeyMissing) {
            assertEquals(ApiProvider.OPENAI, e.provider)
        }
    }
}
