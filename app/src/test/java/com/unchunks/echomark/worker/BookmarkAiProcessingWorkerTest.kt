package com.unchunks.echomark.worker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.data.mapper.toEntity
import com.unchunks.echomark.data.repository.BookmarkRepositoryImpl
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.provider.LlmException
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeEmbeddingProvider
import com.unchunks.echomark.testing.TestDispatcherProvider
import com.unchunks.echomark.testing.embeddingBox
import com.unchunks.echomark.testing.inMemoryBoxStore
import com.unchunks.echomark.testing.initTestWorkManager
import com.unchunks.echomark.testing.tearDownTestWorkManager
import io.objectbox.BoxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** AI 処理ワーカーの再試行・失敗の判断と、削除済みのブックマークの扱い。 */
@RunWith(AndroidJUnit4::class)
class BookmarkAiProcessingWorkerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var boxStore: BoxStore
    private lateinit var workManager: WorkManager
    private lateinit var repository: BookmarkRepositoryImpl
    private val llm = ScriptedLlmProvider()
    private val settings = FakeAppSettingsRepository(backend = LlmBackend.LOCAL)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        boxStore = inMemoryBoxStore()
        workManager = initTestWorkManager(context)
        repository = BookmarkRepositoryImpl(
            bookmarkDao = db.bookmarkDao(),
            tagDao = db.tagDao(),
            vectorSearch = VectorSearchDataSource(boxStore.embeddingBox()),
            dispatcherProvider = TestDispatcherProvider(Dispatchers.Unconfined),
            workScheduler = BookmarkWorkScheduler(workManager, settings),
            embeddingProvider = FakeEmbeddingProvider()
        )
    }

    @After
    fun tearDown() {
        tearDownTestWorkManager(workManager)
        db.close()
        boxStore.close()
    }

    private suspend fun insertBookmark(): Long = db.bookmarkDao().insert(
        Bookmark(type = BookmarkType.TEXT, content = "本文", title = "メモ", createdAt = 1L, lastAccessedAt = 1L)
            .toEntity()
    )

    private suspend fun runWorker(bookmarkId: Long, runAttemptCount: Int): ListenableWorker.Result {
        val resolver = LlmProviderResolver(llm, llm, settings, FakeApiKeyRepository())
        val worker = TestListenableWorkerBuilder<BookmarkAiProcessingWorker>(context)
            .setInputData(workDataOf(BookmarkAiProcessingWorker.KEY_BOOKMARK_ID to bookmarkId))
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ) = BookmarkAiProcessingWorker(appContext, workerParameters, repository, resolver, FakeEmbeddingProvider())
            })
            .build()
        return worker.doWork()
    }

    private suspend fun statusOf(id: Long) = repository.getBookmarkById(id)!!.aiStatus

    @Test
    fun 成功すると要約とタグを保存して完了にする() = runBlocking {
        val id = insertBookmark()

        assertEquals(ListenableWorker.Result.success(), runWorker(id, runAttemptCount = 0))

        assertEquals(AiStatus.DONE, statusOf(id))
        assertEquals("要約", repository.getBookmarkById(id)!!.summary)
    }

    @Test
    fun 削除済みのブックマークはAIを呼ばずに終える() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), runWorker(999L, runAttemptCount = 0))
        assertEquals(0, llm.calls)
    }

    @Test
    fun 通信エラーは試行回数が増えても再試行する() = runBlocking {
        val id = insertBookmark()
        llm.failure = LlmException.Network()

        assertEquals(ListenableWorker.Result.retry(), runWorker(id, runAttemptCount = 5))
        assertEquals(AiStatus.PENDING, statusOf(id))
    }

    @Test
    fun レート制限は試行回数が増えても再試行する() = runBlocking {
        val id = insertBookmark()
        llm.failure = LlmException.RateLimited(retryAfterSeconds = 60)

        assertEquals(ListenableWorker.Result.retry(), runWorker(id, runAttemptCount = 5))
        assertEquals(AiStatus.PENDING, statusOf(id))
    }

    @Test
    fun 通信エラーでも上限に達したら失敗にする() = runBlocking {
        val id = insertBookmark()
        llm.failure = LlmException.Network()

        // 一括処理のチェーンを止めないよう、ワークとしては成功で終える(状態はブックマークの FAILED で表す)
        assertEquals(
            ListenableWorker.Result.success(),
            runWorker(id, runAttemptCount = maxAttemptsFor(LlmException.Network()) - 1)
        )
        assertEquals(AiStatus.FAILED, statusOf(id))
    }

    @Test
    fun サーバーエラーは上限に達したら失敗にする() = runBlocking {
        val id = insertBookmark()
        llm.failure = LlmException.ServerError(503)

        assertEquals(ListenableWorker.Result.retry(), runWorker(id, runAttemptCount = 0))
        assertEquals(
            ListenableWorker.Result.success(),
            runWorker(id, runAttemptCount = maxAttemptsFor(LlmException.ServerError(503)) - 1)
        )
        assertEquals(AiStatus.FAILED, statusOf(id))
    }

    @Test
    fun 試行回数の上限は通信エラーとレート制限で大きくする() {
        val default = maxAttemptsFor(LlmException.ServerError(503))
        assertTrue(maxAttemptsFor(LlmException.Network()) > default)
        assertTrue(maxAttemptsFor(LlmException.RateLimited()) > default)
        assertEquals(default, maxAttemptsFor(IllegalStateException("boom")))
    }
}

/** analyze の結果を差し替えられる LLM。 */
private class ScriptedLlmProvider : LlmProvider {
    var failure: Exception? = null
    var calls = 0

    override suspend fun analyze(text: String): BookmarkAnalysis {
        calls++
        failure?.let { throw it }
        return BookmarkAnalysis("要約", listOf("タグ"), "その他")
    }

    override suspend fun chat(userMessage: String, context: List<String>, history: List<ChatMessage>): String =
        error("not used")
}
