package com.unchunks.echomark.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.attachment.AttachmentStore
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.importer.ImportedLink
import com.unchunks.echomark.domain.model.TagSource
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeEmbeddingProvider
import com.unchunks.echomark.testing.embeddingBox
import com.unchunks.echomark.testing.inMemoryBoxStore
import com.unchunks.echomark.testing.initTestWorkManager
import com.unchunks.echomark.testing.tearDownTestWorkManager
import com.unchunks.echomark.worker.BookmarkWorkScheduler
import io.objectbox.BoxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * 購読の新着の取り込み([BookmarkRepositoryImpl.importLinks])の重さを測る。
 * 5,000 件のブックマークがある DB で、一覧の問い合わせ(observeFiltered)と、全件の問い合わせ(getAllWithTags。
 * 再発見ウィジェットの更新がアプリの動いている間ずっと見ている)を見ている間に、新着 30 件(購読の1回の確認の上限)を取り込む。
 * 書き込みのたびに見ている問い合わせが全件を読み直すので、読み直しの回数と、落ち着くまでの時間を出す。
 */
@RunWith(AndroidJUnit4::class)
class ImportPerformanceTest {

    private lateinit var db: AppDatabase
    private lateinit var boxStore: BoxStore
    private lateinit var workManager: WorkManager
    private lateinit var repository: BookmarkRepositoryImpl
    private val observers = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 端末と同じく、問い合わせ・書き込みは Room の既定の背景スレッドで行う
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        boxStore = inMemoryBoxStore()
        workManager = initTestWorkManager(context)
        val settings = FakeAppSettingsRepository()
        repository = BookmarkRepositoryImpl(
            bookmarkDao = db.bookmarkDao(),
            tagDao = db.tagDao(),
            noteDao = db.bookmarkNoteDao(),
            collectionDao = db.collectionDao(),
            reviewCardDao = db.reviewCardDao(),
            categoryDao = db.categoryDao(),
            vectorSearch = VectorSearchDataSource(boxStore.embeddingBox()),
            dispatcherProvider = TestDispatcherProvider(Dispatchers.Unconfined),
            workScheduler = BookmarkWorkScheduler(workManager, settings),
            embeddingProvider = FakeEmbeddingProvider(),
            attachmentStore = AttachmentStore(context, TestDispatcherProvider(Dispatchers.Unconfined))
        )
    }

    @After
    fun tearDown() {
        // 見ている問い合わせを止め切ってから DB を閉じる
        runBlocking { observers.coroutineContext.job.cancelAndJoin() }
        tearDownTestWorkManager(workManager)
        db.close()
        boxStore.close()
    }

    /** 本文(取得したページの本文。数千字)とタグ 2 つの付いたブックマークを [count] 件入れる */
    private suspend fun seed(count: Int) {
        val body = "本文の文章です。".repeat(BODY_REPEAT)
        db.withTransaction {
            repeat(count) { i ->
                val id = db.bookmarkDao().insert(
                    BookmarkEntity(
                        type = BookmarkType.URL,
                        content = body,
                        contentUri = "https://example.com/seed/$i",
                        title = "既存の記事 $i",
                        summary = "要約 $i",
                        createdAt = i.toLong() + 1,
                        lastAccessedAt = i.toLong() + 1,
                        aiStatus = AiStatus.DONE,
                        contentFetchedAt = 1L
                    )
                )
                db.tagDao().addTagsToBookmark(id, listOf("タグ${i % TAG_KINDS}", "分野${i % 7}"), TagSource.USER)
            }
        }
    }

    /** 数え続けている [counters] が [QUIET_MILLIS] の間増えなくなるまで待つ */
    private fun awaitQuiet(vararg counters: AtomicInteger) {
        var last = counters.map { it.get() }
        while (true) {
            Thread.sleep(QUIET_MILLIS)
            val now = counters.map { it.get() }
            if (now == last) return
            last = now
        }
    }

    @Test
    fun 購読の新着30件を取り込むときの一覧の読み直し() = runBlocking {
        seed(BOOKMARKS)
        val listRuns = AtomicInteger()
        val allRuns = AtomicInteger()
        observers.launch {
            db.bookmarkDao().observeFiltered(
                archived = false, favoriteOnly = false, tagIds = emptyList(), tagCount = 0, matchAll = false,
                categories = emptyList(), categoryCount = 0, types = emptyList(), typeCount = 0,
                createdSince = 0, readFilter = 0, sortOrder = "NEWEST"
            ).collect { listRuns.incrementAndGet() }
        }
        observers.launch { db.bookmarkDao().getAllWithTags().collect { allRuns.incrementAndGet() } }
        awaitQuiet(listRuns, allRuns)
        val listBefore = listRuns.get()
        val allBefore = allRuns.get()

        val links = (1..NEW_ITEMS).map {
            ImportedLink(url = "https://blog.example.com/posts/$it", title = "新着 $it", tags = listOf("開発ブログ", "YouTube"))
        }
        val start = System.nanoTime()
        val result = repository.importLinks(links, folderAsTag = false)
        val importMillis = (System.nanoTime() - start) / 1_000_000
        awaitQuiet(listRuns, allRuns)
        val settleMillis = (System.nanoTime() - start) / 1_000_000 - QUIET_MILLIS

        val listReruns = listRuns.get() - listBefore
        val allReruns = allRuns.get() - allBefore
        println(
            "[benchmark] importLinks($NEW_ITEMS 件, 既存 $BOOKMARKS 件): 取り込み ${importMillis}ms, 落ち着くまで ${settleMillis}ms, " +
                "一覧の読み直し $listReruns 回, 全件の読み直し $allReruns 回"
        )
        assertEquals(NEW_ITEMS, result.added)
        // 取り込みは1回の書き込みにまとまるので、見ている問い合わせの読み直しも1〜2回で済む
        assertTrue("一覧の読み直し: $listReruns 回", listReruns <= MAX_RERUNS)
        assertTrue("全件の読み直し: $allReruns 回", allReruns <= MAX_RERUNS)
    }

    private companion object {
        const val BOOKMARKS = 5_000
        const val NEW_ITEMS = 30
        const val TAG_KINDS = 50
        const val BODY_REPEAT = 250
        const val QUIET_MILLIS = 500L
        const val MAX_RERUNS = 2
    }
}
