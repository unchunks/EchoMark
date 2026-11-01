package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.bookmark.duplicates.BookmarkMerge
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicatePair
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicateReason
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicateSensitivity
import com.unchunks.echomark.domain.bookmark.model.Bookmark

/** 重複の候補1組([first] と [second])。おすすめの残し方は [suggestedKeepId]。 */
data class DuplicateMatch(
    val first: Bookmark,
    val second: Bookmark,
    val reason: DuplicateReason,
    /** [DuplicateReason.SIMILAR_CONTENT] のときの距離(URL の一致では null) */
    val distance: Double?,
    val suggestedKeepId: Long
) {
    val pair: DuplicatePair get() = DuplicatePair.of(first.id, second.id)
}

/** 関連するまとまり([bookmarks] は古い順)。[label] はまとまりの名前。 */
data class RelatedGroup(val label: String, val bookmarks: List<Bookmark>)

/**
 * 見つけた結果。[embeddedCount] / [totalCount] は検索用の索引ができている件数と全体の件数で、
 * 索引が全部ではないとき([isComplete] が false)は、見つからなかった組があるかもしれないと知らせる。
 */
data class DuplicateScan(
    val duplicates: List<DuplicateMatch>,
    val groups: List<RelatedGroup>,
    val embeddedCount: Int,
    val totalCount: Int
) {
    val isComplete: Boolean get() = embeddedCount >= totalCount
}

/** 統合をやり直す(元に戻す)ための記録。 */
data class MergeUndo(
    /** 統合前の、残した方 */
    val keptBefore: Bookmark,
    /** 統合で消した方 */
    val removed: Bookmark,
    /** 残した方に足したタグ(元に戻すときに外す) */
    val addedTags: List<String>,
    /** 残した方へ移したノートの ID(元に戻すときに消した方へ戻す) */
    val movedNoteIds: List<Long> = emptyList()
)

/**
 * 重複するブックマークの検出と整理(#93)、関連するブックマークのまとまり(#35)。
 * 自動では何も消さない。統合は利用者が選んだときだけで、元に戻せる([undoMerge])。
 */
interface DuplicateRepository {

    /**
     * 重複の候補と関連するまとまりを探す。URL が同じもの(一覧に出ているもの全部)と、埋め込みが近いもの
     * (現在の埋め込みモデルの版のベクトルだけ)を、[sensitivity] のしきい値で探す。
     * 「両方残す」と答えた組は除く。埋め込みが使えない・未作成のブックマークは、URL の一致だけで探す。
     */
    suspend fun scan(sensitivity: DuplicateSensitivity): DuplicateScan

    /** 「両方残す」: この組を、次から重複の候補に出さない。 */
    suspend fun keepBoth(pair: DuplicatePair)

    /**
     * [removeId] を [keepId] に統合する。[removeId] の情報のうち [keepId] に無いものを足し([BookmarkMerge])、
     * [removeId] を削除する。どちらかが無ければ null(何もしない)。
     */
    suspend fun merge(keepId: Long, removeId: Long): MergeUndo?

    /** [merge] を元に戻す: 消したブックマークを戻し、残した方を統合前の状態にする。 */
    suspend fun undoMerge(undo: MergeUndo)
}
