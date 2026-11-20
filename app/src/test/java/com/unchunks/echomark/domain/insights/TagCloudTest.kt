package com.unchunks.echomark.domain.insights

import com.unchunks.echomark.domain.model.TagWithCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagCloudTest {

    private fun tag(id: Long, name: String, count: Int) = TagWithCount(id, name, count)

    @Test
    fun タグが無ければ空() {
        val result = TagCloud.build(emptyList())
        assertTrue(result.entries.isEmpty())
        assertEquals(0, result.hiddenCount)
    }

    @Test
    fun 件数ゼロのタグは出さない() {
        val result = TagCloud.build(listOf(tag(1, "a", 0), tag(2, "b", 3)))
        assertEquals(listOf("b"), result.entries.map { it.tag.name })
    }

    @Test
    fun 名前順に並べ大きさは多いほど大きい() {
        val result = TagCloud.build(listOf(tag(1, "Kotlin", 20), tag(2, "android", 5), tag(3, "Zig", 1)))
        assertEquals(listOf("android", "Kotlin", "Zig"), result.entries.map { it.tag.name })
        val byName = result.entries.associateBy { it.tag.name }
        assertEquals(1f, byName.getValue("Kotlin").weight, 1e-6f)
        assertEquals(0f, byName.getValue("Zig").weight, 1e-6f)
        assertTrue(byName.getValue("android").weight in 0.1f..0.9f)
    }

    @Test
    fun 件数が全部同じなら中くらいにそろえる() {
        val result = TagCloud.build(listOf(tag(1, "a", 4), tag(2, "b", 4)))
        assertTrue(result.entries.all { it.weight == 0.5f })
    }

    @Test
    fun 上限を超えたら少ないものを省いて数を知らせる() {
        val tags = (1..10).map { tag(it.toLong(), "t$it", it) }
        val result = TagCloud.build(tags, maxTags = 4)
        assertEquals(setOf("t10", "t9", "t8", "t7"), result.entries.map { it.tag.name }.toSet())
        assertEquals(6, result.hiddenCount)
    }

    @Test
    fun 件数が同じなら名前順で残す() {
        val result = TagCloud.build(listOf(tag(1, "b", 2), tag(2, "a", 2), tag(3, "c", 2)), maxTags = 2)
        assertEquals(listOf("a", "b"), result.entries.map { it.tag.name })
        assertEquals(1, result.hiddenCount)
    }

    @Test
    fun 極端に多いタグが一つあっても他が潰れない() {
        val result = TagCloud.build(listOf(tag(1, "big", 1000), tag(2, "mid", 30), tag(3, "small", 1)))
        val mid = result.entries.first { it.tag.name == "mid" }.weight
        // 対数で見るので、線形なら 0.03 になる中間の値が半分近くになる
        assertTrue("mid=$mid", mid > 0.4f)
    }
}
