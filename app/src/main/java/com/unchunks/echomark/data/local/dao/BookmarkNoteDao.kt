package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unchunks.echomark.data.local.entity.BookmarkNoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkNoteDao {

    /** ブックマークのノート(古い順。書いた順に読めるように) */
    @Query("SELECT * FROM bookmark_notes WHERE bookmarkId = :bookmarkId ORDER BY createdAt, id")
    fun observeByBookmark(bookmarkId: Long): Flow<List<BookmarkNoteEntity>>

    @Query("SELECT * FROM bookmark_notes WHERE bookmarkId = :bookmarkId ORDER BY createdAt, id")
    suspend fun getByBookmark(bookmarkId: Long): List<BookmarkNoteEntity>

    @Query("SELECT * FROM bookmark_notes WHERE id = :id")
    suspend fun getById(id: Long): BookmarkNoteEntity?

    /**
     * ノートを入れる。ブックマークが無い(削除済み)と外部キーの制約で失敗するので、呼び出し側で確かめる。
     * ID が 0 以外なら、その ID で入れる(元に戻すとき)。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: BookmarkNoteEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notes: List<BookmarkNoteEntity>)

    @Query("UPDATE bookmark_notes SET text = :text, quote = :quote, updatedAt = :updatedAt WHERE id = :id")
    suspend fun update(id: Long, text: String, quote: String?, updatedAt: Long)

    @Query("DELETE FROM bookmark_notes WHERE id = :id")
    suspend fun delete(id: Long)

    /** 重複を統合するとき、消す方のノートを残す方へ移す(消す方を削除すると連鎖して消えてしまうため) */
    @Query("UPDATE bookmark_notes SET bookmarkId = :toBookmarkId WHERE id IN (:ids)")
    suspend fun moveToBookmark(ids: List<Long>, toBookmarkId: Long)

    @Query("SELECT COUNT(*) FROM bookmark_notes WHERE bookmarkId = :bookmarkId")
    suspend fun countByBookmark(bookmarkId: Long): Int
}
