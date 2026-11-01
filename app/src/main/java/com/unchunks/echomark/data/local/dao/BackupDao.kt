package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.unchunks.echomark.data.backup.BackupData
import com.unchunks.echomark.data.backup.toBackup
import com.unchunks.echomark.data.backup.toEntity
import com.unchunks.echomark.data.backup.formatReferencedIds
import com.unchunks.echomark.data.backup.parseReferencedIds
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.data.local.entity.CategoryEntity
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.data.local.entity.RemovedTagEntity
import com.unchunks.echomark.data.local.entity.SmartFolderEntity
import com.unchunks.echomark.data.local.entity.SubscriptionEntity
import com.unchunks.echomark.data.local.entity.TagAliasEntity
import com.unchunks.echomark.data.local.entity.TagEntity
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.SmartFolderNames
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
    val messagesAdded: Int,
    /** 追加したスマートフォルダの数(同じ名前のフォルダが既にあれば追加しない) */
    val smartFoldersAdded: Int = 0,
    /** 追加した購読の数(同じフィードの URL が既にあれば追加しない) */
    val subscriptionsAdded: Int = 0
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

    @Query("SELECT * FROM categories ORDER BY createdAt, id")
    abstract suspend fun getAllCategories(): List<CategoryEntity>

    @Query("SELECT * FROM smart_folders ORDER BY createdAt, id")
    abstract suspend fun getAllSmartFolders(): List<SmartFolderEntity>

    @Query("SELECT * FROM subscriptions ORDER BY createdAt, id")
    abstract suspend fun getAllSubscriptions(): List<SubscriptionEntity>

    @Query("SELECT * FROM conversations ORDER BY id")
    abstract suspend fun getAllConversations(): List<ConversationEntity>

    @Query("SELECT * FROM chat_messages ORDER BY id")
    abstract suspend fun getAllMessages(): List<ChatMessageEntity>

    /** 書き出し用に、全テーブルを同じ時点の内容で読む。 */
    @Transaction
    open suspend fun snapshot(exportedAt: Long): BackupData {
        val tags = getAllTags()
        val tagNameById = tags.associate { it.id to it.name }
        return BackupData(
            exportedAt = exportedAt,
            bookmarks = getAllBookmarks(),
            tags = tags,
            bookmarkTags = getAllCrossRefs(),
            conversations = getAllConversations(),
            messages = getAllMessages(),
            tagAliases = getAllTagAliases(),
            removedTags = getAllRemovedTags(),
            categories = getAllCategories(),
            smartFolders = getAllSmartFolders().map { it.toBackup(tagNameById) },
            subscriptions = getAllSubscriptions()
        )
    }

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

    @Query("SELECT name FROM categories")
    abstract suspend fun getAllCategoryNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertCategory(category: CategoryEntity)

    @Query("SELECT name FROM tags")
    abstract suspend fun getAllTagNames(): List<String>

    @Query("SELECT name FROM smart_folders")
    abstract suspend fun getAllSmartFolderNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSmartFolder(folder: SmartFolderEntity): Long

    @Query("SELECT feedUrl FROM subscriptions")
    abstract suspend fun getAllSubscriptionFeedUrls(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSubscription(subscription: SubscriptionEntity): Long

    @Query("SELECT id FROM conversations WHERE createdAt = :createdAt AND title = :title LIMIT 1")
    abstract suspend fun findSameConversationId(title: String, createdAt: Long): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertConversation(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET branchedFromId = :branchedFromId, branchedAt = :branchedAt WHERE id = :id")
    abstract suspend fun updateConversationBranch(id: Long, branchedFromId: Long, branchedAt: Long?)

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
     * - スマートフォルダ: 名前(大文字小文字・全角半角を区別しない)が今の DB に無いものだけ追加する。条件のタグは名前で今の DB のタグを探す(無ければ作る)
     * - カテゴリ: 名前(大文字小文字・全角半角を区別しない)が今の DB に無いものだけ追加する(既にあるカテゴリの説明は変えない)。
     *   カテゴリを自分で選んだ印(categoryEdited)は、追加するブックマークにそのまま付く
     * - 会話・メッセージ: 同じ会話があればスキップ。メッセージの引用と会話の質問の対象(aboutBookmarkId)は
     *   新しいブックマーク ID に付け替える(対象がバックアップに無ければ通常の会話にする)。
     *   分けた元の会話(branchedFromId)は、追加した会話の分だけ、この DB の会話の ID に付け替える(元が無ければ通常の会話にする)
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
        // スキップした会話の書き出し元の ID → この DB の ID(分けた元の付け替えだけに使う)
        val existingConversationIds = mutableMapOf<Long, Long>()
        var conversationsSkipped = 0
        for (conversation in data.conversations) {
            val existingId = findSameConversationId(conversation.title, conversation.createdAt)
            if (existingId != null) {
                existingConversationIds[conversation.id] = existingId
                conversationsSkipped++
                continue
            }
            conversationIds[conversation.id] = insertConversation(
                conversation.copy(
                    id = 0,
                    aboutBookmarkId = conversation.aboutBookmarkId?.let { bookmarkIds[it] },
                    branchedFromId = null,
                    branchedAt = null
                )
            )
        }
        // 元の会話が後に並んでいても付け替えられるよう、全部を入れてから紐付ける
        for (conversation in data.conversations) {
            val id = conversationIds[conversation.id] ?: continue
            val parentId = conversation.branchedFromId?.let { conversationIds[it] ?: existingConversationIds[it] } ?: continue
            updateConversationBranch(id, parentId, conversation.branchedAt)
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

        val categoryKeys = getAllCategoryNames().mapTo(HashSet()) { TagNames.key(it) }
        for (category in data.categories) {
            if (categoryKeys.add(TagNames.key(category.name))) insertCategory(category.copy(id = 0))
        }

        // スマートフォルダ: 同じ名前(大文字小文字・全角半角を区別しない)が今の DB にあれば追加しない。
        // タグは名前でこの DB のタグを探し(ID は書き出し元と違う)、無ければユーザーのタグとして作る
        val folderKeys = getAllSmartFolderNames().mapTo(HashSet()) { SmartFolderNames.key(it) }
        var smartFoldersAdded = 0
        for (folder in data.smartFolders) {
            if (!folderKeys.add(SmartFolderNames.key(folder.name))) continue
            val folderTagIds = folder.tagNames.map { TagNames.clean(it) }.filter { it.isNotEmpty() }.distinct().map { name ->
                findTagIdByName(name) ?: insertTag(TagEntity(name = name, isUserCreated = true)).also { tagsAdded++ }
            }
            if (insertSmartFolder(folder.toEntity(folderTagIds)) >= 0) smartFoldersAdded++
        }

        // 購読: 同じフィードの URL が今の DB にあれば追加しない。確認の状態は含まれないので、最初の確認から始める
        val feedUrls = getAllSubscriptionFeedUrls().toHashSet()
        var subscriptionsAdded = 0
        for (subscription in data.subscriptions) {
            if (!feedUrls.add(subscription.feedUrl)) continue
            if (insertSubscription(subscription.copy(id = 0)) >= 0) subscriptionsAdded++
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
            messagesAdded = messagesAdded,
            smartFoldersAdded = smartFoldersAdded,
            subscriptionsAdded = subscriptionsAdded
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

    @Query("DELETE FROM smart_folders")
    abstract suspend fun deleteAllSmartFolders()

    @Query("DELETE FROM subscriptions")
    abstract suspend fun deleteAllSubscriptions()

    /** ブックマーク・タグ・会話・クラウド API の使用量の記録をすべて削除する(外部キーの子から順に消す)。 */
    @Transaction
    open suspend fun deleteAll() {
        deleteAllMessages()
        deleteAllConversations()
        deleteAllCrossRefs()
        deleteAllTags()
        deleteAllBookmarks()
        deleteAllApiUsage()
        // スマートフォルダは、消えたタグ・ブックマークを前提にした条件なので、いっしょに消す
        deleteAllSmartFolders()
        // 購読も利用者のデータなので、いっしょに消す(確認の状態も含めて)
        deleteAllSubscriptions()
    }
}
