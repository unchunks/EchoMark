package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    /** 削除の取り消し用。ID を保ったまま戻す。 */
    @Insert
    suspend fun insertAll(messages: List<ChatMessageEntity>)

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeMessages(conversationId: Long): Flow<List<ChatMessageEntity>>

    /** 直近 [limit] 件のメッセージを古い順で返す(プロンプトの会話履歴用)。 */
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM chat_messages WHERE conversationId = :conversationId
            ORDER BY createdAt DESC, id DESC LIMIT :limit
        ) ORDER BY createdAt ASC, id ASC
        """
    )
    suspend fun getRecentMessages(conversationId: Long, limit: Int): List<ChatMessageEntity>

    /** [after] より後のメッセージを古い順に最大 [limit] 件返す(会話の要約に含める発言を選ぶ)。 */
    @Query(
        "SELECT * FROM chat_messages WHERE conversationId = :conversationId AND createdAt > :after " +
            "ORDER BY createdAt ASC, id ASC LIMIT :limit"
    )
    suspend fun getMessagesAfter(conversationId: Long, after: Long, limit: Int): List<ChatMessageEntity>

    /**
     * [createdAt]・[id] のメッセージと、それより後のメッセージを削除する(回答の作り直し・質問の編集で、そこから先を消す)。
     * 同じ時刻のメッセージは ID の順で前後を決める。
     */
    @Query(
        "DELETE FROM chat_messages WHERE conversationId = :conversationId " +
            "AND (createdAt > :createdAt OR (createdAt = :createdAt AND id >= :id))"
    )
    suspend fun deleteFrom(conversationId: Long, createdAt: Long, id: Long)

    /** 指定ロールの最初の発言(自動タイトル生成用)。 */
    @Query(
        "SELECT * FROM chat_messages WHERE conversationId = :conversationId AND role = :role " +
            "ORDER BY createdAt ASC, id ASC LIMIT 1"
    )
    suspend fun getFirstByRole(conversationId: Long, role: ChatRole): ChatMessageEntity?
}
