package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.domain.model.ChatRole
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

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

    /** 指定ロールの最初の発言(自動タイトル生成用)。 */
    @Query(
        "SELECT * FROM chat_messages WHERE conversationId = :conversationId AND role = :role " +
            "ORDER BY createdAt ASC, id ASC LIMIT 1"
    )
    suspend fun getFirstByRole(conversationId: Long, role: ChatRole): ChatMessageEntity?
}
