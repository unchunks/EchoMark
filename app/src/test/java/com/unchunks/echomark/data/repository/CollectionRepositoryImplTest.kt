package com.unchunks.echomark.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.mapper.toEntity
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.CollectionNames
import com.unchunks.echomark.domain.bookmark.model.CollectionResult
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

/** コレクションの保存・入れる・外す・削除と元に戻す(実際の Room)。 */
@RunWith(AndroidJUnit4::class)
class CollectionRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: CollectionRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = CollectionRepositoryImpl(db.collectionDao(), db.bookmarkDao(), TestDispatcherProvider(Dispatchers.Unconfined))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun saveBookmark(title: String): Long =
        db.bookmarkDao().insert(
            Bookmark(type = BookmarkType.TEXT, title = title, createdAt = 1, lastAccessedAt = 1).toEntity()
        )

    private suspend fun create(name: String): Long = (repository.create(name) as CollectionResult.Saved).id

    @Test
    fun 名前を付けて作ると前後の空白を除き_作った順に並ぶ() = runBlocking {
        create("  旅行の計画 ")
        create("読書リスト")

        val collections = repository.observeCollections().first()

        assertEquals(listOf("旅行の計画", "読書リスト"), collections.map { it.name })
        assertEquals(listOf(0, 0), collections.map { it.bookmarkCount })
    }

    @Test
    fun 空の名前と長すぎる名前は作れない() = runBlocking {
        assertEquals(CollectionResult.InvalidName, repository.create("   "))
        assertEquals(CollectionResult.InvalidName, repository.create("あ".repeat(CollectionNames.MAX_CHARS + 1)))
        assertTrue(repository.observeCollections().first().isEmpty())
    }

    @Test
    fun 大文字小文字と全角半角が違うだけの名前は同じ名前として作れない() = runBlocking {
        create("Reading")

        assertEquals(CollectionResult.NameTaken, repository.create("reading"))
        assertEquals(CollectionResult.NameTaken, repository.create("ＲＥＡＤＩＮＧ"))
        assertEquals(1, repository.observeCollections().first().size)
    }

    @Test
    fun 説明は前後の空白を除き_空なら説明なしで_長すぎれば切る() = runBlocking {
        val a = create("A")
        repository.update(a, "A", "  説明  ")
        assertEquals("説明", repository.observeCollection(a).first()!!.description)

        repository.update(a, "A", "   ")
        assertNull(repository.observeCollection(a).first()!!.description)

        repository.update(a, "A", "あ".repeat(CollectionNames.MAX_DESCRIPTION_CHARS + 50))
        assertEquals(CollectionNames.MAX_DESCRIPTION_CHARS, repository.observeCollection(a).first()!!.description!!.length)
    }

    @Test
    fun 名前の変更は自分自身の名前と同じなら通り_ほかと重なれば断る() = runBlocking {
        val a = create("A")
        create("B")

        assertEquals(CollectionResult.Saved(a), repository.update(a, "a", null))
        assertEquals("a", repository.observeCollection(a).first()!!.name)
        assertEquals(CollectionResult.NameTaken, repository.update(a, "b", null))
        assertEquals(CollectionResult.InvalidName, repository.update(a, " ", null))
        assertEquals(CollectionResult.NotFound, repository.update(9_999L, "C", null))
    }

    @Test
    fun ブックマークを入れると件数が増え_入れた順に並び_同じものを重ねて入れても1件() = runBlocking {
        val collection = create("集め物")
        val first = saveBookmark("最初")
        val second = saveBookmark("二番目")

        assertTrue(repository.add(collection, first))
        Thread.sleep(3)
        assertTrue(repository.add(collection, second))
        assertTrue(repository.add(collection, first))

        assertEquals(listOf("最初", "二番目"), repository.observeBookmarks(collection).first().map { it.title })
        assertEquals(2, repository.observeCollection(collection).first()!!.bookmarkCount)
        assertEquals(setOf(collection), repository.observeCollectionIdsOf(first).first())
    }

    @Test
    fun 無いコレクションや削除済みのブックマークには入れられない() = runBlocking {
        val collection = create("集め物")
        val bookmark = saveBookmark("記事")

        assertFalse(repository.add(9_999L, bookmark))
        assertFalse(repository.add(collection, 9_999L))
        assertTrue(repository.observeBookmarks(collection).first().isEmpty())
    }

    @Test
    fun ブックマークは複数のコレクションに入れられ_外しても本体は残る() = runBlocking {
        val a = create("A")
        val b = create("B")
        val bookmark = saveBookmark("記事")
        repository.add(a, bookmark)
        repository.add(b, bookmark)
        assertEquals(setOf(a, b), repository.observeCollectionIdsOf(bookmark).first())

        repository.remove(a, bookmark)

        assertEquals(setOf(b), repository.observeCollectionIdsOf(bookmark).first())
        assertNotNull(db.bookmarkDao().getById(bookmark))
    }

    @Test
    fun ブックマークを削除するとコレクションから消え_コレクションは残る() = runBlocking {
        val collection = create("集め物")
        val bookmark = saveBookmark("記事")
        repository.add(collection, bookmark)

        db.bookmarkDao().delete(db.bookmarkDao().getById(bookmark)!!)

        assertTrue(repository.observeBookmarks(collection).first().isEmpty())
        assertEquals(0, repository.observeCollection(collection).first()!!.bookmarkCount)
    }

    @Test
    fun コレクションを削除してもブックマークは残り_元に戻すと同じIDと中身で戻る() = runBlocking {
        val collection = create("集め物")
        repository.update(collection, "集め物", "説明")
        val first = saveBookmark("最初")
        val second = saveBookmark("二番目")
        repository.add(collection, first)
        Thread.sleep(3)
        repository.add(collection, second)
        val before = db.collectionDao().getItems(collection)

        val deleted = repository.delete(collection)!!

        assertNull(repository.observeCollection(collection).first())
        assertNotNull(db.bookmarkDao().getById(first))
        assertTrue(repository.restore(deleted))
        val restored = repository.observeCollection(collection).first()!!
        assertEquals("集め物", restored.name)
        assertEquals("説明", restored.description)
        assertEquals(before, db.collectionDao().getItems(collection))
    }

    @Test
    fun 削除したあとに同じ名前を作っていたら元に戻せない() = runBlocking {
        val collection = create("集め物")
        val deleted = repository.delete(collection)!!
        create("集め物")

        assertFalse(repository.restore(deleted))
        assertEquals(1, repository.observeCollections().first().size)
    }

    @Test
    fun 元に戻すとき_削除のあとに消えたブックマークは入れ直さない() = runBlocking {
        val collection = create("集め物")
        val kept = saveBookmark("残る")
        val gone = saveBookmark("消える")
        repository.add(collection, kept)
        repository.add(collection, gone)
        val deleted = repository.delete(collection)!!
        db.bookmarkDao().delete(db.bookmarkDao().getById(gone)!!)

        assertTrue(repository.restore(deleted))

        assertEquals(listOf("残る"), repository.observeBookmarks(collection).first().map { it.title })
    }

    @Test
    fun 無いコレクションの削除は何も返さない() = runBlocking {
        assertNull(repository.delete(9_999L))
    }
}
