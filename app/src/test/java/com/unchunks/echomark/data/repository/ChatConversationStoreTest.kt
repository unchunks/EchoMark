package com.unchunks.echomark.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.embedding.EmbeddingModelProfile
import com.unchunks.components.embedding.EmbeddingProvider
import com.unchunks.components.embedding.EmbeddingUnavailableException
import com.unchunks.components.llm.ChatRole
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.ai.ChatMemoryCompressor
import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.ai.LocalLlmProviders
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.data.mapper.toDomain
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakeLlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 会話の分岐・固定・検索を、本物の Room で確かめる。 */
@RunWith(AndroidJUnit4::class)
class ChatConversationStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ChatRepositoryImpl
    private val bookmarks = FakeBookmarkRepository()

    private val noEmbedding = object : EmbeddingProvider {
        override val profile = EmbeddingModelProfile.GEMMA_V1.copy(modelVersion = "test")
        override suspend fun embedDocument(text: String): FloatArray = throw EmbeddingUnavailableException()
        override suspend fun embedQuery(text: String): FloatArray = throw EmbeddingUnavailableException()
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dispatchers = TestDispatcherProvider(Dispatchers.Unconfined)
        val llm = FakeLlmProvider()
        val resolver = LlmProviderResolver(LocalLlmProviders { llm }, { llm }, FakeAppSettingsRepository(), FakeApiKeyRepository())
        repository = ChatRepositoryImpl(
            conversationDao = db.conversationDao(),
            chatMessageDao = db.chatMessageDao(),
            vectorSearch = unusedVectorSearch(),
            embeddingProvider = noEmbedding,
            llmProviderResolver = resolver,
            bookmarkRepository = bookmarks,
            chatMemory = ChatMemoryCompressor(db.conversationDao(), db.chatMessageDao(), resolver, dispatchers),
            dispatcherProvider = dispatchers
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** このテストでは検索しないため、中身の無いインスタンスを渡す(ObjectBox を開かない)。 */
    private fun unusedVectorSearch(): VectorSearchDataSource {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, VectorSearchDataSource::class.java) as VectorSearchDataSource
    }

    /** 質問と回答が交互の4発言(日時は 10, 20, 30, 40)の会話を作る。 */
    private suspend fun conversation(
        id: Long = 1,
        title: String = "元の会話",
        summary: String? = null,
        coveredUntil: Long? = null
    ): List<ChatMessage> {
        db.conversationDao().insert(
            ConversationEntity(
                id = id, title = title, summary = summary, summaryCoveredUntil = coveredUntil,
                createdAt = 1, updatedAt = 1, aboutBookmarkId = 9
            )
        )
        listOf("質問1", "回答1", "質問2", "回答2").forEachIndexed { i, text ->
            db.chatMessageDao().insert(
                ChatMessageEntity(
                    conversationId = id,
                    role = if (i % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT,
                    content = text,
                    referencedBookmarkIds = "5".takeIf { i % 2 == 1 },
                    createdAt = (i + 1) * 10L
                )
            )
        }
        return db.chatMessageDao().getRecentMessages(id, 10).map { it.toDomain() }
    }

    private suspend fun messagesOf(id: Long) = db.chatMessageDao().getRecentMessages(id, 10).map { it.toDomain() }

    @Test
    fun 回答から分けると回答までを写し_元の会話は変わらない() = runBlocking {
        val messages = conversation()

        val id = repository.branchConversation(messages[1])!!

        assertEquals(listOf("質問1", "回答1"), messagesOf(id).map { it.content })
        assertEquals(listOf(10L, 20L), messagesOf(id).map { it.createdAt })
        assertEquals(listOf(5L), messagesOf(id)[1].referencedBookmarkIds)
        assertEquals(4, messagesOf(1).size)
        val branch = db.conversationDao().getById(id)!!
        assertEquals(1L, branch.branchedFromId)
        assertEquals(20L, branch.branchedAt)
        assertEquals("元の会話(分岐)", branch.title)
        assertEquals(9L, branch.aboutBookmarkId)
        assertTrue(branch.isTitleManuallySet)
    }

    @Test
    fun 質問から分けるとその質問の手前までを写す() = runBlocking {
        val messages = conversation()

        val id = repository.branchConversation(messages[2])!!

        assertEquals(listOf("質問1", "回答1"), messagesOf(id).map { it.content })
    }

    @Test
    fun 最初の質問からは分けられない() = runBlocking {
        val messages = conversation()

        assertNull(repository.branchConversation(messages[0]))
    }

    @Test
    fun 分けた時点より前の要約だけを持ち越す() = runBlocking {
        val kept = conversation(id = 1, summary = "前の要約", coveredUntil = 20)
        val dropped = conversation(id = 2, title = "別の会話", summary = "後まで含む要約", coveredUntil = 40)

        val withSummary = db.conversationDao().getById(repository.branchConversation(kept[3])!!)!!
        val withoutSummary = db.conversationDao().getById(repository.branchConversation(dropped[1])!!)!!

        assertEquals("前の要約", withSummary.summary)
        assertEquals(20L, withSummary.summaryCoveredUntil)
        assertNull(withoutSummary.summary)
        assertNull(withoutSummary.summaryCoveredUntil)
    }

    @Test
    fun 分けた会話の一覧を流す() = runBlocking {
        val messages = conversation()
        val id = repository.branchConversation(messages[1])!!

        val branch = repository.observeBranches(1).first().single()

        assertEquals(id, branch.id)
        assertEquals(20L, branch.branchedAt)
        assertEquals(emptyList<Any>(), repository.observeBranches(id).first())
    }

    @Test
    fun 元の会話を消しても分けた会話は残る() = runBlocking {
        val messages = conversation()
        val id = repository.branchConversation(messages[1])!!

        repository.deleteConversation(1)

        assertEquals(2, messagesOf(id).size)
    }

    @Test
    fun 固定した会話は一覧の先頭に来る() = runBlocking {
        conversation(id = 1, title = "古い")
        conversation(id = 2, title = "新しい")
        db.conversationDao().touch(2, 100)

        repository.setPinned(1, true)
        assertEquals(listOf(1L, 2L), repository.observeConversationPreviews().first().map { it.conversation.id })
        assertTrue(repository.observeConversationPreviews().first()[0].conversation.isPinned)

        repository.setPinned(1, false)
        assertEquals(listOf(2L, 1L), repository.observeConversationPreviews().first().map { it.conversation.id })
    }

    @Test
    fun タイトルと発言から探し_発言に当たれば最新の発言を添える() = runBlocking {
        conversation(id = 1, title = "コルーチンの話")
        conversation(id = 2, title = "旅行の話")

        val byTitle = repository.searchConversations("コルーチン").single()
        assertEquals(1L, byTitle.conversationId)
        assertNull(byTitle.matchedMessage)

        assertEquals(setOf(1L, 2L), repository.searchConversations("回答").map { it.conversationId }.toSet())
        assertEquals("回答2", repository.searchConversations("回答").first().matchedMessage)
    }

    @Test
    fun 複数の語はすべて含む会話を探す() = runBlocking {
        conversation(id = 1, title = "コルーチンの話")
        conversation(id = 2, title = "旅行の話")

        assertEquals(listOf(1L), repository.searchConversations("質問1　コルーチン").map { it.conversationId })
        assertEquals(emptyList<Any>(), repository.searchConversations("旅行 コルーチン"))
        assertEquals(emptyList<Any>(), repository.searchConversations("   "))
    }

    @Test
    fun 検索語の記号はそのままの文字として探す() = runBlocking {
        conversation(id = 1, title = "100%の話")
        conversation(id = 2, title = "10の話")

        assertEquals(listOf(1L), repository.searchConversations("0%").map { it.conversationId })
        assertEquals(emptyList<Any>(), repository.searchConversations("_"))
    }

    @Test
    fun 回答をメモとしてテキストのブックマークに保存する() = runBlocking {
        val id = repository.saveNote("タイトル", "本文")

        val saved = bookmarks.bookmarks.value.single { it.id == id }
        assertEquals("タイトル", saved.title)
        assertEquals("本文", saved.content)
    }
}
