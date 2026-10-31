package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.data.local.entity.ConversationMatchRow
import com.unchunks.echomark.data.local.entity.ConversationWithLastMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Insert
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    /** 分けた会話と、写した発言を一緒に保存する。発言の ID は振り直す。新しい会話の ID を返す。 */
    @Transaction
    suspend fun insertBranch(conversation: ConversationEntity, messages: List<ChatMessageEntity>): Long {
        val id = insert(conversation)
        insertMessages(messages.map { it.copy(id = 0, conversationId = id) })
        return id
    }

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: Long): Flow<ConversationEntity?>

    /** [bookmarkId] について質問した会話のうち、最後に使ったもの。 */
    @Query("SELECT id FROM conversations WHERE aboutBookmarkId = :bookmarkId ORDER BY updatedAt DESC, id DESC LIMIT 1")
    suspend fun latestIdAbout(bookmarkId: Long): Long?

    /** [conversationId] から分けた会話(新しい順)。 */
    @Query("SELECT * FROM conversations WHERE branchedFromId = :conversationId ORDER BY createdAt DESC, id DESC")
    fun observeBranches(conversationId: Long): Flow<List<ConversationEntity>>

    /**
     * タイトルか発言に [pattern](LIKE。"\\" でエスケープ)を含む会話。発言に含むなら、その最新の発言を添える。
     */
    @Query(
        """
        SELECT c.id AS conversationId,
            (SELECT m.content FROM chat_messages m WHERE m.conversationId = c.id AND m.content LIKE :pattern ESCAPE '\'
                ORDER BY m.createdAt DESC, m.id DESC LIMIT 1) AS matchedMessage
        FROM conversations c
        WHERE c.title LIKE :pattern ESCAPE '\' OR matchedMessage IS NOT NULL
        """
    )
    suspend fun search(pattern: String): List<ConversationMatchRow>

    @Query("UPDATE conversations SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    /**
     * 会話一覧用。固定した会話を先に、それぞれ最終更新の新しい順に、各会話の最後のメッセージと質問の対象のブックマーク名を添えて返す。
     * スキーマを変えずに済むよう、相関サブクエリで最新の1件を引く(chat_messages.conversationId に索引あり)。
     * 対象のブックマークが削除済みなら aboutBookmarkTitle は null になる。
     */
    @Query(
        """
        SELECT c.*,
            (SELECT m.content FROM chat_messages m WHERE m.conversationId = c.id
                ORDER BY m.createdAt DESC, m.id DESC LIMIT 1) AS lastMessage,
            (SELECT m.role FROM chat_messages m WHERE m.conversationId = c.id
                ORDER BY m.createdAt DESC, m.id DESC LIMIT 1) AS lastMessageRole,
            (SELECT b.title FROM bookmarks b WHERE b.id = c.aboutBookmarkId) AS aboutBookmarkTitle
        FROM conversations c
        ORDER BY c.isPinned DESC, c.updatedAt DESC
        """
    )
    fun observeAllWithLastMessage(): Flow<List<ConversationWithLastMessage>>

    /** 最終更新時刻だけを更新する(メッセージ追加のたびに呼ぶ)。 */
    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    /** 手動リネームされていない会話にだけ自動タイトルを設定する。 */
    @Query(
        "UPDATE conversations SET title = :title, updatedAt = :updatedAt " +
            "WHERE id = :id AND isTitleManuallySet = 0"
    )
    suspend fun updateAutoTitle(id: Long, title: String, updatedAt: Long)

    /** 手動リネーム。以降は自動タイトルで上書きしない。 */
    @Query("UPDATE conversations SET title = :title, isTitleManuallySet = 1 WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    /**
     * 会話の要約を保存する。作っている間に別の要約が保存された、含めた最後の発言が消えた(回答の作り直し・質問の編集)ときは、
     * 古い内容を残さないよう保存しない。
     * @param coveredUntil 要約に含めた最後の発言の日時
     * @param previousCoveredUntil 作り始めたときの [ConversationEntity.summaryCoveredUntil]
     */
    @Query(
        """
        UPDATE conversations SET summary = :summary, summaryCoveredUntil = :coveredUntil
        WHERE id = :id AND summaryCoveredUntil IS :previousCoveredUntil
            AND EXISTS (SELECT 1 FROM chat_messages m WHERE m.conversationId = :id AND m.createdAt = :coveredUntil)
        """
    )
    suspend fun updateSummary(id: Long, summary: String, coveredUntil: Long, previousCoveredUntil: Long?)

    /** [createdAt] 以降の発言を含む要約を消す(消した発言を要約に残さない。次に要約を作るときに作り直す)。 */
    @Query("UPDATE conversations SET summary = NULL, summaryCoveredUntil = NULL WHERE id = :id AND summaryCoveredUntil >= :createdAt")
    suspend fun clearSummaryFrom(id: Long, createdAt: Long)

    /** 会話を削除する。メッセージは外部キーの CASCADE で一緒に消える。 */
    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)
}
