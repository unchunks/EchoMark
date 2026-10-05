package com.unchunks.echomark.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.data.mapper.toEntity
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeEmbeddingProvider
import com.unchunks.echomark.testing.TestDispatcherProvider
import com.unchunks.echomark.testing.embeddingBox
import com.unchunks.echomark.testing.inMemoryBoxStore
import com.unchunks.echomark.testing.assertSequential
import com.unchunks.echomark.testing.initTestWorkManager
import com.unchunks.echomark.testing.tearDownTestWorkManager
import com.unchunks.echomark.testing.statesOf
import com.unchunks.echomark.worker.BookmarkAiProcessingWorker
import com.unchunks.echomark.worker.BookmarkWorkScheduler
import io.objectbox.BoxStore
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

/** 保存・削除・再処理とワーク(WorkManager)・埋め込み(ObjectBox)の整合のテスト。 */
@RunWith(AndroidJUnit4::class)
class BookmarkRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var boxStore: BoxStore
    private lateinit var vectorSearch: VectorSearchDataSource
    private lateinit var workManager: WorkManager
    private lateinit var repository: BookmarkRepositoryImpl

    private val vector = FloatArray(768) { if (it == 0) 1f else 0f }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        boxStore = inMemoryBoxStore()
        vectorSearch = VectorSearchDataSource(boxStore.embeddingBox())
        workManager = initTestWorkManager(context)
        repository = BookmarkRepositoryImpl(
            bookmarkDao = db.bookmarkDao(),
            tagDao = db.tagDao(),
            vectorSearch = vectorSearch,
            dispatcherProvider = TestDispatcherProvider(Dispatchers.Unconfined),
            workScheduler = BookmarkWorkScheduler(workManager, FakeAppSettingsRepository()),
            embeddingProvider = FakeEmbeddingProvider()
        )
    }

    @After
    fun tearDown() {
        tearDownTestWorkManager(workManager)
        db.close()
        boxStore.close()
    }

    private fun textBookmark(title: String = "メモ") = Bookmark(
        type = BookmarkType.TEXT, content = "本文", title = title, createdAt = 1L, lastAccessedAt = 1L
    )

    private fun unfinished(name: String) = workManager.statesOf(name).filterNot { it.isFinished }

    private fun urlBookmark(content: String?, status: AiStatus) = Bookmark(
        type = BookmarkType.URL, content = content, contentUri = "https://example.com/${content.hashCode()}",
        title = "https://example.com", createdAt = 1L, lastAccessedAt = 1L, aiStatus = status
    )

    /** 一意名 [name] で登録されたワークの種類(ワーカーのクラスの単純名)を、登録順に関係なく数える。 */
    private fun workerKinds(name: String): Map<String, Int> =
        workManager.getWorkInfosForUniqueWork(name).get()
            .map { info -> info.tags.first { it.startsWith("com.unchunks") }.substringAfterLast('.') }
            .groupingBy { it }.eachCount()

    @Test
    fun URLを保存すると本文取得とAI処理が一意名で登録される() = runBlocking {
        val id = repository.saveUrlBookmark("https://example.com/a", null, null).id

        // 本文取得(ネットワーク待ち)→ AI 処理(前段待ち)
        assertEquals(
            listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED),
            workManager.statesOf("process_bookmark_$id").sorted()
        )
    }

    @Test
    fun 削除すると登録済みの処理を取り消す() = runBlocking {
        val id = repository.saveUrlBookmark("https://example.com/a", null, null).id
        val bookmark = repository.getBookmarkById(id)!!

        repository.deleteBookmark(bookmark)

        val states = workManager.statesOf("process_bookmark_$id")
        assertTrue(states.isNotEmpty())
        assertTrue(states.toString(), states.all { it == WorkInfo.State.CANCELLED })
    }

    @Test
    fun 再処理を連打しても同じブックマークの処理は1つだけ() = runBlocking {
        val id = repository.saveBookmark(textBookmark())

        repository.reprocess(id)
        repository.reprocess(id)

        assertEquals(1, unfinished("process_bookmark_$id").size)
    }

    @Test
    fun 失敗と準備待ちの一括再処理は1件ずつ順番に処理する() = runBlocking {
        val ids = listOf(AiStatus.FAILED, AiStatus.WAITING_MODEL, AiStatus.FAILED, AiStatus.DONE).map { status ->
            db.bookmarkDao().insert(textBookmark().copy(aiStatus = status).toEntity())
        }

        assertEquals(3, repository.enqueueFailedAndWaitingProcessing())

        // 1本の列にまとまり、同時に動くのは1件だけ。個別の一意名では登録しない
        val states = workManager.statesOf(BookmarkWorkScheduler.BULK_WORK_NAME)
        assertSequential(states, expectedSize = 3)
        assertTrue(ids.all { workManager.statesOf("process_bookmark_$it").isEmpty() })
        assertEquals(
            listOf(AiStatus.PENDING, AiStatus.PENDING, AiStatus.PENDING, AiStatus.DONE),
            ids.map { repository.getBookmarkById(it)!!.aiStatus }
        )
    }

    @Test
    fun 本文が未取得のURLは再開時に本文取得から行う() = runBlocking {
        // バックアップの読み込みで「処理待ち(本文未取得)」が準備待ちとして入った場合など
        db.bookmarkDao().insert(urlBookmark(content = null, status = AiStatus.WAITING_MODEL).toEntity())
        db.bookmarkDao().insert(urlBookmark(content = "取得済みの本文", status = AiStatus.WAITING_MODEL).toEntity())

        repository.enqueueWaitingModelProcessing()

        assertEquals(
            mapOf("UrlFetchWorker" to 1, "BookmarkAiProcessingWorker" to 2),
            workerKinds(BookmarkWorkScheduler.BULK_WORK_NAME)
        )
    }

    @Test
    fun 本文が未取得のURLの再処理は本文取得から行う() = runBlocking {
        val id = db.bookmarkDao().insert(urlBookmark(content = " ", status = AiStatus.FAILED).toEntity())

        repository.reprocess(id)

        assertEquals(
            mapOf("UrlFetchWorker" to 1, "BookmarkAiProcessingWorker" to 1),
            workerKinds("process_bookmark_$id")
        )
    }

    @Test
    fun 本文があるURLの再処理はAI処理のみ() = runBlocking {
        val id = db.bookmarkDao().insert(urlBookmark(content = "本文", status = AiStatus.DONE).toEntity())

        repository.reprocess(id)

        assertEquals(mapOf("BookmarkAiProcessingWorker" to 1), workerKinds("process_bookmark_$id"))
    }

    @Test
    fun 存在しないブックマークにはタグを保存しない() = runBlocking {
        repository.saveTags(bookmarkId = 999L, tagNames = listOf("kotlin"))

        assertTrue(db.tagDao().getAllTags().first().isEmpty())
    }

    @Test
    fun タグは既存のブックマークに付く() = runBlocking {
        val id = repository.saveBookmark(textBookmark())

        repository.saveTags(id, listOf("kotlin", "android"))
        repository.saveTags(id, listOf("kotlin"))

        assertEquals(setOf("kotlin", "android"), repository.observeBookmark(id).first()!!.tags.toSet())
    }

    @Test
    fun 存在しないブックマークの埋め込みは保存しない() = runBlocking {
        repository.saveEmbedding(bookmarkId = 999L, vector = vector, modelVersion = "v1")

        assertNull(vectorSearch.getModelVersion(999L))
    }

    @Test
    fun 既存のブックマークの埋め込みは保存する() = runBlocking {
        val id = repository.saveBookmark(textBookmark())

        repository.saveEmbedding(id, vector, "v1")

        assertEquals("v1", vectorSearch.getModelVersion(id))
    }

    @Test
    fun 中断された処理中の状態は処理待ちに戻す() = runBlocking {
        val id = db.bookmarkDao().insert(textBookmark().copy(aiStatus = AiStatus.PROCESSING).toEntity())

        repository.markProcessingInterrupted(id)

        assertEquals(AiStatus.PENDING, repository.getBookmarkById(id)!!.aiStatus)
    }

    @Test
    fun 中断を記録しても後から書かれた状態は上書きしない() = runBlocking {
        // 置き換えた新しい処理が、古い処理の中断の記録より先に完了した場合
        val id = db.bookmarkDao().insert(textBookmark().copy(aiStatus = AiStatus.DONE).toEntity())

        repository.markProcessingInterrupted(id)

        assertEquals(AiStatus.DONE, repository.getBookmarkById(id)!!.aiStatus)
    }

    @Test
    fun ワークが無いまま処理待ち_処理中のものだけを積み直す() = runBlocking {
        // 保存時に登録した処理が残っているもの
        val live = repository.saveBookmark(textBookmark())
        // 処理が失われたもの、完了・準備待ちのもの
        val (stalledPending, stalledProcessing, done, waiting) =
            listOf(AiStatus.PENDING, AiStatus.PROCESSING, AiStatus.DONE, AiStatus.WAITING_MODEL).map { status ->
                db.bookmarkDao().insert(textBookmark().copy(aiStatus = status).toEntity())
            }

        assertEquals(2, repository.enqueueStalledProcessing())

        // 失われた2件は一括の列に積み、処理が残っているものは二重に積まない
        assertSequential(workManager.statesOf(BookmarkWorkScheduler.BULK_WORK_NAME), expectedSize = 2)
        assertEquals(1, unfinished("process_bookmark_$live").size)
        assertEquals(
            listOf(AiStatus.PENDING, AiStatus.PENDING, AiStatus.DONE, AiStatus.WAITING_MODEL),
            listOf(stalledPending, stalledProcessing, done, waiting).map { repository.getBookmarkById(it)!!.aiStatus }
        )

        // 積み直した後は処理が残っているため、もう一度呼んでも積まない
        assertEquals(0, repository.enqueueStalledProcessing())
    }

    @Test
    fun どのブックマークの処理か分からないワークが残っている間は積み直さない() = runBlocking {
        // ブックマークごとのタグを付ける前のバージョンで登録された処理
        workManager.enqueue(
            OneTimeWorkRequestBuilder<BookmarkAiProcessingWorker>().addTag(BookmarkWorkScheduler.TAG).build()
        ).result.get()
        db.bookmarkDao().insert(textBookmark().copy(aiStatus = AiStatus.PROCESSING).toEntity())

        assertEquals(0, repository.enqueueStalledProcessing())
        assertTrue(workManager.statesOf(BookmarkWorkScheduler.BULK_WORK_NAME).isEmpty())
    }
}
