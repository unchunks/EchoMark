package com.unchunks.echomark.data.local.dao

import com.unchunks.echomark.data.local.entity.CategoryEntity
import com.unchunks.echomark.data.backup.BackupJson
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.data.backup.BackupData
import com.unchunks.echomark.data.backup.SmartFolderBackup
import com.unchunks.echomark.data.mapper.smartFolderEntity
import com.unchunks.echomark.data.mapper.toDomain
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.data.local.entity.RemovedTagEntity
import com.unchunks.echomark.data.local.entity.TagAliasEntity
import com.unchunks.echomark.data.local.entity.TagEntity
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkQuery
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.CreatedPeriod
import com.unchunks.echomark.domain.bookmark.model.ReadState
import com.unchunks.echomark.domain.bookmark.model.TagMatch
import com.unchunks.echomark.domain.model.TagSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** バックアップの書き出し(snapshot)・統合(merge)・全削除のテスト。 */
@RunWith(AndroidJUnit4::class)
class BackupDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BackupDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        dao = db.backupDao()
    }

    @After
    fun tearDown() = db.close()

    private fun urlBookmark(id: Long, url: String, status: AiStatus = AiStatus.DONE) = BookmarkEntity(
        id = id, type = BookmarkType.URL, contentUri = url, title = "t$id",
        createdAt = id * 10, lastAccessedAt = id * 10, aiStatus = status
    )

    private fun backup(
        bookmarks: List<BookmarkEntity> = emptyList(),
        tags: List<TagEntity> = emptyList(),
        bookmarkTags: List<BookmarkTagCrossRef> = emptyList(),
        conversations: List<ConversationEntity> = emptyList(),
        messages: List<ChatMessageEntity> = emptyList(),
        tagAliases: List<TagAliasEntity> = emptyList(),
        removedTags: List<RemovedTagEntity> = emptyList(),
        categories: List<CategoryEntity> = emptyList(),
        smartFolders: List<SmartFolderBackup> = emptyList()
    ) = BackupData(0, bookmarks, tags, bookmarkTags, conversations, messages, tagAliases, removedTags, categories, smartFolders)

    @Test
    fun 空のDBに統合すると全件入り_書き出すと同じ内容になる() = runTest {
        val source = backup(
            bookmarks = listOf(urlBookmark(1, "https://a"), urlBookmark(2, "https://b")),
            tags = listOf(TagEntity(1, "kotlin")),
            bookmarkTags = listOf(BookmarkTagCrossRef(2, 1)),
            conversations = listOf(ConversationEntity(1, "会話", createdAt = 5, updatedAt = 6)),
            messages = listOf(
                ChatMessageEntity(1, 1, ChatRole.ASSISTANT, "回答", referencedBookmarkIds = "2", createdAt = 7)
            )
        )

        val result = dao.merge(source)
        assertEquals(2, result.bookmarksAdded)
        assertEquals(1, result.tagsAdded)
        assertEquals(1, result.conversationsAdded)
        assertEquals(1, result.messagesAdded)

        val exported = dao.snapshot(exportedAt = 0)
        assertEquals(source.bookmarks.map { it.contentUri }, exported.bookmarks.map { it.contentUri })
        val bIdOf = exported.bookmarks.associate { it.contentUri to it.id }
        assertEquals(listOf(BookmarkTagCrossRef(bIdOf["https://b"]!!, exported.tags.single().id)), exported.bookmarkTags)
        assertEquals(bIdOf["https://b"].toString(), exported.messages.single().referencedBookmarkIds)
        assertEquals(exported.conversations.single().id, exported.messages.single().conversationId)
    }

    @Test
    fun 同じURLはスキップし_引用は既存のIDに付け替える() = runTest {
        // 既存: https://a (新しい DB では ID 1)
        dao.merge(backup(bookmarks = listOf(urlBookmark(1, "https://a"))))

        // 書き出し元では https://a が ID 50、https://new が ID 51
        val result = dao.merge(
            backup(
                bookmarks = listOf(urlBookmark(50, "https://a"), urlBookmark(51, "https://new")),
                tags = listOf(TagEntity(9, "tag")),
                bookmarkTags = listOf(BookmarkTagCrossRef(50, 9), BookmarkTagCrossRef(51, 9)),
                conversations = listOf(ConversationEntity(3, "c", createdAt = 1, updatedAt = 1)),
                messages = listOf(
                    ChatMessageEntity(1, 3, ChatRole.ASSISTANT, "x", referencedBookmarkIds = "50,51,999", createdAt = 2)
                )
            )
        )

        assertEquals(1, result.bookmarksAdded)
        assertEquals(1, result.bookmarksSkipped)
        val exported = dao.snapshot(0)
        val idOf = exported.bookmarks.associate { it.contentUri to it.id }
        // 既存のブックマークにはタグを足さない(既存データは変更しない)
        assertEquals(listOf(idOf["https://new"]), exported.bookmarkTags.map { it.bookmarkId })
        // 存在しない引用(999)は落とす
        assertEquals("${idOf["https://a"]},${idOf["https://new"]}", exported.messages.single().referencedBookmarkIds)
    }

    @Test
    fun 会話の質問の対象は新しいIDに付け替え_バックアップに無ければ通常の会話にする() = runTest {
        // 既存: https://a (新しい DB では ID 1)
        dao.merge(backup(bookmarks = listOf(urlBookmark(1, "https://a"))))

        dao.merge(
            backup(
                bookmarks = listOf(urlBookmark(50, "https://a"), urlBookmark(51, "https://new")),
                conversations = listOf(
                    ConversationEntity(1, "aについて", createdAt = 1, updatedAt = 1, aboutBookmarkId = 50),
                    ConversationEntity(2, "newについて", createdAt = 2, updatedAt = 2, aboutBookmarkId = 51),
                    ConversationEntity(3, "消えたものについて", createdAt = 3, updatedAt = 3, aboutBookmarkId = 999),
                    ConversationEntity(4, "通常", createdAt = 4, updatedAt = 4)
                )
            )
        )

        val exported = dao.snapshot(0)
        val idOf = exported.bookmarks.associate { it.contentUri to it.id }
        assertEquals(
            mapOf(
                "aについて" to idOf["https://a"],
                "newについて" to idOf["https://new"],
                "消えたものについて" to null,
                "通常" to null
            ),
            exported.conversations.associate { it.title to it.aboutBookmarkId }
        )
    }

    @Test
    fun 同じ内容のテキストと同じ会話はスキップし_同名タグは再利用する() = runTest {
        val text = BookmarkEntity(
            id = 1, type = BookmarkType.TEXT, content = "memo", title = "メモ", createdAt = 1, lastAccessedAt = 1
        )
        val source = backup(
            bookmarks = listOf(text),
            tags = listOf(TagEntity(1, "kotlin")),
            bookmarkTags = listOf(BookmarkTagCrossRef(1, 1)),
            conversations = listOf(ConversationEntity(1, "会話", createdAt = 5, updatedAt = 6)),
            messages = listOf(ChatMessageEntity(1, 1, ChatRole.USER, "q", createdAt = 7))
        )
        dao.merge(source)

        val again = dao.merge(source)
        assertEquals(0, again.bookmarksAdded)
        assertEquals(1, again.bookmarksSkipped)
        assertEquals(0, again.conversationsAdded)
        assertEquals(1, again.conversationsSkipped)
        assertEquals(0, again.messagesAdded)

        // 本文が違えば別のブックマークとして追加し、タグは既存の同名タグを使う
        val other = dao.merge(source.copy(bookmarks = listOf(text.copy(content = "別の本文")), conversations = emptyList()))
        assertEquals(1, other.bookmarksAdded)
        assertEquals(0, other.tagsAdded)
        assertEquals(1, dao.snapshot(0).tags.size)
    }

    @Test
    fun タグを付けた人とユーザーのタグかを引き継ぐ() = runTest {
        dao.merge(
            backup(
                bookmarks = listOf(urlBookmark(1, "https://a")),
                tags = listOf(TagEntity(1, "自分", isUserCreated = true), TagEntity(2, "AI"), TagEntity(3, "付け直した")),
                bookmarkTags = listOf(
                    BookmarkTagCrossRef(1, 1, TagSource.USER),
                    BookmarkTagCrossRef(1, 2, TagSource.AI),
                    // isUserCreated が無くても、ユーザーが付けた紐付けがあればユーザーのタグにする
                    BookmarkTagCrossRef(1, 3, TagSource.USER)
                )
            )
        )

        val exported = dao.snapshot(0)
        val nameOf = exported.tags.associate { it.id to it.name }
        assertEquals(
            mapOf("自分" to true, "AI" to false, "付け直した" to true),
            exported.tags.associate { it.name to it.isUserCreated }
        )
        assertEquals(
            mapOf("自分" to TagSource.USER, "AI" to TagSource.AI, "付け直した" to TagSource.USER),
            exported.bookmarkTags.associate { nameOf.getValue(it.tagId) to it.source }
        )
    }

    @Test
    fun 処理待ち_処理中だったものは準備待ちにして取り込む() = runTest {
        dao.merge(
            backup(
                bookmarks = listOf(
                    urlBookmark(1, "https://a", AiStatus.PENDING),
                    urlBookmark(2, "https://b", AiStatus.PROCESSING),
                    urlBookmark(3, "https://c", AiStatus.FAILED)
                )
            )
        )
        assertEquals(
            listOf(AiStatus.WAITING_MODEL, AiStatus.WAITING_MODEL, AiStatus.FAILED),
            dao.snapshot(0).bookmarks.map { it.aiStatus }
        )
    }

    @Test
    fun 全削除で全テーブルが空になる() = runTest {
        dao.merge(
            backup(
                bookmarks = listOf(urlBookmark(1, "https://a")),
                tags = listOf(TagEntity(1, "t")),
                bookmarkTags = listOf(BookmarkTagCrossRef(1, 1)),
                conversations = listOf(ConversationEntity(1, "c", createdAt = 1, updatedAt = 1)),
                messages = listOf(ChatMessageEntity(1, 1, ChatRole.USER, "q", createdAt = 2))
            )
        )

        dao.deleteAll()

        val exported = dao.snapshot(0)
        assertTrue(exported.bookmarks.isEmpty())
        assertTrue(exported.tags.isEmpty())
        assertTrue(exported.bookmarkTags.isEmpty())
        assertTrue(exported.conversations.isEmpty())
        assertTrue(exported.messages.isEmpty())
    }


    private suspend fun parentNameOf(name: String): String? =
        db.tagDao().getTagByName(name)!!.parentId?.let { db.tagDao().getTagById(it)!!.name }

    @Test
    fun 新しく作ったタグはバックアップの階層を引き継ぎ_使われていない親も作る() = runTest {
        // 書き出し元: 上 ← 中 ← Compose。ブックマークに付いているのは Compose だけ
        val result = dao.merge(
            backup(
                bookmarks = listOf(urlBookmark(1, "https://a")),
                tags = listOf(
                    TagEntity(1, "上", isUserCreated = true),
                    TagEntity(2, "中", isUserCreated = true, parentId = 1),
                    TagEntity(3, "Compose", isUserCreated = true, parentId = 2)
                ),
                bookmarkTags = listOf(BookmarkTagCrossRef(1, 3, TagSource.USER))
            )
        )

        assertEquals(3, result.tagsAdded)
        assertEquals("中", parentNameOf("Compose"))
        assertEquals("上", parentNameOf("中"))
        assertEquals(null, parentNameOf("上"))
        // 階層に入れたタグはユーザーのタグ
        assertTrue(db.tagDao().getTagByName("上")!!.isUserCreated)
    }

    @Test
    fun 既にあるタグの階層は変えず_輪になる親は引き継がない() = runTest {
        val tagDao = db.tagDao()
        tagDao.getOrCreateTagId("既存")
        tagDao.getOrCreateTagId("別の親")

        dao.merge(
            backup(
                bookmarks = listOf(urlBookmark(1, "https://a")),
                tags = listOf(
                    TagEntity(1, "既存", parentId = 2),
                    TagEntity(2, "別の親"),
                    // A と B は互いを親にしている(壊れたバックアップ)。C は自分自身を親にしている
                    TagEntity(3, "A", parentId = 4),
                    TagEntity(4, "B", parentId = 3),
                    TagEntity(5, "C", parentId = 5)
                ),
                bookmarkTags = listOf(1L, 3L, 4L, 5L).map { BookmarkTagCrossRef(1, it, TagSource.AI) }
            )
        )

        assertEquals(null, parentNameOf("既存"))
        assertEquals(null, parentNameOf("C"))
        // A と B のどちらかが親になるが、輪にはならない
        val a = parentNameOf("A")
        val b = parentNameOf("B")
        assertTrue(listOf(a, b).count { it != null } == 1)
    }

    @Test
    fun 階層つきで書き出して読み込むと階層が戻る() = runTest {
        val tagDao = db.tagDao()
        dao.merge(backup(bookmarks = listOf(urlBookmark(1, "https://a"))))
        tagDao.setParent(tagDao.getOrCreateTagId("子")!!, tagDao.getOrCreateTagId("親")!!)
        tagDao.insertCrossRef(BookmarkTagCrossRef(1, tagDao.getTagByName("子")!!.id, TagSource.USER))

        val snapshot = BackupJson.decode(BackupJson.encode(dao.snapshot(exportedAt = 0))).data
        dao.deleteAll()
        dao.merge(snapshot)

        assertEquals("親", parentNameOf("子"))
    }


    @Test
    fun 別名と外したタグの記録を書き出して読み込むと戻る() = runTest {
        val tagDao = db.tagDao()
        dao.merge(backup(bookmarks = listOf(urlBookmark(1, "https://a"))))
        tagDao.replaceAiTags(1, listOf("猫", "android"))
        tagDao.getOrCreateTagId("ネコ")
        tagDao.mergeTags(tagDao.getTagByName("猫")!!.id, listOf(tagDao.getTagByName("ネコ")!!.id))
        tagDao.removeTagFromBookmark(1, "android", removedAt = 42)

        val snapshot = BackupJson.decode(BackupJson.encode(dao.snapshot(exportedAt = 0))).data
        dao.deleteAll()
        val result = dao.merge(snapshot)

        assertEquals(1, result.bookmarksAdded)
        val newBookmarkId = db.bookmarkDao().getAllIds().single()
        assertEquals(mapOf("ネコ" to "猫"), tagDao.getAliasTargets().associate { it.aliasKey to it.tagName })
        assertEquals(listOf("android"), tagDao.getRemovedKeys(newBookmarkId))
        // 復元しても効く: AI が ネコ / android を出しても付けない・猫にする
        tagDao.replaceAiTags(newBookmarkId, listOf("ネコ", "android"))
        assertEquals(listOf("猫"), db.bookmarkDao().getByIdsWithTags(listOf(newBookmarkId)).single().tags.map { it.name })
    }

    @Test
    fun 使われていないタグへの別名も取り込み_既にある名前とぶつかる別名は取り込まない() = runTest {
        val tagDao = db.tagDao()
        tagDao.getOrCreateTagId("既存")
        val source = backup(
            tags = listOf(TagEntity(1, "猫", isUserCreated = true), TagEntity(2, "犬", isUserCreated = true)),
            tagAliases = listOf(
                TagAliasEntity("ネコ", "ネコ", 1),
                // 既にあるタグと同じ名前(キー)の別名は、そのタグの方を優先して取り込まない
                TagAliasEntity("既存", "既存", 2)
            )
        )

        dao.merge(source)

        assertEquals(mapOf("ネコ" to "猫"), tagDao.getAliasTargets().associate { it.aliasKey to it.tagName })
        // 猫はどのブックマークにも付いていないが、別名の行き先として作る
        assertEquals(true, tagDao.getTagByName("猫")?.isUserCreated)
    }


    @Test
    fun カテゴリは名前が無いものだけ追加し_既にあるカテゴリの説明は変えない() = runTest {
        val categoryDao = db.categoryDao()
        categoryDao.insert(CategoryEntity(name = "Tech", description = "今の説明", createdAt = 1))

        dao.merge(
            backup(
                categories = listOf(
                    CategoryEntity(name = "ＴＥＣＨ", description = "バックアップの説明", createdAt = 5),
                    CategoryEntity(name = "旅行", description = "行き先や宿", createdAt = 6)
                )
            )
        )

        val all = categoryDao.getAll()
        assertEquals(listOf("Tech", "旅行"), all.map { it.name })
        assertEquals(listOf("今の説明", "行き先や宿"), all.map { it.description })
    }

    @Test
    fun カテゴリを自分で選んだ印は追加するブックマークに付いて戻り_カテゴリも書き出して読み込むと戻る() = runTest {
        val categoryDao = db.categoryDao()
        categoryDao.insert(CategoryEntity(name = "旅行", description = "行き先や宿", createdAt = 1))
        dao.merge(backup(bookmarks = listOf(urlBookmark(1, "https://a").copy(category = "旅行", categoryEdited = true))))

        val snapshot = BackupJson.decode(BackupJson.encode(dao.snapshot(exportedAt = 0))).data
        dao.deleteAll()
        dao.merge(snapshot)

        val restored = db.bookmarkDao().getByIds(db.bookmarkDao().getAllIds()).single()
        assertEquals("旅行", restored.category)
        assertTrue(restored.categoryEdited)
        assertEquals(listOf("旅行" to "行き先や宿"), categoryDao.getAll().map { it.name to it.description })
    }

    @Test
    fun 分けた会話は読み込むと元の会話の新しいIDに付け替える() = runTest {
        // 元の会話が後に並んでいても付け替えられる
        val source = backup(
            conversations = listOf(
                ConversationEntity(21, "分岐", createdAt = 7, updatedAt = 8, isPinned = true, branchedFromId = 20, branchedAt = 3),
                ConversationEntity(20, "元", createdAt = 5, updatedAt = 6)
            )
        )

        dao.merge(source)

        val conversations = dao.getAllConversations()
        val parent = conversations.single { it.title == "元" }
        val branch = conversations.single { it.title == "分岐" }
        assertEquals(parent.id, branch.branchedFromId)
        assertEquals(3L, branch.branchedAt)
        assertEquals(true, branch.isPinned)
    }

    @Test
    fun スマートフォルダは書き出すとタグが名前になり_全データ削除で消える() = runTest {
        val tagId = db.tagDao().insertTag(TagEntity(name = "kotlin"))
        val missing = tagId + 100
        db.smartFolderDao().insert(
            smartFolderEntity(
                "未読の kotlin",
                BookmarkQuery(tagIds = setOf(tagId, missing), readState = ReadState.UNREAD, period = CreatedPeriod.PAST_WEEK),
                BookmarkSortOrder.TITLE,
                createdAt = 5
            )
        )

        val exported = dao.snapshot(exportedAt = 1).smartFolders.single()

        // タグは名前で持つ。もう無いタグ(削除済み)の ID は外す
        assertEquals("未読の kotlin", exported.name)
        assertEquals(listOf("kotlin"), exported.tagNames)
        assertEquals("UNREAD", exported.readState)
        assertEquals("PAST_WEEK", exported.period)
        assertEquals("TITLE", exported.sortOrder)

        dao.deleteAll()
        assertEquals(emptyList<SmartFolderBackup>(), dao.snapshot(exportedAt = 1).smartFolders)
    }

    @Test
    fun スマートフォルダを読み込むとタグを名前で探し直し_無ければ作る() = runTest {
        // この端末では「kotlin」の ID が 1 ではない
        db.tagDao().insertTag(TagEntity(name = "other"))
        val existingKotlin = db.tagDao().insertTag(TagEntity(name = "kotlin"))
        val source = backup(
            smartFolders = listOf(
                SmartFolderBackup(
                    name = "kotlin と新しいタグ", tagNames = listOf("kotlin", "新しいタグ"), tagMatch = "ANY",
                    readState = "UNREAD", period = "PAST_MONTH", sortOrder = "OLDEST", filter = "FAVORITES"
                ),
                SmartFolderBackup(name = "全部", filter = "ZZZ", period = "PAST_DECADE")
            )
        )

        val result = dao.merge(source)

        assertEquals(2, result.smartFoldersAdded)
        assertEquals(1, result.tagsAdded)
        val folders = db.smartFolderDao().getAll().map { it.toDomain() }
        val first = folders.single { it.name == "kotlin と新しいタグ" }
        val newTagId = dao.getAllTags().single { it.name == "新しいタグ" }.id
        assertEquals(setOf(existingKotlin, newTagId), first.query.tagIds)
        assertEquals(TagMatch.ANY, first.query.tagMatch)
        assertEquals(ReadState.UNREAD, first.query.readState)
        assertEquals(CreatedPeriod.PAST_MONTH, first.query.period)
        assertEquals(BookmarkFilter.FAVORITES, first.query.filter)
        assertEquals(BookmarkSortOrder.OLDEST, first.sortOrder)
        // 知らない値は既定に戻す
        val second = folders.single { it.name == "全部" }
        assertEquals(BookmarkQuery(), second.query)
    }

    @Test
    fun 同じ名前のスマートフォルダがあれば読み込んでも追加しない() = runTest {
        db.smartFolderDao().insert(smartFolderEntity("Kotlin 記事", BookmarkQuery(readState = ReadState.READ), null, createdAt = 1))

        val result = dao.merge(
            backup(
                smartFolders = listOf(
                    SmartFolderBackup(name = "kotlin 記事", readState = "UNREAD"),
                    SmartFolderBackup(name = "別のフォルダ"),
                    SmartFolderBackup(name = "別のフォルダ")
                )
            )
        )

        assertEquals(1, result.smartFoldersAdded)
        val folders = db.smartFolderDao().getAll()
        // 書き出し元の作成日時(未指定は 0)が先の、新しいフォルダの方が前に並ぶので、順序には頼らない
        assertEquals(setOf("Kotlin 記事", "別のフォルダ"), folders.map { it.name }.toSet())
        // 既にあるフォルダの条件は変えない
        assertEquals("READ", folders.single { it.name == "Kotlin 記事" }.readState)
    }

    @Test
    fun 元の会話がスキップされても分けた会話を今の会話に紐付ける() = runTest {
        dao.merge(backup(conversations = listOf(ConversationEntity(20, "元", createdAt = 5, updatedAt = 6))))
        val existing = dao.getAllConversations().single().id

        dao.merge(
            backup(
                conversations = listOf(
                    ConversationEntity(20, "元", createdAt = 5, updatedAt = 6),
                    ConversationEntity(21, "分岐", createdAt = 7, updatedAt = 8, branchedFromId = 20, branchedAt = 3)
                )
            )
        )

        assertEquals(existing, dao.getAllConversations().single { it.title == "分岐" }.branchedFromId)
    }

    @Test
    fun 元の会話がバックアップに無ければ通常の会話にする() = runTest {
        dao.merge(
            backup(conversations = listOf(ConversationEntity(21, "分岐", createdAt = 7, updatedAt = 8, branchedFromId = 99, branchedAt = 3)))
        )

        val branch = dao.getAllConversations().single()
        assertEquals(null, branch.branchedFromId)
        assertEquals(null, branch.branchedAt)
    }
}
