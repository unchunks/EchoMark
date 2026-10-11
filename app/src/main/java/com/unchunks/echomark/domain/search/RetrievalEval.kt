package com.unchunks.echomark.domain.search

import org.json.JSONObject
import kotlin.math.log2
import kotlin.math.sqrt

/**
 * 埋め込みモデルの検索品質を測る評価用の純粋なロジック(アプリの実行には使わない)。
 * JVM の単体テスト(固定のベクトル)と androidTest(実際のモデル)の両方で同じ計算を使う。
 * 手順は docs/embedding-evaluation.md。
 */

/** 評価用の文書(ブックマーク)。 */
data class EvalDocument(val id: String, val title: String, val text: String)

/** 評価用の質問と、正解(関連する)文書の ID。 */
data class EvalQuery(val id: String, val text: String, val relevantIds: Set<String>)

data class RetrievalFixture(val documents: List<EvalDocument>, val queries: List<EvalQuery>) {
    companion object {
        /**
         * `{"documents":[{"id","title","text"}], "queries":[{"id","text","relevant":["d01"]}]}` を読む。
         * 文書が足りない・正解の ID が文書に無いなどの不整合は [IllegalArgumentException]。
         */
        fun parse(json: String): RetrievalFixture {
            val root = JSONObject(json)
            val documents = root.getJSONArray("documents").let { array ->
                (0 until array.length()).map { i ->
                    val d = array.getJSONObject(i)
                    EvalDocument(d.getString("id"), d.getString("title"), d.getString("text"))
                }
            }
            val queries = root.getJSONArray("queries").let { array ->
                (0 until array.length()).map { i ->
                    val q = array.getJSONObject(i)
                    val relevant = q.getJSONArray("relevant")
                    EvalQuery(q.getString("id"), q.getString("text"), (0 until relevant.length()).map { relevant.getString(it) }.toSet())
                }
            }
            val ids = documents.map { it.id }
            require(ids.size == ids.toSet().size) { "文書の ID が重複している" }
            queries.forEach { q ->
                require(q.relevantIds.isNotEmpty()) { "正解の無い質問: ${q.id}" }
                require(ids.containsAll(q.relevantIds)) { "存在しない文書を正解にしている: ${q.id}" }
            }
            return RetrievalFixture(documents, queries)
        }
    }
}

/** 検索結果(近い順の文書 ID)に対する指標。正解は「関連あり/なし」の2値。 */
object RetrievalMetrics {

    /** 上位 [k] 件に入った正解の割合(正解が無ければ 0)。 */
    fun recallAtK(ranked: List<String>, relevant: Set<String>, k: Int): Double {
        if (relevant.isEmpty() || k <= 0) return 0.0
        return ranked.take(k).count { it in relevant }.toDouble() / relevant.size
    }

    /** 最初の正解の順位の逆数(1 位なら 1.0、無ければ 0)。 */
    fun reciprocalRank(ranked: List<String>, relevant: Set<String>): Double {
        val index = ranked.indexOfFirst { it in relevant }
        return if (index < 0) 0.0 else 1.0 / (index + 1)
    }

    /** 上位 [k] 件の nDCG(2値の関連度。理想の並びは正解を先頭に詰めたもの)。 */
    fun ndcgAtK(ranked: List<String>, relevant: Set<String>, k: Int): Double {
        if (relevant.isEmpty() || k <= 0) return 0.0
        val dcg = ranked.take(k).mapIndexed { i, id -> if (id in relevant) 1.0 / log2(i + 2.0) else 0.0 }.sum()
        val ideal = (0 until minOf(relevant.size, k)).sumOf { 1.0 / log2(it + 2.0) }
        return dcg / ideal
    }

    fun mean(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.sum() / values.size

    /** コサイン距離(1 - コサイン類似度。ObjectBox の COSINE と同じ尺度)。ゼロベクトルは 1.0。 */
    fun cosineDistance(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "次元が違う: ${a.size} != ${b.size}" }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 1.0
        return 1.0 - dot / (sqrt(na) * sqrt(nb))
    }
}

/** しきい値の候補。[distance] 以下を「関連あり」としたときの精度・再現率。 */
data class ThresholdSuggestion(
    val distance: Double,
    val precision: Double,
    val recall: Double,
    val f1: Double
) {
    /** チャット(RAG)の最小類似度に直した値 */
    val similarity: Double get() = 1.0 - distance
}

