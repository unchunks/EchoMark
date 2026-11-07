package com.unchunks.echomark.domain.graph

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeGraphBuilderTest {

    private fun bookmark(id: Long, vararg tags: String) = Bookmark(
        id = id, type = BookmarkType.URL, title = "題$id", createdAt = id, lastAccessedAt = id, tags = tags.toList()
    )

    @Test
    fun ブックマークが2件未満なら空() {
        assertEquals(KnowledgeGraph.EMPTY, KnowledgeGraphBuilder.build(listOf(bookmark(1, "a")), emptyList()))
        assertEquals(KnowledgeGraph.EMPTY, KnowledgeGraphBuilder.build(emptyList(), emptyList()))
    }

    @Test
    fun つながりの無いブックマークは点にしない() {
        val graph = KnowledgeGraphBuilder.build(
            listOf(bookmark(1, "a"), bookmark(2, "a"), bookmark(3, "z")), emptyList()
        )
        assertEquals(setOf(1L, 2L), graph.nodes.map { it.id }.toSet())
        assertEquals(1, graph.edges.size)
        assertEquals(EdgeKind.TAG, graph.edges.single().kind)
        assertEquals(listOf("a"), graph.edges.single().sharedTags)
    }

    @Test
    fun 関連のまとまりの中は全部つながる() {
        val graph = KnowledgeGraphBuilder.build(
            listOf(bookmark(1), bookmark(2), bookmark(3), bookmark(4)), listOf(listOf(1L, 2L, 3L))
        )
        assertEquals(3, graph.nodes.size)
        // 3 件の全部の組
        assertEquals(3, graph.edges.size)
        assertTrue(graph.edges.all { it.kind == EdgeKind.RELATED && it.sharedTags.isEmpty() })
        assertEquals(KnowledgeGraphBuilder.RELATED_WEIGHT, graph.edges.first().weight, 0f)
    }

    @Test
    fun 関連と共通のタグの両方でつながると両方の印と太い線になる() {
        val graph = KnowledgeGraphBuilder.build(
            listOf(bookmark(1, "x", "y"), bookmark(2, "x", "y")), listOf(listOf(1L, 2L))
        )
        val edge = graph.edges.single()
        assertEquals(EdgeKind.BOTH, edge.kind)
        assertEquals(listOf("x", "y"), edge.sharedTags)
        assertEquals(KnowledgeGraphBuilder.RELATED_WEIGHT + 2, edge.weight, 0f)
    }

    @Test
    fun 付いている数が多すぎるタグはつながりに使わない() {
        val many = (1L..(KnowledgeGraphBuilder.MAX_TAG_FANOUT + 1L)).map { bookmark(it, "広いタグ") }
        val graph = KnowledgeGraphBuilder.build(many, emptyList())
        assertTrue(graph.nodes.isEmpty())
        val justEnough = (1L..KnowledgeGraphBuilder.MAX_TAG_FANOUT.toLong()).map { bookmark(it, "広いタグ") }
        assertEquals(justEnough.size, KnowledgeGraphBuilder.build(justEnough, emptyList()).nodes.size)
    }

    @Test
    fun 点の数は上限までで_つながりの強いものを残す() {
        // 1〜4 は全部同じ 3 つのタグを共有(強い)。5〜8 は 1 つのタグだけを共有(弱い)
        val strong = (1L..4L).map { bookmark(it, "a", "b", "c") }
        val weak = (5L..8L).map { bookmark(it, "d") }
        val graph = KnowledgeGraphBuilder.build(strong + weak, emptyList(), GraphOptions(maxNodes = 4))
        assertEquals(setOf(1L, 2L, 3L, 4L), graph.nodes.map { it.id }.toSet())
        assertEquals(8, graph.candidateCount)
        assertTrue(graph.isTruncated)
        // 線は選んだ点の間だけ
        assertTrue(graph.edges.all { it.a in graph.nodes.indices && it.b in graph.nodes.indices })
    }

    @Test
    fun タグで絞り込むと_そのタグが付いたものだけで作り_絞ったタグはつながりにしない() {
        val list = listOf(
            bookmark(1, "Android", "Compose"), bookmark(2, "Android", "Compose"),
            bookmark(3, "Android"), bookmark(4, "料理", "Compose"), bookmark(5, "料理", "Compose")
        )
        val graph = KnowledgeGraphBuilder.build(list, emptyList(), GraphOptions(tagFilter = "Android"))
        assertEquals(setOf(1L, 2L), graph.nodes.map { it.id }.toSet())
        // Android は絞り込みに使ったので理由に出ない
        assertEquals(listOf("Compose"), graph.edges.single().sharedTags)
    }

    @Test
    fun まとまりは大きい順に番号が付き_名前は共通のタグになる() {
        val list = listOf(
            bookmark(1, "猫"), bookmark(2, "猫"), bookmark(3, "猫"),
            bookmark(10, "犬"), bookmark(11, "犬")
        )
        val graph = KnowledgeGraphBuilder.build(list, emptyList())
        assertEquals(listOf(3, 2), graph.clusters.map { it.size })
        assertEquals(listOf("猫", "犬"), graph.clusters.map { it.label })
        assertEquals(0, graph.nodes.first { it.id == 1L }.cluster)
        assertEquals(1, graph.nodes.first { it.id == 10L }.cluster)
    }

    @Test
    fun つながりの数が点ごとに数えられる() {
        val graph = KnowledgeGraphBuilder.build(listOf(bookmark(1, "a"), bookmark(2, "a"), bookmark(3, "a")), emptyList())
        assertTrue(graph.nodes.all { it.degree == 2 })
        assertEquals(2, graph.edgesOf(0).size)
    }

    @Test
    fun 数千件でも点の数は上限に収まり線は選んだ点の間だけ() {
        // 5000 件。10 件ずつ同じタグ(上限の 12 以下)と、100 件ごとの関連のまとまり
        val list = (1L..5000L).map { bookmark(it, "t${it / 10}", "u${it / 7}") }
        val groups = (1L..5000L).chunked(100).map { it.take(12) }
        val graph = KnowledgeGraphBuilder.build(list, groups, GraphOptions(maxNodes = KnowledgeGraphBuilder.MAX_NODES))
        assertEquals(KnowledgeGraphBuilder.MAX_NODES, graph.nodes.size)
        assertTrue(graph.candidateCount > graph.nodes.size)
        assertTrue(graph.edges.all { it.a < it.b && it.b < graph.nodes.size })
        assertFalse(graph.edges.isEmpty())
    }

    @Test
    fun 同じ入力なら同じグラフになる() {
        val list = (1L..40L).map { bookmark(it, "t${it % 5}", "u${it % 3}") }
        val a = KnowledgeGraphBuilder.build(list, emptyList(), GraphOptions(maxNodes = 20))
        val b = KnowledgeGraphBuilder.build(list, emptyList(), GraphOptions(maxNodes = 20))
        assertEquals(a.nodes.map { it.id }, b.nodes.map { it.id })
        assertEquals(a.edges, b.edges)
    }
}
