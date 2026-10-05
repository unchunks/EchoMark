package com.unchunks.echomark.ui.bookmark

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
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

    /** 選んだタグ(と絞り込み)に当てはまるものが無い */
    NO_TAG_MATCH,

    /** 検索で1件も見つからない */
    NO_SEARCH_RESULT
}

data class BookmarkListUiState(
    val bookmarks: List<Bookmark> = emptyList(),
    val isLoading: Boolean = false,
    /** 読み込みに失敗したときのメッセージ(null なら正常) */
    val errorMessage: String? = null,
    /** 絞り込み用のタグ(ブックマークが付いているものだけ。選択中のタグを先頭にする) */
    val allTags: List<Tag> = emptyList(),
    val selectedTagId: Long? = null,
    val filter: BookmarkFilter = BookmarkFilter.ACTIVE,
    val sortOrder: BookmarkSortOrder = BookmarkSortOrder.NEWEST,
    /** 検索モード(トップバーが検索欄になっている)か */
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    /** 入力中で、表示中の結果がまだ今の検索語のものではない */
    val isSearching: Boolean = false,
    /** 意味(ベクトル)検索が使えているか。false ならキーワード検索のみ */
    val semanticSearchAvailable: Boolean = true,
    /** キーワードには一致せず、意味が近いことで見つかったもの */
    val semanticMatchIds: Set<Long> = emptySet(),
    /** 「今日の再発見」(しばらく開いていないもの)。空なら出さない */
    val rediscover: List<Bookmark> = emptyList(),
    /** 保存済みの総数(アーカイブも含む) */
    val totalCount: Int = 0
) {
    /** 検索語が入っていて、検索結果を表示している状態か */
    val isShowingSearchResults: Boolean get() = isSearchActive && searchQuery.isNotBlank()

    val selectedTag: Tag? get() = allTags.firstOrNull { it.id == selectedTagId }

    val showRediscover: Boolean
        get() = !isSearchActive && filter == BookmarkFilter.ACTIVE && selectedTagId == null && rediscover.isNotEmpty()

    val emptyKind: ListEmptyKind
        get() = when {
            isLoading || errorMessage != null || bookmarks.isNotEmpty() || isSearching -> ListEmptyKind.NONE
            isShowingSearchResults -> ListEmptyKind.NO_SEARCH_RESULT
            selectedTagId != null -> ListEmptyKind.NO_TAG_MATCH
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

    /** ファイルを保存した。[savedCount] 件を保存し、[failedCount] 件は保存できなかった */
    data class FilesSaved(val firstId: Long, val savedCount: Int, val failedCount: Int) : BookmarkListMessage
}
