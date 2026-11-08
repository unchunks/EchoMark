package com.unchunks.echomark.domain.bookmark.model

/**
 * ブックマークに付けた自分のノート(#95)。AI の要約・本文とは別に持ち、再処理しても変わらない。
 * [quote] があれば、本文などから引用した文章に付けた注釈。
 */
data class BookmarkNote(
    val id: Long = 0,
    val bookmarkId: Long,
    val text: String,
    val quote: String? = null,
    val createdAt: Long,
    val updatedAt: Long = createdAt
) {
    /** 書いたあとに直した */
    val isEdited: Boolean get() = updatedAt > createdAt
}

/** ノートの文章の整え方(保存前に通す)。 */
object NoteText {
    /** ノートの文章の最大の長さ。バックアップや一覧が重くならないよう、これを超える分は切る */
    const val MAX_TEXT = 10_000

    /** 引用の最大の長さ */
    const val MAX_QUOTE = 2_000

    /** 前後の空白を除く。空なら null(空のノートは保存しない)。長すぎれば [MAX_TEXT] で切る */
    fun cleanText(text: String): String? = text.trim().takeIf { it.isNotEmpty() }?.take(MAX_TEXT)

    /** 前後の空白を除く。空なら null(引用なし)。長すぎれば [MAX_QUOTE] で切る */
    fun cleanQuote(quote: String?): String? = quote?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_QUOTE)
}
