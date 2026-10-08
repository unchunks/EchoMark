package com.unchunks.echomark.domain.chat

import com.unchunks.echomark.domain.model.ChatMessage

/**
 * チャット(RAG)で使う純粋なヘルパー群。Android 依存なしで単体テストできる。
 */
object RagSupport {

    /**
     * 文脈として採用する最小の類似度(コサイン類似度)は埋め込みモデルごとに異なるため、
     * [com.unchunks.echomark.domain.provider.EmbeddingModelProfile.minRagSimilarity] を使う。
     * ObjectBox の COSINE は「類似度」ではなく距離(1 - cos類似度、0〜2)を返すため、
     * [distanceToSimilarity] で変換してから比較する。
     */

    /** 検索する近傍の最大件数。 */
    const val SEARCH_LIMIT = 5

    /** プロンプトに含める会話履歴の最大件数。 */
    const val HISTORY_LIMIT = 6

    /** 文脈1件あたりの本文の最大文字数。 */
    const val MAX_SNIPPET_CHARS = 300

    /** 自動タイトルの最大文字数。 */
    const val TITLE_MAX_CHARS = 30

    /** ObjectBox COSINE の距離をコサイン類似度へ変換する。 */
    fun distanceToSimilarity(distance: Double): Double = 1.0 - distance

    /**
     * (ID, 距離) のリストから、類似度が [minSimilarity] 以上のものだけを
     * 近い順に残して ID を返す。同一 ID は最初のものだけ残す。
     */
    fun selectRelevantIds(
        hits: List<Pair<Long, Double>>,
        minSimilarity: Double
    ): List<Long> = hits
        .filter { distanceToSimilarity(it.second) >= minSimilarity }
        .sortedBy { it.second }
        .map { it.first }
        .distinct()

    /** 引用しやすい "[n] タイトル: 要約or本文冒頭" 形式の文脈1件を作る。 */
    fun formatContextEntry(index: Int, title: String, body: String?, maxChars: Int = MAX_SNIPPET_CHARS): String {
        val text = body.orEmpty().trim().replace(Regex("\\s+"), " ").take(maxChars)
        return if (text.isEmpty()) "[$index] $title" else "[$index] $title: $text"
    }

    /** 「このブックマークについて質問」で固定する1件の本文の最大文字数(要約と本文を合わせて)。 */
    const val PINNED_SNIPPET_CHARS = 1000

    /** 質問の対象として固定したブックマークの印(文脈の先頭に付ける)。 */
    const val PINNED_MARK = "(質問の対象)"

    /**
     * 質問の対象として固定したブックマークの文脈1件。要約と本文の両方を、通常より長めに含める。
     * 例: "[1] (質問の対象) タイトル: 要約 本文: ..."
     */
    fun formatPinnedContextEntry(index: Int, title: String, summary: String?, content: String?): String {
        val body = listOfNotNull(
            summary?.takeIf { it.isNotBlank() },
            content?.takeIf { it.isNotBlank() && it != summary }?.let { "本文: $it" }
        ).joinToString(" ")
        return formatContextEntry(index, "$PINNED_MARK $title", body, PINNED_SNIPPET_CHARS)
    }

    /**
     * 固定したもの([pinned])を先頭に置き、検索結果([others])から重複を除いて続ける。合計 [limit] 件まで。
     */
    fun <T> pinFirst(pinned: T?, others: List<T>, idOf: (T) -> Long, limit: Int = SEARCH_LIMIT): List<T> {
        if (pinned == null) return others.distinctBy(idOf).take(limit)
        val pinnedId = idOf(pinned)
        return (listOf(pinned) + others.filter { idOf(it) != pinnedId }).distinctBy(idOf).take(limit)
    }

    /**
     * 固定したブックマークがあるときの検索クエリ。「要約して」のような短い質問でも
     * 関連するものが見つかるよう、対象のタイトルを添える。
     */
    fun searchQueryFor(userMessage: String, pinnedTitle: String?): String =
        if (pinnedTitle.isNullOrBlank()) userMessage else "$pinnedTitle $userMessage"

    /** 「このブックマークについて質問」で作る会話の既定タイトル。 */
    fun aboutBookmarkTitle(bookmarkTitle: String, maxChars: Int = TITLE_MAX_CHARS): String =
        titleFrom(bookmarkTitle, maxChars - ABOUT_SUFFIX.length) + ABOUT_SUFFIX

    private const val ABOUT_SUFFIX = "について"

    /** 履歴のうち直近 [limit] 件を古い順で返す。 */
    fun recentHistory(history: List<ChatMessage>, limit: Int = HISTORY_LIMIT): List<ChatMessage> =
        history.takeLast(limit)

    /** 最初のユーザー発言から自動タイトルを作る(改行は空白化し、超過分は省略記号)。 */
    fun titleFrom(firstUserMessage: String, maxChars: Int = TITLE_MAX_CHARS): String {
        val oneLine = firstUserMessage.trim().replace(Regex("\\s+"), " ")
        return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "…"
    }
}
