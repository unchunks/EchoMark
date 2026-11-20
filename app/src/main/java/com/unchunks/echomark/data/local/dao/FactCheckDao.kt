package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unchunks.echomark.data.local.entity.FactCheckEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FactCheckDao {
    @Query("SELECT * FROM fact_checks WHERE targetType = :targetType AND targetId = :targetId")
    fun observe(targetType: String, targetId: Long): Flow<FactCheckEntity?>

    /** 同じ対象の前の結果は置き換える */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FactCheckEntity): Long

    /** 対象(ブックマーク・メッセージ)が消えた結果を消す */
    @Query(
        """
        DELETE FROM fact_checks WHERE
            (targetType = 'BOOKMARK_SUMMARY' AND targetId NOT IN (SELECT id FROM bookmarks)) OR
            (targetType = 'CHAT_ANSWER' AND targetId NOT IN (SELECT id FROM chat_messages))
        """
    )
    suspend fun deleteOrphans()
}
