package com.unchunks.echomark.data.repository

import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.local.dao.ChatMessageDao
import com.unchunks.echomark.data.local.dao.ConversationDao
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.data.local.entity.ConversationWithLastMessage
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.chat.RagSupport
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import com.unchunks.echomark.domain.provider.EmbeddingUnavailableException
import com.unchunks.echomark.domain.provider.LlmException
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.repository.ChatStreamEvent
import com.unchunks.echomark.domain.repository.AiTask
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryImplStreamTest {

    private val messageDao = FakeChatMessageDao()
    private val conversationDao = FakeConversationDao()
    private val fakeBookmarks = FakeBookmarkRepository().apply {
        bookmarks.value = listOf(testBookmark(id = 5, title = "Kotlin 入門"))
    }

    /** true なら検索(キーワード)がクエリに関係なく全件を返す(固定ブックマークとの重複を試すため)。 */
    private var searchReturnsAll = false

    private val bookmarkRepository: BookmarkRepository = object : BookmarkRepository by fakeBookmarks {
        override suspend fun getBookmarksByIds(ids: List<Long>): List<Bookmark> =
            fakeBookmarks.bookmarks.value.filter { it.id in ids }

        override suspend fun search(query: String, tagId: Long?): List<Bookmark> =
            if (searchReturnsAll) fakeBookmarks.bookmarks.value else fakeBookmarks.search(query, tagId)
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
        llmProviderResolver = LlmProviderResolver(llm, { llm }, settings, FakeApiKeyRepository()),
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
    fun チャットの用途の設定でプロバイダを選ぶ() = runBlocking {
        // 要約・タグ付けはキーの無い API でも、チャットが端末内なら答えられる
        val settings = FakeAppSettingsRepository(LlmBackend.API, ApiProvider.OPENAI)
        settings.setLlmBackend(AiTask.CHAT, LlmBackend.LOCAL)
        val events = repository(FakeLlmProvider(listOf("回答")), settings).sendMessageStream(1L, "質問").toList()

        assertTrue(events.last() is ChatStreamEvent.Completed)
    }

    @Test
    fun キャンセルすると途中までの回答を停止付きで保存する() = runBlocking {
        val endless = object : LlmProvider {
            override suspend fun analyze(input: AnalysisInput, existingTags: List<String>, scope: AnalysisScope): BookmarkAnalysis =
                TODO("not used")
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

    /** 渡された文脈・履歴を記録する LLM。 */
    private class RecordingLlm(private val answer: String = "回答", var failure: Throwable? = null) : LlmProvider {
        var lastContext: List<String> = emptyList()
        var lastHistory: List<ChatMessage> = emptyList()

        override suspend fun analyze(input: AnalysisInput, existingTags: List<String>, scope: AnalysisScope): BookmarkAnalysis =
            TODO("not used")
        override suspend fun chat(userMessage: String, context: List<String>, history: List<ChatMessage>) =
            TODO("not used")

        override fun chatStream(userMessage: String, context: List<String>, history: List<ChatMessage>): Flow<String> =
            flow {
                lastContext = context
                lastHistory = history
                failure?.let { throw it }
                emit(answer)
            }
    }

    @Test
    fun 固定したブックマークは検索結果より先頭に入り_重複しない() = runBlocking {
        fakeBookmarks.bookmarks.value = listOf(
            testBookmark(id = 5, title = "Kotlin 入門"),
            testBookmark(id = 7, title = "Compose メモ").copy(summary = "Compose の要約", content = "本文です")
        )
        searchReturnsAll = true
        val llm = RecordingLlm()

        val events = repository(llm).sendMessageStream(1L, "要約して", pinnedBookmarkId = 7L).toList()

        assertEquals(ChatStreamEvent.Started(listOf(7L, 5L)), events.first())
        assertEquals(2, llm.lastContext.size)
        assertTrue(llm.lastContext[0].startsWith("[1] ${RagSupport.PINNED_MARK} Compose メモ: Compose の要約"))
        assertTrue(llm.lastContext[0].contains("本文です"))
        assertTrue(llm.lastContext[1].startsWith("[2] Kotlin 入門"))
        assertEquals("7,5", messageDao.all.value.last().referencedBookmarkIds)
    }

    @Test
    fun 固定したブックマークが削除済みなら通常の検索だけで答える() = runBlocking {
        val events = repository(RecordingLlm()).sendMessageStream(1L, "Kotlin", pinnedBookmarkId = 99L).toList()

        assertEquals(ChatStreamEvent.Started(listOf(5L)), events.first())
    }

    @Test
    fun 再試行では失敗した同じ発言を使い回し_二重に保存しない() = runBlocking {
        val llm = RecordingLlm(failure = LlmException.Network())
        val repo = repository(llm)
        repo.sendMessageStream(1L, "質問").toList()
        assertEquals(listOf("質問"), messageDao.all.value.map { it.content })

        llm.failure = null
        val events = repo.sendMessageStream(1L, "質問", isRetry = true).toList()

        assertTrue(events.last() is ChatStreamEvent.Completed)
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), messageDao.all.value.map { it.role })
        // 履歴には今回の質問を含めない
        assertTrue(llm.lastHistory.isEmpty())
    }

    @Test
    fun 再試行でも最後の発言が違えば新しく保存する() = runBlocking {
        val repo = repository(RecordingLlm())
        repo.sendMessageStream(1L, "最初").toList()

        repo.sendMessageStream(1L, "次の質問", isRetry = true).toList()

        assertEquals(listOf("最初", "回答", "次の質問", "回答"), messageDao.all.value.map { it.content })
    }

    @Test
    fun タイトルを指定して作った会話は自動タイトルで上書きしない設定になる() = runBlocking {
        val repo = repository(RecordingLlm())
        repo.createConversation("Compose メモについて")
        repo.createConversation()

        assertEquals("Compose メモについて", conversationDao.inserted[0].title)
        assertTrue(conversationDao.inserted[0].isTitleManuallySet)
        assertEquals(ChatRepositoryImpl.DEFAULT_TITLE, conversationDao.inserted[1].title)
        assertFalse(conversationDao.inserted[1].isTitleManuallySet)
    }

    @Test
    fun 質問の対象を指定して作った会話はその対象を保存する() = runBlocking {
        val repo = repository(RecordingLlm())
        repo.createConversation("Compose メモについて", aboutBookmarkId = 7L)
        repo.createConversation()

        assertEquals(7L, conversationDao.inserted[0].aboutBookmarkId)
        assertEquals(null, conversationDao.inserted[1].aboutBookmarkId)
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

    override suspend fun insertAll(messages: List<ChatMessageEntity>) {
        all.value = all.value + messages
    }

    override fun observeMessages(conversationId: Long): Flow<List<ChatMessageEntity>> = all

    override suspend fun getRecentMessages(conversationId: Long, limit: Int): List<ChatMessageEntity> =
        all.value.filter { it.conversationId == conversationId }.takeLast(limit)

    override suspend fun getFirstByRole(conversationId: Long, role: ChatRole): ChatMessageEntity? =
        all.value.firstOrNull { it.conversationId == conversationId && it.role == role }
}

private class FakeConversationDao : ConversationDao {
    var autoTitle: String? = null
    val inserted = mutableListOf<ConversationEntity>()

    override suspend fun insert(conversation: ConversationEntity): Long {
        inserted += conversation
        return 1L
    }
    override suspend fun getById(id: Long): ConversationEntity? = null
    override fun observeById(id: Long): Flow<ConversationEntity?> = MutableStateFlow(null)
    override fun getAll(): Flow<List<ConversationEntity>> = MutableStateFlow(emptyList())
    override fun observeAllWithLastMessage(): Flow<List<ConversationWithLastMessage>> =
        MutableStateFlow(emptyList())
    override suspend fun touch(id: Long, updatedAt: Long) = Unit
    override suspend fun updateAutoTitle(id: Long, title: String, updatedAt: Long) {
        autoTitle = title
    }
    override suspend fun rename(id: Long, title: String) = Unit
    override suspend fun deleteById(id: Long) = Unit
}
