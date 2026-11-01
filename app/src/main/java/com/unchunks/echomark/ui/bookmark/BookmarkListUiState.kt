package com.unchunks.echomark.ui.bookmark

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkListDisplay
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.CreatedPeriod
import com.unchunks.echomark.domain.bookmark.model.ReadState
import com.unchunks.echomark.domain.bookmark.model.Recommendation
import com.unchunks.echomark.domain.bookmark.model.SearchMatch
import com.unchunks.echomark.domain.bookmark.model.SmartFolder
import com.unchunks.echomark.domain.bookmark.model.SearchSuggestion
import com.unchunks.echomark.domain.bookmark.model.TagMatch
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.ui.common.SelectedFile

/** 一覧が空のとき、どの理由で空なのか(空状態の文言を変えるため)。 */
enum class ListEmptyKind {
    /** 空ではない(または読み込み中・エラー) */
    NONE,

    /** まだ1件も保存していない(初回) */
    FIRST_RUN,

    /** 保存はしているが、すべてアーカイブ済みで通常の一覧が空 */
    ALL_ARCHIVED,

    /** お気に入りが無い */
    NO_FAVORITES,

    /** アーカイブが無い */
    NO_ARCHIVED,

    /** 選んだタグ・カテゴリ(と絞り込み)に当てはまるものが無い */
    NO_FILTER_MATCH,

    /** 検索で1件も見つからない */
    NO_SEARCH_RESULT
}

data class BookmarkListUiState(
    val bookmarks: List<Bookmark> = emptyList(),
    val isLoading: Boolean = false,
    /** 読み込みに失敗したときのメッセージ(null なら正常) */
    val errorMessage: String? = null,
    /** 絞り込み用のタグ(ブックマークが付いているものと、選択中のもの。選択中のタグを先頭にする) */
    val allTags: List<Tag> = emptyList(),
    /** 選んでいるタグ。複数のときは [tagMatch] で組み合わせる */
    val selectedTagIds: Set<Long> = emptySet(),
    val tagMatch: TagMatch = TagMatch.ALL,
    /** 絞り込み用のカテゴリ(ブックマークがあるものと、選択中のもの。選択中のカテゴリを先頭にする) */
    val allCategories: List<String> = emptyList(),
    /** 選んでいるカテゴリ(どれかであればよい) */
    val selectedCategories: Set<String> = emptySet(),
    /** 選んでいる種類(リンク・メモ・画像など。どれかであればよい) */
    val selectedTypes: Set<BookmarkType> = emptySet(),
    /** 保存した日からの期間(null なら絞らない) */
    val period: CreatedPeriod? = null,
    /** 読んだかどうか(null なら絞らない) */
    val readState: ReadState? = null,
    val filter: BookmarkFilter = BookmarkFilter.ACTIVE,
    val sortOrder: BookmarkSortOrder = BookmarkSortOrder.NEWEST,
    /** 一覧の見せ方(カード・コンパクト・グリッド) */
    val display: BookmarkListDisplay = BookmarkListDisplay.CARD,
    /** 検索モード(トップバーが検索欄になっている)か */
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    /** 検索語を ViewModel の側で書き換えた回数。検索欄が入力中の文字を取り込み直すきっかけ */
    val queryRevision: Int = 0,
    /** 検索欄の下に出す候補(入力が空なら最近の検索、入力があれば履歴・タグ・カテゴリ) */
    val suggestions: List<SearchSuggestion> = emptyList(),
    /** 結果に使った検索語(空白で分けたもの)。カードで一致した語を強調する */
    val searchTerms: List<String> = emptyList(),
    /** 検索結果それぞれが、どこに一致したか(ブックマークの ID → 一致した場所) */
    val searchMatches: Map<Long, SearchMatch> = emptyMap(),
    /** 入力中で、表示中の結果がまだ今の検索語のものではない */
    val isSearching: Boolean = false,
    /** 意味(ベクトル)検索が使えているか。false ならキーワード検索のみ */
    val semanticSearchAvailable: Boolean = true,
    /** キーワードには一致せず、意味が近いことで見つかったもの */
    val semanticMatchIds: Set<Long> = emptySet(),
    /** スマートフォルダ(保存した絞り込み条件)。作った順 */
    val smartFolders: List<SmartFolder> = emptyList(),
    /** 今の条件と同じ条件のスマートフォルダ(開いているフォルダ)。無ければ null */
    val activeSmartFolderId: Long? = null,
    /** 開いたフォルダの条件を変えたとき、その条件で上書きできるフォルダ。無ければ null */
    val smartFolderToUpdate: SmartFolder? = null,
    /** 「次に読む」(まだ読んでいないものから選んだおすすめ)。空なら出さない */
    val recommendations: List<Recommendation> = emptyList(),
    /** 「今日の再発見」(しばらく開いていないもの)。空なら出さない */
    val rediscover: List<Bookmark> = emptyList(),
    /** 保存済みの総数(アーカイブも含む) */
    val totalCount: Int = 0
) {
    /** 検索語が入っていて、検索結果を表示している状態か */
    val isShowingSearchResults: Boolean get() = isSearchActive && searchQuery.isNotBlank()

    /** 選んでいるタグ(絞り込み用のタグに無いものは含まない) */
    val selectedTags: List<Tag> get() = allTags.filter { it.id in selectedTagIds }

    /** タグ・カテゴリ・種類・期間のどれかで絞り込んでいる(「すべて解除」で外せる条件がある) */
    val isNarrowed: Boolean
        get() = selectedTagIds.isNotEmpty() || selectedCategories.isNotEmpty() || selectedTypes.isNotEmpty() || period != null || readState != null

    /** 今の条件を、スマートフォルダとして保存できるか(条件を付けていて、同じ条件のフォルダがまだ無い)。検索語は条件に含めない */
    val canSaveSmartFolder: Boolean
        get() = activeSmartFolderId == null && (filter != BookmarkFilter.ACTIVE || isNarrowed)

    val showRediscover: Boolean
        get() = !isSearchActive && filter == BookmarkFilter.ACTIVE && !isNarrowed && rediscover.isNotEmpty()

    /** 「次に読む」を出すか(通常の一覧で、絞り込み・検索をしていないとき) */
    val showRecommendations: Boolean
        get() = !isSearchActive && filter == BookmarkFilter.ACTIVE && !isNarrowed && recommendations.isNotEmpty()

    val emptyKind: ListEmptyKind
        get() = when {
            isLoading || errorMessage != null || bookmarks.isNotEmpty() || isSearching -> ListEmptyKind.NONE
            isShowingSearchResults -> ListEmptyKind.NO_SEARCH_RESULT
            isNarrowed -> ListEmptyKind.NO_FILTER_MATCH
            filter == BookmarkFilter.FAVORITES -> ListEmptyKind.NO_FAVORITES
            filter == BookmarkFilter.ARCHIVED -> ListEmptyKind.NO_ARCHIVED
            totalCount == 0 -> ListEmptyKind.FIRST_RUN
            else -> ListEmptyKind.ALL_ARCHIVED
        }
}

