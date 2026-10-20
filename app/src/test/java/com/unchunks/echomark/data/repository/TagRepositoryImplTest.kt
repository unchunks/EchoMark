package com.unchunks.echomark.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.data.local.entity.TagEntity
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.repository.TagRenameResult
import com.unchunks.echomark.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** タグ管理(件数つき一覧・名前変更・統合・削除)を実 DB(インメモリ)で確かめる。 */
@RunWith(AndroidJUnit4::class)
class TagRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TagRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = TagRepositoryImpl(db.tagDao(), TestDispatcherProvider(Dispatchers.Unconfined))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun bookmark(id: Long) {
        db.bookmarkDao().insert(
            BookmarkEntity(id = id, type = BookmarkType.TEXT, title = "b$id", createdAt = id, lastAccessedAt = id)
        )
    }

    private suspend fun tag(name: String, vararg bookmarkIds: Long): Long {
        val id = db.tagDao().insertTag(TagEntity(name = name))
        bookmarkIds.forEach { db.tagDao().insertCrossRef(BookmarkTagCrossRef(it, id)) }
        return id
    }

    private suspend fun tagsOf(bookmarkId: Long): List<String> =
        db.bookmarkDao().getByIdsWithTags(listOf(bookmarkId)).single().tags.map { it.name }.sorted()

    @Test
    fun 件数つきで名前順に並び件数0のタグも含む() = runTest {
        bookmark(1); bookmark(2)
        tag("kotlin", 1, 2)
        tag("Android", 1)
        tag("empty")

        val result = repository.observeTagsWithCount().first().map { it.name to it.bookmarkCount }

        assertEquals(listOf("Android" to 1, "empty" to 0, "kotlin" to 2), result)
    }

    @Test
    fun 名前を変更できる() = runTest {
        bookmark(1)
        val id = tag("kotln", 1)

        val result = repository.renameTag(id, "  kotlin ")

        assertEquals(TagRenameResult.Renamed(id), result)
        assertEquals(listOf("kotlin"), tagsOf(1))
    }

    @Test
    fun 既存タグと同名に変えると統合され重複は1つになる() = runTest {
        bookmark(1); bookmark(2); bookmark(3)
        val from = tag("Kotlin言語", 1, 2)
        val into = tag("kotlin", 2, 3)

        val result = repository.renameTag(from, "kotlin")

        assertEquals(TagRenameResult.Merged(into), result)
        assertNull(db.tagDao().getTagById(from))
        assertEquals(listOf("kotlin"), tagsOf(1))
        assertEquals(listOf("kotlin"), tagsOf(2))
        assertEquals(listOf("kotlin" to 3), repository.observeTagsWithCount().first().map { it.name to it.bookmarkCount })
    }

    @Test
    fun AIのタグは名前を変更すると自分のタグになる() = runTest {
        bookmark(1)
        db.tagDao().replaceAiTags(1, listOf("andorid"))
        val id = db.tagDao().getTagByName("andorid")!!.id
        assertEquals(listOf(false), repository.observeTagsWithCount().first().map { it.isUserTag })

        repository.renameTag(id, "Android")

        assertEquals(listOf("Android" to true), repository.observeTagsWithCount().first().map { it.name to it.isUserTag })
    }

    @Test
    fun 同じ名前や空の名前では何もしない() = runTest {
        val id = tag("kotlin")

        assertEquals(TagRenameResult.Unchanged, repository.renameTag(id, "kotlin"))
        assertEquals(TagRenameResult.Invalid, repository.renameTag(id, "   "))
        assertEquals(TagRenameResult.Invalid, repository.renameTag(999, "x"))
    }

    @Test
    fun 削除するとタグだけ消えてブックマークは残る() = runTest {
        bookmark(1)
        val id = tag("kotlin", 1)
        tag("android", 1)

        repository.deleteTag(id)

        assertEquals(listOf("android"), tagsOf(1))
        assertEquals(listOf("android"), repository.observeTagsWithCount().first().map { it.name })
        assertEquals(1, db.bookmarkDao().observeCount().first())
    }
}
