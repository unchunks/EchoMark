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

/** 検索語の区切り(半角・全角の空白) */
private val SEARCH_TERM_SEPARATOR = Regex("[\\s\\u3000]+")

/**
 * 検索語を空白(半角・全角)で語に分ける。キーワード検索はすべての語を含むもの(AND)を探す。
 * 重複した語は1つにまとめ、空白だけなら空。
 */
fun splitSearchTerms(query: String): List<String> =
    query.split(SEARCH_TERM_SEPARATOR).filter { it.isNotEmpty() }.distinct()
