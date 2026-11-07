package com.unchunks.echomark.data.local.dao

import org.junit.Assert.assertNull
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

        dao.removeTagFromBookmark(1, "自分", removedAt = 1)
        dao.removeTagFromBookmark(1, "AI", removedAt = 1)

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


    @Test
    fun 名前の変更は大文字小文字と全角半角の違いを除いて同じタグに統合する() = runTest {
        insertBookmark(1)
        insertBookmark(2)
        link(1, "Android", TagSource.USER)
        link(2, "andoroid", TagSource.AI)
        val from = dao.getTagByName("andoroid")!!.id

        val into = dao.renameOrMerge(from, "android")

        assertEquals(dao.getTagByName("Android")!!.id, into)
        assertEquals(setOf("Android"), tagNames())
        assertEquals(mapOf("Android" to TagSource.AI), linksOf(2))
    }

    @Test
    fun 自分自身の大文字小文字だけを変える名前の変更は統合にならない() = runTest {
        insertBookmark(1)
        link(1, "kotlin", TagSource.USER)
        val id = dao.getTagByName("kotlin")!!.id

        assertEquals(id, dao.renameOrMerge(id, "Kotlin"))
        assertEquals(setOf("Kotlin"), tagNames())
    }

    @Test
    fun タグの統合では付けた人を引き継ぎ_元のタグは消える() = runTest {
        insertBookmark(1)
        insertBookmark(2)
        insertBookmark(3)
        link(1, "Android", TagSource.AI)
        link(1, "android", TagSource.USER)
        link(2, "android", TagSource.AI)
        link(3, "ＡＮＤＲＯＩＤ", TagSource.AI)
        val into = dao.getTagByName("Android")!!.id
        val fromIds = listOf("android", "ＡＮＤＲＯＩＤ").map { dao.getTagByName(it)!!.id }

        assertEquals(2, dao.mergeTags(into, fromIds + into + 999L))

        assertEquals(setOf("Android"), tagNames())
        assertEquals(mapOf("Android" to TagSource.USER), linksOf(1))
        assertEquals(mapOf("Android" to TagSource.AI), linksOf(2))
        assertEquals(mapOf("Android" to TagSource.AI), linksOf(3))
        // 名前を付け直すわけではないが、ユーザーのタグが混ざっていれば統合先もユーザーのタグ
        assertTrue(isUserCreated("Android"))
    }

    @Test
    fun AIのタグ同士の統合では統合先もAIのタグのまま() = runTest {
        insertBookmark(1)
        dao.replaceAiTags(1, listOf("android", "Android"))
        link(1, "ANDROID", TagSource.AI)
        val into = dao.getTagByName("android")!!.id
        val from = dao.getTagByName("ANDROID")!!.id

        dao.mergeTags(into, listOf(from))

        assertFalse(isUserCreated("android"))
        // 件数 0 になれば自動で消える AI のタグのまま
        dao.replaceAiTags(1, emptyList())
        assertTrue(tagNames().isEmpty())
    }

    @Test
    fun 統合先が無ければ何もしない() = runTest {
        insertBookmark(1)
        link(1, "kotlin", TagSource.USER)

        assertEquals(0, dao.mergeTags(999, listOf(dao.getTagByName("kotlin")!!.id)))
        assertEquals(setOf("kotlin"), tagNames())
    }


    private suspend fun idOf(name: String) = dao.getTagByName(name)!!.id

    private suspend fun parentOf(name: String): String? =
        dao.getTagByName(name)!!.parentId?.let { dao.getTagById(it)!!.name }

    private suspend fun tags(vararg names: String) = names.forEach { dao.getOrCreateTagId(it) }

    @Test
    fun 親のタグを設定し_外して最上位に戻せる() = runTest {
        tags("プログラミング", "Android")

        assertTrue(dao.setParent(idOf("Android"), idOf("プログラミング")))
        assertEquals("プログラミング", parentOf("Android"))

        assertTrue(dao.setParent(idOf("Android"), null))
        assertEquals(null, parentOf("Android"))
    }

    @Test
    fun 自分や子孫を親にして輪になる設定はできない() = runTest {
        tags("A", "B", "C")
        dao.setParent(idOf("B"), idOf("A"))
        dao.setParent(idOf("C"), idOf("B"))

        assertFalse(dao.setParent(idOf("A"), idOf("A")))
        assertFalse(dao.setParent(idOf("A"), idOf("C")))
        assertEquals(null, parentOf("A"))
        assertEquals(setOf(idOf("A"), idOf("B"), idOf("C")), dao.getSubtreeIds(idOf("A")).toSet())
        assertEquals(setOf(idOf("C"), idOf("B"), idOf("A")), dao.getAncestorIds(idOf("C")).toSet())
    }

    @Test
    fun 存在しないタグや親は設定できない() = runTest {
        tags("A")

        assertFalse(dao.setParent(999, idOf("A")))
        assertFalse(dao.setParent(idOf("A"), 999))
        assertEquals(null, parentOf("A"))
    }

    @Test
    fun 階層に組み込んだAIのタグはユーザーのタグになり件数0でも残る() = runTest {
        insertBookmark(1)
        dao.replaceAiTags(1, listOf("Android", "Compose"))
        assertFalse(isUserCreated("Android"))

        dao.setParent(idOf("Compose"), idOf("Android"))
        dao.replaceAiTags(1, emptyList())

        assertTrue(isUserCreated("Android"))
        assertTrue(isUserCreated("Compose"))
        assertEquals(setOf("Android", "Compose"), tagNames())
        assertEquals("Android", parentOf("Compose"))
    }

    @Test
    fun 親を削除すると子は削除した親の親につく() = runTest {
        tags("A", "B", "C", "D")
        dao.setParent(idOf("B"), idOf("A"))
        dao.setParent(idOf("C"), idOf("B"))
        dao.setParent(idOf("D"), idOf("B"))

        dao.deleteTagWithRefs(idOf("B"))
        assertEquals("A", parentOf("C"))
        assertEquals("A", parentOf("D"))

        dao.deleteTagWithRefs(idOf("A"))
        assertEquals(null, parentOf("C"))
    }

    @Test
    fun 統合すると子は統合先へ移り_統合先に親が無ければ親を引き継ぐ() = runTest {
        tags("親", "android", "Android", "Compose")
        dao.setParent(idOf("android"), idOf("親"))
        dao.setParent(idOf("Compose"), idOf("android"))

        dao.mergeTags(idOf("Android"), listOf(idOf("android")))

        assertEquals("Android", parentOf("Compose"))
        assertEquals("親", parentOf("Android"))
        assertNull(dao.getTagByName("android"))
    }

    @Test
    fun 統合先に親があればその親のまま() = runTest {
        tags("親1", "親2", "x", "X")
        dao.setParent(idOf("x"), idOf("親1"))
        dao.setParent(idOf("X"), idOf("親2"))

        dao.mergeTags(idOf("X"), listOf(idOf("x")))

        assertEquals("親2", parentOf("X"))
    }

    @Test
    fun 統合先が元のタグの子孫でも輪にならない() = runTest {
        tags("上", "動物", "哺乳類", "猫")
        dao.setParent(idOf("動物"), idOf("上"))
        dao.setParent(idOf("哺乳類"), idOf("動物"))
        dao.setParent(idOf("猫"), idOf("哺乳類"))

        // 動物(猫の祖先)を猫へ統合する: 猫は動物の位置(上の下)へ上がり、動物の子(哺乳類)が猫の下になる
        dao.mergeTags(idOf("猫"), listOf(idOf("動物")))

        assertEquals("上", parentOf("猫"))
        assertEquals("猫", parentOf("哺乳類"))
        assertNull(dao.getTagByName("動物"))
        // 輪になっていない: 上から全部たどれる
        assertEquals(setOf("上", "猫", "哺乳類"), dao.getSubtreeIds(idOf("上")).map { dao.getTagById(it)!!.name }.toSet())
    }

    @Test
    fun 引き継ぐ親が統合先の子孫なら引き継がない() = runTest {
        tags("into", "mid", "from")
        dao.setParent(idOf("mid"), idOf("into"))
        dao.setParent(idOf("from"), idOf("mid"))

        // from の親(mid)は into の子孫。into の親にすると輪になる
        dao.mergeTags(idOf("into"), listOf(idOf("from")))

        assertEquals(null, parentOf("into"))
        assertEquals("into", parentOf("mid"))
    }

    @Test
    fun 名前の変更による統合でも階層を引き継ぐ() = runTest {
        tags("親", "andoroid", "Android", "Compose")
        dao.setParent(idOf("andoroid"), idOf("親"))
        dao.setParent(idOf("Compose"), idOf("andoroid"))

        dao.renameOrMerge(idOf("andoroid"), "android")

        assertEquals("親", parentOf("Android"))
        assertEquals("Android", parentOf("Compose"))
    }

    @Test
    fun 件数つき一覧は親のタグを返す() = runTest {
        tags("親", "子")
        dao.setParent(idOf("子"), idOf("親"))

        val rows = dao.observeTagsWithCount().first().associateBy { it.name }

        assertEquals(idOf("親"), rows.getValue("子").parentId)
        assertEquals(null, rows.getValue("親").parentId)
    }


    private suspend fun aliasTargets(): Map<String, String> = dao.getAliasTargets().associate { it.aliasKey to it.tagName }

    private suspend fun removedKeys(bookmarkId: Long): Set<String> = dao.getRemovedKeys(bookmarkId).toSet()

    @Test
    fun 統合すると元の名前が別名になり_AIが同じ名前を出しても統合先に付く() = runTest {
        insertBookmark(1)
        tags("猫", "ネコ")

        dao.mergeTags(idOf("猫"), listOf(idOf("ネコ")))
        assertEquals(mapOf("ネコ" to "猫"), aliasTargets())

        dao.replaceAiTags(1, listOf("ネコ", "ねこ"))

        assertEquals(setOf("猫"), tagNames())
        assertEquals(mapOf("猫" to TagSource.AI), linksOf(1))
    }

    @Test
    fun 元のタグの別名は統合先に引き継ぎ_名前変更でも元の名前を別名にする() = runTest {
        tags("A", "B", "C")
        dao.mergeTags(idOf("B"), listOf(idOf("A")))
        dao.renameOrMerge(idOf("C"), "Cの新名")
        dao.mergeTags(idOf("Cの新名"), listOf(idOf("B")))

        // A → B → Cの新名 と統合し、C → Cの新名 と名前を変えた。どの元の名前も今のタグを指す
        assertEquals(mapOf("a" to "Cの新名", "b" to "Cの新名", "c" to "Cの新名"), aliasTargets())
    }

    @Test
    fun 大文字小文字だけの違いでは別名を作らない() = runTest {
        tags("android", "Android")
        dao.mergeTags(idOf("Android"), listOf(idOf("android")))
        dao.renameOrMerge(idOf("Android"), "ANDROID")

        assertTrue(aliasTargets().isEmpty())
    }

    @Test
    fun 同じ名前のタグを作り直すと別名は消える() = runTest {
        tags("猫", "ネコ")
        dao.mergeTags(idOf("猫"), listOf(idOf("ネコ")))

        dao.getOrCreateTagId("ネコ")

        assertTrue(aliasTargets().isEmpty())
        assertEquals(setOf("猫", "ネコ"), tagNames())
    }

    @Test
    fun タグを削除すると別名も消える() = runTest {
        tags("猫", "ネコ")
        dao.mergeTags(idOf("猫"), listOf(idOf("ネコ")))

        dao.deleteTagRemembering(idOf("猫"), removedAt = 1)

        assertTrue(aliasTargets().isEmpty())
    }

    @Test
    fun 外したタグは覚えておき_AIの再処理でまた付けない() = runTest {
        insertBookmark(1)
        insertBookmark(2)
        dao.replaceAiTags(1, listOf("kotlin", "android"))
        dao.replaceAiTags(2, listOf("kotlin"))

        dao.removeTagFromBookmark(1, "kotlin", removedAt = 5)
        assertEquals(setOf("kotlin"), removedKeys(1))
        assertEquals(emptySet<String>(), removedKeys(2))

        // 再処理: 1 には kotlin を付けず(表記ゆれの違いでも)、2 には付ける
        dao.replaceAiTags(1, listOf("Kotlin", "android"))
        dao.replaceAiTags(2, listOf("Kotlin"))

        assertEquals(mapOf("android" to TagSource.AI), linksOf(1))
        assertEquals(mapOf("kotlin" to TagSource.AI), linksOf(2))
    }

    @Test
    fun ユーザーが付け直したタグは覚えから消え_AIも付けられる() = runTest {
        insertBookmark(1)
        dao.replaceAiTags(1, listOf("kotlin"))
        dao.removeTagFromBookmark(1, "kotlin", removedAt = 5)

        dao.addTagsToBookmark(1, listOf("Kotlin"), TagSource.USER)

        assertEquals(emptySet<String>(), removedKeys(1))
        dao.replaceAiTags(1, listOf("kotlin"))
        // AI の kotlin は、付け直したタグ Kotlin にそろえられ、ユーザーの紐付けのまま
        assertEquals(mapOf("Kotlin" to TagSource.USER), linksOf(1))
    }

    @Test
    fun タグ管理で削除したタグは付いていたブックマークから外したものとして覚える() = runTest {
        insertBookmark(1)
        insertBookmark(2)
        dao.replaceAiTags(1, listOf("技術"))
        dao.replaceAiTags(2, listOf("技術", "読書"))

        dao.deleteTagRemembering(idOf("技術"), removedAt = 9)
        dao.replaceAiTags(1, listOf("技術"))
        dao.replaceAiTags(2, listOf("技術", "読書"))

        assertEquals(emptyMap<String, TagSource>(), linksOf(1))
        assertEquals(mapOf("読書" to TagSource.AI), linksOf(2))
    }

    @Test
    fun 統合したタグを外した記録は統合先の名前で覚え直す() = runTest {
        insertBookmark(1)
        dao.addTagsToBookmark(1, listOf("ネコ"), TagSource.USER)
        // 件数 0 で消えないよう、猫もユーザーのタグにしておく
        dao.markUserCreated(dao.getOrCreateTagId("猫")!!)
        dao.removeTagFromBookmark(1, "ネコ", removedAt = 5)
        assertEquals(setOf("ネコ"), removedKeys(1))

        dao.mergeTags(idOf("猫"), listOf(idOf("ネコ")))

        assertEquals(setOf("猫"), removedKeys(1))
        // ネコを外した後で猫に統合した。AI が ネコ・猫 のどちらを出しても、このブックマークには付けない
        dao.replaceAiTags(1, listOf("ネコ", "猫", "Compose"))
        assertEquals(mapOf("Compose" to TagSource.AI), linksOf(1))
    }

    @Test
    fun 親子のどちらも出てきたら子だけを付ける() = runTest {
        insertBookmark(1)
        tags("プログラミング", "Android", "読書")
        dao.setParent(idOf("Android"), idOf("プログラミング"))

        dao.replaceAiTags(1, listOf("プログラミング", "Android", "読書"))

        assertEquals(mapOf("Android" to TagSource.AI, "読書" to TagSource.AI), linksOf(1))
    }

    @Test
    fun ユーザーが付けた子のタグがあれば親はAIが付けない() = runTest {
        insertBookmark(1)
        tags("プログラミング", "Android")
        dao.setParent(idOf("Android"), idOf("プログラミング"))
        dao.addTagsToBookmark(1, listOf("Android"), TagSource.USER)

        dao.replaceAiTags(1, listOf("プログラミング"))

        assertEquals(mapOf("Android" to TagSource.USER), linksOf(1))
    }

    @Test
    fun 親だけが出たときは親を付ける() = runTest {
        insertBookmark(1)
        tags("プログラミング", "Android")
        dao.setParent(idOf("Android"), idOf("プログラミング"))

        dao.replaceAiTags(1, listOf("プログラミング"))

        assertEquals(mapOf("プログラミング" to TagSource.AI), linksOf(1))
    }
}
