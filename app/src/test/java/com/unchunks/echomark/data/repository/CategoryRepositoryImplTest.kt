package com.unchunks.echomark.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.CategoryKind
import com.unchunks.echomark.domain.model.CategoryNames
import com.unchunks.echomark.domain.repository.CategoryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** カテゴリの管理(作る・直す・消す・一覧と件数)を実 DB(インメモリ)で確かめる。 */
@RunWith(AndroidJUnit4::class)
class CategoryRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: CategoryRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = CategoryRepositoryImpl(db.categoryDao(), TestDispatcherProvider(Dispatchers.Unconfined))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun bookmark(id: Long, category: String?, edited: Boolean = false) {
        db.bookmarkDao().insert(
            BookmarkEntity(
                id = id, type = BookmarkType.TEXT, title = "b$id", createdAt = id, lastAccessedAt = id,
                category = category, categoryEdited = edited
            )
        )
    }

    private suspend fun entries() = repository.observeCategories().first()

    @Test
    fun カテゴリを作ると説明つきで一覧に出る() = runTest {
        val result = repository.addCategory("  旅行  の計画 ", "  行き先や宿の情報  ")

        assertEquals(CategoryResult.Saved("旅行 の計画"), result)
        val mine = entries().single { it.kind == CategoryKind.USER }
        assertEquals("旅行 の計画", mine.name)
        assertEquals("行き先や宿の情報", mine.description)
        assertEquals(0, mine.bookmarkCount)
    }

    @Test
    fun 名前が空や長すぎる_説明が長すぎるカテゴリは作れない() = runTest {
        assertTrue(repository.addCategory("   ", "") is CategoryResult.Invalid)
        assertTrue(repository.addCategory("あ".repeat(CategoryNames.MAX_NAME_CHARS + 1), "") is CategoryResult.Invalid)
        assertTrue(repository.addCategory("名前", "あ".repeat(CategoryNames.MAX_DESCRIPTION_CHARS + 1)) is CategoryResult.Invalid)
        assertEquals(CategoryNames.MAX_NAME_CHARS, "あ".repeat(CategoryNames.MAX_NAME_CHARS).length)
        assertEquals(
            CategoryResult.Saved("あ".repeat(CategoryNames.MAX_NAME_CHARS)),
            repository.addCategory("あ".repeat(CategoryNames.MAX_NAME_CHARS), "")
        )
    }

    @Test
    fun 標準のカテゴリや自分のカテゴリと同じ名前は表記ゆれも含めて作れない() = runTest {
        repository.addCategory("Tech", "")

        assertEquals(CategoryResult.Duplicate("技術"), repository.addCategory("技術", ""))
        assertEquals(CategoryResult.Duplicate("ToDo"), repository.addCategory("ｔｏｄｏ", ""))
        assertEquals(CategoryResult.Duplicate("Tech"), repository.addCategory(" ＴＥＣＨ", ""))
        assertEquals(1, entries().count { it.kind == CategoryKind.USER })
    }

    @Test
    fun 名前を変えるとそのカテゴリのブックマークも新しい名前になり_ほかは変わらない() = runTest {
        repository.addCategory("旅行", "")
        val id = entries().single { it.kind == CategoryKind.USER }.id!!
        bookmark(1, "旅行", edited = true)
        bookmark(2, "技術")

        val result = repository.updateCategory(id, "出張", "仕事の移動")

        assertEquals(CategoryResult.Saved("出張"), result)
        val bookmarks = db.bookmarkDao().getByIds(listOf(1, 2)).associateBy { it.id }
        assertEquals("出張", bookmarks.getValue(1).category)
        assertTrue(bookmarks.getValue(1).categoryEdited)
        assertEquals("技術", bookmarks.getValue(2).category)
        assertEquals("仕事の移動", entries().single { it.kind == CategoryKind.USER }.description)
    }

    @Test
    fun 自分自身の名前の大文字小文字だけを変えても重複にならず_他のカテゴリの名前にはできない() = runTest {
        repository.addCategory("tech", "")
        repository.addCategory("旅行", "")
        val ids = entries().filter { it.kind == CategoryKind.USER }.associate { it.name to it.id!! }

        assertEquals(CategoryResult.Saved("Tech"), repository.updateCategory(ids.getValue("tech"), "Tech", ""))
        assertEquals(CategoryResult.Duplicate("Tech"), repository.updateCategory(ids.getValue("旅行"), "ＴＥＣＨ", ""))
        assertTrue(repository.updateCategory(999, "x", "") is CategoryResult.Invalid)
    }

    @Test
    fun 削除するとそのカテゴリのブックマークは未分類に戻り_自分で選んだ印も外れる() = runTest {
        repository.addCategory("旅行", "")
        val id = entries().single { it.kind == CategoryKind.USER }.id!!
        bookmark(1, "旅行", edited = true)
        bookmark(2, "旅行")
        bookmark(3, "技術", edited = true)

        repository.deleteCategory(id)

        val bookmarks = db.bookmarkDao().getByIds(listOf(1, 2, 3)).associateBy { it.id }
        assertNull(bookmarks.getValue(1).category)
        assertFalse(bookmarks.getValue(1).categoryEdited)
        assertNull(bookmarks.getValue(2).category)
        assertEquals("技術", bookmarks.getValue(3).category)
        assertTrue(bookmarks.getValue(3).categoryEdited)
        assertTrue(entries().none { it.kind == CategoryKind.USER })
    }

    @Test
    fun 一覧は自分のカテゴリ_標準_AIが付けたその他の順で件数つき_表記ゆれは1つに数える() = runTest {
        repository.addCategory("旅行", "出かける計画")
        repository.addCategory("読書メモ", "")
        bookmark(1, "旅行")
        bookmark(2, "技術")
        bookmark(3, "技術")
        // AI が自由に付けた分類。大文字小文字の違いは1つにして、多い方の表記で出す
        bookmark(4, "Gadget")
        bookmark(5, "gadget")
        bookmark(6, "ＧＡＤＧＥＴ")
        bookmark(7, "料理教室")
        bookmark(8, null)

        val list = entries()

        assertEquals(listOf("旅行", "読書メモ"), list.filter { it.kind == CategoryKind.USER }.map { it.name })
        assertEquals(1, list.single { it.name == "旅行" }.bookmarkCount)
        assertEquals(2, list.single { it.name == "技術" }.bookmarkCount)
        assertEquals(0, list.single { it.name == "ニュース" }.bookmarkCount)
        assertEquals(CategoryNames.BUILT_IN.size, list.count { it.kind == CategoryKind.BUILT_IN })
        val others = list.filter { it.kind == CategoryKind.AI_OTHER }
        assertEquals(listOf(3, 1), others.map { it.bookmarkCount })
        assertTrue(others.first().name.equals("gadget", ignoreCase = true) || others.first().name == "ＧＡＤＧＥＴ")
        assertEquals("料理教室", others.last().name)
        assertEquals(CategoryNames.AI_OTHER_DESCRIPTION, others.last().description)
        // 先頭から: 自分のカテゴリ → 標準 → その他
        assertEquals(
            listOf(CategoryKind.USER, CategoryKind.USER) + List(CategoryNames.BUILT_IN.size) { CategoryKind.BUILT_IN } +
                listOf(CategoryKind.AI_OTHER, CategoryKind.AI_OTHER),
            list.map { it.kind }
        )
    }
}
