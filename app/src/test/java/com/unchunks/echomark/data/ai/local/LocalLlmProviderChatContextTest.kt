package com.unchunks.echomark.data.ai.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.llm.ChatRole
import com.unchunks.components.llm.ondevice.GenerationKind
import com.unchunks.components.llm.ondevice.LocalChatInput
import com.unchunks.components.llm.ondevice.LocalGeneration
import com.unchunks.components.llm.ondevice.LocalTextEngine
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.provider.ChatContextUsage
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CancellationException

/** 端末内 AI のチャットの文脈の扱い(会話の形での入力・トークン数の知らせ・文脈に入らないとき)と、会話の要約。 */
@RunWith(AndroidJUnit4::class)
class LocalLlmProviderChatContextTest {

    private lateinit var context: Context
    private lateinit var modelsDir: File

    /** 始まった生成を順に記録する(`chat:メッセージ`・`plain`・`json`) */
    private val starts = Collections.synchronizedList(mutableListOf<String>())
    private val chatInputs = mutableListOf<LocalChatInput>()

    /** 会話の形での生成の、次回以降の失敗。空なら成功 */
    private val chatFailures = ArrayDeque<Throwable>()

    /** 1つにまとめたプロンプトでの生成の、次回以降の失敗 */
    private val plainFailures = ArrayDeque<Throwable>()

    /** true なら会話の形の入力を、作るときの例外で断る(モデルの書式が受け付けない) */
    private var rejectTurnsOnStart = false
    private var tokenCount: Int? = 1000
    private var jsonOutput = """{"notes":"会話の要約"}"""

    /** 次の JSON の生成を、中断されるまで終わらせない */
    private var holdNextJson: CompletableDeferred<Unit>? = null

    private inner class FakeEngine : LocalTextEngine {
        private fun generation(onDone: (Throwable?) -> Unit) = object : LocalGeneration {
            override fun cancel() = onDone(CancellationException("中断"))
            override fun close() = Unit
            override fun tokenCount(): Int? = this@LocalLlmProviderChatContextTest.tokenCount
        }

        private fun finish(failure: Throwable?, onPartial: (String) -> Unit, onDone: (Throwable?) -> Unit) {
            if (failure == null) onPartial("回答")
            onDone(failure)
        }

        override fun start(
            prompt: String,
            kind: GenerationKind,
            onPartial: (String) -> Unit,
            onDone: (Throwable?) -> Unit
        ): LocalGeneration {
            if (kind == GenerationKind.CHAT) {
                starts += "plain"
                finish(plainFailures.removeFirstOrNull(), onPartial, onDone)
                return generation(onDone)
            }
            starts += "json"
            val held = holdNextJson
            holdNextJson = null
            if (held != null) {
                held.complete(Unit)
            } else {
                onPartial(jsonOutput)
                onDone(null)
            }
            return generation(onDone)
        }

        override fun startChat(input: LocalChatInput, onPartial: (String) -> Unit, onDone: (Throwable?) -> Unit): LocalGeneration {
            starts += "chat"
            chatInputs += input
            if (rejectTurnsOnStart) throw IllegalArgumentException("Failed to apply template")
            finish(chatFailures.removeFirstOrNull(), onPartial, onDone)
            return generation(onDone)
        }

        override fun close() = Unit
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        File(modelsDir, "a.litertlm").writeBytes(byteArrayOf(1, 2, 3))
        File(modelsDir, "local_llm_models.json").writeText(
            JSONObject().put(
                "models",
                JSONArray().put(
                    JSONObject()
                        .put("id", "a")
                        .put("fileName", "a.litertlm")
                        .put("displayName", "model-a.litertlm")
                        .put("sizeBytes", 3L)
                        .put("importedAt", 1L)
                )
            ).toString()
        )
    }

    @After
    fun tearDown() {
        modelsDir.deleteRecursively()
    }

    private val dispatchers = TestDispatcherProvider(Dispatchers.Unconfined)
    private val modelManager by lazy { ModelManager(context, FakeBookmarkRepository(), dispatchers) }

    private val provider: LlmProvider by lazy {
        LocalLlmProvider(context, modelManager, FakeAppSettingsRepository(), dispatchers).apply {
            engineFactory = { _, _, _ -> FakeEngine() }
        }
    }

    private val history = listOf(
        ChatMessage(conversationId = 1, role = ChatRole.USER, content = "前の質問", createdAt = 1),
        ChatMessage(conversationId = 1, role = ChatRole.ASSISTANT, content = "前の回答", createdAt = 2)
    )

