package com.unchunks.echomark.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** タグの階層(親子)の並べ方と、親をたどる処理。 */
class TagTreeTest {

    private fun tag(id: Long, name: String, parentId: Long? = null) = TagWithCount(id, name, 0, parentId = parentId)

    private val tags = listOf(
        tag(1, "プログラミング"),
        tag(2, "Android", parentId = 1),
        tag(3, "Compose", parentId = 2),
        tag(4, "Kotlin", parentId = 1),
        tag(5, "読書")
    )

    @Test
    fun 親の直後にその子を深さつきで名前順に並べる() {
        val ordered = TagTree.order(tags.shuffled()).map { it.tag.name to it.depth }

        assertEquals(
            listOf("プログラミング" to 0, "Android" to 1, "Compose" to 2, "Kotlin" to 1, "読書" to 0),
            ordered
        )
    }

    @Test
    fun 親が一覧に無いタグは最上位として出す() {
        val ordered = TagTree.order(listOf(tag(2, "Android", parentId = 99), tag(3, "Compose", parentId = 2)))

        assertEquals(listOf("Android" to 0, "Compose" to 1), ordered.map { it.tag.name to it.depth })
    }

    @Test
    fun 輪になったデータでも止まり_タグを落とさない() {
        val looped = listOf(tag(1, "A", parentId = 2), tag(2, "B", parentId = 1), tag(3, "C", parentId = 3))

        val ordered = TagTree.order(looped)

        assertEquals(setOf(1L, 2L, 3L), ordered.map { it.tag.id }.toSet())
        assertEquals(3, ordered.size)
        assertEquals(setOf(2L), TagTree.descendantIds(looped, 1) - 1L)
        assertEquals(listOf("B"), TagTree.ancestorNames(looped, looped[0]))
    }

    @Test
    fun 子孫と祖先をたどる() {
        assertEquals(setOf(2L, 3L, 4L), TagTree.descendantIds(tags, 1))
        assertEquals(setOf(3L), TagTree.descendantIds(tags, 2))
        assertTrue(TagTree.descendantIds(tags, 5).isEmpty())
        assertEquals(listOf("プログラミング", "Android"), TagTree.ancestorNames(tags, tags[2]))
        assertTrue(TagTree.ancestorNames(tags, tags[0]).isEmpty())
    }
}
