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
        tagIds: List<Long> = emptyList(),
        matchAll: Boolean = true,
        categories: List<String> = emptyList(),
        types: List<BookmarkType> = emptyList(),
        createdSince: Long = 0L,
        sortOrder: BookmarkSortOrder = BookmarkSortOrder.NEWEST
    ): List<Long> = dao.observeFiltered(
        archived, favoriteOnly, tagIds, tagIds.size, matchAll, categories, categories.size,
        types.map { it.name }, types.size, createdSince, sortOrder.name
    )
        .first()
        .map { it.bookmark.id }

    @Test
    fun filtersByTypeAndCreatedPeriod() = runTest {
        suspend fun insertTyped(id: Long, type: BookmarkType, createdAt: Long) {
            dao.insert(BookmarkEntity(id = id, type = type, title = "t$id", createdAt = createdAt, lastAccessedAt = createdAt))
        }
        insertTyped(1, BookmarkType.URL, createdAt = 100)
        insertTyped(2, BookmarkType.PDF, createdAt = 200)
        insertTyped(3, BookmarkType.IMAGE, createdAt = 300)
        insertTyped(4, BookmarkType.URL, createdAt = 400)

        // 種類: どれかであればよい
        assertEquals(listOf(4L, 1L), ids(types = listOf(BookmarkType.URL)))
        assertEquals(listOf(3L, 2L), ids(types = listOf(BookmarkType.PDF, BookmarkType.IMAGE)))
        // 期間: その時刻以後(ちょうどの時刻も含む)。0 なら絞らない
        assertEquals(listOf(4L, 3L, 2L), ids(createdSince = 200))
        assertEquals(listOf(4L, 3L, 2L, 1L), ids(createdSince = 0))
        // 組み合わせは AND
        assertEquals(listOf(4L), ids(types = listOf(BookmarkType.URL), createdSince = 200))
    }

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
        assertEquals(listOf(1L), ids(archived = false, tagIds = listOf(tagId)))
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

        assertEquals(listOf(3L, 2L, 1L), ids(tagIds = listOf(parent)))
        assertEquals(listOf(3L, 2L), ids(tagIds = listOf(child)))
        assertEquals(listOf(3L), ids(tagIds = listOf(grandchild)))
        assertEquals(listOf(4L), ids(tagIds = listOf(other)))
        // 階層を変えると、絞り込みの結果も変わる(観測しているクエリが更新される)
        tagDao.setParent(child, null)
        assertEquals(listOf(1L), ids(tagIds = listOf(parent)))
    }


    private suspend fun categoryOf(id: Long) = dao.getByIds(listOf(id)).single().let { it.category to it.categoryEdited }

    @Test
    fun AIのカテゴリは自分で選んだカテゴリを置き換えない() = runTest {
        insert(1, title = "a", createdAt = 1)

        dao.updateCategory(1, "技術")
        assertEquals("技術" to false, categoryOf(1))

        dao.setCategoryByUser(1, "旅行")
        dao.updateCategory(1, "ニュース")
        assertEquals("旅行" to true, categoryOf(1))

        // AI に任せる状態に戻すと、カテゴリは空になり、次の AI の答えが入る
        dao.clearCategory(1)
        assertEquals(null to false, categoryOf(1))
        dao.updateCategory(1, "ニュース")
        assertEquals("ニュース" to false, categoryOf(1))
    }


    /** タグの階層(プログラミング ← Android ← Compose、別に 読書)と、ブックマーク 1〜5 にタグ・カテゴリを付けた状態を作る。 */
    private suspend fun setUpTagsAndCategories(): Map<String, Long> {
        val tagDao = db.tagDao()
        val programming = tagDao.insertTag(TagEntity(name = "プログラミング"))
        val android = tagDao.insertTag(TagEntity(name = "Android"))
        val compose = tagDao.insertTag(TagEntity(name = "Compose"))
        val reading = tagDao.insertTag(TagEntity(name = "読書"))
        tagDao.setParent(android, programming)
        tagDao.setParent(compose, android)
        // 1: Compose(技術)  2: Android・読書(技術)  3: プログラミング(Tech)  4: 読書(旅行)  5: タグなし(技術)
        listOf(
            Triple(1L, "技術", listOf(compose)),
            Triple(2L, "技術", listOf(android, reading)),
            Triple(3L, "Tech", listOf(programming)),
            Triple(4L, "旅行", listOf(reading)),
            Triple(5L, "技術", emptyList())
        ).forEach { (id, category, tagIds) ->
            insert(id, title = "b$id", createdAt = id)
            dao.updateCategory(id, category)
            tagIds.forEach { tagDao.insertCrossRef(BookmarkTagCrossRef(id, it)) }
        }
        return mapOf("programming" to programming, "android" to android, "compose" to compose, "reading" to reading)
    }

    @Test
    fun タグを複数選ぶと_すべて含むは両方を満たすもの_どれかを含むはどちらかを満たすもの_どちらも階層の下のタグを含む() = runTest {
        val tag = setUpTagsAndCategories()

        // Android(Compose も含む) と 読書: 両方を持つのは 2 だけ
        assertEquals(listOf(2L), ids(tagIds = listOf(tag.getValue("android"), tag.getValue("reading")), matchAll = true))
        // どちらか: Android の下(1・2)と 読書(2・4)
        assertEquals(listOf(4L, 2L, 1L), ids(tagIds = listOf(tag.getValue("android"), tag.getValue("reading")), matchAll = false))
        // プログラミングを選ぶと、Android・Compose の付いたものも含めて 1・2・3
        assertEquals(listOf(3L, 2L, 1L), ids(tagIds = listOf(tag.getValue("programming"))))
        // 親と子を一緒に選んでも、AND は両方満たす(子を満たせば親も満たす)ので子と同じ
        assertEquals(
            listOf(2L, 1L),
            ids(tagIds = listOf(tag.getValue("programming"), tag.getValue("android")), matchAll = true)
        )
        // 1 つだけなら AND と OR は同じ
        assertEquals(ids(tagIds = listOf(tag.getValue("reading")), matchAll = true), ids(tagIds = listOf(tag.getValue("reading")), matchAll = false))
    }

    @Test
    fun カテゴリは選んだもののどれか_大文字小文字を区別せず_タグとの条件はANDで組み合わさる() = runTest {
        val tag = setUpTagsAndCategories()

        assertEquals(listOf(5L, 3L, 2L, 1L), ids(categories = listOf("技術", "tech")))
        assertEquals(listOf(4L), ids(categories = listOf("旅行")))
        // タグ(どれか: 読書・プログラミング。Compose の 1 も含む)と、カテゴリ(技術)の両方を満たす: 1 と 2(3 は Tech、4 は旅行)
        assertEquals(
            listOf(2L, 1L),
            ids(tagIds = listOf(tag.getValue("reading"), tag.getValue("programming")), matchAll = false, categories = listOf("技術"))
        )
        // 当てはまるものが無い組み合わせは空
        assertEquals(emptyList<Long>(), ids(tagIds = listOf(tag.getValue("reading")), categories = listOf("Tech")))
    }

    @Test
    fun 絞り込みは通常_お気に入り_アーカイブと組み合わさる() = runTest {
        val tag = setUpTagsAndCategories()
        dao.updateArchived(2, true)
        dao.updateFavorite(1, true)

        assertEquals(listOf(1L), ids(tagIds = listOf(tag.getValue("android")), categories = listOf("技術")))
        assertEquals(listOf(2L), ids(archived = true, tagIds = listOf(tag.getValue("android"))))
        assertEquals(listOf(1L), ids(favoriteOnly = true, tagIds = listOf(tag.getValue("programming"))))
    }

    @Test
    fun 削除されたタグが選択に残っていても無視する() = runTest {
        val tag = setUpTagsAndCategories()
        val missing = 9_999L

        // 存在しないタグは条件に数えない(AND でも、残りのタグだけで絞る)
        assertEquals(listOf(4L, 2L), ids(tagIds = listOf(tag.getValue("reading"), missing), matchAll = true))
        // 選んだタグがすべて存在しないときは、タグでは絞らない
        assertEquals(ids(), ids(tagIds = listOf(missing), matchAll = true))
        assertEquals(ids(), ids(tagIds = listOf(missing), matchAll = false))
    }

    @Test
    fun 階層を変えると絞り込みの結果も変わる() = runTest {
        val tag = setUpTagsAndCategories()
        assertEquals(listOf(3L, 2L, 1L), ids(tagIds = listOf(tag.getValue("programming")), matchAll = false))

        db.tagDao().setParent(tag.getValue("android"), null)

        assertEquals(listOf(3L), ids(tagIds = listOf(tag.getValue("programming")), matchAll = false))
    }
}
