package com.unchunks.echomark.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 保存するときのタグの提案の選び方。 */
class TagSuggestionsTest {

    @Test
    fun 内容に出てくるタグを先に_似たブックマークのタグを似ている順に出す() {
        val result = TagSuggestions.rank(
            mentioned = listOf("Compose"),
            similar = listOf(listOf("Android", "UI"), listOf("読書"), listOf("料理")),
            popular = listOf("あとで読む")
        )

        assertEquals(listOf("Compose", "Android", "UI", "読書", "料理", "あとで読む"), result)
    }

    @Test
    fun 何度も出てくるタグは点数が上がる() {
        val result = TagSuggestions.rank(
            mentioned = emptyList(),
            similar = listOf(listOf("X"), listOf("A"), listOf("B"), listOf("B"), listOf("B")),
            popular = emptyList()
        )

        // B は 3〜5 位に出て、2 位の A を上回る(1 位の X には届かない)
        assertEquals(listOf("X", "B", "A"), result)
    }

    @Test
    fun よく使うタグは足りないときだけ補う() {
        assertEquals(
            listOf("Compose", "x", "y"),
            TagSuggestions.rank(listOf("Compose"), emptyList(), popular = listOf("x", "y", "z"), limit = 3)
        )
        // 上の 2 つで limit に届いていれば、よく使うタグは入れない
        assertEquals(
            listOf("a", "b"),
            TagSuggestions.rank(listOf("a", "b"), emptyList(), popular = listOf("x"), limit = 2)
        )
    }

    @Test
    fun 入力済みのタグは表記ゆれも含めて出さない() {
        val result = TagSuggestions.rank(
            mentioned = listOf("android", "Kotlin"),
            similar = listOf(listOf("ＫＯＴＬＩＮ", "UI")),
            popular = listOf("ANDROID", "読書"),
            exclude = listOf("Android", "kotlin")
        )

        assertEquals(listOf("UI", "読書"), result)
    }

    @Test
    fun 表記ゆれの重複は先に出たほうの表記で1つにする() {
        assertEquals(
            listOf("Android"),
            TagSuggestions.rank(listOf("Android", "android"), listOf(listOf("ANDROID")), emptyList())
        )
    }

    @Test
    fun 材料が無ければ空() {
        assertEquals(emptyList<String>(), TagSuggestions.rank(emptyList(), emptyList(), emptyList()))
    }
}
