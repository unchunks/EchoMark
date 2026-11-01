package com.unchunks.echomark.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.attachment.AttachmentStore
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicatePair
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicateReason
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicateSensitivity
import com.unchunks.echomark.domain.bookmark.model.Bookmark
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.sin

/** 重複の候補・関連のまとまりの検出と、統合(元に戻す)を、実際の Room と ObjectBox で確かめる。 */
@RunWith(AndroidJUnit4::class)
class DuplicateRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var boxStore: BoxStore
    private lateinit var vectorSearch: VectorSearchDataSource
    private lateinit var workManager: WorkManager
    private lateinit var bookmarkRepository: BookmarkRepositoryImpl
    private lateinit var repository: DuplicateRepositoryImpl

    private val embedding = FakeEmbeddingProvider()
    private val settings = FakeAppSettingsRepository()
    private val version get() = embedding.modelVersion

    /** 2 次元の単位ベクトル。2 つの距離(COSINE)は 1 - cos(角度の差) */
    private fun angled(theta: Double) = FloatArray(768) {
        when (it) {
            0 -> cos(theta).toFloat()
            1 -> sin(theta).toFloat()
            else -> 0f
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        boxStore = inMemoryBoxStore()
        vectorSearch = VectorSearchDataSource(boxStore.embeddingBox())
        workManager = initTestWorkManager(context)
        val dispatcherProvider = TestDispatcherProvider(Dispatchers.Unconfined)
        bookmarkRepository = BookmarkRepositoryImpl(
            bookmarkDao = db.bookmarkDao(),
            tagDao = db.tagDao(),
            categoryDao = db.categoryDao(),
            vectorSearch = vectorSearch,
            dispatcherProvider = dispatcherProvider,
            workScheduler = BookmarkWorkScheduler(workManager, settings),
            embeddingProvider = embedding,
            attachmentStore = AttachmentStore(context, dispatcherProvider)
        )
        repository = DuplicateRepositoryImpl(
            bookmarkDao = db.bookmarkDao(),
            tagDao = db.tagDao(),
            vectorSearch = vectorSearch,
            embeddingProvider = embedding,
            appSettings = settings,
            bookmarkRepository = bookmarkRepository,
            dispatcherProvider = dispatcherProvider
        )
    }

    @After
    fun tearDown() {
        tearDownTestWorkManager(workManager)
        db.close()
        boxStore.close()
    }

    private suspend fun save(url: String, theta: Double? = null): Long {
        val id = bookmarkRepository.saveUrlBookmark(url, url, null).id
        if (theta != null) vectorSearch.upsert(id, angled(theta), version)
        return id
    }

    private suspend fun load(id: Long): Bookmark? = bookmarkRepository.observeBookmark(id).first()

    @Test
    fun 末尾の斜線や追跡用のクエリだけが違うURLは同じページとして見つかる() = runBlocking {
        val a = save("https://example.com/a")
        val b = save("https://www.example.com/a/?utm_source=x")
        save("https://example.com/other")

        val scan = repository.scan(DuplicateSensitivity.STANDARD)

        val match = scan.duplicates.single()
        assertEquals(DuplicatePair.of(a, b), match.pair)
        assertEquals(DuplicateReason.SAME_URL, match.reason)
    }

    @Test
    fun 埋め込みが近い組は内容が近い重複として見つかり_遠い組は出ない() = runBlocking {
        val a = save("https://example.com/1", theta = 0.0)
        val b = save("https://mirror.example/1", theta = 0.1) // 距離 1 - cos(0.1) ≈ 0.005
        save("https://example.com/far", theta = 2.0)

        val scan = repository.scan(DuplicateSensitivity.STANDARD)

        val match = scan.duplicates.single()
        assertEquals(DuplicatePair.of(a, b), match.pair)
        assertEquals(DuplicateReason.SIMILAR_CONTENT, match.reason)
        assertTrue(match.distance!! < 0.01)
    }

    @Test
    fun 厳しさのしきい値の外の組は出ず_ゆるめにすると出る() = runBlocking {
        // 距離 1 - cos(0.5) ≈ 0.122: 標準(0.10)では出ず、ゆるめ(0.16)では出る
        save("https://example.com/1", theta = 0.0)
        save("https://example.com/2", theta = 0.5)

        assertTrue(repository.scan(DuplicateSensitivity.STANDARD).duplicates.isEmpty())
        assertEquals(1, repository.scan(DuplicateSensitivity.LOOSE).duplicates.size)
    }

    @Test
    fun 両方残すと答えた組は次から出ない() = runBlocking {
        val a = save("https://example.com/1", theta = 0.0)
        val b = save("https://example.com/2", theta = 0.1)
        assertEquals(1, repository.scan(DuplicateSensitivity.STANDARD).duplicates.size)

        repository.keepBoth(DuplicatePair.of(b, a))

        assertTrue(repository.scan(DuplicateSensitivity.STANDARD).duplicates.isEmpty())
        assertEquals(setOf(DuplicatePair.of(a, b)), settings.dismissedDuplicatesFlow.value)
    }

    @Test
    fun 削除済みのブックマークの組は出さず_両方残すの記録からも消える() = runBlocking {
        val a = save("https://example.com/1", theta = 0.0)
        val b = save("https://example.com/2", theta = 0.1)
        repository.keepBoth(DuplicatePair.of(a, b))
        // 埋め込みだけが残った(削除済みの)ブックマーク
        vectorSearch.upsert(9_999L, angled(0.05), version)
        settings.dismissedDuplicatesFlow.value = settings.dismissedDuplicatesFlow.value + DuplicatePair.of(a, 9_999L)

        val scan = repository.scan(DuplicateSensitivity.STANDARD)

        assertTrue(scan.duplicates.isEmpty())
        assertEquals(setOf(DuplicatePair.of(a, b)), settings.dismissedDuplicatesFlow.value)
    }

    @Test
    fun 古い版の埋め込みは比べず_索引が全部でないことを知らせる() = runBlocking {
        val a = save("https://example.com/1")
        val b = save("https://example.com/2")
        vectorSearch.upsert(a, angled(0.0), "old-model")
        vectorSearch.upsert(b, angled(0.0), "old-model")
        save("https://example.com/3", theta = 0.0)

        val scan = repository.scan(DuplicateSensitivity.STANDARD)

        assertTrue(scan.duplicates.isEmpty())
        assertEquals(1, scan.embeddedCount)
        assertEquals(3, scan.totalCount)
        assertFalse(scan.isComplete)
    }

    @Test
    fun 近い内容のブックマークは関連のまとまりになる() = runBlocking {
        // 隣どうしの距離 1 - cos(0.6) ≈ 0.175(重複ではなく関連)。両端の距離は大きい
        val ids = listOf(0.0, 0.6, 1.2).map { save("https://example.com/t$it", theta = it) }
        save("https://example.com/unrelated", theta = 3.0)
        bookmarkRepository.addTag(ids[0], "Kotlin")
        bookmarkRepository.addTag(ids[1], "Kotlin")

        val scan = repository.scan(DuplicateSensitivity.STANDARD)

        assertTrue(scan.duplicates.isEmpty())
        val group = scan.groups.single()
        assertEquals(ids, group.bookmarks.map { it.id })
        assertEquals("Kotlin", group.label)
    }

    @Test
    fun 統合すると消す方の情報が残す方に足され_消す方は削除される() = runBlocking {
        val keep = save("https://example.com/keep")
        val remove = save("https://example.com/remove")
        bookmarkRepository.addTag(keep, "共通")
        bookmarkRepository.addTag(remove, "共通")
        bookmarkRepository.addTag(remove, "消す方だけ")
        bookmarkRepository.saveAiTags(remove, listOf("AIのタグ"))
        bookmarkRepository.setFavorite(remove, true)
        val beforeKeep = load(keep)!!
        val beforeRemove = load(remove)!!

        val undo = repository.merge(keep, remove)

        assertNotNull(undo)
        assertNull(load(remove))
        val merged = load(keep)!!
        assertEquals(setOf("共通", "消す方だけ", "AIのタグ"), merged.tags.toSet())
        // 付けた人も引き継ぐ
        assertEquals(setOf("AIのタグ"), merged.aiTags)
        assertTrue(merged.isFavorite)
        assertEquals(minOf(beforeKeep.createdAt, beforeRemove.createdAt), merged.createdAt)
    }

    @Test
    fun 消す方のオフラインコピーは残す方が持つようになる() = runBlocking {
        val keep = save("https://example.com/keep")
        val remove = save("https://example.com/remove")
        db.bookmarkDao().updateSnapshot(remove, "snapshots/r.mhtml", "snapshots/r.pdf", 5L)

        repository.merge(keep, remove)

        val merged = load(keep)!!
        assertEquals("snapshots/r.mhtml", merged.snapshotPath)
        assertEquals("snapshots/r.pdf", merged.snapshotPdfPath)
        assertEquals(5L, merged.snapshotAt)
    }

    @Test
    fun 元に戻すと消した方が戻り_残した方も統合前になる() = runBlocking {
        val keep = save("https://example.com/keep")
        val remove = save("https://example.com/remove")
        bookmarkRepository.addTag(keep, "共通")
        bookmarkRepository.addTag(remove, "消す方だけ")
        bookmarkRepository.setFavorite(remove, true)
        db.bookmarkDao().updateSnapshot(remove, "snapshots/r.mhtml", null, 5L)
        val beforeKeep = load(keep)!!

        val undo = repository.merge(keep, remove)!!
        repository.undoMerge(undo)

        val restoredRemove = load(remove)!!
        assertEquals(listOf("消す方だけ"), restoredRemove.tags)
        assertTrue(restoredRemove.isFavorite)
        assertEquals("snapshots/r.mhtml", restoredRemove.snapshotPath)
        val restoredKeep = load(keep)!!
        assertEquals(listOf("共通"), restoredKeep.tags)
        assertFalse(restoredKeep.isFavorite)
        assertNull(restoredKeep.snapshotPath)
        assertEquals(beforeKeep.createdAt, restoredKeep.createdAt)
    }

    @Test
    fun どちらかが無ければ統合しない() = runBlocking {
        val keep = save("https://example.com/keep")

        assertNull(repository.merge(keep, 12_345L))
        assertNull(repository.merge(12_345L, keep))
        assertNull(repository.merge(keep, keep))
        assertNotNull(load(keep))
    }
}
