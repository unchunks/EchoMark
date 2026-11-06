package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unchunks.echomark.data.local.entity.ReviewCardEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewCardDao {

    /** 復習に入れたものすべて(次に復習する順) */
    @Query("SELECT * FROM review_cards ORDER BY dueAt, bookmarkId")
    fun observeAll(): Flow<List<ReviewCardEntity>>

    @Query("SELECT * FROM review_cards WHERE bookmarkId = :bookmarkId")
    suspend fun getByBookmark(bookmarkId: Long): ReviewCardEntity?

    /** 復習の日時が来たもの(早い順)。[limit] 件まで */
    @Query("SELECT * FROM review_cards WHERE dueAt <= :now ORDER BY dueAt, bookmarkId LIMIT :limit")
    suspend fun getDue(now: Long, limit: Int): List<ReviewCardEntity>

    /** 復習に入れる(すでに入っていれば置き換える。元に戻すときも使う)。ブックマークが無いと外部キーの制約で失敗する。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(card: ReviewCardEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cards: List<ReviewCardEntity>)

    @Query("DELETE FROM review_cards WHERE bookmarkId = :bookmarkId")
    suspend fun delete(bookmarkId: Long)

    /** 重複を統合するとき、消す方のカードを残す方へ移す(消す方を削除すると連鎖して消えてしまうため) */
    @Query("UPDATE review_cards SET bookmarkId = :toBookmarkId WHERE bookmarkId = :fromBookmarkId")
    suspend fun moveToBookmark(fromBookmarkId: Long, toBookmarkId: Long)

    @Query("SELECT * FROM review_cards ORDER BY bookmarkId")
    suspend fun getAll(): List<ReviewCardEntity>

    @Query("DELETE FROM review_cards")
    suspend fun deleteAll()
}
