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
import com.unchunks.echomark.domain.bookmark.model.BookmarkQuery
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.readingMinutes
import com.unchunks.echomark.domain.model.TagSource
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeEmbeddingProvider
import com.unchunks.echomark.testing.embeddingBox
import com.unchunks.echomark.testing.inMemoryBoxStore
import com.unchunks.echomark.testing.initTestWorkManager
import com.unchunks.echomark.testing.tearDownTestWorkManager
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

/**
 * 一覧の問い合わせの重さを測る。5,000 件(本文 約 2,000 字・タグ 2 つ)のある DB で、これまでの読み方
 * ([BookmarkRepositoryImpl.observeBookmarks]。本文ごと読む)と、一覧用の読み方([BookmarkRepositoryImpl.observeBookmarkList]。
 * 本文を読まず、読む時間の目安は本文が変わったものだけ求め直す)を比べる。一覧はブックマークが変わるたびにこれを読み直す。
 */
@RunWith(AndroidJUnit4::class)
class ListQueryPerformanceTest {

    private lateinit var db: AppDatabase
    private lateinit var boxStore: BoxStore
    private lateinit var workManager: WorkManager
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        boxStore = inMemoryBoxStore()
        workManager = initTestWorkManager(context)
    }

    @After
    fun tearDown() {
        tearDownTestWorkManager(workManager)
        db.close()
        boxStore.close()
    }

    private fun newRepository() = BookmarkRepositoryImpl(
        bookmarkDao = db.bookmarkDao(),
        tagDao = db.tagDao(),
        noteDao = db.bookmarkNoteDao(),
        collectionDao = db.collectionDao(),
        reviewCardDao = db.reviewCardDao(),
        categoryDao = db.categoryDao(),
        vectorSearch = VectorSearchDataSource(boxStore.embeddingBox()),
        dispatcherProvider = TestDispatcherProvider(Dispatchers.Unconfined),
        workScheduler = BookmarkWorkScheduler(workManager, FakeAppSettingsRepository()),
        embeddingProvider = FakeEmbeddingProvider(),
        attachmentStore = AttachmentStore(context, TestDispatcherProvider(Dispatchers.Unconfined))
    )

    /** 本文は日本語・英語・短いもの・無いものを混ぜる(読む時間の目安が出るもの・出ないものの両方) */
    private suspend fun seed(count: Int) {
        val japanese = "本文の文章です。".repeat(BODY_REPEAT)
        val english = "This is an English sentence. ".repeat(BODY_REPEAT / 2)
        db.withTransaction {
            repeat(count) { i ->
                val content = when (i % 4) {
                    0 -> japanese
                    1 -> english
                    2 -> "短いメモ"
                    else -> null
                }
                val id = db.bookmarkDao().insert(
                    BookmarkEntity(
                        type = BookmarkType.URL,
                        content = content,
                        contentUri = "https://example.com/posts/$i",
                        title = "記事 $i",
                        summary = "要約 $i",
                        createdAt = i.toLong() + 1,
                        lastAccessedAt = i.toLong() + 1,
                        aiStatus = AiStatus.DONE,
                        contentFetchedAt = 1L
                    )
                )
                db.tagDao().addTagsToBookmark(id, listOf("タグ${i % 50}", "分野${i % 7}"), TagSource.USER)
            }
        }
    }

    private fun medianMillis(block: () -> Unit): Double {
        repeat(WARMUP) { block() }
        val times = (1..RUNS).map {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / 1_000_000.0
        }
        return times.sorted()[RUNS / 2]
    }

    @Test
    fun 一覧用の読み方は本文を読まずに同じものを同じ順に返し_読み直しが軽い() = runBlocking {
        seed(BOOKMARKS)
        val repository = newRepository()
        val query = BookmarkQuery()

        val full = repository.observeBookmarks(query, BookmarkSortOrder.NEWEST).first()
        val coldStart = System.nanoTime()
        val light = repository.observeBookmarkList(query, BookmarkSortOrder.NEWEST).first()
        val coldMillis = (System.nanoTime() - coldStart) / 1_000_000.0

        // 本文と、本文から求める読む時間の目安のほかは同じ。目安も同じ値になる
        assertEquals(full.map { it.id }, light.map { it.id })
        assertEquals(full.map { it.copy(content = null) }, light.map { it.copy(estimatedReadingMinutes = null) })
        assertEquals(full.map { it.readingMinutes() }, light.map { it.readingMinutes() })
        assertTrue(light.all { it.content == null })
        assertTrue(light.any { it.readingMinutes() != null })

        val fullMillis = medianMillis { runBlocking { repository.observeBookmarks(query, BookmarkSortOrder.NEWEST).first() } }
        val warmMillis = medianMillis { runBlocking { repository.observeBookmarkList(query, BookmarkSortOrder.NEWEST).first() } }

        // 1件の本文が変わったら、その目安だけ求め直す
        val changed = full.first { it.content == null }
        db.bookmarkDao().updateTitleAndContent(changed.id, changed.title, "長い文章になりました。".repeat(BODY_REPEAT))
        val afterEdit = repository.observeBookmarkList(query, BookmarkSortOrder.NEWEST).first()
        assertTrue((afterEdit.first { it.id == changed.id }.readingMinutes() ?: 0) > 0)
        assertNull(changed.readingMinutes())

        println(
            "[benchmark] 一覧の読み直し1回(既存 $BOOKMARKS 件): 本文ごと %.1fms → 本文なし %.1fms(初回は目安を求めるため %.1fms)".format(
                fullMillis, warmMillis, coldMillis
            )
        )
        assertTrue("本文なし %.1fms・本文ごと %.1fms".format(warmMillis, fullMillis), warmMillis < fullMillis)
    }

    private companion object {
        const val BOOKMARKS = 5_000
        const val BODY_REPEAT = 250
        const val WARMUP = 2
        const val RUNS = 5
    }
}
