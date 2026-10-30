package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.ProcessingFailureReason
import com.unchunks.echomark.domain.bookmark.model.ProcessingQueue
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage
import com.unchunks.echomark.domain.bookmark.model.StoredAttachment
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.EmbeddingProgress
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
    suspend fun saveBookmark(bookmark: Bookmark): SaveResult
    suspend fun saveUrlBookmark(url: String, title: String?, memo: String?): SaveResult
    /**
     * アプリ内に取り込んだファイル([StoredAttachment])をブックマークとして保存し、
     * 「中身の取り出し(OCR・文字起こしなど) → AI 処理」を登録する。
     * 種類(画像・PDF・音声・動画・テキスト)は MIME タイプから決める。[title] が空ならファイル名(拡張子なし)、
     * [memo] は本文の先頭に入れる。テキストファイルは中身をそのまま本文に入れる(取り出しの段は使わない)。
     * 保存できない形式なら [com.unchunks.echomark.domain.bookmark.model.AttachmentException]。
     */
    suspend fun saveFileBookmark(attachment: StoredAttachment, title: String?, memo: String?): SaveResult
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
    /** URL の本文(リンク先)を取得できたことを記録する。記録の無い URL は再処理で本文の取得からやり直す。 */
    suspend fun markContentFetched(id: Long, fetchedAt: Long)
    /** 保存したファイルの情報を差し替える(リンク先が PDF・画像などで、ダウンロードして保存したとき)。null ならファイルなしにする。 */
    suspend fun updateAttachment(id: Long, attachment: StoredAttachment?)

    // AI 処理
    /** 要約をクリアして AI 処理(要約・タグ・埋め込み)をやり直す。 */
    suspend fun reprocess(id: Long) = reprocess(id, AnalysisScope.ALL)
    /**
     * AI 処理をやり直す。[scope] が [AnalysisScope.ALL] なら要約をクリアして全部(本文の取得から)、
     * [AnalysisScope.SUMMARY] なら要約だけ、[AnalysisScope.TAGS] ならタグとカテゴリだけを作り直す
     * (部分の再処理は、今の本文のまま AI 処理だけを行い、ほかの結果には触れない)。
     */
    suspend fun reprocess(id: Long, scope: AnalysisScope)
    /** モデル未取得で待機中(WAITING_MODEL)のブックマークの AI 処理を再度キューに積む。 */
    suspend fun enqueueWaitingModelProcessing()
    /**
     * 失敗(FAILED)・準備待ち(WAITING_MODEL)のブックマークの AI 処理をまとめてやり直す。
     * API キーの設定後などに使う。キューに積んだ件数を返す。
     */
    suspend fun enqueueFailedAndWaitingProcessing(): Int

    // 処理の状態の移り変わり(ワーカーから呼ぶ。ProcessingInfo の説明を参照)
    /** 処理の状況(処理中・処理待ち・最近の失敗)。 */
    fun observeProcessingQueue(): Flow<ProcessingQueue>
    /** 処理をやめる(処理待ち・処理中のものだけ)。やめたものは失敗(理由はやめたこと)になり、再処理で戻せる。 */
    suspend fun cancelProcessing(ids: List<Long>)
    /** 処理待ちのものを今すぐ(順番待ちの列を飛ばして)処理する。夜間にまとめて処理する設定でもすぐ積む。 */
    suspend fun processNow(id: Long)
    /** まとめての処理の列を止め、列にあったものの処理をやめる。やめた件数を返す。 */
    suspend fun cancelBulkProcessing(): Int
    /** 実行中の段の進み具合(0〜1)。段が報告していない(ダウンロード・文字起こし以外)・実行中でなければ null。 */
    fun observeStageProgress(id: Long): Flow<Float?>
    /** 処理の段を始める(処理中にする)。始めた日時を返す。段を終えるとき・中断されたときに [endProcessingStage] へ渡す。 */
    suspend fun startProcessingStage(id: Long, stage: ProcessingStage): Long
    /**
     * 段を終え、次の段を待つ(処理待ちに戻す)。中断されたときも呼ぶ。
     * その後に別の処理が段を始めた・結果を書いたなら([stageStartedAt] が違えば)何もしない。
     */
    suspend fun endProcessingStage(id: Long, stageStartedAt: Long)
    /** 再試行を待つ(処理待ちに戻し、直前の失敗の理由を残す)。 */
    suspend fun markProcessingRetrying(id: Long, reason: ProcessingFailureReason)
    /** 処理を終える。[status] は完了・失敗・準備待ちのどれか。失敗・準備待ちなら [reason] に理由を渡す。 */
    suspend fun finishProcessing(id: Long, status: AiStatus, reason: ProcessingFailureReason? = null)
    /**
     * 処理待ち・処理中のまま止まっているブックマークの処理を積み直す。アプリの起動時に呼ぶ。積み直した件数を返す。
     * - [processStartedAt](このプロセスの開始日時)より前に始まった段のまま処理中のものは、実行しているワークが無いので処理待ちに戻す
     *   (プロセスが止められた・強制終了された)。ワークが残っていれば WorkManager が再実行し、段を書き直す
     * - 処理待ちのまま、対応するワークが残っていない(取り消された・失われた)ものは積み直す
     */
    suspend fun enqueueStalledProcessing(processStartedAt: Long): Int
    /**
     * 処理待ちのまま、ワークを積んでいないブックマーク(夜間にまとめて処理する設定で、保存したまま夜を待っているもの)を
     * 1件ずつ順番に処理する列に積む。[nightly] なら夜間の処理として充電中を条件にする。
     * 夜間にまとめて処理する設定のままで [nightly] でなければ積まない。積んだ件数を返す。
     */
    suspend fun enqueueWaitingProcessing(nightly: Boolean): Int

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

    /** 現在の埋め込みモデルの版で保存済みの件数とブックマークの総数。「検索インデックスを更新中 (x/y)」の表示用。 */
    suspend fun getEmbeddingProgress(): EmbeddingProgress
    /**
     * AI に伝える既存のタグ名(ユーザーのタグ → よく使われている順)。似たタグを増やさず使い回させるために使う。
     * プロンプトに入れる量は各 LlmProvider がさらに絞る。
     */
    suspend fun getTagNamesForAi(): List<String>

    // Observe / Search
    fun observeBookmarks(): Flow<List<Bookmark>>
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
     * キーワードは空白(全角を含む)で区切った語をすべて含むもの(AND)。
     * tagId が非NULLならそのタグを持つものに絞る(AND)。埋め込みが使えない場合はキーワードのみ。
     */
    suspend fun search(query: String, tagId: Long?): List<Bookmark>

    /**
     * キーワード(LIKE)だけの検索。タイトル・要約・本文・URL・タグ名などに [query] の語をすべて含むものを
     * 新しい順に最大 [limit] 件返す。
     * 意味検索(埋め込み)はしない(チャットの文脈の検索で、語ごとに何度も呼ぶため)。
     */
    suspend fun searchByKeyword(query: String, limit: Int): List<Bookmark> = search(query, null).take(limit)

    /**
     * [search] のキーワードの部分だけ。一覧の検索で、意味検索の準備(検索語の埋め込み)を待たずに先に出す結果。
     * [tagId] が非NULLならそのタグを持つものに絞る。
     */
    suspend fun searchKeywordOnly(query: String, tagId: Long?): List<Bookmark> = search(query, tagId)

    /**
     * 意味検索の準備として [query] を埋め込み、結果を覚えておく(続く [searchWithDetails] では埋め込み直さない)。
     * 埋め込みモデルが使えないときは false(検索はキーワードのみになる)。
     * 埋め込みはモデルの読み込みや AI 処理との順番待ちで時間がかかることがあるため、一覧はこれを待つ間キーワードの結果を出す。
     */
    suspend fun prepareSemanticSearch(query: String): Boolean = true

    /** [search] と同じ検索をして、意味検索が使えたか・意味だけで見つかったものも返す。 */
    suspend fun searchWithDetails(query: String, tagId: Long?): BookmarkSearchResult

    /** 保存済みブックマークの総数(アーカイブも含む)。 */
    fun observeBookmarkCount(): Flow<Int>
}
