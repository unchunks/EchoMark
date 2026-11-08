package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.unchunks.echomark.data.local.entity.BookmarkWithTags
import com.unchunks.echomark.data.local.entity.CollectionEntity
import com.unchunks.echomark.data.local.entity.CollectionItemEntity
import kotlinx.coroutines.flow.Flow

/** コレクションと、入れたブックマークの数(一覧用の1行)。 */
data class CollectionCountRow(
    val id: Long,
    val name: String,
    val description: String?,
    val createdAt: Long,
    val bookmarkCount: Int
)

@Dao
interface CollectionDao {

    /** 作った順(古いものが先)。件数つき */
    @Query(
        """
        SELECT c.id AS id, c.name AS name, c.description AS description, c.createdAt AS createdAt,
            COUNT(i.bookmarkId) AS bookmarkCount
        FROM collections AS c
        LEFT JOIN collection_items AS i ON i.collectionId = c.id
        GROUP BY c.id
        ORDER BY c.createdAt, c.id
        """
    )
    fun observeAllWithCount(): Flow<List<CollectionCountRow>>

    @Query("SELECT * FROM collections ORDER BY createdAt, id")
    suspend fun getAll(): List<CollectionEntity>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun getById(id: Long): CollectionEntity?

    @Query("SELECT * FROM collections WHERE id = :id")
    fun observeById(id: Long): Flow<CollectionEntity?>

    /** 同じ名前のコレクションが既にあれば IGNORE され、-1 を返す(大文字小文字・全角半角の違いを除く確認は呼び出し側)。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(collection: CollectionEntity): Long

    @Query("UPDATE collections SET name = :name, description = :description WHERE id = :id")
    suspend fun update(id: Long, name: String, description: String?)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: Long)

    // ---- 入れたブックマーク ----

    /** すでに入っていれば何もしない */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: CollectionItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<CollectionItemEntity>)

    @Query("DELETE FROM collection_items WHERE collectionId = :collectionId AND bookmarkId = :bookmarkId")
    suspend fun deleteItem(collectionId: Long, bookmarkId: Long)

    /** ブックマークが入っているコレクション(追加のシートでチェックを付ける用) */
    @Query("SELECT collectionId FROM collection_items WHERE bookmarkId = :bookmarkId")
    fun observeCollectionIdsOf(bookmarkId: Long): Flow<List<Long>>

    @Query("SELECT * FROM collection_items WHERE bookmarkId = :bookmarkId ORDER BY collectionId")
    suspend fun getItemsOfBookmark(bookmarkId: Long): List<CollectionItemEntity>

    @Query("SELECT * FROM collection_items WHERE collectionId = :collectionId ORDER BY addedAt, bookmarkId")
    suspend fun getItems(collectionId: Long): List<CollectionItemEntity>

    @Query("SELECT * FROM collection_items ORDER BY collectionId, addedAt, bookmarkId")
    suspend fun getAllItems(): List<CollectionItemEntity>

    /** コレクションのブックマーク(入れた順。タグつき) */
    @Transaction
    @Query(
        """
        SELECT b.* FROM bookmarks AS b
        INNER JOIN collection_items AS i ON i.bookmarkId = b.id
        WHERE i.collectionId = :collectionId
        ORDER BY i.addedAt, b.id
        """
    )
    fun observeBookmarks(collectionId: Long): Flow<List<BookmarkWithTags>>

    @Transaction
    @Query(
        """
        SELECT b.* FROM bookmarks AS b
        INNER JOIN collection_items AS i ON i.bookmarkId = b.id
        WHERE i.collectionId = :collectionId
        ORDER BY i.addedAt, b.id
        """
    )
    suspend fun getBookmarks(collectionId: Long): List<BookmarkWithTags>

    @Query("DELETE FROM collections")
    suspend fun deleteAll()
}
