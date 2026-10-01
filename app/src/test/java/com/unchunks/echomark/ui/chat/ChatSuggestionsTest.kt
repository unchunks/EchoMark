package com.unchunks.echomark.ui.chat

import com.unchunks.echomark.testing.testBookmark
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSuggestionsTest {

    @Test
    fun ブックマークが無ければ汎用の例だけ() {
        assertEquals(
            listOf("最近保存した記事の要点は？", "今週保存したものを振り返って", "保存したものをテーマ別に整理して"),
            ChatSuggestions.forLibrary(emptyList())
        )
    }

    @Test
    fun よく付いているタグを多い順に2つ使い_同数なら新しい方を優先する() {
        val bookmarks = listOf(
            testBookmark(1).copy(tags = listOf("Android", "Kotlin")),
            testBookmark(2).copy(tags = listOf("料理", "Kotlin")),
            testBookmark(3).copy(tags = listOf("旅行"))
        )

        assertEquals(
            listOf(
                "最近保存した記事の要点は？",
                "「Kotlin」の保存をまとめて",
                "「Android」の保存をまとめて",
                "今週保存したものを振り返って"
            ),
            ChatSuggestions.forLibrary(bookmarks)
        )
    }

    @Test
    fun カテゴリも使い_その他や長すぎる名前は使わない() {
        val bookmarks = listOf(
            testBookmark(1).copy(tags = listOf("とても長いタグの名前はチップが崩れるので使わない"), category = "その他"),
            testBookmark(2).copy(category = "技術"),
            testBookmark(3).copy(category = "技術")
        )

        assertEquals(
            listOf(
                "最近保存した記事の要点は？",
                "「技術」の保存から学べることは？",
                "今週保存したものを振り返って",
                "保存したものをテーマ別に整理して"
            ),
            ChatSuggestions.forLibrary(bookmarks)
        )
    }

    @Test
    fun タグと同じ名前のカテゴリは重ねて出さない() {
        val bookmarks = listOf(testBookmark(1).copy(tags = listOf("技術"), category = "技術"))

        assertEquals(
            listOf(
                "最近保存した記事の要点は？",
                "「技術」の保存をまとめて",
                "今週保存したものを振り返って",
                "保存したものをテーマ別に整理して"
            ),
            ChatSuggestions.forLibrary(bookmarks)
        )
    }

    @Test
    fun 最大数を超えない() {
        val bookmarks = (1L..10L).map { testBookmark(it).copy(tags = listOf("t$it"), category = "c$it") }

        assertEquals(ChatSuggestions.MAX_SUGGESTIONS, ChatSuggestions.forLibrary(bookmarks).size)
    }
}
