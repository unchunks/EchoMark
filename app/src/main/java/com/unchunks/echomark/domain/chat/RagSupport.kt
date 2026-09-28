package com.unchunks.echomark.domain.chat

import com.unchunks.echomark.domain.model.ChatMessage

/**
 * チャット(RAG)で使う純粋なヘルパー群。Android 依存なしで単体テストできる。
 */
object RagSupport {

    /**
     * 文脈として採用する最小の類似度(コサイン類似度)。
     * ObjectBox の COSINE は「類似度」ではなく距離(1 - cos類似度、0〜2)を返すため、
     * [distanceToSimilarity] で変換してから比較する。
     * 埋め込みモデルによって分布が変わるので、実機で調整する前提の暫定値。
     */
    const val MIN_SIMILARITY = 0.5

    /** 検索する近傍の最大件数。 */
    const val SEARCH_LIMIT = 5

    /** プロンプトに含める会話履歴の最大件数。 */
    const val HISTORY_LIMIT = 6

    /** 文脈1件あたりの本文の最大文字数。 */
    const val MAX_SNIPPET_CHARS = 600

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
        minSimilarity: Double = MIN_SIMILARITY
    ): List<Long> = hits
        .filter { distanceToSimilarity(it.second) >= minSimilarity }
        .sortedBy { it.second }
        .map { it.first }
        .distinct()

    /** 引用しやすい "[n] タイトル: 要約or本文冒頭" 形式の文脈1件を作る。 */
    fun formatContextEntry(index: Int, title: String, body: String?): String {
        val text = body.orEmpty().trim().replace(Regex("\\s+"), " ").take(MAX_SNIPPET_CHARS)
        return if (text.isEmpty()) "[$index] $title" else "[$index] $title: $text"
    }

    /** 履歴のうち直近 [limit] 件を古い順で返す。 */
    fun recentHistory(history: List<ChatMessage>, limit: Int = HISTORY_LIMIT): List<ChatMessage> =
        history.takeLast(limit)

    /** 最初のユーザー発言から自動タイトルを作る(改行は空白化し、超過分は省略記号)。 */
    fun titleFrom(firstUserMessage: String, maxChars: Int = TITLE_MAX_CHARS): String {
        val oneLine = firstUserMessage.trim().replace(Regex("\\s+"), " ")
        return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "…"
    }
}