/** 評価結果。 */
data class RetrievalReport(
    val queryCount: Int,
    val documentCount: Int,
    val recallAt: Map<Int, Double>,
    val mrr: Double,
    val ndcgAt: Map<Int, Double>,
    val relevantDistances: List<Double>,
    val irrelevantDistances: List<Double>,
    /** 関連あり/なしの距離の分布から選んだ、F1 が最大になるしきい値。分布が片方だけなら null */
    val suggestedThreshold: ThresholdSuggestion?
) {
    fun format(): String = buildString {
        appendLine("質問 $queryCount 件 / 文書 $documentCount 件")
        recallAt.toSortedMap().forEach { (k, v) -> appendLine("Recall@$k = ${"%.3f".format(v)}") }
        appendLine("MRR = ${"%.3f".format(mrr)}")
        ndcgAt.toSortedMap().forEach { (k, v) -> appendLine("nDCG@$k = ${"%.3f".format(v)}") }
        appendLine("距離(関連あり): ${describe(relevantDistances)}")
        appendLine("距離(関連なし): ${describe(irrelevantDistances)}")
        suggestedThreshold?.let {
            appendLine(
                "提案: maxSearchDistance = ${"%.3f".format(it.distance)} / minRagSimilarity = ${"%.3f".format(it.similarity)} " +
                    "(precision ${"%.3f".format(it.precision)}, recall ${"%.3f".format(it.recall)}, F1 ${"%.3f".format(it.f1)})"
            )
        } ?: appendLine("提案: 関連あり/なしの両方が必要")
    }

    private fun describe(values: List<Double>): String {
        if (values.isEmpty()) return "なし"
        val sorted = values.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        return "n=${sorted.size} min=${"%.3f".format(sorted.first())} p10=${"%.3f".format(pct(0.1))} " +
            "median=${"%.3f".format(pct(0.5))} p90=${"%.3f".format(pct(0.9))} max=${"%.3f".format(sorted.last())}"
    }
}

object RetrievalEvaluator {

    /**
     * 文書と質問のベクトルから、全質問の検索結果(コサイン距離の近い順)を作って指標とバラつきを集計する。
     * @param ks Recall と nDCG を測る上位件数
     */
    fun evaluate(
        fixture: RetrievalFixture,
        documentVectors: Map<String, FloatArray>,
        queryVectors: Map<String, FloatArray>,
        ks: List<Int> = listOf(1, 3, 5)
    ): RetrievalReport {
        val relevantDistances = mutableListOf<Double>()
        val irrelevantDistances = mutableListOf<Double>()
        val recalls = ks.associateWith { mutableListOf<Double>() }
        val ndcgs = ks.associateWith { mutableListOf<Double>() }
        val reciprocalRanks = mutableListOf<Double>()

        for (query in fixture.queries) {
            val qv = queryVectors.getValue(query.id)
            val distances = fixture.documents.map { doc ->
                doc.id to RetrievalMetrics.cosineDistance(qv, documentVectors.getValue(doc.id))
            }
            val ranked = distances.sortedBy { it.second }.map { it.first }
            distances.forEach { (id, d) -> if (id in query.relevantIds) relevantDistances += d else irrelevantDistances += d }
            ks.forEach { k ->
                recalls.getValue(k) += RetrievalMetrics.recallAtK(ranked, query.relevantIds, k)
                ndcgs.getValue(k) += RetrievalMetrics.ndcgAtK(ranked, query.relevantIds, k)
            }
            reciprocalRanks += RetrievalMetrics.reciprocalRank(ranked, query.relevantIds)
        }

        return RetrievalReport(
            queryCount = fixture.queries.size,
            documentCount = fixture.documents.size,
            recallAt = recalls.mapValues { RetrievalMetrics.mean(it.value) },
            mrr = RetrievalMetrics.mean(reciprocalRanks),
            ndcgAt = ndcgs.mapValues { RetrievalMetrics.mean(it.value) },
            relevantDistances = relevantDistances,
            irrelevantDistances = irrelevantDistances,
            suggestedThreshold = suggestThreshold(relevantDistances, irrelevantDistances)
        )
    }

    /**
     * 「距離が c 以下なら関連あり」としたときの F1 が最大になる c を選ぶ(候補は隣り合う距離の中点)。
     * 同点なら小さい c(厳しいほう)。どちらかの分布が空なら null。
     */
    fun suggestThreshold(relevant: List<Double>, irrelevant: List<Double>): ThresholdSuggestion? {
        if (relevant.isEmpty() || irrelevant.isEmpty()) return null
        val all = (relevant + irrelevant).sorted().distinct()
        val candidates = if (all.size == 1) all else all.zipWithNext { a, b -> (a + b) / 2 }
        return candidates.map { c ->
            val tp = relevant.count { it <= c }
            val fp = irrelevant.count { it <= c }
            val precision = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
            val recall = tp.toDouble() / relevant.size
            val f1 = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
            ThresholdSuggestion(c, precision, recall, f1)
        }.maxByOrNull { it.f1 }
    }
}
