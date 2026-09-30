package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.unchunks.echomark.data.backup.BackupData
import com.unchunks.echomark.data.backup.formatReferencedIds
import com.unchunks.echomark.data.backup.parseReferencedIds
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.data.local.entity.TagEntity
import com.unchunks.echomark.domain.bookmark.model.AiStatus

/** バックアップの読み込みで追加・スキップした件数。 */
data class BackupMergeResult(
    val bookmarksAdded: Int,
    /** 同じ URL(テキストは同じタイトル・本文・作成日時)が既にあって追加しなかった件数 */
    val bookmarksSkipped: Int,
    val tagsAdded: Int,
    val conversationsAdded: Int,
    /** 同じ作成日時・タイトルの会話が既にあって追加しなかった件数 */
    val conversationsSkipped: Int,
    val messagesAdded: Int
)

/**
 * バックアップの書き出し・読み込み(統合)と全データ削除。
 * 複数テーブルにまたがる処理をトランザクションでまとめるため、他の DAO とは分けている。
 */
@Dao
abstract class BackupDao {

    // ---- 書き出し ----

    @Query("SELECT * FROM bookmarks ORDER BY id")
    abstract suspend fun getAllBookmarks(): List<BookmarkEntity>

    @Query("SELECT * FROM tags ORDER BY id")
    abstract suspend fun getAllTags(): List<TagEntity>

    @Query("SELECT * FROM bookmark_tag_cross_ref ORDER BY bookmarkId, tagId")
    abstract suspend fun getAllCrossRefs(): List<BookmarkTagCrossRef>

    @Query("SELECT * FROM conversations ORDER BY id")
    abstract suspend fun getAllConversations(): List<ConversationEntity>

    @Query("SELECT * FROM chat_messages ORDER BY id")
    abstract suspend fun getAllMessages(): List<ChatMessageEntity>

    /** 書き出し用に、全テーブルを同じ時点の内容で読む。 */
    @Transaction
    open suspend fun snapshot(exportedAt: Long): BackupData = BackupData(
        exportedAt = exportedAt,
        bookmarks = getAllBookmarks(),
        tags = getAllTags(),
        bookmarkTags = getAllCrossRefs(),
        conversations = getAllConversations(),
        messages = getAllMessages()
    )

    // ---- 読み込み(統合) ----

    @Query("SELECT id FROM bookmarks WHERE contentUri = :contentUri LIMIT 1")
    abstract suspend fun findBookmarkIdByUri(contentUri: String): Long?

