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
import com.unchunks.echomark.data.local.entity.SummaryHistoryEntity
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.ProcessingFailureReason
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage
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

    /** 要約を書き換える([edited] は手で直した要約か。AI が作り直したら false に戻す) */
    @Query("UPDATE bookmarks SET summary = :summary, summaryEdited = :edited WHERE id = :id")
    suspend fun setSummary(id: Long, summary: String, edited: Boolean)

    // ---- 要約の履歴(#21) ----

    @Insert
    suspend fun insertSummaryVersion(version: SummaryHistoryEntity): Long

    /** 新しい版から先に */
    @Query("SELECT * FROM summary_history WHERE bookmarkId = :bookmarkId ORDER BY createdAt DESC, id DESC")
    fun observeSummaryHistory(bookmarkId: Long): Flow<List<SummaryHistoryEntity>>

    @Query("SELECT * FROM summary_history WHERE id = :id")
    suspend fun getSummaryVersion(id: Long): SummaryHistoryEntity?

    /** [bookmarkId] の履歴を、新しい [keep] 版だけ残して古い版を消す */
    @Query(
        "DELETE FROM summary_history WHERE bookmarkId = :bookmarkId AND id NOT IN (" +
            "SELECT id FROM summary_history WHERE bookmarkId = :bookmarkId ORDER BY createdAt DESC, id DESC LIMIT :keep)"
    )
    suspend fun trimSummaryHistory(bookmarkId: Long, keep: Int)

    /** AI が決めたカテゴリを保存する。ユーザーが選んだカテゴリ([setCategoryByUser])は置き換えない。 */
    @Query("UPDATE bookmarks SET category = :category WHERE id = :id AND categoryEdited = 0")
    suspend fun updateCategory(id: Long, category: String)

    /** ユーザーが選んだカテゴリにする(再処理しても AI は置き換えない)。 */
    @Query("UPDATE bookmarks SET category = :category, categoryEdited = 1 WHERE id = :id")
    suspend fun setCategoryByUser(id: Long, category: String)

    /** カテゴリを AI に任せる状態に戻す(カテゴリは空になり、次の処理で AI が決める)。 */
    @Query("UPDATE bookmarks SET category = NULL, categoryEdited = 0 WHERE id = :id")
    suspend fun clearCategory(id: Long)

    @Query("UPDATE bookmarks SET aiStatus = :status WHERE id = :id")
    suspend fun updateAiStatus(id: Long, status: AiStatus)

    // ---- 処理の状態の移り変わり(ProcessingInfo の説明を参照) ----

    /** 処理待ちにする(保存・再処理・積み直し)。前の処理の段・日時・失敗の理由は消す。 */
    @Query(
        "UPDATE bookmarks SET aiStatus = 'PENDING', processingStage = NULL, queuedAt = :now, " +
            "processingStartedAt = NULL, stageStartedAt = NULL, processingFinishedAt = NULL, failureReason = NULL " +
            "WHERE id = :id"
    )
    suspend fun markQueued(id: Long, now: Long)

    /** 段を始める(処理中にする)。最初の段なら処理を始めた日時も入れる。 */
    @Query(
        "UPDATE bookmarks SET aiStatus = 'PROCESSING', processingStage = :stage, stageStartedAt = :now, " +
            "processingStartedAt = COALESCE(processingStartedAt, :now), processingFinishedAt = NULL " +
            "WHERE id = :id AND NOT (aiStatus = 'FAILED' AND COALESCE(failureReason, '') = 'CANCELLED')"
    )
    suspend fun startStage(id: Long, stage: ProcessingStage, now: Long)

    /**
     * 段を終え、次の段を待つ(処理待ちに戻す)。中断されたときも使う。
     * [stageStartedAt] に始めた段のままのときだけ変える(その後に別の処理が始めた段・書いた結果を上書きしない)。
     */
    @Query(
        "UPDATE bookmarks SET aiStatus = 'PENDING', processingStage = NULL, stageStartedAt = NULL " +
            "WHERE id = :id AND aiStatus = 'PROCESSING' AND stageStartedAt = :stageStartedAt"
    )
    suspend fun endStage(id: Long, stageStartedAt: Long)

    /** 再試行を待つ(処理待ちに戻し、直前の失敗の理由を残す)。 */
    @Query(
        "UPDATE bookmarks SET aiStatus = 'PENDING', processingStage = NULL, stageStartedAt = NULL, " +
            "failureReason = :reason WHERE id = :id AND NOT (aiStatus = 'FAILED' AND COALESCE(failureReason, '') = 'CANCELLED')"
    )
    suspend fun markRetrying(id: Long, reason: ProcessingFailureReason)

    /** 処理を終える(完了・失敗・準備待ち)。 */
    @Query(
        "UPDATE bookmarks SET aiStatus = :status, processingStage = NULL, stageStartedAt = NULL, " +
            "processingFinishedAt = :now, failureReason = :reason " +
            "WHERE id = :id AND NOT (aiStatus = 'FAILED' AND COALESCE(failureReason, '') = 'CANCELLED')"
    )
    suspend fun finishProcessing(id: Long, status: AiStatus, reason: ProcessingFailureReason?, now: Long)

    /**
     * [before] より前に始まった段のまま処理中になっているものを、処理待ちに戻す。
     * アプリのプロセスの開始日時を渡すと、前のプロセスで実行していて止まった段だけが対象になる
     * (ワークは同じプロセスで動くため、今のプロセスで始まった段は消さない)。
     */
    @Query(
        "UPDATE bookmarks SET aiStatus = 'PENDING', processingStage = NULL, stageStartedAt = NULL " +
            "WHERE aiStatus = 'PROCESSING' AND (stageStartedAt IS NULL OR stageStartedAt < :before)"
    )
    suspend fun resetProcessingStartedBefore(before: Long): Int

    /**
     * 処理をやめる(失敗にし、理由をユーザーがやめたことにする)。処理待ち・処理中のものだけを変える。
     * やめたものはワーカーが処理せず、状態も書き換えない(再処理で処理待ちに戻すまで)。
     */
    @Query(
        "UPDATE bookmarks SET aiStatus = 'FAILED', processingStage = NULL, stageStartedAt = NULL, " +
            "processingFinishedAt = :now, failureReason = 'CANCELLED' " +
            "WHERE id IN (:ids) AND aiStatus IN ('PENDING', 'PROCESSING')"
    )
    suspend fun cancelProcessing(ids: List<Long>, now: Long): Int

    // ---- 処理の状況の画面 ----

    /** 処理中・処理待ちのもの。処理中を先に、処理待ちは頼んだ順(古い順)に並べる */
    @Query(
        "SELECT * FROM bookmarks WHERE aiStatus IN ('PENDING', 'PROCESSING') " +
            "ORDER BY CASE aiStatus WHEN 'PROCESSING' THEN 0 ELSE 1 END, COALESCE(queuedAt, createdAt), id"
    )
    fun observeProcessingQueue(): Flow<List<BookmarkEntity>>

    /** 処理に失敗したもの(新しい順、[limit] 件まで) */
    @Query(
        "SELECT * FROM bookmarks WHERE aiStatus = 'FAILED' " +
            "ORDER BY COALESCE(processingFinishedAt, createdAt) DESC, id DESC LIMIT :limit"
    )
    fun observeRecentFailures(limit: Int): Flow<List<BookmarkEntity>>

    @Query("UPDATE bookmarks SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: Long, isFavorite: Boolean)

    @Query("UPDATE bookmarks SET isArchived = :isArchived WHERE id = :id")
    suspend fun updateArchived(id: Long, isArchived: Boolean)

    @Query("UPDATE bookmarks SET imageUrl = :imageUrl, siteName = :siteName WHERE id = :id")
    suspend fun updateLinkMetadata(id: Long, imageUrl: String?, siteName: String?)

    /** URL の本文を取得できた日時を記録する(再処理で取得し直すかの判断に使う) */
    @Query("UPDATE bookmarks SET contentFetchedAt = :fetchedAt WHERE id = :id")
    suspend fun updateContentFetchedAt(id: Long, fetchedAt: Long)

    /** 保存したファイルの情報を差し替える(リンク先からファイルをダウンロードしたときなど) */
    @Query(
        "UPDATE bookmarks SET filePath = :filePath, mimeType = :mimeType, fileName = :fileName, fileSize = :fileSize " +
            "WHERE id = :id"
    )
    suspend fun updateAttachment(id: Long, filePath: String?, mimeType: String?, fileName: String?, fileSize: Long?)

    /** ブックマークが参照している添付ファイルの相対パス(使われていないファイルの掃除用) */
    @Query("SELECT filePath FROM bookmarks WHERE filePath IS NOT NULL")
    suspend fun getAllFilePaths(): List<String>

    /** AI再処理用: 要約を消して処理待ちにする([markQueued] と同じく、前の処理の段・日時・失敗の理由も消す) */
    @Query(
        "UPDATE bookmarks SET summary = NULL, aiStatus = 'PENDING', processingStage = NULL, queuedAt = :now, " +
            "processingStartedAt = NULL, stageStartedAt = NULL, processingFinishedAt = NULL, failureReason = NULL " +
            "WHERE id = :id"
    )
    suspend fun resetAiResult(id: Long, now: Long)


    // 削除
    @Delete
    suspend fun delete(bookmark: BookmarkEntity)


    /** 保存済みブックマークの総数(アーカイブも含む)。一覧の「初回の空状態」の判定に使う */
    @Query("SELECT COUNT(*) FROM bookmarks")
    fun observeCount(): Flow<Int>


    @Query("SELECT COUNT(*) FROM bookmarks")
    suspend fun count(): Int

    // IDの取得
    @Query("SELECT id FROM bookmarks")
    suspend fun getAllIds(): List<Long>


    @Query("SELECT id FROM bookmarks WHERE aiStatus = :status")
    suspend fun getIdsByAiStatus(status: AiStatus): List<Long>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE id = :id)")
    suspend fun exists(id: Long): Boolean


    // ブックマークの取得
    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun getById(id: Long): BookmarkEntity?

    /** 再発見通知用: 指定時刻以前から開かれていないものを、古い順に取得する。 */
    @Query("SELECT * FROM bookmarks WHERE lastAccessedAt <= :threshold ORDER BY lastAccessedAt ASC LIMIT :limit")
    suspend fun getStale(threshold: Long, limit: Int): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<BookmarkEntity>

    // タグ付きブックマークの取得
    @Transaction
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAllWithTags(): Flow<List<BookmarkWithTags>>

    /**
     * 一覧用の観測クエリ(絞り込み・並べ替えつき)。
     * :archived はアーカイブ状態で絞る(NULL なら絞らない)、:favoriteOnly が true ならお気に入りだけ。
     * タグ: :tagIds が空でなければ、選んだタグそれぞれについて「そのタグか、階層でその下にあるタグ」が付いているかを数え、
     * :matchAll が 1 なら選んだタグ(存在するもの)のすべて(AND)、0 なら 1 つ以上(OR)を満たすものだけ。
     * 削除済みのタグが :tagIds に残っていても、存在しないものとして無視する。
     * カテゴリ: :categories が空でなければ、そのどれかであるものだけ(大文字小文字は区別しない)。
     * :tagCount・:categoryCount は :tagIds・:categories の個数(空のときの IN () を避けて、条件を使うかの判定に使う)。
     * :sortOrder は BookmarkSortOrder の name。並べ替えは CASE で切り替え、該当しない CASE は NULL になって順序に影響しない。
     * 同順位は新しい順。
     */
    @Transaction
    @Query("""
        WITH RECURSIVE selected(root, id) AS (
            SELECT id, id FROM tags WHERE id IN (:tagIds)
            UNION
            SELECT s.root, t.id FROM tags AS t INNER JOIN selected AS s ON t.parentId = s.id
        )
        SELECT * FROM bookmarks
        WHERE (:archived IS NULL OR isArchived = :archived)
        AND (:favoriteOnly = 0 OR isFavorite = 1)
        AND (:tagCount = 0 OR NOT EXISTS (SELECT 1 FROM selected) OR (
            SELECT COUNT(DISTINCT s.root) FROM bookmark_tag_cross_ref AS r
            INNER JOIN selected AS s ON s.id = r.tagId
            WHERE r.bookmarkId = bookmarks.id
        ) >= CASE WHEN :matchAll = 1 THEN (SELECT COUNT(DISTINCT root) FROM selected) ELSE 1 END)
        AND (:categoryCount = 0 OR category COLLATE NOCASE IN (:categories))
        ORDER BY
            CASE WHEN :sortOrder = 'OLDEST' THEN createdAt END ASC,
            CASE WHEN :sortOrder = 'RECENTLY_OPENED' THEN lastAccessedAt END DESC,
            CASE WHEN :sortOrder = 'TITLE' THEN title END COLLATE NOCASE ASC,
            createdAt DESC,
            id DESC
    """)
    fun observeFiltered(
        archived: Boolean?,
        favoriteOnly: Boolean,
        tagIds: List<Long>,
        tagCount: Int,
        matchAll: Boolean,
        categories: List<String>,
        categoryCount: Int,
        sortOrder: String
    ): Flow<List<BookmarkWithTags>>

    @Transaction
    @Query("SELECT * FROM bookmarks WHERE id = :id")
    fun observeByIdWithTags(id: Long): Flow<BookmarkWithTags?>

    @Transaction
    @Query("SELECT * FROM bookmarks WHERE id IN (:ids)")
    suspend fun getByIdsWithTags(ids: List<Long>): List<BookmarkWithTags>

    /**
     * キーワード検索(タイトル・要約・本文・ファイル名・URL・サイト名・タグ名の部分一致)。
     * :pattern は呼び出し側で LIKE 用にエスケープ済み(エスケープ文字は '\')の検索語(1語)。
     */
    @Transaction
    @Query("""
        SELECT * FROM bookmarks
        WHERE (
            title LIKE '%' || :pattern || '%' ESCAPE '\'
            OR summary LIKE '%' || :pattern || '%' ESCAPE '\'
            OR content LIKE '%' || :pattern || '%' ESCAPE '\'
            OR fileName LIKE '%' || :pattern || '%' ESCAPE '\'
            OR contentUri LIKE '%' || :pattern || '%' ESCAPE '\'
            OR siteName LIKE '%' || :pattern || '%' ESCAPE '\'
            OR id IN (
                SELECT r.bookmarkId FROM bookmark_tag_cross_ref AS r
                INNER JOIN tags AS t ON t.id = r.tagId
                WHERE t.name LIKE '%' || :pattern || '%' ESCAPE '\'
            )
        )
        ORDER BY createdAt DESC
    """)
    suspend fun searchByKeyword(pattern: String): List<BookmarkWithTags>
}