/** 追加シートから保存する内容。 */
sealed interface NewBookmarkInput {
    /** リンク。[title] が空ならページのタイトルを自動で取得する */
    data class Link(val url: String, val title: String = "", val memo: String = "") : NewBookmarkInput

    /** テキストのメモ。[title] が空なら本文の1行目をタイトルにする */
    data class Note(val text: String, val title: String = "") : NewBookmarkInput

    /** 端末のファイル(1件ずつ保存する)。[title] は1件のときだけ使い、空ならファイル名にする */
    data class Files(val files: List<SelectedFile>, val title: String = "") : NewBookmarkInput
}

/** 一覧画面に一度だけ伝える出来事(Snackbar で知らせる)。 */
sealed interface BookmarkListMessage {
    /** 削除した(元に戻せる) */
    data class Deleted(val bookmark: Bookmark) : BookmarkListMessage

    /** アーカイブした/アーカイブから戻した(元に戻せる) */
    data class ArchiveChanged(val bookmark: Bookmark, val archived: Boolean) : BookmarkListMessage

    /** 保存した。[isDuplicate] なら同じ URL が保存済みだった */
    data class Saved(val id: Long, val isDuplicate: Boolean, val isLink: Boolean) : BookmarkListMessage

    /** 保存できなかった */
    data class SaveFailed(val message: String) : BookmarkListMessage

    /** ファイルをアプリ内へコピーしている(大きなファイルは時間がかかる) */
    data class SavingFiles(val count: Int) : BookmarkListMessage

    /** スマートフォルダを保存した */
    data class SmartFolderSaved(val name: String) : BookmarkListMessage

    /** スマートフォルダの条件を、今の条件で置き換えた */
    data class SmartFolderUpdated(val name: String) : BookmarkListMessage

    /** スマートフォルダを削除した */
    data class SmartFolderDeleted(val name: String) : BookmarkListMessage

    /** スマートフォルダの保存・更新・削除ができなかった */
    data class SmartFolderFailed(val message: String) : BookmarkListMessage

    /** ファイルを保存した。[savedCount] 件を保存し、[failedCount] 件は保存できなかった */
    data class FilesSaved(val firstId: Long, val savedCount: Int, val failedCount: Int) : BookmarkListMessage
}
