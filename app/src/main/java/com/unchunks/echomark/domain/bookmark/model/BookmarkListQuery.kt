package com.unchunks.echomark.domain.bookmark.model

/** 一覧に表示するブックマークの絞り込み。 */
enum class BookmarkFilter {
    /** アーカイブしたものを除いたすべて(通常の一覧) */
    ACTIVE,
    /** アーカイブしたものだけ */
    ARCHIVED,
    /** お気に入りだけ(アーカイブしたものは除く) */
    FAVORITES
}

/** 一覧の並べ替え。 */
enum class BookmarkSortOrder {
    /** 保存日時が新しい順 */
    NEWEST,
    /** 保存日時が古い順 */
    OLDEST,
    /** 最近開いた順(lastAccessedAt の降順) */
    RECENTLY_OPENED,
    /** タイトル順(大文字小文字を区別しない昇順) */
    TITLE
}
