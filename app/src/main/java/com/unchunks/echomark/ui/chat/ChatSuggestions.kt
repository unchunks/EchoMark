package com.unchunks.echomark.ui.chat

import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.model.ChatMessage

/** 空の会話で出す「質問の例」を作る。Android 依存なしの純粋関数。 */
object ChatSuggestions {

    /** 出す例の最大数。 */
    const val MAX_SUGGESTIONS = 4

    /** 例に使うタグ・カテゴリ名の最大文字数(長すぎるものはチップが崩れるので使わない)。 */
    private const val MAX_TOPIC_CHARS = 16

    /** 質問の例に使わない、意味の薄いカテゴリ。 */
    private val IGNORED_CATEGORIES = setOf("その他")

    /** 回答の後に出す、次の質問の候補の最大数。 */
    const val MAX_FOLLOW_UPS = 3

    /** 候補に入れるタイトルの最大文字数 */
    private const val MAX_FOLLOW_UP_TITLE_CHARS = 14

    private const val COMPARE_SOURCES = "引用した保存の共通点と違いは？"
    private const val MORE_DETAIL = "もっと詳しく教えて"
    private const val EXAMPLE = "具体例を挙げて"
    private const val NEXT_STEP = "次にやるとよいことは？"

    /**
     * 最後の回答に続く質問の候補。AI を待たせないよう、回答が引用した保存から作る。最後が回答でなければ空。
     * 引用した保存を比べる・掘り下げる質問、そのタグの別の保存を聞く質問、汎用の質問の順に、まだ聞いていないものを選ぶ。
     * @param isAboutBookmark 「このブックマークについて質問」の会話か(対象の外の保存を勧めない)
     */
    fun forAnswer(
        messages: List<ChatMessage>,
        review: AnswerReview?,
        bookmarks: Map<Long, Bookmark>,
        isAboutBookmark: Boolean
    ): List<String> {
        if (messages.lastOrNull()?.role != ChatRole.ASSISTANT) return emptyList()
        val cited = review?.sources.orEmpty().mapNotNull { bookmarks[it.bookmarkId] }
        val candidates = buildList {
            if (!isAboutBookmark) {
                if (cited.size >= 2) add(COMPARE_SOURCES)
                cited.firstOrNull()?.let { add("「${shortTitle(it.title)}」について詳しく") }
                topics(cited.map { it.tags.distinct() }).firstOrNull()?.let { add("「$it」の他の保存は？") }
            } else {
                addAll(forBookmark)
            }
            add(EXAMPLE)
            add(MORE_DETAIL)
            add(NEXT_STEP)
        }
        val asked = messages.filter { it.role == ChatRole.USER }.mapTo(HashSet()) { it.content.trim() }
        return candidates.filter { it !in asked }.distinct().take(MAX_FOLLOW_UPS)
    }

    private fun shortTitle(title: String): String {
        val trimmed = title.trim()
        return if (trimmed.length <= MAX_FOLLOW_UP_TITLE_CHARS) trimmed else trimmed.take(MAX_FOLLOW_UP_TITLE_CHARS) + "…"
    }

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
