package com.unchunks.echomark.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.log2

/** 評価用の指標・集計(本物のモデル無しで、固定のベクトルで確かめる)と、日本語の評価データの整合性。 */
class RetrievalEvalTest {

    private val delta = 1e-9

    // ---- 指標 ----

    @Test
    fun recallAtK_上位k件に入った正解の割合() {
        val ranked = listOf("a", "b", "c", "d")
        val relevant = setOf("b", "d")
        assertEquals(0.0, RetrievalMetrics.recallAtK(ranked, relevant, 1), delta)
        assertEquals(0.5, RetrievalMetrics.recallAtK(ranked, relevant, 2), delta)
        assertEquals(1.0, RetrievalMetrics.recallAtK(ranked, relevant, 4), delta)
        // k が結果より大きくてもよい
        assertEquals(1.0, RetrievalMetrics.recallAtK(ranked, relevant, 10), delta)
    }

    @Test
    fun recallAtK_正解が無いか_kが0なら0() {
        assertEquals(0.0, RetrievalMetrics.recallAtK(listOf("a"), emptySet(), 3), delta)
        assertEquals(0.0, RetrievalMetrics.recallAtK(listOf("a"), setOf("a"), 0), delta)
    }

    @Test
    fun reciprocalRank_最初の正解の順位の逆数() {
        assertEquals(1.0, RetrievalMetrics.reciprocalRank(listOf("a", "b"), setOf("a")), delta)
        assertEquals(0.5, RetrievalMetrics.reciprocalRank(listOf("x", "a", "b"), setOf("a", "b")), delta)
        assertEquals(1.0 / 3, RetrievalMetrics.reciprocalRank(listOf("x", "y", "b"), setOf("b")), delta)
        assertEquals(0.0, RetrievalMetrics.reciprocalRank(listOf("x", "y"), setOf("b")), delta)
    }

    @Test
    fun ndcgAtK_理想の並びなら1_後ろにずれると下がる() {
        val relevant = setOf("r1", "r2")
        assertEquals(1.0, RetrievalMetrics.ndcgAtK(listOf("r1", "r2", "x"), relevant, 3), delta)

        val dcg = 1.0 / log2(3.0) + 1.0 / log2(4.0)
        val ideal = 1.0 + 1.0 / log2(3.0)
        assertEquals(dcg / ideal, RetrievalMetrics.ndcgAtK(listOf("x", "r1", "r2"), relevant, 3), delta)

        assertEquals(0.0, RetrievalMetrics.ndcgAtK(listOf("x", "y", "z"), relevant, 3), delta)
    }

    @Test
    fun ndcgAtK_正解がkより多くても上位がすべて正解なら1() {
        assertEquals(1.0, RetrievalMetrics.ndcgAtK(listOf("a", "b", "c"), setOf("a", "b", "c"), 2), delta)
    }

