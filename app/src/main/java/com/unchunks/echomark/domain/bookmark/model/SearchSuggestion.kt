package com.unchunks.echomark.domain.bookmark.model

import java.text.Normalizer

/** 検索欄の下に出す候補(#55)。 */
sealed interface SearchSuggestion {
    /** 前に検索した語。選ぶと、その検索語で検索する */
    data class History(val query: String) : SearchSuggestion

    /** 入力中の語に合うタグ。選ぶと、その語をタグの絞り込みに置き換える */
    data class Tag(val id: Long, val name: String) : SearchSuggestion

    /** 入力中の語に合うカテゴリ。選ぶと、その語をカテゴリの絞り込みに置き換える */
    data class Category(val name: String) : SearchSuggestion
}

/**
 * 検索の履歴を残す・並べる。純粋関数(JVM 単体テスト可能)。
 * 履歴は新しい順で、同じ検索語(空白・大文字小文字・全角半角の違いを除いて同じもの)は1つにまとめる。
 */
object SearchHistory {
    const val MAX_ENTRIES = 20

    /** 履歴に残す検索語の最大の長さ(長い文章を貼り付けて検索したものまでは残さない) */
    const val MAX_QUERY_CHARS = 60

    /** 履歴に残す形にする(前後の空白を除き、語の間の空白は半角1つにする)。残さないもの(空・長すぎる)は null */
    fun normalize(query: String): String? {
        val terms = splitSearchTerms(query)
        if (terms.isEmpty()) return null
        return terms.joinToString(" ").takeIf { it.length <= MAX_QUERY_CHARS }
    }

    /** [history] の先頭に [query] を足す。同じ検索語がすでにあれば前に出す。残さない検索語ならそのまま返す */
    fun add(history: List<String>, query: String): List<String> {
        val entry = normalize(query) ?: return history
        val key = matchKey(entry)
        return (listOf(entry) + history.filter { matchKey(it) != key }).take(MAX_ENTRIES)
    }

    fun remove(history: List<String>, query: String): List<String> {
        val key = matchKey(query)
        return history.filter { matchKey(it) != key }
    }

    internal fun matchKey(text: String): String = fold(text).replace(" ", "")
}

/** 検索欄に入力中の語から、履歴・タグ・カテゴリの候補を作る。純粋関数(JVM 単体テスト可能)。 */
object SearchSuggester {
    const val MAX_HISTORY = 4
    const val MAX_TAGS = 4
    const val MAX_CATEGORIES = 2

    /** 検索語が空のときに出す履歴の最大数 */
    const val MAX_RECENT = 10

    /**
     * 候補を返す。
     * - 入力が空なら、最近の検索([MAX_RECENT] 件まで)。
     * - 入力があれば、入力を含む履歴(入力そのものは除く)と、入力中の語(最後の語)に合うタグ・カテゴリ。
     *   最後の語の後ろに空白があるとき(その語は打ち終わっている)は、タグ・カテゴリは出さない。
     *   すでに選んでいるタグ・カテゴリと、件数が 0 のタグは出さない。前方一致を先に、同じなら件数の多い順。
     * @param tags 候補にするタグ(ID・名前・件数)
     * @param categories 候補にするカテゴリの名前
     */
    fun suggest(
        input: String,
        history: List<String>,
        tags: List<TagCandidate>,
        categories: List<String>,
        selectedTagIds: Set<Long> = emptySet(),
        selectedCategories: Set<String> = emptySet()
    ): List<SearchSuggestion> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return history.take(MAX_RECENT).map { SearchSuggestion.History(it) }

        val key = SearchHistory.matchKey(trimmed)
        val historySuggestions = history
            .filter { SearchHistory.matchKey(it).let { entry -> entry != key && entry.contains(key) } }
            .take(MAX_HISTORY)
            .map { SearchSuggestion.History(it) }

        val stillTyping = !input.last().isWhitespace()
        val last = if (stillTyping) fold(splitSearchTerms(input).last()) else ""
        if (last.isEmpty()) return historySuggestions

        val tagSuggestions = tags
            .filter { it.id !in selectedTagIds && it.bookmarkCount > 0 && fold(it.name).contains(last) }
            .sortedWith(compareByDescending<TagCandidate> { fold(it.name).startsWith(last) }.thenByDescending { it.bookmarkCount })
            .take(MAX_TAGS)
            .map { SearchSuggestion.Tag(it.id, it.name) }
        val categorySuggestions = categories
            .filter { name -> selectedCategories.none { it.equals(name, ignoreCase = true) } && fold(name).contains(last) }
            .sortedByDescending { fold(it).startsWith(last) }
            .take(MAX_CATEGORIES)
            .map { SearchSuggestion.Category(it) }
        return historySuggestions + tagSuggestions + categorySuggestions
    }

    /** 入力の最後の語を取り除く(タグ・カテゴリの候補を選んで、絞り込みに置き換えるとき)。語が1つだけなら空 */
    fun withoutLastTerm(input: String): String =
        input.trim().split(Regex("[\\s\\u3000]+")).dropLast(1).filter { it.isNotEmpty() }.joinToString(" ")
}

/** 候補にするタグ。 */
data class TagCandidate(val id: Long, val name: String, val bookmarkCount: Int)

/** 候補の照合用に、全角・半角をそろえて小文字にする。 */
private fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