    private suspend fun chat(memory: String? = null): Pair<String, ChatContextUsage?> {
        var usage: ChatContextUsage? = null
        val answer = provider.chatStream("質問", listOf("[1] 記事: 要約"), history, memory) { usage = it }
            .fold("") { acc, chunk -> acc + chunk }
        return answer to usage
    }

    @Test
    fun 会話の形で渡し_記憶をシステムの指示に入れる() = runBlocking {
        val (answer, _) = chat(memory = "旅行の話をした")

        assertEquals("回答", answer)
        assertEquals(listOf("chat"), starts)
        val input = chatInputs.single()
        assertTrue(input.systemInstruction, input.systemInstruction.contains("旅行の話をした"))
        assertEquals(listOf("前の質問", "前の回答"), input.history.map { it.text })
        assertEquals("質問", input.message)
    }

    @Test
    fun 文脈に入ったトークン数を知らせる() = runBlocking {
        tokenCount = 3500

        val (_, usage) = chat()

        assertEquals(ChatContextUsage(3500, 4096, promptShrunk = false), usage)
        assertTrue(usage!!.isTight)
    }

    @Test
    fun トークン数を数えられなくても答える() = runBlocking {
        tokenCount = null

        val (answer, usage) = chat()

        assertEquals("回答", answer)
        assertEquals(ChatContextUsage(null, 4096, promptShrunk = false), usage)
    }

    @Test
    fun 文脈に入らないと言われたら入力を減らしてやり直し_余裕が無いと知らせる() = runBlocking {
        chatFailures += IllegalStateException("Not enough space left in the context window for the input")

        var usage: ChatContextUsage? = null

        val answer = provider.chatStream("質問", listOf("[1] 記事: " + "あ".repeat(1_500)), history, null) { usage = it }
            .fold("") { acc, chunk -> acc + chunk }

        assertEquals("回答", answer)
        assertEquals(listOf("chat", "chat"), starts)
        assertTrue(chatInputs[1].plainPrompt.length < chatInputs[0].plainPrompt.length)
        assertEquals(true, usage?.promptShrunk)
    }

    @Test
    fun 減らしても文脈に入らなければ余裕が無いと知らせて失敗する() = runBlocking {
        repeat(3) { chatFailures += IllegalStateException("Not enough space left in the context window for the input") }
        var usage: ChatContextUsage? = null

        try {
            provider.chatStream("質問", emptyList(), history, null) { usage = it }.fold("") { acc, chunk -> acc + chunk }
            fail("文脈に入らないのに成功してしまった")
        } catch (e: IllegalStateException) {
            assertEquals(true, usage?.isTight)
        }
        // 形のせいではないため、まとめたプロンプトには切り替えない
        assertEquals(listOf("chat", "chat", "chat"), starts)
    }

    @Test
    fun 書式が会話の形を受け付けなければまとめたプロンプトに切り替え_以後もそれを使う() = runBlocking {
        rejectTurnsOnStart = true

        val (first, _) = chat()
        val (second, _) = chat()

        assertEquals("回答", first)
        assertEquals("回答", second)
        assertEquals(listOf("chat", "plain", "plain"), starts)
        assertEquals("書式のせいで読み込めない扱いにしない", emptyMap<String, Any>(), modelManager.loadFailures.first())
    }

    @Test
    fun 生成の途中の失敗でも回答が始まる前ならまとめたプロンプトに切り替える() = runBlocking {
        chatFailures += IllegalStateException("template error")

        val (answer, _) = chat()

        assertEquals("回答", answer)
        assertEquals(listOf("chat", "plain"), starts)
    }

    @Test
    fun 古い発言を要約する() = runBlocking {
        val summary = provider.summarizeConversation("前の要約", history)

        assertEquals("会話の要約", summary)
        assertEquals(listOf("json"), starts)
    }

    @Test
    fun 要約は上限の長さまでにし_空なら無いものとする() = runBlocking {
        jsonOutput = JSONObject().put("notes", "あ".repeat(1_000)).toString()
        assertEquals(LocalChatPrompt.MAX_MEMORY_CHARS, provider.summarizeConversation(null, history)?.length)

        jsonOutput = """{"notes":"  "}"""
        assertNull(provider.summarizeConversation(null, history))
    }

    @Test
    fun 要約の途中でチャットが来たら要約を諦めて先に答える() = runBlocking {
        val generating = CompletableDeferred<Unit>()
        holdNextJson = generating
        val summary = async(Dispatchers.Default) { provider.summarizeConversation(null, history) }
        generating.await()

        val (answer, _) = chat()

        assertEquals("回答", answer)
        assertNull(summary.await())
        assertEquals(listOf("json", "chat"), starts)
    }
}
