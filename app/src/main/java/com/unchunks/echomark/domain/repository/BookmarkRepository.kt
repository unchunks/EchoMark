package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.model.Tag
import kotlinx.coroutines.flow.Flow

/** URLブックマーク保存の結果。isDuplicate=true なら既存のブックマークを再利用した */
data class SaveResult(val id: Long, val isDuplicate: Boolean)

interface BookmarkRepository {
    // Create / Save
    suspend fun saveBookmark(bookmark: Bookmark): Long
    suspend fun saveUrlBookmark(url: String, title: String?, memo: String?): SaveResult
    suspend fun saveBookmarkWithResult(bookmark: Bookmark): SaveResult
    suspend fun saveTags(bookmarkId: Long, tagNames: List<String>)
    suspend fun saveEmbedding(bookmarkId: Long, vector: FloatArray, modelVersion: String)

    // Update
    suspend fun updateSummary(id: Long, summary: String)
    suspend fun updateCategory(id: Long, category: String)
    suspend fun updateTitleAndContent(id: Long, title: String, content: String?)
    suspend fun updateAiStatus(id: Long, status: AiStatus)

    /** 削除の取り消し用。同じ ID・タグで復元し、埋め込みを再生成する。 */
    suspend fun restoreBookmark(bookmark: Bookmark)
    suspend fun addTag(bookmarkId: Long, tagName: String)
    suspend fun removeTag(bookmarkId: Long, tagName: String)
    /** 詳細画面を開いたときに lastAccessedAt を現在時刻に更新する。 */
    suspend fun markAccessed(id: Long)

    // AI 処理
    /** 要約をクリアして AI 処理(要約・タグ・埋め込み)をやり直す。 */
    suspend fun reprocess(id: Long)
    /** モデル未取得で待機中(WAITING_MODEL)のブックマークの AI 処理を再度キューに積む。 */
    suspend fun enqueueWaitingModelProcessing()

    // Delete
    suspend fun deleteBookmark(bookmark: Bookmark)

    // Read
    suspend fun getBookmarkById(id: Long): Bookmark?
    suspend fun getBookmarksByIds(ids: List<Long>): List<Bookmark>
    suspend fun getAllBookmarkIds(): List<Long>
    suspend fun getRelatedBookmarks(bookmarkId: Long, limit: Int = 5): List<Bookmark>
    suspend fun getEmbeddingModelVersion(bookmarkId: Long): String?

    // Observe / Search
    fun observeBookmarks(): Flow<List<Bookmark>>
    fun observeBookmarksByTag(tagId: Long): Flow<List<Bookmark>>
    fun observeAllTags(): Flow<List<Tag>>
    fun observeBookmark(id: Long): Flow<Bookmark?>

    /**
     * ハイブリッド検索。キーワード(LIKE)とベクトル類似の結果を RRF で統合して返す。
     * tagId が非NULLならそのタグを持つものに絞る(AND)。埋め込みが使えない場合はキーワードのみ。
     */
    suspend fun search(query: String, tagId: Long?): List<Bookmark>
}
