package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkWithTags
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    // 重複発生時は置き換えてIDを返す
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long


    // 競合時は何もせず -1 を返す(URL重複用。REPLACEだとタグ参照がカスケード削除されるため使わない)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(bookmark: BookmarkEntity): Long

    @Query("SELECT * FROM bookmarks WHERE contentUri = :contentUri LIMIT 1")
    suspend fun getByContentUri(contentUri: String): BookmarkEntity?

    @Query("UPDATE bookmarks SET lastAccessedAt = :lastAccessedAt WHERE id = :id")
    suspend fun updateLastAccessedAt(id: Long, lastAccessedAt: Long)

    @Query("UPDATE bookmarks SET title = :title, content = :content WHERE id = :id")
    suspend fun updateTitleAndContent(id: Long, title: String, content: String?)


    // 更新
    @Update
    suspend fun update(bookmark: BookmarkEntity)

    @Query("UPDATE bookmarks SET summary = :summary WHERE id = :id")
    suspend fun updateSummary(id: Long, summary: String)

    @Query("UPDATE bookmarks SET category = :category WHERE id = :id")
    suspend fun updateCategory(id: Long, category: String)

    @Query("UPDATE bookmarks SET aiStatus = :status WHERE id = :id")
    suspend fun updateAiStatus(id: Long, status: AiStatus)


    // 削除
    @Delete
    suspend fun delete(bookmark: BookmarkEntity)


    // IDの取得
    @Query("SELECT id FROM bookmarks")
    suspend fun getAllIds(): List<Long>


    @Query("SELECT id FROM bookmarks WHERE aiStatus = :status")
    suspend fun getIdsByAiStatus(status: AiStatus): List<Long>


    // ブックマークの取得
    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun getById(id: Long): BookmarkEntity?

    @Query("SELECT * FROM bookmarks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<BookmarkEntity>


    // タグ付きブックマークの取得
    @Transaction
    @Query("""
        SELECT bookmarks.* FROM bookmarks
        INNER JOIN bookmark_tag_cross_ref ON bookmarks.id = bookmark_tag_cross_ref.bookmarkId
        WHERE bookmark_tag_cross_ref.tagId = :tagId
        ORDER BY bookmarks.createdAt DESC
    """)
    fun getByTag(tagId: Long): Flow<List<BookmarkWithTags>>

    @Transaction
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAllWithTags(): Flow<List<BookmarkWithTags>>

    @Transaction
    @Query("""
        SELECT * FROM bookmarks 
        WHERE title LIKE '%' || :query || '%' 
            OR summary LIKE '%' || :query || '%' 
        ORDER BY createdAt DESC
    """)
    fun searchWithTags(query: String): Flow<List<BookmarkWithTags>>
}
