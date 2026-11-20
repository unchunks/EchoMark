package com.unchunks.echomark.data.factcheck

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.llm.ChatRole
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.ai.LocalLlmProviders
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.factcheck.ClaimVerdict
import com.unchunks.echomark.domain.factcheck.FactCheckTarget
import com.unchunks.echomark.domain.factcheck.NothingToFactCheckException
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.websearch.CollectedWebSource
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeLlmProvider
import com.unchunks.echomark.testing.FakeWebSourceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 確かめた結果の保存(開き直したときに検索し直さない)と、対象の消えた結果の片付け。 */
@RunWith(AndroidJUnit4::class)
class FactCheckRepositoryImplTest {

    private lateinit var db: AppDatabase
    private val page = CollectedWebSource(
        url = "https://example.com/a", title = "ページ", domain = "example.com",
        excerpt = "Kotlin 2.3 では、K2 コンパイラーが既定になりました。", fromPage = true
    )
    private val web = FakeWebSourceProvider(listOf(page))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun repository(now: Long = 1_000L): FactCheckRepositoryImpl {
        // AI を使えない(文の比べ合わせだけで確かめる)
        val resolver = LlmProviderResolver(
            LocalLlmProviders { throw ModelNotAvailableException() }, { FakeLlmProvider() },
            FakeAppSettingsRepository(LlmBackend.LOCAL), FakeApiKeyRepository()
        )
        return FactCheckRepositoryImpl(db.factCheckDao(), FactChecker(resolver, web), TestDispatcherProvider(Dispatchers.Unconfined)) { now }
    }

    private suspend fun bookmark(): Long = db.bookmarkDao().insert(
        BookmarkEntity(type = BookmarkType.URL, contentUri = "https://a", title = "記事", createdAt = 1, lastAccessedAt = 1)
    )

    @Test
    fun 確かめた結果を保存し_開き直したときは保存したものを出す() = runTest {
        val target = FactCheckTarget.bookmark(bookmark())
        val text = "Kotlin 2.3 では、K2 コンパイラーが既定になりました。"
        val repository = repository()

        val result = repository.check(target, text)
        val stored = repository.observe(target).first()!!

        assertEquals(result, stored)
        assertEquals(ClaimVerdict.SUPPORTED, stored.claims.single().verdict)
        assertEquals(1_000L, stored.checkedAt)
        assertFalse(stored.isStaleFor(text))
        assertTrue(stored.isStaleFor("要約を直した"))
        // 開き直しは保存したものを読むだけで、検索しない
        assertEquals(1, web.queries.size)
    }

    @Test
    fun 確かめ直すと前の結果を置き換える() = runTest {
        val target = FactCheckTarget.bookmark(bookmark())
        repository(now = 1).check(target, "Kotlin 2.3 では、K2 コンパイラーが既定になりました。")

        repository(now = 2).check(target, "Kotlin 2.3 では、K2 コンパイラーが既定になりました。ほかは 2026 年です。")

        assertEquals(2L, repository().observe(target).first()!!.checkedAt)
    }

    @Test
    fun 対象の消えた結果は次に確かめるときに消す() = runTest {
        val bookmarkId = bookmark()
        val conversationId = db.conversationDao().insert(ConversationEntity(title = "会話", createdAt = 1, updatedAt = 1))
        val messageId = db.chatMessageDao().insert(
            ChatMessageEntity(conversationId = conversationId, role = ChatRole.ASSISTANT, content = "回答", createdAt = 1)
        )
        val repository = repository()
        repository.check(FactCheckTarget.chatAnswer(messageId), "Kotlin 2.3 では、K2 コンパイラーが既定になりました。")
        repository.check(FactCheckTarget.bookmark(999), "Kotlin 2.3 では、K2 コンパイラーが既定になりました。")

        repository.check(FactCheckTarget.bookmark(bookmarkId), "Kotlin 2.3 では、K2 コンパイラーが既定になりました。")

        assertNull(repository.observe(FactCheckTarget.bookmark(999)).first())
        assertTrue(repository.observe(FactCheckTarget.chatAnswer(messageId)).first() != null)
        assertTrue(repository.observe(FactCheckTarget.bookmark(bookmarkId)).first() != null)
    }

    @Test
    fun 確かめる文が無ければ検索せずに知らせる() = runTest {
        try {
            repository().check(FactCheckTarget.bookmark(1), "感想です。")
            fail("例外が出るはず")
        } catch (e: NothingToFactCheckException) {
            assertTrue(web.queries.isEmpty())
        }
    }
}
