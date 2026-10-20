package com.unchunks.echomark.domain.search

import org.junit.Assert.assertEquals
import org.junit.Test

class RankFusionTest {

    @Test
    fun 空入力は空を返す() {
        assertEquals(emptyList<Long>(), RankFusion.fuse(emptyList<List<Long>>()))
        assertEquals(emptyList<Long>(), RankFusion.fuse(listOf(emptyList<Long>(), emptyList())))
    }

    @Test
    fun 単一リストは順序を保つ() {
        assertEquals(listOf(3L, 1L, 2L), RankFusion.fuse(listOf(listOf(3L, 1L, 2L))))
    }

    @Test
    fun 両方に含まれる要素が上位になる() {
        val keyword = listOf(1L, 2L, 3L)
        val semantic = listOf(4L, 3L, 5L)
        // 3 は 1/63 + 1/62 で最上位
        assertEquals(3L, RankFusion.fuse(listOf(keyword, semantic)).first())
    }

    @Test
    fun 同点は初出順() {
        val fused = RankFusion.fuse(listOf(listOf("a", "b"), listOf("b", "a")), k = 60)
        assertEquals(listOf("a", "b"), fused)
    }

    @Test
    fun 重複は一度だけ返す() {
        val fused = RankFusion.fuse(listOf(listOf(1L, 2L), listOf(2L, 3L)))
        assertEquals(listOf(2L, 1L, 3L), fused)
    }

    @Test
    fun リスト内の重複は二重加点されない() {
        val fused = RankFusion.fuse(listOf(listOf(1L, 1L, 2L), listOf(2L)))
        assertEquals(listOf(2L, 1L), fused)
    }
}
