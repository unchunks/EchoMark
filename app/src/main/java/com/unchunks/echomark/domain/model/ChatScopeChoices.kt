package com.unchunks.echomark.domain.model

import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkQuery

/**
 * チャットが参照する範囲を選ぶときの選択肢。
 * @property tags 選べるタグ(保存の多い順)
 * @property categories 選べるカテゴリの名前(保存の多い順)
 */
data class ChatScopeChoices(
    val tags: List<Tag> = emptyList(),
    val categories: List<String> = emptyList()
) {
    /** [query] の短い説明(入力欄の上に出す)。全部を対象にしているなら null。 */
    fun describe(query: BookmarkQuery): String? {
        if (query == ALL) return null
        val names = listOfNotNull(FILTER_LABELS[query.filter]) +
            tags.filter { it.id in query.tagIds }.map { it.name } +
            query.categories.sorted()
        return when {
            names.isEmpty() -> null
            names.size <= MAX_LABEL_NAMES -> names.joinToString("・")
            else -> names.take(MAX_LABEL_NAMES).joinToString("・") + " ほか${names.size - MAX_LABEL_NAMES}件"
        }
    }

    companion object {
        /** 絞り込まない範囲 */
        val ALL = BookmarkQuery()

        private const val MAX_LABEL_NAMES = 2

        private val FILTER_LABELS = mapOf(
            BookmarkFilter.FAVORITES to "お気に入り",
            BookmarkFilter.ARCHIVED to "アーカイブ"
        )
    }
}
