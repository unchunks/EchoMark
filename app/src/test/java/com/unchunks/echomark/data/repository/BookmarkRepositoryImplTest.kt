package com.unchunks.echomark.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.testing.FakeEmbeddingProvider
import com.unchunks.echomark.testing.TestDispatcherProvider
import com.unchunks.echomark.testing.embeddingBox
import com.unchunks.echomark.testing.inMemoryBoxStore
import com.unchunks.echomark.testing.initTestWorkManager
import com.unchunks.echomark.testing.statesOf
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
            workScheduler = BookmarkWorkScheduler(workManager),
            embeddingProvider = FakeEmbeddingProvider()
        )
    }

    @After
    fun tearDown() {
        db.close()
        boxStore.close()
    }

    private fun textBookmark(title: String = "メモ") = Bookmark(
        type = BookmarkType.TEXT, content = "本文", title = title, createdAt = 1L, lastAccessedAt = 1L
    )

    private fun unfinished(name: String) = workManager.statesOf(name).filterNot { it.isFinished }

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
}
