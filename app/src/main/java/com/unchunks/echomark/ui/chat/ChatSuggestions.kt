package com.unchunks.echomark.ui.chat

import com.unchunks.echomark.domain.bookmark.model.Bookmark

/** 空の会話で出す「質問の例」を作る。Android 依存なしの純粋関数。 */
object ChatSuggestions {

    /** 出す例の最大数。 */
    const val MAX_SUGGESTIONS = 4

    /** 例に使うタグ・カテゴリ名の最大文字数(長すぎるものはチップが崩れるので使わない)。 */
    private const val MAX_TOPIC_CHARS = 16

    /** 質問の例に使わない、意味の薄いカテゴリ。 */
    private val IGNORED_CATEGORIES = setOf("その他")

    /** 「このブックマークについて質問」のときの例。 */
    val forBookmark: List<String> = listOf("要約して", "重要なポイントは？", "関連する保存は？")

    private const val RECENT_SUMMARY = "最近保存した記事の要点は？"
    private val FALLBACKS = listOf("今週保存したものを振り返って", "保存したものをテーマ別に整理して")

    /**
     * 保存済みのブックマーク(新しい順)から例を作る。
     * よく付いているタグ(同数なら新しい方)を最大2つ、多いカテゴリを1つ使い、足りなければ汎用の例で埋める。
     */
    fun forLibrary(recentBookmarks: List<Bookmark>): List<String> {
        val suggestions = mutableListOf(RECENT_SUMMARY)

        topics(recentBookmarks.map { it.tags.distinct() })
            .take(2)
            .forEach { suggestions += "「$it」の保存をまとめて" }

        topics(recentBookmarks.map { listOfNotNull(it.category) }, ignored = IGNORED_CATEGORIES)
            .firstOrNull { it !in suggestions.joinToString() }
            ?.let { suggestions += "「$it」の保存から学べることは？" }

        suggestions += FALLBACKS
        return suggestions.distinct().take(MAX_SUGGESTIONS)
    }

    /** 出現回数の多い順(同数なら先に出たもの = 新しいブックマークのもの)に並べる。 */
    private fun topics(namesPerBookmark: List<List<String>>, ignored: Set<String> = emptySet()): List<String> {
        val counts = LinkedHashMap<String, Int>()
        namesPerBookmark.flatten()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_TOPIC_CHARS && it !in ignored }
            .forEach { counts[it] = (counts[it] ?: 0) + 1 }
        // sortedByDescending は安定ソートなので、同数なら挿入順(新しい順)を保つ
        return counts.entries.sortedByDescending { it.value }.map { it.key }
    }
}
