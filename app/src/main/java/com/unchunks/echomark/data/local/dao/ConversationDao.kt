package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.unchunks.echomark.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<ConversationEntity>>

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

    /** 会話を削除する。メッセージは外部キーの CASCADE で一緒に消える。 */
    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)
}
