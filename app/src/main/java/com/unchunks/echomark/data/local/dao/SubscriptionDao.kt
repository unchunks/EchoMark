package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unchunks.echomark.data.local.entity.SubscriptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {

    /** 登録した順(古いものが先) */
    @Query("SELECT * FROM subscriptions ORDER BY createdAt, id")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY createdAt, id")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE enabled = 1 ORDER BY createdAt, id")
    suspend fun getEnabled(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun getById(id: Long): SubscriptionEntity?

    @Query("SELECT COUNT(*) FROM subscriptions")
    suspend fun count(): Int

    /** 同じフィードの URL が既にあれば入れずに -1 を返す */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(subscription: SubscriptionEntity): Long

    @Query("SELECT id FROM subscriptions WHERE feedUrl = :feedUrl LIMIT 1")
    suspend fun findIdByFeedUrl(feedUrl: String): Long?

    @Query("UPDATE subscriptions SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun delete(id: Long)

    /** 確認できたときの記録。見た記事の目印・条件付き取得の値を更新し、失敗の記録を消す */
    @Query(
        "UPDATE subscriptions SET title = :title, siteUrl = COALESCE(:siteUrl, siteUrl), lastCheckedAt = :checkedAt, " +
            "lastSuccessAt = :checkedAt, etag = :etag, lastModified = :lastModified, failureCount = 0, lastError = NULL, " +
            "seenKeys = :seenKeys WHERE id = :id"
    )
    suspend fun recordSuccess(
        id: Long,
        title: String,
        siteUrl: String?,
        checkedAt: Long,
        etag: String?,
        lastModified: String?,
        seenKeys: String
    )

    /** 変わっていなかったとき(304)の記録。見た記事の目印は変えない */
    @Query("UPDATE subscriptions SET lastCheckedAt = :checkedAt, lastSuccessAt = :checkedAt, failureCount = 0, lastError = NULL WHERE id = :id")
    suspend fun recordNotModified(id: Long, checkedAt: Long)

    /** 確認に失敗したときの記録 */
    @Query("UPDATE subscriptions SET lastCheckedAt = :checkedAt, failureCount = failureCount + 1, lastError = :error WHERE id = :id")
    suspend fun recordFailure(id: Long, checkedAt: Long, error: String)
}