    @Test
    fun cosineDistance_同じ向き0_直交1_逆向き2_ゼロベクトル1() {
        assertEquals(0.0, RetrievalMetrics.cosineDistance(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)), 1e-7)
        assertEquals(1.0, RetrievalMetrics.cosineDistance(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-7)
        assertEquals(2.0, RetrievalMetrics.cosineDistance(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)), 1e-7)
        assertEquals(1.0, RetrievalMetrics.cosineDistance(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)), delta)
    }

    // ---- 集計としきい値 ----

    private val tinyFixture = RetrievalFixture(
        documents = listOf(
            EvalDocument("d1", "x", "x 軸"),
            EvalDocument("d2", "y", "y 軸"),
            EvalDocument("d3", "z", "z 軸")
        ),
        queries = listOf(
            EvalQuery("q1", "x に近い", setOf("d1")),
            EvalQuery("q2", "y に近い", setOf("d2"))
        )
    )
    private val axisDocs = mapOf(
        "d1" to floatArrayOf(1f, 0f, 0f),
        "d2" to floatArrayOf(0f, 1f, 0f),
        "d3" to floatArrayOf(0f, 0f, 1f)
    )

    @Test
    fun evaluate_完全に取れていれば指標は1でしきい値は両分布の間() {
        val queries = mapOf(
            "q1" to floatArrayOf(1f, 0.1f, 0f),
            "q2" to floatArrayOf(0.1f, 1f, 0f)
        )

        val report = RetrievalEvaluator.evaluate(tinyFixture, axisDocs, queries, ks = listOf(1, 3))

        assertEquals(1.0, report.recallAt.getValue(1), delta)
        assertEquals(1.0, report.mrr, delta)
        assertEquals(1.0, report.ndcgAt.getValue(3), delta)
        assertEquals(2, report.relevantDistances.size)
        assertEquals(4, report.irrelevantDistances.size)

        val threshold = report.suggestedThreshold!!
        assertTrue(threshold.distance > report.relevantDistances.max())
        assertTrue(threshold.distance < report.irrelevantDistances.min())
        assertEquals(1.0, threshold.precision, delta)
        assertEquals(1.0, threshold.recall, delta)
        assertEquals(1.0 - threshold.distance, threshold.similarity, delta)
        assertTrue(report.format().contains("Recall@1"))
    }

    @Test
    fun evaluate_正解が2位ならMRRは下がる() {
        // q1 は d3 のほうが d1 より近い
        val queries = mapOf(
            "q1" to floatArrayOf(0.5f, 0f, 1f),
            "q2" to floatArrayOf(0f, 1f, 0f)
        )

        val report = RetrievalEvaluator.evaluate(tinyFixture, axisDocs, queries, ks = listOf(1))

        assertEquals(0.5, report.recallAt.getValue(1), delta) // q1 は外れ、q2 は当たり
        assertEquals((0.5 + 1.0) / 2, report.mrr, delta)
    }

    @Test
    fun suggestThreshold_重なる分布ではF1が最大の位置を選ぶ() {
        val relevant = listOf(0.1, 0.2, 0.3, 0.6)
        val irrelevant = listOf(0.5, 0.7, 0.8, 0.9)

        val s = RetrievalEvaluator.suggestThreshold(relevant, irrelevant)!!

        // 0.6 と 0.7 の間(0.65)で切ると 関連 4/4・誤り 1(0.5) → precision 0.8, recall 1.0, F1 0.889 が最大
        assertEquals(0.65, s.distance, 1e-9)
        assertEquals(0.8, s.precision, delta)
        assertEquals(1.0, s.recall, delta)
    }

    @Test
    fun suggestThreshold_どちらかが空ならnull() {
        assertNull(RetrievalEvaluator.suggestThreshold(emptyList(), listOf(0.5)))
        assertNull(RetrievalEvaluator.suggestThreshold(listOf(0.5), emptyList()))
    }

    // ---- 評価データ ----

    private fun loadFixture(): RetrievalFixture {
        val stream = javaClass.classLoader!!.getResourceAsStream("eval/ja_bookmarks.json")
        assertNotNull("eval/ja_bookmarks.json が見つからない", stream)
        return RetrievalFixture.parse(stream!!.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    @Test
    fun 日本語の評価データは件数と参照が整合している() {
        val fixture = loadFixture()

        assertTrue(fixture.documents.size >= 20)
        assertTrue(fixture.queries.size >= 10)
        assertEquals(fixture.queries.size, fixture.queries.map { it.id }.toSet().size)
        assertTrue(fixture.documents.all { it.title.isNotBlank() && it.text.isNotBlank() })
        // 質問が文書とそのまま同じ文だと評価にならない
        assertTrue(fixture.queries.none { q -> fixture.documents.any { it.text == q.text || it.title == q.text } })
    }

    @Test
    fun 評価データを簡易な埋め込みで流せる() {
        // 文字 2-gram をハッシュした疑似ベクトル。品質は問わず、評価の流れが通ることだけを確かめる
        fun embed(text: String): FloatArray {
            val v = FloatArray(256)
            text.windowed(2).forEach { v[(it.hashCode() and 0x7fffffff) % 256] += 1f }
            return v
        }
        val fixture = loadFixture()

        val report = RetrievalEvaluator.evaluate(
            fixture,
            documentVectors = fixture.documents.associate { it.id to embed(it.title + "\n" + it.text) },
            queryVectors = fixture.queries.associate { it.id to embed(it.text) }
        )

        assertEquals(fixture.queries.size, report.queryCount)
        assertEquals(fixture.queries.sumOf { it.relevantIds.size }, report.relevantDistances.size)
        assertEquals(
            fixture.queries.size * fixture.documents.size - report.relevantDistances.size,
            report.irrelevantDistances.size
        )
        assertTrue(report.mrr in 0.0..1.0)
    }

    @Test
    fun 存在しない文書を正解にした評価データは読めない() {
        val json = """
            {"documents":[{"id":"d1","title":"t","text":"x"}],
             "queries":[{"id":"q1","text":"q","relevant":["d9"]}]}
        """.trimIndent()
        try {
            RetrievalFixture.parse(json)
            fail("IllegalArgumentException が出るはず")
        } catch (e: IllegalArgumentException) {
            // 期待どおり
        }
    }
}
