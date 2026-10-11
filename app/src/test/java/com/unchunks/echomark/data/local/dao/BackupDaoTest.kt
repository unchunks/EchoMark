package com.unchunks.echomark.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.data.backup.BackupData
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.data.local.entity.TagEntity
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.ChatRole
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
        messages: List<ChatMessageEntity> = emptyList()
    ) = BackupData(0, bookmarks, tags, bookmarkTags, conversations, messages)

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
}
