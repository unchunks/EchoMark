package com.unchunks.echomark.worker

import com.unchunks.echomark.data.attachment.AttachmentStore
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
import com.unchunks.echomark.data.ai.BookmarkAnalyzer
import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.data.mapper.toEntity
import com.unchunks.echomark.data.repository.BookmarkRepositoryImpl
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.model.AnalysisAttachment
import com.unchunks.echomark.domain.provider.EmbeddingModelProfile
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import com.unchunks.echomark.domain.provider.NothingToAnalyzeException
import java.io.File
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.TagSource
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
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
    private val embedding = RecordingEmbeddingProvider()
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
            embeddingProvider = FakeEmbeddingProvider(),
            attachmentStore = AttachmentStore(context, TestDispatcherProvider(Dispatchers.Unconfined))
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
        val analyzer = BookmarkAnalyzer(LlmProviderResolver(llm, { llm }, settings, FakeApiKeyRepository()), settings)
        val worker = TestListenableWorkerBuilder<BookmarkAiProcessingWorker>(context)
            .setInputData(workDataOf(BookmarkAiProcessingWorker.KEY_BOOKMARK_ID to bookmarkId))
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ) = BookmarkAiProcessingWorker(appContext, workerParameters, repository, analyzer, embedding)
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
    fun 既存のタグをAIに渡し_再処理ではAIのタグだけを付け直す() = runBlocking {
        val id = insertBookmark()
        repository.addTag(id, "自分のタグ")
        db.tagDao().addTagsToBookmark(insertBookmark2(), listOf("Android"), TagSource.USER)
        llm.tags = listOf("android", "古いAIタグ")

        runWorker(id, runAttemptCount = 0)

        assertEquals(listOf("Android", "自分のタグ"), llm.lastExistingTags)
        val first = repository.observeBookmark(id).first()!!
        // 表記ゆれは既存のタグにそろえる
        assertEquals(setOf("自分のタグ", "Android", "古いAIタグ"), first.tags.toSet())
        assertEquals(setOf("Android", "古いAIタグ"), first.aiTags)

        repository.reprocess(id)
        llm.tags = listOf("新しいAIタグ")
        runWorker(id, runAttemptCount = 0)

        val second = repository.observeBookmark(id).first()!!
        assertEquals(setOf("自分のタグ", "新しいAIタグ"), second.tags.toSet())
        assertEquals(setOf("新しいAIタグ"), second.aiTags)
        // どこにも付かなくなった AI のタグは消え、ユーザーのタグ(別のブックマークに付いている Android)は残る
        val allTags = db.tagDao().getAllTags().first().map { it.name }.toSet()
        assertEquals(setOf("自分のタグ", "新しいAIタグ", "Android"), allTags)
    }

    private suspend fun insertBookmark2(): Long = db.bookmarkDao().insert(
        Bookmark(type = BookmarkType.TEXT, content = "別の本文", title = "別のメモ", createdAt = 2L, lastAccessedAt = 2L)
            .toEntity()
    )

    @Test
    fun ファイルのブックマークは種類と元のファイルをAIに渡す() = runBlocking {
        val file = File(context.filesDir, "attachments/receipt.png").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(1234))
        }
        val id = db.bookmarkDao().insert(
            Bookmark(
                type = BookmarkType.IMAGE, content = "合計 1,200円", title = "receipt.png", createdAt = 1L,
                lastAccessedAt = 1L, filePath = "attachments/receipt.png", mimeType = "image/png", fileSize = 1234L
            ).toEntity()
        )

        runWorker(id, runAttemptCount = 0)

        val input = llm.lastInput!!
        assertEquals(ContentKind.IMAGE, input.kind)
        assertEquals("合計 1,200円", input.text)
        assertEquals(AnalysisAttachment(file.canonicalPath, "image/png", 1234L), input.attachment)
    }

    @Test
    fun 中身を読み取れないファイルは準備待ちにする() = runBlocking {
        val id = insertBookmark()
        llm.failure = NothingToAnalyzeException()

        assertEquals(ListenableWorker.Result.success(), runWorker(id, runAttemptCount = 0))

        // 端末内 AI では読めない写真など。クラウド API に切り替えた後などに再処理で要約できる
        assertEquals(AiStatus.WAITING_MODEL, statusOf(id))
        assertEquals(null, repository.getBookmarkById(id)!!.summary)
    }

    @Test
    fun 埋め込みには作った要約を含め_要約を作り直したら埋め込みも作り直す() = runBlocking {
        val id = insertBookmark()

        runWorker(id, runAttemptCount = 0)
        assertEquals(listOf("メモ\n要約\n本文"), embedding.documents)

        repository.reprocess(id)
        runWorker(id, runAttemptCount = 0)
        // 埋め込みモデルは同じでも、要約が変わったため作り直す
        assertEquals(2, embedding.documents.size)
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

    @Test
    fun 生成の時間切れは再試行せず失敗にする() = runBlocking {
        val id = insertBookmark()
        llm.failure = LlmException.Timeout(180_000L)

        // 一括処理のチェーンを止めないよう、ワークとしては成功で終える。設定画面から手動で再処理できる
        assertEquals(ListenableWorker.Result.success(), runWorker(id, runAttemptCount = 0))
        assertEquals(AiStatus.FAILED, statusOf(id))
    }

    @Test
    fun 中断されたら処理中のまま残さず処理待ちに戻す() = runBlocking {
        val id = insertBookmark()
        llm.hang = true

        val work = launch { runWorker(id, runAttemptCount = 0) }
        llm.started.await()
        assertEquals(AiStatus.PROCESSING, statusOf(id))

        // WorkManager による停止(実行時間の上限・取り消しなど)を、コルーチンのキャンセルで再現する
        work.cancelAndJoin()
        assertEquals(AiStatus.PENDING, statusOf(id))
    }
}

/** analyze の結果を差し替えられる LLM。渡された既存タグを記録する。 */
private class ScriptedLlmProvider : LlmProvider {
    var failure: Exception? = null
    var calls = 0
    /** true なら analyze は取り消されるまで終わらない。 */
    var hang = false
    val started = CompletableDeferred<Unit>()
    var tags: List<String> = listOf("タグ")
    var lastExistingTags: List<String>? = null
    var lastInput: AnalysisInput? = null

    override suspend fun analyze(input: AnalysisInput, existingTags: List<String>, scope: AnalysisScope): BookmarkAnalysis {
        calls++
        lastInput = input
        started.complete(Unit)
        if (hang) awaitCancellation()
        lastExistingTags = existingTags
        failure?.let { throw it }
        return BookmarkAnalysis("要約", tags, "その他")
    }

    override suspend fun chat(userMessage: String, context: List<String>, history: List<ChatMessage>): String =
        error("not used")
}

/** 埋め込みにしたテキストを記録する。 */
private class RecordingEmbeddingProvider : EmbeddingProvider {
    private val delegate = FakeEmbeddingProvider()
    val documents = mutableListOf<String>()
    override val profile: EmbeddingModelProfile = delegate.profile
    override suspend fun embedDocument(text: String): FloatArray {
        documents += text
        return delegate.embedDocument(text)
    }
    override suspend fun embedQuery(text: String): FloatArray = delegate.embedQuery(text)
}
