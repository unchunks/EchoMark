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
import com.unchunks.echomark.data.local.entity.RemovedTagEntity
import com.unchunks.echomark.data.local.entity.TagAliasEntity
import com.unchunks.echomark.data.local.entity.TagEntity
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.model.TagNames
import com.unchunks.echomark.domain.model.TagSource

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

    @Query("SELECT * FROM tag_aliases ORDER BY aliasKey")
    abstract suspend fun getAllTagAliases(): List<TagAliasEntity>

    @Query("SELECT * FROM removed_tags ORDER BY bookmarkId, tagKey")
    abstract suspend fun getAllRemovedTags(): List<RemovedTagEntity>

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
        messages = getAllMessages(),
        tagAliases = getAllTagAliases(),
        removedTags = getAllRemovedTags()
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

    @Query("UPDATE tags SET isUserCreated = 1 WHERE id = :id")
    abstract suspend fun markTagUserCreated(id: Long)

    @Query("UPDATE tags SET parentId = :parentId WHERE id = :id")
    abstract suspend fun updateTagParent(id: Long, parentId: Long?)

    /** [tagId] とその子孫のタグの ID(階層が輪にならないかの確認用)。 */
    @Query(
        "WITH RECURSIVE subtree(id) AS (SELECT :tagId UNION " +
            "SELECT t.id FROM tags AS t INNER JOIN subtree AS s ON t.parentId = s.id) SELECT id FROM subtree"
    )
    abstract suspend fun getTagSubtreeIds(tagId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertCrossRef(crossRef: BookmarkTagCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTagAlias(alias: TagAliasEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertRemovedTag(removed: RemovedTagEntity)

    @Query("SELECT name FROM tags")
    abstract suspend fun getAllTagNames(): List<String>

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
     * - タグ・紐付け: 追加したブックマークの分だけ、同名のタグに付け替える(無ければ作る)。誰が付けたかも引き継ぐ。
     *   バックアップでユーザーのタグだったもの(ユーザーが付けた紐付けがあるものも)は、この DB でもユーザーのタグにする
     * - タグの階層: この読み込みで新しく作ったタグだけ、バックアップの親を引き継ぐ(親が無ければ作る。階層が輪になるものは引き継がない)。
     *   既にあるタグの階層は変えない
     * - タグの別名: 行き先のタグがあればそのタグへ、無ければ(どのブックマークにも付いていないタグでも)ユーザーのタグとして作って付ける。
     *   別名と同じ名前のタグが既にあるとき、同じ別名が既にあるときは取り込まない
     * - 外したタグの記録: 追加したブックマークの分だけ取り込む
     * - 会話・メッセージ: 同じ会話があればスキップ。メッセージの引用と会話の質問の対象(aboutBookmarkId)は
     *   新しいブックマーク ID に付け替える(対象がバックアップに無ければ通常の会話にする)
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
        val userTagNames = buildSet {
            data.tags.filter { it.isUserCreated }.forEach { add(it.name.trim()) }
            data.bookmarkTags.filter { it.source == TagSource.USER }.forEach { ref -> tagNames[ref.tagId]?.let { add(it) } }
        }
        val tagIds = mutableMapOf<String, Long>()
        val createdTagNames = mutableSetOf<String>()
        var tagsAdded = 0
        suspend fun tagIdFor(name: String, isUserTag: Boolean): Long = tagIds.getOrPut(name) {
            val id = findTagIdByName(name)
                ?: insertTag(TagEntity(name = name, isUserCreated = isUserTag)).also {
                    tagsAdded++
                    createdTagNames += name
                }
            if (isUserTag) markTagUserCreated(id)
            id
        }
        for (crossRef in data.bookmarkTags) {
            val bookmarkId = bookmarkIds[crossRef.bookmarkId]?.takeIf { it in addedBookmarkIds } ?: continue
            val name = tagNames[crossRef.tagId]?.takeIf { it.isNotEmpty() } ?: continue
            val tagId = tagIdFor(name, isUserTag = name in userTagNames)
            insertCrossRef(BookmarkTagCrossRef(bookmarkId = bookmarkId, tagId = tagId, source = crossRef.source))
        }

        // 階層: 新しく作ったタグを子にして、親をたどって引き継ぐ(親を作ったら、その親も同じように)。
        // 階層に組み込んだタグはユーザーのタグにする(TagDao.setParent と同じ)
        val backupParentNames = data.tags.mapNotNull { tag ->
            val parentName = tag.parentId?.let { tagNames[it] }?.takeIf { it.isNotEmpty() }
            tagNames[tag.id]?.takeIf { it.isNotEmpty() && parentName != null }?.let { it to parentName!! }
        }.toMap()
        val pending = ArrayDeque(createdTagNames.toList())
        val handled = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (!handled.add(name)) continue
            val parentName = backupParentNames[name]?.takeIf { it != name } ?: continue
            val childId = tagIds[name] ?: continue
            val parentId = tagIdFor(parentName, isUserTag = false)
            if (parentName in createdTagNames) pending += parentName
            if (parentId in getTagSubtreeIds(childId)) continue
            updateTagParent(childId, parentId)
            markTagUserCreated(childId)
            markTagUserCreated(parentId)
        }

        val conversationIds = mutableMapOf<Long, Long>()
        var conversationsSkipped = 0
        for (conversation in data.conversations) {
            if (findSameConversationId(conversation.title, conversation.createdAt) != null) {
                conversationsSkipped++
                continue
            }
            conversationIds[conversation.id] = insertConversation(
                conversation.copy(id = 0, aboutBookmarkId = conversation.aboutBookmarkId?.let { bookmarkIds[it] })
            )
        }

        // 別名: 今の DB にある名前・別名とぶつからないものだけ
        val takenKeys = (getAllTagNames().map { TagNames.key(it) } + getAllTagAliases().map { it.aliasKey }).toMutableSet()
        for (alias in data.tagAliases) {
            val targetName = tagNames[alias.tagId]?.takeIf { it.isNotEmpty() } ?: continue
            if (alias.aliasKey in takenKeys || alias.aliasKey == TagNames.key(targetName)) continue
            val targetId = findTagIdByName(targetName)
                ?: insertTag(TagEntity(name = targetName, isUserCreated = true)).also { tagsAdded++ }
            takenKeys += alias.aliasKey
            insertTagAlias(alias.copy(tagId = targetId))
        }

        for (removed in data.removedTags) {
            val bookmarkId = bookmarkIds[removed.bookmarkId]?.takeIf { it in addedBookmarkIds } ?: continue
            insertRemovedTag(removed.copy(bookmarkId = bookmarkId))
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

    @Query("DELETE FROM api_usage")
    abstract suspend fun deleteAllApiUsage()

    /** ブックマーク・タグ・会話・クラウド API の使用量の記録をすべて削除する(外部キーの子から順に消す)。 */
    @Transaction
    open suspend fun deleteAll() {
        deleteAllMessages()
        deleteAllConversations()
        deleteAllCrossRefs()
        deleteAllTags()
        deleteAllBookmarks()
        deleteAllApiUsage()
    }
}
