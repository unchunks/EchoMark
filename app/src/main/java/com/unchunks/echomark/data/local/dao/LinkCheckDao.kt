package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.unchunks.echomark.data.local.entity.BookmarkWithTags
import com.unchunks.echomark.data.local.entity.LinkCheckEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LinkCheckDao {

    @Query("SELECT * FROM link_checks")
    suspend fun getAll(): List<LinkCheckEntity>

    @Query("SELECT * FROM link_checks WHERE bookmarkId = :bookmarkId")
    suspend fun get(bookmarkId: Long): LinkCheckEntity?

    @Query("SELECT * FROM link_checks WHERE bookmarkId = :bookmarkId")
    fun observe(bookmarkId: Long): Flow<LinkCheckEntity?>

    /** 状態を書く(同じブックマークの行は置き換える)。ブックマークが無い(削除済み)と外部キーで失敗するので、呼び出し側で確かめる */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LinkCheckEntity)

    @Query("UPDATE link_checks SET muted = :muted WHERE bookmarkId = :bookmarkId")
    suspend fun updateMuted(bookmarkId: Long, muted: Boolean): Int

    /** リンク切れと判断したブックマーク(新しく切れたものが先)。「確認しない」を選んだものは除く */
    @Transaction
    @Query(
        """
        SELECT b.* FROM bookmarks AS b
        INNER JOIN link_checks AS c ON c.bookmarkId = b.id
        WHERE c.deadAt IS NOT NULL AND c.muted = 0
        ORDER BY c.deadAt DESC, b.id
        """
    )
    fun observeDeadBookmarks(): Flow<List<BookmarkWithTags>>

    @Query("SELECT * FROM link_checks WHERE deadAt IS NOT NULL AND muted = 0")
    fun observeDead(): Flow<List<LinkCheckEntity>>

    @Query("DELETE FROM link_checks")
    suspend fun deleteAll()
}
