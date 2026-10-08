package com.unchunks.echomark.domain.search

/**
 * Reciprocal Rank Fusion(RRF)。複数の順位付きリストを1つに統合する純粋関数。
 * 各要素のスコアは Σ 1 / (k + rank)(rank は 1 始まり)。スコア同点は最初に登場した順を保つ。
 */
object RankFusion {
    const val DEFAULT_K = 60

    fun <T> fuse(rankedLists: List<List<T>>, k: Int = DEFAULT_K): List<T> {
        require(k >= 0) { "k must be non-negative" }
        val scores = LinkedHashMap<T, Double>()
        rankedLists.forEach { list ->
            // 同一リスト内の重複は最初の順位のみ数える
            list.distinct().forEachIndexed { index, item ->
                scores[item] = (scores[item] ?: 0.0) + 1.0 / (k + index + 1)
            }
        }
        // sortedByDescending は安定ソートなので、同点は挿入(初出)順のまま
        return scores.entries.sortedByDescending { it.value }.map { it.key }
    }
}
