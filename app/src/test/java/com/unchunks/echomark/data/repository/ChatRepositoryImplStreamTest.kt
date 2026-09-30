package com.unchunks.echomark.data.repository

import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.local.dao.ChatMessageDao
import com.unchunks.echomark.data.local.dao.ConversationDao
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import com.unchunks.echomark.domain.provider.EmbeddingUnavailableException
import com.unchunks.echomark.domain.provider.LlmException
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.repository.ChatStreamEvent
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakeLlmProvider
import com.unchunks.echomark.testing.TestDispatcherProvider
import com.unchunks.echomark.testing.testBookmark
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryImplStreamTest {

    private val messageDao = FakeChatMessageDao()
    private val conversationDao = FakeConversationDao()
    private val bookmarkRepository = FakeBookmarkRepository().apply {
        bookmarks.value = listOf(testBookmark(id = 5, title = "Kotlin 入門"))
    }

    /** 埋め込みモデルが無い環境(assets 未同梱)を再現する。 */
    private val noEmbedding = object : EmbeddingProvider {
        override val dimensions = 768
        override val modelVersion = "test"
        override suspend fun embedDocument(text: String): FloatArray = throw EmbeddingUnavailableException()
        override suspend fun embedQuery(text: String): FloatArray = throw EmbeddingUnavailableException()
    }

    private fun repository(
        llm: LlmProvider,
        settings: FakeAppSettingsRepository = FakeAppSettingsRepository(LlmBackend.LOCAL)
    ) = ChatRepositoryImpl(
        conversationDao = conversationDao,
        chatMessageDao = messageDao,
        vectorSearch = unusedVectorSearch(),
        embeddingProvider = noEmbedding,
        llmProviderResolver = LlmProviderResolver(llm, llm, settings, FakeApiKeyRepository()),
        bookmarkRepository = bookmarkRepository,
        dispatcherProvider = TestDispatcherProvider(Dispatchers.Unconfined)
    )

    @Test
    fun 増分を積み上げて流し_完了時に全文を保存する() = runBlocking {
        val events = repository(FakeLlmProvider(listOf("こんにちは", "、世界")))
            .sendMessageStream(1L, "Kotlin").toList()

        // 埋め込みが無いのでキーワード検索で文脈を探す
        assertEquals(ChatStreamEvent.Started(listOf(5L)), events[0])
        assertEquals(ChatStreamEvent.Delta("こんにちは"), events[1])
        assertEquals(ChatStreamEvent.Delta("こんにちは、世界"), events[2])
        val completed = events[3] as ChatStreamEvent.Completed
        val saved = messageDao.all.value
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), saved.map { it.role })
        assertEquals("こんにちは、世界", saved[1].content)
        assertEquals("5", saved[1].referencedBookmarkIds)
        assertEquals(saved[1].id, completed.messageId)
        assertEquals("Kotlin", conversationDao.autoTitle)
    }

    @Test
    fun 生成途中の失敗はFailedで_途中までの回答は保存しない() = runBlocking {
        val refused = LlmException.Refused("cyber")
        val events = repository(FakeLlmProvider(listOf("途中"), failure = refused))
            .sendMessageStream(1L, "質問").toList()

        assertEquals(ChatStreamEvent.Failed(refused), events.last())
        assertEquals(listOf(ChatRole.USER), messageDao.all.value.map { it.role })
    }

    @Test
    fun プロバイダを選べない失敗もFailedになりユーザー発言は残る() = runBlocking {
        val settings = FakeAppSettingsRepository(LlmBackend.API, ApiProvider.OPENAI)
        val events = repository(FakeLlmProvider(), settings).sendMessageStream(1L, "質問").toList()

        assertTrue((events.last() as ChatStreamEvent.Failed).error is LlmException.ApiKeyMissing)
        assertEquals(listOf("質問"), messageDao.all.value.map { it.content })
    }

    @Test
    fun キャンセルすると途中までの回答を停止付きで保存する() = runBlocking {
        val endless = object : LlmProvider {
            override suspend fun analyze(text: String): BookmarkAnalysis = TODO("not used")
            override suspend fun chat(userMessage: String, context: List<String>, history: List<ChatMessage>) =
                TODO("not used")

            override fun chatStream(userMessage: String, context: List<String>, history: List<ChatMessage>): Flow<String> =
                flow {
                    emit("途中まで")
                    awaitCancellation()
                }
        }
        val deltaSeen = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            repository(endless).sendMessageStream(1L, "質問").collect {
                if (it is ChatStreamEvent.Delta) deltaSeen.complete(Unit)
            }
        }
        deltaSeen.await()
        job.cancelAndJoin()

        val saved = messageDao.all.value
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), saved.map { it.role })
        assertEquals("途中まで" + ChatRepositoryImpl.STOPPED_SUFFIX, saved[1].content)
    }

    /**
     * ObjectBox(ネイティブライブラリ)に依存するため JVM では生成できない。
     * このテストは埋め込み無しの経路だけを通り、ベクトル検索は呼ばれないので未初期化のインスタンスで足りる。
     */
    private fun unusedVectorSearch(): VectorSearchDataSource {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, VectorSearchDataSource::class.java) as VectorSearchDataSource
    }
}

private class FakeChatMessageDao : ChatMessageDao {
    val all = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun insert(message: ChatMessageEntity): Long {
        val id = nextId++
        all.value = all.value + message.copy(id = id)
        return id
    }

    override fun observeMessages(conversationId: Long): Flow<List<ChatMessageEntity>> = all

    override suspend fun getRecentMessages(conversationId: Long, limit: Int): List<ChatMessageEntity> =
        all.value.filter { it.conversationId == conversationId }.takeLast(limit)

    override suspend fun getFirstByRole(conversationId: Long, role: ChatRole): ChatMessageEntity? =
        all.value.firstOrNull { it.conversationId == conversationId && it.role == role }
}

private class FakeConversationDao : ConversationDao {
    var autoTitle: String? = null

    override suspend fun insert(conversation: ConversationEntity): Long = 1L
    override suspend fun getById(id: Long): ConversationEntity? = null
    override fun getAll(): Flow<List<ConversationEntity>> = MutableStateFlow(emptyList())
    override suspend fun touch(id: Long, updatedAt: Long) = Unit
    override suspend fun updateAutoTitle(id: Long, title: String, updatedAt: Long) {
        autoTitle = title
    }
    override suspend fun rename(id: Long, title: String) = Unit
    override suspend fun deleteById(id: Long) = Unit
}
