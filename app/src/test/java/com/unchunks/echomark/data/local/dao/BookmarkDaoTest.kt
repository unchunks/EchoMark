package com.unchunks.echomark.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.data.local.entity.TagEntity
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 一覧用の絞り込み・並べ替えクエリ(observeFiltered)と v7 で追加した更新クエリのテスト。 */
@RunWith(AndroidJUnit4::class)
class BookmarkDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BookmarkDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        dao = db.bookmarkDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insert(
        id: Long,
        title: String,
        createdAt: Long,
        lastAccessedAt: Long = createdAt
    ) {
        dao.insert(
            BookmarkEntity(
                id = id,
                type = BookmarkType.TEXT,
                title = title,
                createdAt = createdAt,
                lastAccessedAt = lastAccessedAt
            )
        )
    }

    private suspend fun ids(
        archived: Boolean? = false,
        favoriteOnly: Boolean = false,
        tagId: Long? = null,
        sortOrder: BookmarkSortOrder = BookmarkSortOrder.NEWEST
    ): List<Long> = dao.observeFiltered(archived, favoriteOnly, tagId, sortOrder.name)
        .first()
        .map { it.bookmark.id }

    @Test
    fun sortOrders() = runTest {
        insert(1, title = "banana", createdAt = 100, lastAccessedAt = 500)
        insert(2, title = "Apple", createdAt = 300, lastAccessedAt = 300)
        insert(3, title = "cherry", createdAt = 200, lastAccessedAt = 900)

        assertEquals(listOf(2L, 3L, 1L), ids(sortOrder = BookmarkSortOrder.NEWEST))
        assertEquals(listOf(1L, 3L, 2L), ids(sortOrder = BookmarkSortOrder.OLDEST))
        assertEquals(listOf(3L, 1L, 2L), ids(sortOrder = BookmarkSortOrder.RECENTLY_OPENED))
        // 大文字小文字を区別しない
        assertEquals(listOf(2L, 1L, 3L), ids(sortOrder = BookmarkSortOrder.TITLE))
    }

    @Test
    fun filtersByArchiveFavoriteAndTag() = runTest {
        insert(1, title = "a", createdAt = 100)
        insert(2, title = "b", createdAt = 200)
        insert(3, title = "c", createdAt = 300)
        dao.updateArchived(2, true)
        dao.updateFavorite(3, true)
        dao.updateFavorite(2, true)
        val tagId = db.tagDao().insertTag(TagEntity(name = "kotlin"))
        db.tagDao().insertCrossRef(BookmarkTagCrossRef(1, tagId))

        // 通常の一覧はアーカイブを除く
        assertEquals(listOf(3L, 1L), ids(archived = false))
        assertEquals(listOf(2L), ids(archived = true))
        // お気に入り(アーカイブ済みは除く)
        assertEquals(listOf(3L), ids(archived = false, favoriteOnly = true))
        assertEquals(listOf(1L), ids(archived = false, tagId = tagId))
        // 絞り込みなし
        assertEquals(listOf(3L, 2L, 1L), ids(archived = null))
    }

    @Test
    fun updateLinkMetadata() = runTest {
        insert(1, title = "a", createdAt = 100)

        dao.updateLinkMetadata(1, imageUrl = "https://example.com/og.png", siteName = "Example")

        val entity = dao.getById(1)!!
        assertEquals("https://example.com/og.png", entity.imageUrl)
        assertEquals("Example", entity.siteName)
    }


    @Test
    fun 親のタグで絞ると階層でその下にあるタグのブックマークも含む() = runTest {
        insert(1, title = "親に直接", createdAt = 100)
        insert(2, title = "子", createdAt = 200)
        insert(3, title = "孫", createdAt = 300)
        insert(4, title = "別のタグ", createdAt = 400)
        val tagDao = db.tagDao()
        val parent = tagDao.insertTag(TagEntity(name = "プログラミング"))
        val child = tagDao.insertTag(TagEntity(name = "Android"))
        val grandchild = tagDao.insertTag(TagEntity(name = "Compose"))
        val other = tagDao.insertTag(TagEntity(name = "読書"))
        tagDao.setParent(child, parent)
        tagDao.setParent(grandchild, child)
        listOf(1L to parent, 2L to child, 3L to grandchild, 4L to other).forEach { (bookmarkId, tagId) ->
            tagDao.insertCrossRef(BookmarkTagCrossRef(bookmarkId, tagId))
        }

        assertEquals(listOf(3L, 2L, 1L), ids(tagId = parent))
        assertEquals(listOf(3L, 2L), ids(tagId = child))
        assertEquals(listOf(3L), ids(tagId = grandchild))
        assertEquals(listOf(4L), ids(tagId = other))
        // 検索の絞り込みも同じ
        assertEquals(listOf(3L, 2L), dao.searchByKeyword("", child).map { it.bookmark.id })
        // 階層を変えると、絞り込みの結果も変わる(観測しているクエリが更新される)
        tagDao.setParent(child, null)
        assertEquals(listOf(1L), ids(tagId = parent))
    }
}
