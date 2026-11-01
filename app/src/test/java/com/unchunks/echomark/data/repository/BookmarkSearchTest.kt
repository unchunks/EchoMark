package com.unchunks.echomark.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.unchunks.components.embedding.EmbeddingModelProfile
import com.unchunks.components.embedding.EmbeddingProvider
import com.unchunks.components.embedding.EmbeddingUnavailableException
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.attachment.AttachmentStore
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.embeddingBox
import com.unchunks.echomark.testing.inMemoryBoxStore
import com.unchunks.echomark.testing.initTestWorkManager
import com.unchunks.echomark.testing.tearDownTestWorkManager
import com.unchunks.echomark.worker.BookmarkWorkScheduler
import io.objectbox.BoxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 一覧の検索(キーワードの語の分け方・検索する項目・埋め込みが使えないときのフォールバック・検索語の埋め込みの控え)のテスト。 */
@RunWith(AndroidJUnit4::class)
class BookmarkSearchTest {

    /** 埋め込みの Fake。[available] が false ならモデルが無いときと同じく失敗する。質問の埋め込みの回数を数える */
    private class CountingEmbeddingProvider : EmbeddingProvider {
        var available = true
        var queryCount = 0
        override val profile = EmbeddingModelProfile.GEMMA_V1.copy(modelVersion = "test-embedding-v1")
        override suspend fun embedDocument(text: String): FloatArray = vector()
        override suspend fun embedQuery(text: String): FloatArray {
            queryCount++
            return vector()
        }

        private fun vector(): FloatArray {
            if (!available) throw EmbeddingUnavailableException()
            return FloatArray(dimensions) { if (it == 0) 1f else 0f }
        }
    }

    private lateinit var db: AppDatabase
    private lateinit var boxStore: BoxStore
    private lateinit var workManager: WorkManager
    private lateinit var repository: BookmarkRepositoryImpl
    private val embedding = CountingEmbeddingProvider()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        boxStore = inMemoryBoxStore()
        workManager = initTestWorkManager(context)
        repository = BookmarkRepositoryImpl(
            bookmarkDao = db.bookmarkDao(),
            tagDao = db.tagDao(),
            noteDao = db.bookmarkNoteDao(),
            categoryDao = db.categoryDao(),
            vectorSearch = VectorSearchDataSource(boxStore.embeddingBox()),
            dispatcherProvider = TestDispatcherProvider(Dispatchers.Unconfined),
            workScheduler = BookmarkWorkScheduler(workManager, FakeAppSettingsRepository()),
            embeddingProvider = embedding,
            attachmentStore = AttachmentStore(context, TestDispatcherProvider(Dispatchers.Unconfined))
        )
        runBlocking {
            insert(1, "東京の美味しいラーメン", content = "醤油味のスープ")
            insert(2, "Some page", contentUri = "https://github.com/foo/bar", siteName = "GitHub")
            insert(3, "React Hooks 入門")
            insert(4, "大阪のラーメン")
        }
    }

    @After
    fun tearDown() {
        tearDownTestWorkManager(workManager)
        db.close()
        boxStore.close()
    }

    private suspend fun insert(
        id: Long,
        title: String,
        content: String? = null,
        contentUri: String? = null,
        siteName: String? = null
    ) {
        db.bookmarkDao().insert(
            BookmarkEntity(
                id = id,
                type = if (contentUri != null) BookmarkType.URL else BookmarkType.TEXT,
                title = title,
                content = content,
                contentUri = contentUri,
                siteName = siteName,
                createdAt = id,
                lastAccessedAt = id
            )
        )
    }

    private suspend fun keywordTitles(query: String) = repository.searchKeywordOnly(query).map { it.title }

    @Test
    fun 空白で区切った語をすべて含むものを探す() = runBlocking {
        // 語が離れていても見つかる(以前は「東京 ラーメン」という続いた文字列を探していて見つからなかった)
        assertEquals(listOf("東京の美味しいラーメン"), keywordTitles("東京 ラーメン"))
        assertEquals(listOf("東京の美味しいラーメン"), keywordTitles("ラーメン　東京"))
        assertEquals(listOf("React Hooks 入門"), keywordTitles("hooks react"))
        // 1語なら、その語を含むものすべて(新しい順)
        assertEquals(listOf("大阪のラーメン", "東京の美味しいラーメン"), keywordTitles("ラーメン"))
        // どれかの語を含まなければ出さない
        assertEquals(emptyList<String>(), keywordTitles("東京 うどん"))
    }

    @Test
    fun 本文とURLとサイト名でも見つかる() = runBlocking {
        assertEquals(listOf("東京の美味しいラーメン"), keywordTitles("醤油"))
        assertEquals(listOf("Some page"), keywordTitles("github.com"))
        assertEquals(listOf("Some page"), keywordTitles("GitHub"))
    }

    @Test
    fun 全角の英数字でも半角で保存したものが見つかる() = runBlocking {
        assertEquals(listOf("React Hooks 入門"), keywordTitles("ＲＥＡＣＴ"))
    }

    @Test
    fun 埋め込みが使えなければキーワードの結果だけを返す() = runBlocking {
        embedding.available = false

        assertFalse(repository.prepareSemanticSearch("東京 ラーメン"))
        val result = repository.searchWithDetails("東京 ラーメン")

        assertEquals(listOf(1L), result.bookmarks.map { it.id })
        assertFalse(result.semanticAvailable)
    }

    @Test
    fun 検索語の埋め込みは覚えておき検索し直しても埋め込み直さない() = runBlocking {
        assertTrue(repository.prepareSemanticSearch("ラーメン"))
        repository.searchWithDetails("ラーメン")
        repository.searchWithDetails("ラーメン")
        assertEquals(1, embedding.queryCount)

        repository.searchWithDetails("うどん")
        assertEquals(2, embedding.queryCount)
    }
}
