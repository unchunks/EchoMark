package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.unchunks.echomark.data.local.entity.ApiUsageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ApiUsageDao {
    @Insert
    suspend fun insert(usage: ApiUsageEntity)

    /** [from] 以降の記録(古い順) */
    @Query("SELECT * FROM api_usage WHERE createdAt >= :from ORDER BY createdAt, id")
    fun observeSince(from: Long): Flow<List<ApiUsageEntity>>

    /** [before] より古い記録を消す */
    @Query("DELETE FROM api_usage WHERE createdAt < :before")
    suspend fun deleteBefore(before: Long)
}
