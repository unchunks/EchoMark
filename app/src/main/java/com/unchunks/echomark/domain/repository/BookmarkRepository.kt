package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.model.Tag
import kotlinx.coroutines.flow.Flow

/** URLブックマーク保存の結果。isDuplicate=true なら既存のブックマークを再利用した */
data class SaveResult(val id: Long, val isDuplicate: Boolean)

/**
 * ハイブリッド検索の結果(一覧画面の表示用)。
 * @param semanticAvailable 意味(ベクトル)検索が使えたか。埋め込みモデルが使えずキーワードのみのときは false
 * @param semanticOnlyIds キーワードには一致せず、意味が近いことで見つかったブックマークの ID
 */
data class BookmarkSearchResult(
    val bookmarks: List<Bookmark>,
    val semanticAvailable: Boolean,
    val semanticOnlyIds: Set<Long> = emptySet()
)

interface BookmarkRepository {
    // Create / Save
    suspend fun saveBookmark(bookmark: Bookmark): Long
    suspend fun saveUrlBookmark(url: String, title: String?, memo: String?): SaveResult
    suspend fun saveBookmarkWithResult(bookmark: Bookmark): SaveResult
    /**
     * AI が付けたタグとして保存する。前回 AI が付けたタグは外して置き換え、ユーザーが付けたタグには触れない。
     * タグ名は表記ゆれ(大文字小文字・全角半角)の範囲で既存のタグにそろえる。
     */
    suspend fun saveAiTags(bookmarkId: Long, tagNames: List<String>)
    suspend fun saveEmbedding(bookmarkId: Long, vector: FloatArray, modelVersion: String)

    // Update
    suspend fun updateSummary(id: Long, summary: String)
    suspend fun updateCategory(id: Long, category: String)
    suspend fun updateTitleAndContent(id: Long, title: String, content: String?)
    suspend fun updateAiStatus(id: Long, status: AiStatus)

    /** 削除の取り消し用。同じ ID・タグで復元し、埋め込みを再生成する。 */
    suspend fun restoreBookmark(bookmark: Bookmark)
    /** ユーザーがタグを付ける。AI が付けていたタグなら、ユーザーが付けたものに変える(再処理で外れなくなる)。 */
    suspend fun addTag(bookmarkId: Long, tagName: String)
    /** タグを外す。AI だけが付けていたタグで、どのブックマークにも付かなくなったらタグ自体も消す。 */
    suspend fun removeTag(bookmarkId: Long, tagName: String)
    /** 詳細画面を開いたときに lastAccessedAt を現在時刻に更新する。 */
    suspend fun markAccessed(id: Long)
    suspend fun setFavorite(id: Long, isFavorite: Boolean)
    suspend fun setArchived(id: Long, isArchived: Boolean)
    /** リンク先から取得した OG 画像 URL・サイト名を保存する(取得できなかった項目は null)。 */
    suspend fun updateLinkMetadata(id: Long, imageUrl: String?, siteName: String?)

    // AI 処理
    /** 要約をクリアして AI 処理(要約・タグ・埋め込み)をやり直す。 */
    suspend fun reprocess(id: Long)
    /** モデル未取得で待機中(WAITING_MODEL)のブックマークの AI 処理を再度キューに積む。 */
    suspend fun enqueueWaitingModelProcessing()
    /**
     * 失敗(FAILED)・準備待ち(WAITING_MODEL)のブックマークの AI 処理をまとめてやり直す。
     * API キーの設定後などに使う。キューに積んだ件数を返す。
     */
    suspend fun enqueueFailedAndWaitingProcessing(): Int
    /**
     * 中断された AI 処理の状態を、処理中(PROCESSING)から処理待ち(PENDING)に戻す。
     * 既に別の処理が状態を書き換えていれば(完了・失敗など)上書きしない。
     */
    suspend fun markProcessingInterrupted(id: Long)
    /**
     * 処理待ち・処理中のまま、対応するワークが残っていない(取り消された・失われた)ブックマークの AI 処理を積み直す。
     * 状態が「AI処理中…」のまま終わらなくなるのを防ぐため、アプリの起動時に呼ぶ。積み直した件数を返す。
     */
    suspend fun enqueueStalledProcessing(): Int

    // Delete
    suspend fun deleteBookmark(bookmark: Bookmark)

    // Read
    suspend fun getBookmarkById(id: Long): Bookmark?
    suspend fun getBookmarksByIds(ids: List<Long>): List<Bookmark>
    /** [threshold] (epoch millis) 以前から開かれていないブックマークを、最終アクセスが古い順に返す。再発見通知用。 */
    suspend fun getStaleBookmarks(threshold: Long, limit: Int): List<Bookmark>
    suspend fun getAllBookmarkIds(): List<Long>
    suspend fun getRelatedBookmarks(bookmarkId: Long, limit: Int = 5): List<Bookmark>
    suspend fun getEmbeddingModelVersion(bookmarkId: Long): String?
    /**
     * AI に伝える既存のタグ名(ユーザーのタグ → よく使われている順)。似たタグを増やさず使い回させるために使う。
     * プロンプトに入れる量は各 LlmProvider がさらに絞る。
     */
    suspend fun getTagNamesForAi(): List<String>

    // Observe / Search
    fun observeBookmarks(): Flow<List<Bookmark>>
    fun observeBookmarksByTag(tagId: Long): Flow<List<Bookmark>>
    fun observeAllTags(): Flow<List<Tag>>
    fun observeBookmark(id: Long): Flow<Bookmark?>

    /**
     * 一覧用の観測。[filter] で通常/アーカイブ/お気に入りを切り替え、[sortOrder] で並べ替える。
     * [tagId] が非NULLならそのタグを持つものに絞る。
     * (既存の [observeBookmarks] はアーカイブも含めた全件を新しい順で返す)
     */
    fun observeBookmarks(
        filter: BookmarkFilter,
        sortOrder: BookmarkSortOrder = BookmarkSortOrder.NEWEST,
        tagId: Long? = null
    ): Flow<List<Bookmark>>

    /**
     * ハイブリッド検索。キーワード(LIKE)とベクトル類似の結果を RRF で統合して返す。
     * tagId が非NULLならそのタグを持つものに絞る(AND)。埋め込みが使えない場合はキーワードのみ。
     */
    suspend fun search(query: String, tagId: Long?): List<Bookmark>

    /** [search] と同じ検索をして、意味検索が使えたか・意味だけで見つかったものも返す。 */
    suspend fun searchWithDetails(query: String, tagId: Long?): BookmarkSearchResult

    /** 保存済みブックマークの総数(アーカイブも含む)。 */
    fun observeBookmarkCount(): Flow<Int>
}
