package com.unchunks.echomark.domain.search

import com.unchunks.components.search.RetrievalEvaluator
import com.unchunks.components.search.RetrievalFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** このアプリの日本語の評価データ(src/test/resources/eval)の整合性と、評価の流れが通ること。 */
class EvalFixtureTest {

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
}
