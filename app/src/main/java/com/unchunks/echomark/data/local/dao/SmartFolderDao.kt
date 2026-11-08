package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unchunks.echomark.data.local.entity.SmartFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SmartFolderDao {

    /** 作った順(古いものが先)。並べ替えは今のところ無い */
    @Query("SELECT * FROM smart_folders ORDER BY createdAt, id")
    fun observeAll(): Flow<List<SmartFolderEntity>>

    @Query("SELECT * FROM smart_folders ORDER BY createdAt, id")
    suspend fun getAll(): List<SmartFolderEntity>

    @Query("SELECT * FROM smart_folders WHERE id = :id")
    suspend fun getById(id: Long): SmartFolderEntity?

    /** 同じ名前(大文字小文字・全角半角の違いを除く確認は呼び出し側)のフォルダが既にあれば IGNORE され、-1 を返す。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: SmartFolderEntity): Long

    @Query("UPDATE smart_folders SET name = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String)

    @Query(
        "UPDATE smart_folders SET filter = :filter, tagIds = :tagIds, tagMatch = :tagMatch, categories = :categories, " +
            "types = :types, period = :period, readState = :readState, sortOrder = :sortOrder WHERE id = :id"
    )
    suspend fun updateConditions(
        id: Long,
        filter: String,
        tagIds: String,
        tagMatch: String,
        categories: String,
        types: String,
        period: String?,
        readState: String?,
        sortOrder: String?
    )

    @Query("DELETE FROM smart_folders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM smart_folders")
    suspend fun deleteAll()
}
