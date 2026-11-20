package com.unchunks.echomark.ui.chat

import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.testing.testBookmark
import com.unchunks.echomark.testing.testBookmark
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSuggestionsTest {

    private fun user(content: String) = ChatMessage(conversationId = 1, role = ChatRole.USER, content = content, createdAt = 0)
    private fun assistant(content: String = "回答") =
        ChatMessage(conversationId = 1, role = ChatRole.ASSISTANT, content = content, createdAt = 1)

    private val first = testBookmark(1, title = "Kotlin のコルーチンを基礎から学ぶ長いタイトル").copy(tags = listOf("Kotlin"))
    private val second = testBookmark(2, title = "Compose").copy(tags = listOf("Android"))
    private val bookmarks = listOf(first, second).associateBy { it.id }
    private val review = AnswerReview(sources = listOf(CitedSource(1, 1L, null), CitedSource(2, 2L, null)))

    @Test
    fun 複数を引用した回答には比べる質問と掘り下げる質問を出す() {
        val result = ChatSuggestions.forAnswer(listOf(user("質問"), assistant()), review, bookmarks, isAboutBookmark = false)

        assertEquals(
            listOf("引用した保存の共通点と違いは？", "「Kotlin のコルーチンを…」について詳しく", "「Kotlin」の他の保存は？"),
            result
        )
    }

    @Test
    fun 引用が無ければ汎用の質問を出す() {
        val result = ChatSuggestions.forAnswer(listOf(user("こんにちは"), assistant()), null, emptyMap(), isAboutBookmark = false)

        assertEquals(listOf("具体例を挙げて", "もっと詳しく教えて", "次にやるとよいことは？"), result)
    }

    @Test
    fun もう聞いた質問は出さない() {
        val result = ChatSuggestions.forAnswer(
            listOf(user("具体例を挙げて"), assistant()),
            null,
            emptyMap(),
            isAboutBookmark = false
        )

        assertEquals(listOf("もっと詳しく教えて", "次にやるとよいことは？"), result)
    }

    @Test
    fun ブックマークについての会話では対象についての質問を出す() {
        val result = ChatSuggestions.forAnswer(listOf(user("要約して"), assistant()), review, bookmarks, isAboutBookmark = true)

        assertEquals(listOf("重要なポイントは？", "関連する保存は？", "具体例を挙げて"), result)
    }

    @Test
    fun 最後が回答でなければ出さない() {
        assertEquals(emptyList<String>(), ChatSuggestions.forAnswer(listOf(user("質問")), null, emptyMap(), false))
        assertEquals(emptyList<String>(), ChatSuggestions.forAnswer(emptyList(), null, emptyMap(), false))
    }

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
