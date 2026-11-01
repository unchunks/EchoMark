package com.unchunks.echomark.ui.chat

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.testing.testBookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回答の引用番号の読み取りと、資料との照らし合わせ。 */
class AnswerReviewTest {

    private val kotlin = source(1, "コルーチンは軽量なスレッドのように動き、中断と再開ができます。例外は親のスコープへ伝わります。")
    private val compose = source(2, "Lazy リストには key を指定すると、再コンポーズの範囲が小さくなります。")

    private fun source(id: Long, content: String): Bookmark = testBookmark(id).copy(content = content)

    private fun review(answer: String, vararg sources: Bookmark, ids: List<Long> = sources.map { it.id }) =
        AnswerReviewer.review(answer, ids, sources.associateBy { it.id })

    @Test
    fun 引用した資料を出てきた順に出し_根拠の一節を添える() {
        val result = review(
            "Lazy リストには key を指定すると再コンポーズの範囲が小さくなります [2]。コルーチンは中断と再開ができます [1]。",
            kotlin,
            compose
        )

        assertEquals(listOf(2, 1), result.sources.map { it.number })
        assertEquals(listOf(2L, 1L), result.sources.map { it.bookmarkId })
        assertTrue(result.sources[0].quote!!.contains("key を指定する"))
        assertTrue(result.flagged.isEmpty())
        assertEquals(2, result.checkedCount)
    }

    @Test
    fun 句点の後の番号は前の文の番号として読む() {
        val result = review("コルーチンは中断と再開ができます。[1]", kotlin)

        assertEquals(listOf(AnswerClaim("コルーチンは中断と再開ができます。", listOf(1), ClaimStatus.SUPPORTED)), result.claims)
    }

    @Test
    fun まとめて付けた番号はどれかに根拠があればよい() {
        val result = review("コルーチンは中断と再開ができます [1, 2]。", kotlin, compose)

        assertEquals(ClaimStatus.SUPPORTED, result.claims.single().status)
        assertEquals(listOf(1, 2), result.sources.map { it.number })
        assertNull(result.sources[1].quote)
    }

    @Test
    fun 引用しなかった資料は分けて出す() {
        val result = review("コルーチンは中断と再開ができます [1]。", kotlin, compose)

        assertEquals(listOf(1), result.sources.map { it.number })
        assertEquals(listOf(2), result.uncitedNumbers)
    }

    @Test
    fun 引用がなければ照らし合わせない() {
        val result = review("こんにちは。ご用件をどうぞ。", kotlin)

        assertTrue(result.sources.isEmpty())
        assertTrue(result.claims.isEmpty())
        assertEquals(listOf(1), result.uncitedNumbers)
    }

    @Test
    fun 引用した資料に無く別の資料にある文は_その番号を示す() {
        val result = review("Lazy リストには key を指定すると再コンポーズの範囲が小さくなります [1]。", kotlin, compose)

        val claim = result.flagged.single()
        assertEquals(ClaimStatus.OTHER_SOURCE, claim.status)
        assertEquals(2, claim.betterSource)
        assertEquals("[1] より [2] に近い内容です", claimNote(claim))
    }

    @Test
    fun どの資料にも無い文は確かめる文にする() {
        val result = review("月面基地の建設費用は三兆円を超えると発表されました [1]。", kotlin, compose)

        val claim = result.flagged.single()
        assertEquals(ClaimStatus.NOT_FOUND, claim.status)
        assertEquals("[1] に根拠が見つかりません", claimNote(claim))
    }

    @Test
    fun 資料に無い番号を出し_資料の次の番号は添付かもしれないので出さない() {
        val result = review("コルーチンは中断と再開ができます [1][2][7]。", kotlin)

        assertEquals(listOf(7), result.unknownNumbers)
    }

    @Test
    fun 削除済みの資料は引用元に出さず_照らし合わせない() {
        val result = review("コルーチンは中断と再開ができます [1]。", ids = listOf(99L))

        assertTrue(result.sources.isEmpty())
        assertEquals(ClaimStatus.UNCHECKED, result.claims.single().status)
        assertEquals(0, result.checkedCount)
        assertTrue(result.flagged.isEmpty())
    }

    @Test
    fun 資料と言語が違えば照らし合わせない() {
        val english = source(3, "Coroutines are lightweight threads that can suspend and resume.")

        val result = review("コルーチンは中断と再開ができます [1]。", english)

        assertEquals(ClaimStatus.UNCHECKED, result.claims.single().status)
        assertEquals(listOf(1), result.sources.map { it.number })
    }

    @Test
    fun 要約も資料として比べる() {
        val summarized = testBookmark(4).copy(summary = "コルーチンは中断と再開ができる仕組みです。")

        val result = review("コルーチンは中断と再開ができます [1]。", summarized)

        assertEquals(ClaimStatus.SUPPORTED, result.claims.single().status)
    }

    @Test
    fun 箇条書きの文も読み_コードの中の番号は読まない() {
        val answer = """
            - コルーチンは中断と再開ができます [1]

            ```
            val first = list[2]
            ```
        """.trimIndent()

        val result = review(answer, kotlin, compose)

        assertEquals(listOf(listOf(1)), result.claims.map { it.cited })
        assertEquals(listOf(2), result.uncitedNumbers)
    }
}
