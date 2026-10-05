package com.unchunks.echomark.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.TagSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** タグの付け外しで、誰が付けたか(ユーザー / AI)の区別と、AI のタグの自動削除が正しく働くこと。 */
@RunWith(AndroidJUnit4::class)
class TagDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: TagDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        dao = db.tagDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertBookmark(id: Long) {
        db.bookmarkDao().insert(
            BookmarkEntity(id = id, type = BookmarkType.TEXT, title = "b$id", createdAt = id, lastAccessedAt = id)
        )
    }

    /** ブックマークに付いているタグ名 → 付けた人 */
    private suspend fun linksOf(bookmarkId: Long): Map<String, TagSource> {
        val withTags = db.bookmarkDao().getByIdsWithTags(listOf(bookmarkId)).single()
        val names = withTags.tags.associate { it.id to it.name }
        return withTags.tagRefs.associate { names.getValue(it.tagId) to it.source }
    }

    private suspend fun tagNames(): Set<String> = dao.getAllTags().first().map { it.name }.toSet()

    private suspend fun isUserCreated(name: String): Boolean = dao.getTagByName(name)!!.isUserCreated

    @Test
    fun AIのタグを置き換えてもユーザーのタグは残る() = runTest {
        insertBookmark(1)
        dao.addTagsToBookmark(1, listOf("自分"), TagSource.USER)
        dao.replaceAiTags(1, listOf("古い", "自分"))

        dao.replaceAiTags(1, listOf("新しい"))

        // AI が「自分」も出していたが、ユーザーが付けたものなので USER のまま
        assertEquals(mapOf("自分" to TagSource.USER, "新しい" to TagSource.AI), linksOf(1))
        // どこにも付かなくなった AI のタグは消える
        assertEquals(setOf("自分", "新しい"), tagNames())
    }

    @Test
    fun AIのタグは表記ゆれの範囲で既存のタグにそろえる() = runTest {
        insertBookmark(1)
        insertBookmark(2)
        dao.addTagsToBookmark(1, listOf("Android"), TagSource.USER)

        dao.replaceAiTags(2, listOf("android", "ＡＮＤＲＯＩＤ", "#Kotlin"))

        assertEquals(mapOf("Android" to TagSource.AI, "Kotlin" to TagSource.AI), linksOf(2))
        assertEquals(setOf("Android", "Kotlin"), tagNames())
    }

    @Test
    fun ユーザーが付け直すとAIの紐付けがユーザーのものになる() = runTest {
        insertBookmark(1)
        dao.replaceAiTags(1, listOf("kotlin"))
        assertFalse(isUserCreated("kotlin"))

        dao.addTagsToBookmark(1, listOf("kotlin"), TagSource.USER)

        assertEquals(mapOf("kotlin" to TagSource.USER), linksOf(1))
        assertTrue(isUserCreated("kotlin"))
        // 再処理で AI の結果が変わっても外れない
        dao.replaceAiTags(1, emptyList())
        assertEquals(mapOf("kotlin" to TagSource.USER), linksOf(1))
    }

    @Test
    fun 件数0になってもユーザーのタグは残し_AIのタグは消す() = runTest {
        insertBookmark(1)
        dao.addTagsToBookmark(1, listOf("自分"), TagSource.USER)
        dao.replaceAiTags(1, listOf("AI"))

        dao.removeTagFromBookmark(1, "自分")
        dao.removeTagFromBookmark(1, "AI")

        assertEquals(setOf("自分"), tagNames())
    }

    @Test
    fun ブックマークを削除したら孤立したAIのタグだけを消せる() = runTest {
        insertBookmark(1)
        insertBookmark(2)
        dao.addTagsToBookmark(1, listOf("自分"), TagSource.USER)
        dao.replaceAiTags(1, listOf("1だけ", "共通"))
        dao.replaceAiTags(2, listOf("共通"))

        db.bookmarkDao().delete(db.bookmarkDao().getById(1)!!)
        assertEquals(1, dao.deleteOrphanAiTags())

        assertEquals(setOf("自分", "共通"), tagNames())
    }

    @Test
    fun 削除済みのブックマークにはAIのタグを付けない() = runTest {
        assertFalse(dao.replaceAiTags(999, listOf("kotlin")))
        assertTrue(tagNames().isEmpty())
    }

    @Test
    fun 名前を変更したタグはユーザーのタグになる() = runTest {
        insertBookmark(1)
        dao.replaceAiTags(1, listOf("andorid"))
        val id = dao.getTagByName("andorid")!!.id

        dao.renameOrMerge(id, "Android")
        dao.replaceAiTags(1, emptyList())

        // 紐付けは AI のものなので外れるが、名前を決めたタグは件数 0 でも残る
        assertTrue(isUserCreated("Android"))
        assertEquals(setOf("Android"), tagNames())
    }

    @Test
    fun 統合では付けた人を引き継ぎ_ユーザーの紐付けを優先する() = runTest {
        insertBookmark(1)
        insertBookmark(2)
        // 1: kotlin(USER)・Kotlin(AI)、2: kotlin(AI) から、kotlin を Kotlin へ統合する
        link(1, "kotlin", TagSource.USER)
        link(1, "Kotlin", TagSource.AI)
        link(2, "kotlin", TagSource.AI)
        val from = dao.getTagByName("kotlin")!!.id

        val into = dao.renameOrMerge(from, "Kotlin")

        assertEquals(dao.getTagByName("Kotlin")!!.id, into)
        assertEquals(mapOf("Kotlin" to TagSource.USER), linksOf(1))
        assertEquals(mapOf("Kotlin" to TagSource.AI), linksOf(2))
        assertTrue(isUserCreated("Kotlin"))
        assertEquals(setOf("Kotlin"), tagNames())
    }

    /** 名前の正規化を通さずに、指定した付けた人で紐付ける(状態を作るため) */
    private suspend fun link(bookmarkId: Long, name: String, source: TagSource) {
        val tagId = dao.getOrCreateTagId(name)!!
        if (source == TagSource.USER) dao.markUserCreated(tagId)
        dao.insertCrossRef(BookmarkTagCrossRef(bookmarkId, tagId, source))
    }

    @Test
    fun AIに渡すタグはユーザーのタグを先に_よく使われている順に並べる() = runTest {
        (1L..3L).forEach { insertBookmark(it) }
        dao.replaceAiTags(1, listOf("よく使う"))
        dao.replaceAiTags(2, listOf("よく使う", "たまに"))
        dao.replaceAiTags(3, listOf("よく使う"))
        dao.addTagsToBookmark(1, listOf("自分"), TagSource.USER)

        assertEquals(listOf("自分", "よく使う", "たまに"), dao.getTagNamesByPriority(limit = -1))
        assertEquals(listOf("自分", "よく使う"), dao.getTagNamesByPriority(limit = 2))
    }

    @Test
    fun 件数つき一覧でユーザーのタグかを返す() = runTest {
        insertBookmark(1)
        dao.addTagsToBookmark(1, listOf("自分"), TagSource.USER)
        dao.replaceAiTags(1, listOf("AI"))

        val rows = dao.observeTagsWithCount().first().associateBy { it.name }

        assertTrue(rows.getValue("自分").isUserCreated)
        assertFalse(rows.getValue("AI").isUserCreated)
        assertEquals(1, rows.getValue("AI").bookmarkCount)
    }
}
