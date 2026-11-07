package com.unchunks.echomark.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 表記ゆれの候補の見つけ方。 */
class TagVariantsTest {

    private fun names(groups: List<List<TagWithCount>>) = groups.map { group -> group.map { it.name } }

    @Test
    fun 大文字小文字と全角半角の違いは同じグループになる() {
        val tags = listOf(
            TagWithCount(1, "android", 2),
            TagWithCount(2, "Android", 5),
            TagWithCount(3, "ＡＮＤＲＯＩＤ", 1),
            TagWithCount(4, "Compose", 3)
        )

        assertEquals(listOf(listOf("Android", "android", "ＡＮＤＲＯＩＤ")), names(TagVariants.groups(tags)))
    }

    @Test
    fun 区切り_かな_語尾の長音の違いも候補にする() {
        val tags = listOf(
            TagWithCount(1, "machine learning", 1),
            TagWithCount(2, "machine-learning", 1),
            TagWithCount(3, "サーバー", 4),
            TagWithCount(4, "サーバ", 1),
            TagWithCount(5, "ねこ", 2),
            TagWithCount(6, "ネコ", 3),
            TagWithCount(7, "犬", 1)
        )

        // グループは先頭のタグの名前順
        assertEquals(
            listOf(
                listOf("machine learning", "machine-learning"),
                listOf("サーバー", "サーバ"),
                listOf("ネコ", "ねこ")
            ),
            names(TagVariants.groups(tags))
        )
    }

    @Test
    fun 残す候補は自分のタグ_件数の多い順_名前順に先頭に来る() {
        val tags = listOf(
            TagWithCount(1, "kotlin", 9, isUserTag = false),
            TagWithCount(2, "Kotlin", 1, isUserTag = true),
            TagWithCount(3, "KOTLIN", 1, isUserTag = true)
        )

        assertEquals(listOf(listOf("Kotlin", "KOTLIN", "kotlin")), names(TagVariants.groups(tags)))
    }

    @Test
    fun 表記ゆれのないタグだけなら候補は無い() {
        val tags = listOf(TagWithCount(1, "Android", 1), TagWithCount(2, "Kotlin", 1), TagWithCount(3, "", 0))

        assertTrue(TagVariants.groups(tags).isEmpty())
    }

    @Test
    fun 記号だけのタグ名同士は候補にしない() {
        val tags = listOf(TagWithCount(1, "-", 1), TagWithCount(2, "_", 1))

        assertTrue(TagVariants.groups(tags).isEmpty())
    }
}
