package com.unchunks.echomark.domain.graph

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ForceLayoutTest {

    private fun graphOf(n: Int, edges: List<Pair<Int, Int>>): KnowledgeGraph {
        val nodes = (0 until n).map {
            GraphNode(Bookmark(id = it + 1L, type = BookmarkType.URL, title = "t$it", createdAt = 0, lastAccessedAt = 0), cluster = 0, degree = 0)
        }
        val graphEdges = edges.map { (a, b) -> GraphEdge(minOf(a, b), maxOf(a, b), EdgeKind.TAG, 1f, listOf("x")) }
        return KnowledgeGraph(nodes, graphEdges, listOf(GraphCluster(0, "", n)), n)
    }

    @Test
    fun 点が無ければ空_1つなら中央() {
        assertEquals(0, ForceLayout.layout(KnowledgeGraph.EMPTY).size)
        val one = ForceLayout.layout(graphOf(1, emptyList()))
        assertEquals(0.5f, one.x[0], 0f)
        assertEquals(0.5f, one.y[0], 0f)
    }

    @Test
    fun 全部の点が0から1の範囲に収まる() {
        val n = 60
        val graph = graphOf(n, (0 until n - 1).map { it to it + 1 } + listOf(0 to 30, 10 to 50))
        val layout = ForceLayout.layout(graph)
        assertEquals(n, layout.size)
        for (i in 0 until n) {
            assertTrue("x[$i]=${layout.x[i]}", layout.x[i] in 0f..1f)
            assertTrue("y[$i]=${layout.y[i]}", layout.y[i] in 0f..1f)
        }
    }

    @Test
    fun 同じグラフは同じ配置になる() {
        val graph = graphOf(30, (0 until 29).map { it to it + 1 })
        val a = ForceLayout.layout(graph)
        val b = ForceLayout.layout(graph)
        assertArrayEquals(a.x, b.x, 0f)
        assertArrayEquals(a.y, b.y, 0f)
    }

    @Test
    fun 点が重ならない() {
        val graph = graphOf(40, (0 until 39).map { it to it + 1 })
        val layout = ForceLayout.layout(graph)
        var closest = Float.MAX_VALUE
        for (i in 0 until layout.size) for (j in i + 1 until layout.size) {
            closest = minOf(closest, hypot(layout.x[i] - layout.x[j], layout.y[i] - layout.y[j]))
        }
        assertTrue("最も近い 2 点の距離 $closest", closest > 0.005f)
    }

    @Test
    fun つながった点は_つながっていない点より近くに置かれる() {
        // 0-1-2 の三角形と、離れた 3-4 の組
        val graph = graphOf(5, listOf(0 to 1, 1 to 2, 0 to 2, 3 to 4))
        val layout = ForceLayout.layout(graph)
        fun d(i: Int, j: Int) = hypot(layout.x[i] - layout.x[j], layout.y[i] - layout.y[j])
        val connected = (d(0, 1) + d(1, 2) + d(0, 2) + d(3, 4)) / 4
        val unconnected = (d(0, 3) + d(1, 4) + d(2, 3) + d(2, 4)) / 4
        assertTrue("connected=$connected unconnected=$unconnected", connected < unconnected)
    }

    @Test
    fun 取り消されたら途中でやめる() {
        val graph = graphOf(50, (0 until 49).map { it to it + 1 })
        var calls = 0
        // 3 回目の確認で取り消す。途中でも、範囲に収まった位置を返す
        val layout = ForceLayout.layout(graph, isCancelled = { ++calls >= 3 })
        assertEquals(3, calls)
        assertEquals(50, layout.size)
        assertTrue(layout.x.all { it in 0f..1f })
    }

    @Test
    fun 上限の300点でも_数秒以内に並べられる() {
        val n = KnowledgeGraphBuilder.MAX_NODES
        val graph = graphOf(n, (0 until n - 1).map { it to it + 1 } + (0 until n step 7).map { it to (it * 13) % n }.filter { it.first != it.second })
        val started = System.nanoTime()
        val layout = ForceLayout.layout(graph)
        val seconds = (System.nanoTime() - started) / 1e9
        assertEquals(n, layout.size)
        // 余裕を見た上限(実際は 1 秒前後)。極端に遅くなる変更を検知するためのもの
        assertTrue("所要 ${seconds}秒", seconds < 20)
    }
}
