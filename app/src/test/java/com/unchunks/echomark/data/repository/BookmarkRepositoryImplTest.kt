package com.unchunks.echomark.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.attachment.AttachmentStore
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.data.mapper.toEntity
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.AttachmentException
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.ProcessingFailureReason
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage
import com.unchunks.echomark.domain.bookmark.model.StoredAttachment
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.EmbeddingProgress
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeEmbeddingProvider
import com.unchunks.echomark.testing.assertSequential
import com.unchunks.echomark.testing.embeddingBox
import com.unchunks.echomark.testing.inMemoryBoxStore
import com.unchunks.echomark.testing.initTestWorkManager
import com.unchunks.echomark.testing.statesOf
import com.unchunks.echomark.testing.tearDownTestWorkManager
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
    private lateinit var attachmentStore: AttachmentStore

    private val vector = FloatArray(768) { if (it == 0) 1f else 0f }
    private val embedding = FakeEmbeddingProvider()
    private val settings = FakeAppSettingsRepository()
    private val currentVersion get() = embedding.modelVersion

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        boxStore = inMemoryBoxStore()
        vectorSearch = VectorSearchDataSource(boxStore.embeddingBox())
        workManager = initTestWorkManager(context)
        attachmentStore = AttachmentStore(context, TestDispatcherProvider(Dispatchers.Unconfined))
        repository = BookmarkRepositoryImpl(
            bookmarkDao = db.bookmarkDao(),
            tagDao = db.tagDao(),
            vectorSearch = vectorSearch,
            dispatcherProvider = TestDispatcherProvider(Dispatchers.Unconfined),
            workScheduler = BookmarkWorkScheduler(workManager, settings),
            embeddingProvider = embedding,
            attachmentStore = attachmentStore
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

    /** 一意名 [name] の未完了のワークの種類(ワーカーのクラスの単純名)の数 */
    private fun unfinishedKinds(name: String): Map<String, Int> =
        workManager.getWorkInfosForUniqueWork(name).get()
            .filterNot { it.state.isFinished }
            .map { info -> info.tags.first { it.startsWith("com.unchunks") }.substringAfterLast('.') }
            .groupingBy { it }.eachCount()

    /** [contentFetchedAt] の既定は、本文があれば取得済み・無ければ未取得 */
    private fun urlBookmark(
        content: String?,
        status: AiStatus,
        contentFetchedAt: Long? = if (content.isNullOrBlank()) null else 1L
    ) = Bookmark(
        type = BookmarkType.URL, content = content, contentUri = "https://example.com/${content.hashCode()}",
        title = "https://example.com", createdAt = 1L, lastAccessedAt = 1L, aiStatus = status,
        contentFetchedAt = contentFetchedAt
    )

    /** 一意名 [name] で登録されたワークの種類(ワーカーのクラスの単純名)を、登録順に関係なく数える。 */
    private fun workerKinds(name: String): Map<String, Int> =
        workManager.getWorkInfosForUniqueWork(name).get()
            .map { info -> info.tags.first { it.startsWith("com.unchunks") }.substringAfterLast('.') }
            .groupingBy { it }.eachCount()

    @Test
    fun URLを保存すると本文取得とAI処理が一意名で登録される() = runBlocking {
        val id = repository.saveUrlBookmark("https://example.com/a", null, null).id

        // 本文取得(ネットワーク待ち)→ 中身の取り出し(リンク先がファイルだったとき用。前段待ち)→ AI 処理(前段待ち)
        assertEquals(
            listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.BLOCKED),
            workManager.statesOf("process_bookmark_$id").sorted()
        )
        assertEquals(
            mapOf("UrlFetchWorker" to 1, "ContentExtractionWorker" to 1, "BookmarkAiProcessingWorker" to 1),
            workerKinds("process_bookmark_$id")
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
        val id = repository.saveBookmark(textBookmark()).id

        repository.reprocess(id)
        repository.reprocess(id)

        assertEquals(1, unfinished("process_bookmark_$id").size)
    }

    @Test
    fun タグだけの再処理は要約を残し_本文の取得をせずAI処理だけを作り直すものを指定して積む() = runBlocking {
        val id = db.bookmarkDao().insert(
            urlBookmark(content = "本文", status = AiStatus.DONE).copy(summary = "要約").toEntity()
        )

        repository.reprocess(id, AnalysisScope.TAGS)

        val bookmark = repository.getBookmarkById(id)!!
        assertEquals("要約", bookmark.summary)
        assertEquals(AiStatus.PENDING, bookmark.aiStatus)
        val infos = workManager.getWorkInfosForUniqueWork("process_bookmark_$id").get()
        assertEquals(mapOf("BookmarkAiProcessingWorker" to 1), workerKinds("process_bookmark_$id"))
        assertTrue(infos.single().tags.contains(BookmarkWorkScheduler.bookmarkTag(id)))
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
            mapOf("UrlFetchWorker" to 1, "ContentExtractionWorker" to 1, "BookmarkAiProcessingWorker" to 2),
            workerKinds(BookmarkWorkScheduler.BULK_WORK_NAME)
        )
    }

    @Test
    fun 本文が未取得のURLの再処理は本文取得から行う() = runBlocking {
        val id = db.bookmarkDao().insert(urlBookmark(content = " ", status = AiStatus.FAILED).toEntity())

        repository.reprocess(id)

        assertEquals(
            mapOf("UrlFetchWorker" to 1, "ContentExtractionWorker" to 1, "BookmarkAiProcessingWorker" to 1),
            workerKinds("process_bookmark_$id")
        )
    }

    // ---- ファイルのブックマーク ----

    private fun storeFile(name: String, bytes: ByteArray, mimeType: String): StoredAttachment =
        attachmentStore.saveStream(bytes.inputStream(), mimeType, name, maxBytes = 1024 * 1024)

    @Test
    fun 画像ファイルを保存すると中身の取り出しとAI処理が登録される() = runBlocking {
        val stored = storeFile("旅行の写真.JPG", byteArrayOf(1, 2, 3), "image/jpeg")

        val id = repository.saveFileBookmark(stored, title = " ", memo = "京都で撮影").id

        val saved = repository.getBookmarkById(id)!!
        assertEquals(BookmarkType.IMAGE, saved.type)
        assertEquals("旅行の写真", saved.title)
        assertEquals("京都で撮影", saved.content)
        assertEquals(stored.filePath, saved.filePath)
        assertEquals("image/jpeg", saved.mimeType)
        assertEquals("旅行の写真.JPG", saved.fileName)
        assertEquals(3L, saved.fileSize)
        assertEquals(
            mapOf("ContentExtractionWorker" to 1, "BookmarkAiProcessingWorker" to 1),
            workerKinds("process_bookmark_$id")
        )
    }

    @Test
    fun テキストファイルは中身を本文に入れてAI処理のみ登録する() = runBlocking {
        val stored = storeFile("notes.md", "# 見出し\n本文です".toByteArray(), "text/markdown")

        val id = repository.saveFileBookmark(stored, title = "読書メモ", memo = null).id

        val saved = repository.getBookmarkById(id)!!
        assertEquals(BookmarkType.TEXT, saved.type)
        assertEquals("読書メモ", saved.title)
        assertEquals("# 見出し\n本文です", saved.content)
        assertEquals(mapOf("BookmarkAiProcessingWorker" to 1), workerKinds("process_bookmark_$id"))
    }

    @Test(expected = AttachmentException::class)
    fun 保存できない形式のファイルは保存しない(): Unit = runBlocking {
        repository.saveFileBookmark(
            StoredAttachment("attachments/a.zip", "application/zip", "a.zip", 10L),
            title = null,
            memo = null
        )
        Unit
    }

    @Test
    fun ファイルのあるブックマークの再処理と復元は中身の取り出しから行う() = runBlocking {
        val stored = storeFile("talk.m4a", byteArrayOf(9, 9), "audio/mp4")
        val id = repository.saveFileBookmark(stored, null, null).id

        repository.reprocess(id)
        assertEquals(
            mapOf("ContentExtractionWorker" to 1, "BookmarkAiProcessingWorker" to 1),
            unfinishedKinds("process_bookmark_$id")
        )

        val bookmark = repository.getBookmarkById(id)!!
        repository.deleteBookmark(bookmark)
        repository.restoreBookmark(bookmark)
        assertEquals(
            mapOf("ContentExtractionWorker" to 1, "BookmarkAiProcessingWorker" to 1),
            unfinishedKinds("process_bookmark_$id")
        )
    }

    @Test
    fun 準備待ちから再開するときファイルのあるものは中身の取り出しから行う() = runBlocking {
        val stored = storeFile("doc.pdf", byteArrayOf(1), "application/pdf")
        val id = repository.saveFileBookmark(stored, null, null).id
        db.bookmarkDao().updateAiStatus(id, AiStatus.WAITING_MODEL)

        repository.enqueueWaitingModelProcessing()

        assertEquals(
            mapOf("ContentExtractionWorker" to 1, "BookmarkAiProcessingWorker" to 1),
            workerKinds(BookmarkWorkScheduler.BULK_WORK_NAME)
        )
    }

    @Test
    fun ブックマークを削除しても添付ファイルはすぐには消さず更新日時を今にする() = runBlocking {
        val stored = storeFile("photo.png", byteArrayOf(1, 2), "image/png")
        val file = attachmentStore.existingFile(stored.filePath)!!
        file.setLastModified(1_000L)
        val id = repository.saveFileBookmark(stored, null, null).id

        repository.deleteBookmark(repository.getBookmarkById(id)!!)

        assertTrue(file.isFile)
        assertTrue(file.lastModified() > 1_000L)
    }

    @Test
    fun リンク先から保存したファイルの情報を差し替えられる() = runBlocking {
        val id = repository.saveUrlBookmark("https://example.com/paper.pdf", null, null).id
        val stored = StoredAttachment("attachments/x.pdf", "application/pdf", "paper.pdf", 42L)

        repository.updateAttachment(id, stored)

        val saved = repository.getBookmarkById(id)!!
        assertEquals(BookmarkType.URL, saved.type)
        assertEquals("attachments/x.pdf", saved.filePath)
        assertEquals("application/pdf", saved.mimeType)
        assertEquals("paper.pdf", saved.fileName)
        assertEquals(42L, saved.fileSize)
    }

    @Test
    fun 本文があるURLの再処理はAI処理のみ() = runBlocking {
        val id = db.bookmarkDao().insert(urlBookmark(content = "本文", status = AiStatus.DONE).toEntity())

        repository.reprocess(id)

        assertEquals(mapOf("BookmarkAiProcessingWorker" to 1), workerKinds("process_bookmark_$id"))
    }

    @Test
    fun メモはあるが本文を取得できていないURLの再処理は本文取得から行う() = runBlocking {
        val id = db.bookmarkDao().insert(
            urlBookmark(content = "あとで読む", status = AiStatus.DONE, contentFetchedAt = null).toEntity()
        )

        repository.reprocess(id)

        assertEquals(
            mapOf("UrlFetchWorker" to 1, "ContentExtractionWorker" to 1, "BookmarkAiProcessingWorker" to 1),
            workerKinds("process_bookmark_$id")
        )
    }

    @Test
    fun 本文を取得できたことを記録すると再処理はAI処理のみになる() = runBlocking {
        val id = db.bookmarkDao().insert(
            urlBookmark(content = "あとで読む", status = AiStatus.DONE, contentFetchedAt = null).toEntity()
        )

        repository.markContentFetched(id, fetchedAt = 42L)
        repository.reprocess(id)

        assertEquals(42L, repository.getBookmarkById(id)?.contentFetchedAt)
        assertEquals(mapOf("BookmarkAiProcessingWorker" to 1), workerKinds("process_bookmark_$id"))
    }

    @Test
    fun 存在しないブックマークにはタグを保存しない() = runBlocking {
        repository.saveAiTags(bookmarkId = 999L, tagNames = listOf("kotlin"))

        assertTrue(db.tagDao().getAllTags().first().isEmpty())
    }

    @Test
    fun AIのタグは前回の分を置き換え_ユーザーのタグは残す() = runBlocking {
        val id = repository.saveBookmark(textBookmark()).id
        repository.addTag(id, "自分")

        repository.saveAiTags(id, listOf("kotlin", "android"))
        repository.saveAiTags(id, listOf("kotlin"))

        val bookmark = repository.observeBookmark(id).first()!!
        assertEquals(setOf("自分", "kotlin"), bookmark.tags.toSet())
        assertEquals(setOf("kotlin"), bookmark.aiTags)
    }

    @Test
    fun 削除するとAIだけのタグは消え_取り消すと付けた人ごと元に戻る() = runBlocking {
        val id = repository.saveBookmark(textBookmark()).id
        repository.addTag(id, "自分")
        repository.saveAiTags(id, listOf("AI"))
        val bookmark = repository.observeBookmark(id).first()!!

        repository.deleteBookmark(bookmark)
        assertEquals(listOf("自分"), db.tagDao().getAllTags().first().map { it.name })

        repository.restoreBookmark(bookmark)
        val restored = repository.observeBookmark(id).first()!!
        assertEquals(setOf("自分", "AI"), restored.tags.toSet())
        assertEquals(setOf("AI"), restored.aiTags)
    }

    @Test
    fun AIのタグだけを外すとタグも消える() = runBlocking {
        val id = repository.saveBookmark(textBookmark()).id
        repository.addTag(id, "自分")
        repository.saveAiTags(id, listOf("AI"))

        repository.removeTag(id, "AI")
        repository.removeTag(id, "自分")

        // ユーザーのタグは件数 0 でも残す(タグ管理から消せる)
        assertEquals(listOf("自分"), db.tagDao().getAllTags().first().map { it.name })
    }

    @Test
    fun 存在しないブックマークの埋め込みは保存しない() = runBlocking {
        repository.saveEmbedding(bookmarkId = 999L, vector = vector, modelVersion = "v1")

        assertNull(vectorSearch.getModelVersion(999L))
    }

    @Test
    fun 既存のブックマークの埋め込みは保存する() = runBlocking {
        val id = repository.saveBookmark(textBookmark()).id

        repository.saveEmbedding(id, vector, "v1")

        assertEquals("v1", vectorSearch.getModelVersion(id))
    }

    @Test
    fun 関連ブックマークは近い順に並ぶ() = runBlocking {
        val self = repository.saveBookmark(textBookmark("自分")).id
        // ID の小さいほうを遠くし、ID 順と近い順を逆にする
        val far = repository.saveBookmark(textBookmark("遠い")).id
        val near = repository.saveBookmark(textBookmark("近い")).id
        repository.saveEmbedding(self, vector, currentVersion)
        repository.saveEmbedding(far, FloatArray(768) { if (it <= 1) 1f else 0f }, currentVersion)
        repository.saveEmbedding(near, FloatArray(768) { if (it == 0) 1f else if (it == 1) 0.1f else 0f }, currentVersion)

        assertEquals(listOf(near, far), repository.getRelatedBookmarks(self, limit = 5).map { it.id })
    }

    @Test
    fun 関連ブックマークは旧モデルのベクトルを混ぜない() = runBlocking {
        val self = repository.saveBookmark(textBookmark("自分")).id
        val sameVersion = repository.saveBookmark(textBookmark("新しい版")).id
        val oldVersion = repository.saveBookmark(textBookmark("旧版")).id
        repository.saveEmbedding(self, vector, currentVersion)
        repository.saveEmbedding(sameVersion, FloatArray(768) { if (it <= 1) 1f else 0f }, currentVersion)
        // 自分と全く同じ向きでも、版が違えば比べない
        repository.saveEmbedding(oldVersion, vector, "old-model")

        assertEquals(listOf(sameVersion), repository.getRelatedBookmarks(self, limit = 5).map { it.id })
    }

    @Test
    fun 自分のベクトルが旧モデルのものなら関連ブックマークは空() = runBlocking {
        val self = repository.saveBookmark(textBookmark("自分")).id
        val other = repository.saveBookmark(textBookmark("他")).id
        repository.saveEmbedding(self, vector, "old-model")
        repository.saveEmbedding(other, vector, currentVersion)

        assertEquals(emptyList<Long>(), repository.getRelatedBookmarks(self, limit = 5).map { it.id })
    }

    @Test
    fun 意味検索は旧モデルのベクトルを混ぜない() = runBlocking {
        val current = repository.saveBookmark(textBookmark("あ")).id
        val old = repository.saveBookmark(textBookmark("い")).id
        // FakeEmbeddingProvider の質問ベクトル(vector)と同じ向き。版だけが違う
        repository.saveEmbedding(current, vector, currentVersion)
        repository.saveEmbedding(old, vector, "old-model")

        val result = repository.searchWithDetails("キーワードに一致しない質問", null)

        assertEquals(setOf(current), result.bookmarks.map { it.id }.toSet())
    }

    @Test
    fun 埋め込みの進捗は現在の版で保存済みの件数を数える() = runBlocking {
        val a = repository.saveBookmark(textBookmark("a")).id
        val b = repository.saveBookmark(textBookmark("b")).id
        repository.saveBookmark(textBookmark("c"))
        repository.saveEmbedding(a, vector, currentVersion)
        repository.saveEmbedding(b, vector, "old-model")

        assertEquals(EmbeddingProgress(embedded = 1, total = 3), repository.getEmbeddingProgress())
    }

    @Test
    fun 保存すると処理待ちにした日時が入る() = runBlocking {
        val before = System.currentTimeMillis()
        val bookmark = repository.getBookmarkById(repository.saveBookmark(textBookmark()).id)!!

        assertEquals(AiStatus.PENDING, bookmark.aiStatus)
        assertTrue(bookmark.processing.queuedAt!! >= before)
        assertNull(bookmark.processing.stage)
        assertNull(bookmark.processing.startedAt)
    }

    @Test
    fun 段を始めると処理中になり_終えると次の段を待つ処理待ちに戻る() = runBlocking {
        val id = repository.saveBookmark(textBookmark()).id

        val startedAt = repository.startProcessingStage(id, ProcessingStage.FETCH_CONTENT)
        repository.getBookmarkById(id)!!.let {
            assertEquals(AiStatus.PROCESSING, it.aiStatus)
            assertEquals(ProcessingStage.FETCH_CONTENT, it.processing.stage)
            assertEquals(startedAt, it.processing.stageStartedAt)
            assertEquals(startedAt, it.processing.startedAt)
        }

        repository.endProcessingStage(id, startedAt)
        repository.getBookmarkById(id)!!.let {
            assertEquals(AiStatus.PENDING, it.aiStatus)
            assertNull(it.processing.stage)
            assertNull(it.processing.stageStartedAt)
            // 処理を始めた日時は、次の段・完了まで残す
            assertEquals(startedAt, it.processing.startedAt)
        }
    }

    @Test
    fun 次の段を始めても処理を始めた日時は最初の段のまま() = runBlocking {
        val id = repository.saveBookmark(textBookmark()).id
        db.bookmarkDao().startStage(id, ProcessingStage.FETCH_CONTENT, now = 100L)
        db.bookmarkDao().endStage(id, stageStartedAt = 100L)

        db.bookmarkDao().startStage(id, ProcessingStage.ANALYZE, now = 200L)

        val processing = repository.getBookmarkById(id)!!.processing
        assertEquals(ProcessingStage.ANALYZE, processing.stage)
        assertEquals(100L, processing.startedAt)
        assertEquals(200L, processing.stageStartedAt)
    }

    @Test
    fun 中断を記録しても後から始まった段は処理待ちに戻さない() = runBlocking {
        // 置き換えた新しい処理が、古い処理の中断の記録より先に段を始めた場合
        val id = repository.saveBookmark(textBookmark()).id
        db.bookmarkDao().startStage(id, ProcessingStage.FETCH_CONTENT, now = 100L)
        db.bookmarkDao().startStage(id, ProcessingStage.ANALYZE, now = 200L)

        repository.endProcessingStage(id, stageStartedAt = 100L)

        val bookmark = repository.getBookmarkById(id)!!
        assertEquals(AiStatus.PROCESSING, bookmark.aiStatus)
        assertEquals(ProcessingStage.ANALYZE, bookmark.processing.stage)
    }

    @Test
    fun 中断を記録しても後から書かれた結果は上書きしない() = runBlocking {
        // 置き換えた新しい処理が、古い処理の中断の記録より先に完了した場合
        val id = repository.saveBookmark(textBookmark()).id
        db.bookmarkDao().startStage(id, ProcessingStage.ANALYZE, now = 100L)
        repository.finishProcessing(id, AiStatus.DONE)

        repository.endProcessingStage(id, stageStartedAt = 100L)

        assertEquals(AiStatus.DONE, repository.getBookmarkById(id)!!.aiStatus)
    }

    @Test
    fun 処理を終えると段を消し_終えた日時と失敗の理由を残す() = runBlocking {
        val id = repository.saveBookmark(textBookmark()).id
        repository.startProcessingStage(id, ProcessingStage.ANALYZE)

        repository.finishProcessing(id, AiStatus.FAILED, ProcessingFailureReason.REFUSED)

        val bookmark = repository.getBookmarkById(id)!!
        assertEquals(AiStatus.FAILED, bookmark.aiStatus)
        assertNull(bookmark.processing.stage)
        assertTrue(bookmark.processing.finishedAt != null)
        assertEquals(ProcessingFailureReason.REFUSED, bookmark.processing.failureReason)
    }

    @Test
    fun 再試行を待つ間は処理待ちにし_直前の失敗の理由を残す() = runBlocking {
        val id = repository.saveBookmark(textBookmark()).id
        repository.startProcessingStage(id, ProcessingStage.ANALYZE)

        repository.markProcessingRetrying(id, ProcessingFailureReason.NETWORK)

        val bookmark = repository.getBookmarkById(id)!!
        assertEquals(AiStatus.PENDING, bookmark.aiStatus)
        assertNull(bookmark.processing.stage)
        assertEquals(ProcessingFailureReason.NETWORK, bookmark.processing.failureReason)
        assertTrue(bookmark.processing.startedAt != null)
    }

    @Test
    fun 再処理すると前の処理の段_日時_失敗の理由を消して処理待ちにする() = runBlocking {
        val id = repository.saveBookmark(textBookmark()).id
        repository.startProcessingStage(id, ProcessingStage.ANALYZE)
        repository.finishProcessing(id, AiStatus.FAILED, ProcessingFailureReason.TIMEOUT)

        repository.reprocess(id)

        val processing = repository.getBookmarkById(id)!!.let {
            assertEquals(AiStatus.PENDING, it.aiStatus)
            it.processing
        }
        assertTrue(processing.queuedAt != null)
        assertNull(processing.startedAt)
        assertNull(processing.finishedAt)
        assertNull(processing.failureReason)
    }

    @Test
    fun 前のプロセスで始まった段は処理待ちに戻して積み直し_今のプロセスで始まった段は触らない() = runBlocking {
        val processStartedAt = 1_000L
        // 前のプロセスで本文の取得中に止められたもの(ワークも失われた)
        val stopped = db.bookmarkDao().insert(textBookmark().toEntity())
        db.bookmarkDao().startStage(stopped, ProcessingStage.FETCH_CONTENT, now = processStartedAt - 1)
        // このプロセスで要約を作っているもの
        val running = db.bookmarkDao().insert(textBookmark().toEntity())
        db.bookmarkDao().startStage(running, ProcessingStage.ANALYZE, now = processStartedAt + 1)

        assertEquals(1, repository.enqueueStalledProcessing(processStartedAt))

        repository.getBookmarkById(stopped)!!.let {
            assertEquals(AiStatus.PENDING, it.aiStatus)
            assertNull(it.processing.stage)
        }
        repository.getBookmarkById(running)!!.let {
            assertEquals(AiStatus.PROCESSING, it.aiStatus)
            assertEquals(ProcessingStage.ANALYZE, it.processing.stage)
        }
        assertSequential(workManager.statesOf(BookmarkWorkScheduler.BULK_WORK_NAME), expectedSize = 1)
    }

    @Test
    fun ワークが無いまま処理待ち_処理中のものだけを積み直す() = runBlocking {
        // 保存時に登録した処理が残っているもの
        val live = repository.saveBookmark(textBookmark()).id
        // 処理が失われたもの、完了・準備待ちのもの
        val (stalledPending, stalledProcessing, done, waiting) =
            listOf(AiStatus.PENDING, AiStatus.PROCESSING, AiStatus.DONE, AiStatus.WAITING_MODEL).map { status ->
                db.bookmarkDao().insert(textBookmark().copy(aiStatus = status).toEntity())
            }

        assertEquals(2, repository.enqueueStalledProcessing(System.currentTimeMillis()))

        // 失われた2件は一括の列に積み、処理が残っているものは二重に積まない
        assertSequential(workManager.statesOf(BookmarkWorkScheduler.BULK_WORK_NAME), expectedSize = 2)
        assertEquals(1, unfinished("process_bookmark_$live").size)
        assertEquals(
            listOf(AiStatus.PENDING, AiStatus.PENDING, AiStatus.DONE, AiStatus.WAITING_MODEL),
            listOf(stalledPending, stalledProcessing, done, waiting).map { repository.getBookmarkById(it)!!.aiStatus }
        )

        // 積み直した後は処理が残っているため、もう一度呼んでも積まない
        assertEquals(0, repository.enqueueStalledProcessing(System.currentTimeMillis()))
    }

    @Test
    fun 夜間にまとめて処理する設定では保存しても積まず_夜間の仕事が充電中を条件に積む() = runBlocking {
        settings.updateProcessingSettings { it.copy(nightlyBatch = true) }
        val id = repository.saveBookmark(textBookmark()).id

        // 処理待ちのまま夜を待つ(昼の積み直しでも積まない)
        assertEquals(AiStatus.PENDING, repository.getBookmarkById(id)!!.aiStatus)
        assertTrue(unfinished("process_bookmark_$id").isEmpty())
        assertEquals(0, repository.enqueueStalledProcessing(System.currentTimeMillis()))
        assertEquals(0, repository.enqueueWaitingProcessing(nightly = false))

        assertEquals(1, repository.enqueueWaitingProcessing(nightly = true))
        val bulk = workManager.getWorkInfosForUniqueWork(BookmarkWorkScheduler.BULK_WORK_NAME).get()
        assertEquals(1, bulk.size)
        assertTrue(bulk.single().constraints.requiresCharging())
        // 積んだ後はワークがあるので二重に積まない
        assertEquals(0, repository.enqueueWaitingProcessing(nightly = true))
    }

    @Test
    fun どのブックマークの処理か分からないワークが残っている間は積み直さない() = runBlocking {
        // ブックマークごとのタグを付ける前のバージョンで登録された処理
        workManager.enqueue(
            OneTimeWorkRequestBuilder<BookmarkAiProcessingWorker>().addTag(BookmarkWorkScheduler.TAG).build()
        ).result.get()
        db.bookmarkDao().insert(textBookmark().copy(aiStatus = AiStatus.PROCESSING).toEntity())

        assertEquals(0, repository.enqueueStalledProcessing(System.currentTimeMillis()))
        assertTrue(workManager.statesOf(BookmarkWorkScheduler.BULK_WORK_NAME).isEmpty())
    }
}
