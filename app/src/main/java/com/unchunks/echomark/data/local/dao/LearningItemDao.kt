package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.unchunks.echomark.data.local.entity.LearningItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LearningItemDao {
    @Insert
    suspend fun insert(item: LearningItemEntity): Long

    @Query("SELECT * FROM learning_items WHERE id = :id")
    suspend fun getById(id: Long): LearningItemEntity?

    @Query("SELECT * FROM learning_items ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<LearningItemEntity>>
}
