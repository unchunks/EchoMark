package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.unchunks.echomark.data.local.entity.OfflineVideoEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class OfflineVideoDao {
    @Query("SELECT * FROM offline_videos WHERE bookmarkId = :bookmarkId")
    abstract fun observe(bookmarkId: Long): Flow<OfflineVideoEntity?>

    @Query("SELECT * FROM offline_videos WHERE bookmarkId = :bookmarkId")
    abstract suspend fun get(bookmarkId: Long): OfflineVideoEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfAbsent(entity: OfflineVideoEntity): Long

    @Query("UPDATE offline_videos SET thumbnailPath = :thumbnailPath, savedAt = :savedAt WHERE bookmarkId = :bookmarkId")
    protected abstract suspend fun updateThumbnail(bookmarkId: Long, thumbnailPath: String?, savedAt: Long)

    /**
     * サムネイルを結びつけ、保存した日時を [savedAt] にする(行が無ければ作る)。動画のファイルの記録には触れない。
     * ブックマークが無ければ何もせず false(削除された後に保存を終えたとき)。
     */
    @Transaction
    open suspend fun saveThumbnail(bookmarkId: Long, thumbnailPath: String?, savedAt: Long): Boolean {
        if (!bookmarkExists(bookmarkId)) return false
        insertIfAbsent(OfflineVideoEntity(bookmarkId = bookmarkId, savedAt = savedAt))
        updateThumbnail(bookmarkId, thumbnailPath, savedAt)
        return true
    }

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE id = :bookmarkId)")
    protected abstract suspend fun bookmarkExists(bookmarkId: Long): Boolean

    @Query("DELETE FROM offline_videos WHERE bookmarkId = :bookmarkId")
    abstract suspend fun delete(bookmarkId: Long)

    /** 保存したサムネイルの置き場所(添付ファイルの掃除で、参照されているものとして残す) */
    @Query("SELECT thumbnailPath FROM offline_videos WHERE thumbnailPath IS NOT NULL")
    abstract suspend fun getAllThumbnailPaths(): List<String>

    /** ブックマークが無くなった行を消す。消した行を返す(呼び出し側でファイルを掃除する) */
    @Transaction
    open suspend fun deleteOrphans(): List<OfflineVideoEntity> {
        val orphans = getOrphans()
        if (orphans.isNotEmpty()) deleteOrphanRows()
        return orphans
    }

    @Query("SELECT * FROM offline_videos WHERE bookmarkId NOT IN (SELECT id FROM bookmarks)")
    protected abstract suspend fun getOrphans(): List<OfflineVideoEntity>

    @Query("DELETE FROM offline_videos WHERE bookmarkId NOT IN (SELECT id FROM bookmarks)")
    protected abstract suspend fun deleteOrphanRows()
}
