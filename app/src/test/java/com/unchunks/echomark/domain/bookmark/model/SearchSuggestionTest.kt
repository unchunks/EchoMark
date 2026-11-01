package com.unchunks.echomark.domain.bookmark.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSuggestionTest {

    private val tags = listOf(
        TagCandidate(1, "Kotlin", 5),
        TagCandidate(2, "Android", 9),
        TagCandidate(3, "kotlin-native", 2),
        TagCandidate(4, "古いタグ", 0),
        TagCandidate(5, "ラーメン", 3)
    )

    private fun suggest(
        input: String,
        history: List<String> = emptyList(),
        categories: List<String> = emptyList(),
        selectedTagIds: Set<Long> = emptySet(),
        selectedCategories: Set<String> = emptySet()
    ) = SearchSuggester.suggest(input, history, tags, categories, selectedTagIds, selectedCategories)

    @Test
    fun 履歴は新しい順で同じ検索語は1つにまとめる() {
        var history = emptyList<String>()
        history = SearchHistory.add(history, "kotlin")
        history = SearchHistory.add(history, "東京　ラーメン")
        history = SearchHistory.add(history, "  KOTLIN ")
        history = SearchHistory.add(history, "東京 ラーメン")

        assertEquals(listOf("東京 ラーメン", "KOTLIN"), history)
    }

    @Test
    fun 空や長すぎる検索語は履歴に残さない() {
        assertEquals(emptyList<String>(), SearchHistory.add(emptyList(), "  　 "))
        assertEquals(emptyList<String>(), SearchHistory.add(emptyList(), "あ".repeat(SearchHistory.MAX_QUERY_CHARS + 1)))
        assertEquals(1, SearchHistory.add(emptyList(), "あ".repeat(SearchHistory.MAX_QUERY_CHARS)).size)
    }

    @Test
    fun 履歴の数には上限があり古いものから消える() {
        var history = emptyList<String>()
        repeat(SearchHistory.MAX_ENTRIES + 5) { history = SearchHistory.add(history, "検索$it") }

        assertEquals(SearchHistory.MAX_ENTRIES, history.size)
        assertEquals("検索${SearchHistory.MAX_ENTRIES + 4}", history.first())
        assertEquals("検索5", history.last())
    }

    @Test
    fun 履歴の1件を消す() {
        assertEquals(listOf("b"), SearchHistory.remove(listOf("a", "b"), " A "))
    }

    @Test
    fun 入力が空なら最近の検索を出す() {
        val history = (1..12).map { "検索$it" }

        val suggestions = suggest("  ", history)

        assertEquals(history.take(SearchSuggester.MAX_RECENT).map { SearchSuggestion.History(it) }, suggestions)
    }

    @Test
    fun 入力を含む履歴を出し_入力そのものは出さない() {
        val suggestions = suggest("東京", history = listOf("東京", "東京 ラーメン", "大阪", "新東京駅"))

        assertEquals(listOf(SearchSuggestion.History("東京 ラーメン"), SearchSuggestion.History("新東京駅")), suggestions)
    }

    @Test
    fun 最後の語に合うタグを前方一致が先_件数の多い順で出す() {
        val suggestions = suggest("記事 kot")

        assertEquals(
            listOf(SearchSuggestion.Tag(1, "Kotlin"), SearchSuggestion.Tag(3, "kotlin-native")),
            suggestions
        )
    }

    @Test
    fun 全角半角と大文字小文字を区別せず_件数0のタグと選択済みは出さない() {
        assertEquals(listOf(SearchSuggestion.Tag(1, "Kotlin"), SearchSuggestion.Tag(3, "kotlin-native")), suggest("ＫＯＴ"))
        assertEquals(emptyList<SearchSuggestion>(), suggest("古い"))
        assertEquals(listOf(SearchSuggestion.Tag(3, "kotlin-native")), suggest("kot", selectedTagIds = setOf(1)))
    }

    @Test
    fun 語を打ち終えて空白があればタグやカテゴリは出さず履歴だけ() {
        val suggestions = suggest("kotlin ", history = listOf("kotlin 入門"), categories = listOf("技術"))

        assertEquals(listOf(SearchSuggestion.History("kotlin 入門")), suggestions)
    }

    @Test
    fun カテゴリの候補も出し_選択済みは出さない() {
        assertEquals(listOf(SearchSuggestion.Category("技術")), suggest("技", categories = listOf("技術", "旅行")))
        assertEquals(emptyList<SearchSuggestion>(), suggest("技", categories = listOf("技術"), selectedCategories = setOf("技術")))
    }

    @Test
    fun 候補を選んだら入力の最後の語を取り除く() {
        assertEquals("東京", SearchSuggester.withoutLastTerm("東京 ラー"))
        assertEquals("", SearchSuggester.withoutLastTerm("ラー"))
        assertEquals("a b", SearchSuggester.withoutLastTerm("a b a"))
    }
}
