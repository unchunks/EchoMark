package com.unchunks.echomark.data.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.llm.ChatRole
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeLlmProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/** 会話の古い発言を要約に畳み、保存する。保存の条件は実際の SQL で確かめる。 */
@RunWith(AndroidJUnit4::class)
class ChatMemoryCompressorTest {

    private lateinit var db: AppDatabase

    /** 要約の依頼を記録し、[summary] を返す LLM。[gate] があれば、それが完了するまで返さない。 */
    private class SummarizingLlm(
        private val base: FakeLlmProvider = FakeLlmProvider()
    ) : LlmProvider by base {
        var summary: String? = "要約1"
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        val requests = Collections.synchronizedList(mutableListOf<Pair<String?, List<String>>>())

        /** 要約の依頼が届いた */
        val started = CompletableDeferred<Unit>()

        override suspend fun summarizeConversation(previousSummary: String?, messages: List<ChatMessage>): String? {
            requests += previousSummary to messages.map { it.content }
            started.complete(Unit)
            gate?.await()
            failure?.let { throw it }
            return summary
        }
    }

    private val llm = SummarizingLlm()
    private val settings = FakeAppSettingsRepository(LlmBackend.LOCAL)

    private fun compressor(provider: LlmProvider = llm) = ChatMemoryCompressor(
        db.conversationDao(),
        db.chatMessageDao(),
        LlmProviderResolver(LocalLlmProviders { provider }, { FakeLlmProvider() }, settings, FakeApiKeyRepository()),
        TestDispatcherProvider(Dispatchers.Unconfined)
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 会話と [count] 件の発言(日時は 10, 20, ...)を作る。 */
    private suspend fun conversationWith(count: Int, firstAt: Int = 1, id: Long = 1): Long {
        if (db.conversationDao().getById(id) == null) {
            db.conversationDao().insert(ConversationEntity(id = id, title = "会話", createdAt = 0, updatedAt = 0))
        }
        (firstAt until firstAt + count).forEach { i ->
            db.chatMessageDao().insert(
                ChatMessageEntity(
                    conversationId = id,
                    role = if (i % 2 == 1) ChatRole.USER else ChatRole.ASSISTANT,
                    content = "発言$i",
                    createdAt = i * 10L
                )
            )
        }
        return id
    }

    private suspend fun conversation() = db.conversationDao().getById(1)!!

    @Test
    fun 回答の引用番号は要約に渡さない() = runBlocking {
        db.conversationDao().insert(ConversationEntity(id = 1, title = "会話", createdAt = 0, updatedAt = 0))
        (1..10).forEach { i ->
            db.chatMessageDao().insert(
                ChatMessageEntity(
                    conversationId = 1,
                    role = if (i % 2 == 1) ChatRole.USER else ChatRole.ASSISTANT,
                    content = "発言$i [1]",
                    createdAt = i * 10L
                )
            )
        }

        compressor().compress(1, tight = false)

        assertEquals(listOf("発言1 [1]", "発言2", "発言3 [1]", "発言4"), llm.requests.single().second)
    }

    @Test
    fun 発言がたまるまでは要約しない() = runBlocking {
        val id = conversationWith(9)

        compressor().compress(id, tight = false)

        assertTrue(llm.requests.isEmpty())
        assertNull(conversation().summary)
    }

    @Test
    fun 古い発言を要約して保存し_直近の発言は残す() = runBlocking {
        val id = conversationWith(10)

        compressor().compress(id, tight = false)

        assertEquals(listOf(null to (1..4).map { "発言$it" }), llm.requests)
        assertEquals("要約1", conversation().summary)
        assertEquals(40L, conversation().summaryCoveredUntil)
    }

    @Test
    fun 開き直しても要約を作り直さず_次は続きから前の要約に足す() = runBlocking {
        val id = conversationWith(10)
        compressor().compress(id, tight = false)
        llm.requests.clear()

        // 作り直した部品(アプリの再起動)でも、保存した要約から続ける
        compressor().compress(id, tight = false)
        assertTrue("新しい発言が無ければ要約しない", llm.requests.isEmpty())

        conversationWith(4, firstAt = 11)
        llm.summary = "要約2"
        compressor().compress(id, tight = false)

        assertEquals(listOf("要約1" to (5..8).map { "発言$it" }), llm.requests)
        assertEquals("要約2", conversation().summary)
        assertEquals(80L, conversation().summaryCoveredUntil)
    }

    @Test
    fun 文脈に余裕が無ければ早めに要約する() = runBlocking {
        val id = conversationWith(4)

        compressor().compress(id, tight = true)

        assertEquals(listOf(null to listOf("発言1", "発言2")), llm.requests)
        assertEquals(20L, conversation().summaryCoveredUntil)
    }

    @Test
    fun 要約を作らないAIでは何も保存しない() = runBlocking {
        val id = conversationWith(10)

        // クラウド API は summarizeConversation を実装しない(既定で null)
        compressor(FakeLlmProvider()).compress(id, tight = false)

        assertNull(conversation().summary)
        assertNull(conversation().summaryCoveredUntil)
    }

    @Test
    fun 失敗しても落ちず_次の回にやり直す() = runBlocking {
        val id = conversationWith(10)
        llm.failure = IllegalStateException("生成に失敗")
        val compressor = compressor()

        compressor.compress(id, tight = false)
        assertNull(conversation().summary)

        llm.failure = null
        compressor.compress(id, tight = false)
        assertEquals("要約1", conversation().summary)
    }

    @Test
    fun 要約を作っている間に同じ会話では重ねない() = runBlocking {
        val id = conversationWith(10)
        val gate = CompletableDeferred<Unit>()
        llm.gate = gate
        val compressor = compressor()

        compressor.compressIfNeeded(id, tight = false)
        llm.started.await()
        compressor.compressIfNeeded(id, tight = false)
        gate.complete(Unit)

        withTimeout(WAIT_MS) { while (conversation().summary == null) delay(10) }
        assertEquals(1, llm.requests.size)
    }

    @Test
    fun 含めた最後の発言が作っている間に消えたら保存しない() = runBlocking {
        val id = conversationWith(10)
        val gate = CompletableDeferred<Unit>()
        llm.gate = gate
        val compressing = async(Dispatchers.Default) { compressor().compress(id, tight = false) }
        llm.started.await()

        // 要約に含めた発言(日時 40)から先を消す
        val deleted = db.chatMessageDao().getRecentMessages(id, 10)[3]
        db.chatMessageDao().deleteFrom(id, deleted.createdAt, deleted.id)
        gate.complete(Unit)
        compressing.await()

        assertNull(conversation().summary)
        assertNull(conversation().summaryCoveredUntil)
    }

    @Test
    fun 作っている間に別の要約が保存されたら上書きしない() = runBlocking {
        val id = conversationWith(10)
        val gate = CompletableDeferred<Unit>()
        llm.gate = gate
        val compressing = async(Dispatchers.Default) { compressor().compress(id, tight = false) }
        llm.started.await()

        db.conversationDao().updateSummary(id, "先に保存された要約", coveredUntil = 20, previousCoveredUntil = null)
        gate.complete(Unit)
        compressing.await()

        assertEquals("先に保存された要約", conversation().summary)
        assertEquals(20L, conversation().summaryCoveredUntil)
    }

    @Test
    fun 消した発言を含む要約だけを消す() = runBlocking {
        val id = conversationWith(10)
        compressor().compress(id, tight = false)

        db.conversationDao().clearSummaryFrom(id, createdAt = 50)
        assertEquals("含めていない発言なら残す", "要約1", conversation().summary)

        db.conversationDao().clearSummaryFrom(id, createdAt = 40)
        assertNull(conversation().summary)
        assertNull(conversation().summaryCoveredUntil)
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
