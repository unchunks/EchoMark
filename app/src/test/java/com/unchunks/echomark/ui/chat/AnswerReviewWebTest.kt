package com.unchunks.echomark.ui.chat

import com.unchunks.echomark.domain.websearch.WebSource
import com.unchunks.echomark.testing.testBookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Web のページを引用した回答の照らし合わせ。 */
class AnswerReviewWebTest {

    private val bookmark = testBookmark(1).copy(content = "コルーチンは軽量なスレッドのように動き、中断と再開ができます。")
    private val page = WebSource(
        number = 2,
        url = "https://example.com/flow",
        title = "Flow の解説",
        domain = "example.com",
        excerpt = "Flow はコールドストリームで、collect されるまで動きません。"
    )

    /** ページを読めず、概要だけで答えたもの(抜粋は保存していない) */
    private val snippetOnly = WebSource(3, "https://other.example.org/x", "other.example.org", "other.example.org", "")

    @Test
    fun Webのページの引用を分けて出し_抜粋から根拠の一節を添える() {
        val review = AnswerReviewer.review(
            "Flow はコールドストリームで、collect されるまで動きません [2]。コルーチンは中断と再開ができます [1]。",
            listOf(1L),
            mapOf(1L to bookmark),
            listOf(page, snippetOnly)
        )

        assertEquals(listOf(1), review.sources.map { it.number })
        assertEquals(listOf(2), review.webSources.map { it.number })
        assertTrue(review.webSources.single().quote!!.contains("コールドストリーム"))
        assertEquals(listOf(snippetOnly), review.uncitedWeb)
        assertTrue(review.unknownNumbers.isEmpty())
        assertTrue(review.flagged.isEmpty())
    }

    @Test
    fun 抜粋の無いページは照らし合わせず_確かめられない文にする() {
        val review = AnswerReviewer.review("別のサイトではそう書かれています [3]。", listOf(1L), mapOf(1L to bookmark), listOf(page, snippetOnly))

        val cited = review.webSources.single()
        assertEquals(3, cited.number)
        assertNull(cited.quote)
        assertEquals(ClaimStatus.UNCHECKED, review.claims.single().status)
    }

    @Test
    fun Webのページに無い番号は対応する資料が無いものとして出す() {
        val review = AnswerReviewer.review("何か [9]。", listOf(1L), mapOf(1L to bookmark), listOf(page))

        assertEquals(listOf(9), review.unknownNumbers)
    }

    @Test
    fun メモには引用したWebのページも書き出す() {
        val answer = com.unchunks.echomark.domain.model.ChatMessage(
            conversationId = 1, role = com.unchunks.components.llm.ChatRole.ASSISTANT, content = "答え [2]。", createdAt = 0, webSources = listOf(page)
        )

        val note = ChatNotes.from("質問", answer, emptyList(), listOf(page))

        assertTrue(note.content, note.content.contains("- (Web) Flow の解説 (https://example.com/flow)"))
    }
}