    @Query(
        "SELECT id FROM bookmarks WHERE contentUri IS NULL AND title = :title AND createdAt = :createdAt " +
            "AND IFNULL(content, '') = IFNULL(:content, '') LIMIT 1"
    )
    abstract suspend fun findSameTextBookmarkId(title: String, content: String?, createdAt: Long): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("SELECT id FROM tags WHERE name = :name LIMIT 1")
    abstract suspend fun findTagIdByName(name: String): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertTag(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertCrossRef(crossRef: BookmarkTagCrossRef)

    @Query("SELECT id FROM conversations WHERE createdAt = :createdAt AND title = :title LIMIT 1")
    abstract suspend fun findSameConversationId(title: String, createdAt: Long): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertMessage(message: ChatMessageEntity): Long

    /**
     * [data] を今の DB に統合する(既存のデータは変更しない)。途中で失敗したら全体を取り消す。
     * - ブックマーク: 同じ URL があればスキップ。ID は振り直す。処理待ち・処理中だったものは「AI の準備待ち」にする
     *   (書き出し元の処理キューは引き継がれないため。読み込み後に再処理する)
     * - タグ・紐付け: 追加したブックマークの分だけ、同名のタグに付け替える(無ければ作る)
     * - 会話・メッセージ: 同じ会話があればスキップ。メッセージの引用は新しいブックマーク ID に付け替える
     */
    @Transaction
    open suspend fun merge(data: BackupData): BackupMergeResult {
        // 書き出し元の ID → この DB の ID(スキップしたものは既存の ID)
        val bookmarkIds = mutableMapOf<Long, Long>()
        val addedBookmarkIds = mutableSetOf<Long>()
        var bookmarksSkipped = 0
        for (bookmark in data.bookmarks) {
            val existingId = bookmark.contentUri?.let { findBookmarkIdByUri(it) }
                ?: if (bookmark.contentUri == null) {
                    findSameTextBookmarkId(bookmark.title, bookmark.content, bookmark.createdAt)
                } else {
                    null
                }
            if (existingId != null) {
                bookmarkIds[bookmark.id] = existingId
                bookmarksSkipped++
                continue
            }
            val newId = insertBookmark(
                bookmark.copy(
                    id = 0,
                    aiStatus = when (bookmark.aiStatus) {
                        AiStatus.PENDING, AiStatus.PROCESSING -> AiStatus.WAITING_MODEL
                        else -> bookmark.aiStatus
                    }
                )
            )
            bookmarkIds[bookmark.id] = newId
            addedBookmarkIds += newId
        }

        val tagNames = data.tags.associate { it.id to it.name.trim() }
        val tagIds = mutableMapOf<String, Long>()
        var tagsAdded = 0
        for (crossRef in data.bookmarkTags) {
            val bookmarkId = bookmarkIds[crossRef.bookmarkId]?.takeIf { it in addedBookmarkIds } ?: continue
            val name = tagNames[crossRef.tagId]?.takeIf { it.isNotEmpty() } ?: continue
            val tagId = tagIds.getOrPut(name) {
                findTagIdByName(name) ?: insertTag(TagEntity(name = name)).also { tagsAdded++ }
            }
            insertCrossRef(BookmarkTagCrossRef(bookmarkId = bookmarkId, tagId = tagId))
        }

        val conversationIds = mutableMapOf<Long, Long>()
        var conversationsSkipped = 0
        for (conversation in data.conversations) {
            if (findSameConversationId(conversation.title, conversation.createdAt) != null) {
                conversationsSkipped++
                continue
            }
            conversationIds[conversation.id] = insertConversation(conversation.copy(id = 0))
        }

        var messagesAdded = 0
        for (message in data.messages) {
            val conversationId = conversationIds[message.conversationId] ?: continue
            val references = parseReferencedIds(message.referencedBookmarkIds).mapNotNull { bookmarkIds[it] }
            insertMessage(
                message.copy(
                    id = 0,
                    conversationId = conversationId,
                    referencedBookmarkIds = formatReferencedIds(references)
                )
            )
            messagesAdded++
        }

        return BackupMergeResult(
            bookmarksAdded = addedBookmarkIds.size,
            bookmarksSkipped = bookmarksSkipped,
            tagsAdded = tagsAdded,
            conversationsAdded = conversationIds.size,
            conversationsSkipped = conversationsSkipped,
            messagesAdded = messagesAdded
        )
    }

    // ---- 全データ削除 ----

    @Query("DELETE FROM chat_messages")
    abstract suspend fun deleteAllMessages()

    @Query("DELETE FROM conversations")
    abstract suspend fun deleteAllConversations()

    @Query("DELETE FROM bookmark_tag_cross_ref")
    abstract suspend fun deleteAllCrossRefs()

    @Query("DELETE FROM tags")
    abstract suspend fun deleteAllTags()

    @Query("DELETE FROM bookmarks")
    abstract suspend fun deleteAllBookmarks()

    /** ブックマーク・タグ・会話をすべて削除する(外部キーの子から順に消す)。 */
    @Transaction
    open suspend fun deleteAll() {
        deleteAllMessages()
        deleteAllConversations()
        deleteAllCrossRefs()
        deleteAllTags()
        deleteAllBookmarks()
    }
}
