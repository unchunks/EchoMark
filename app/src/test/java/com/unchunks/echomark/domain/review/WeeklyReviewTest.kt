package com.unchunks.echomark.domain.review

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class WeeklyReviewTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 100 * day

    private fun bookmark(
        id: Long, daysAgo: Double, tags: List<String> = emptyList(), readDaysAgo: Double? = null,
        archived: Boolean = false, readingListAt: Long? = null, content: String? = null
    ) = Bookmark(
        id = id, type = BookmarkType.URL, title = "題$id", createdAt = now - (daysAgo * day).toLong(), lastAccessedAt = 0,
        tags = tags, readAt = readDaysAgo?.let { now - (it * day).toLong() }, isArchived = archived,
        readingListAt = readingListAt, content = content
    )

    // ---- ふり返り ----

    @Test
    fun 保存と読んだ数を直近7日間と前の7日間で数える() {
        val review = WeeklyReviewCalculator.compute(
            listOf(
                bookmark(1, 1.0), bookmark(2, 6.9), bookmark(3, 7.1), bookmark(4, 13.0), bookmark(5, 20.0),
                bookmark(6, 30.0, readDaysAgo = 2.0), bookmark(7, 30.0, readDaysAgo = 9.0), bookmark(8, 30.0, readDaysAgo = 40.0)
            ),
            now
        )
        assertEquals(2, review.savedCount)
        assertEquals(2, review.savedPrevious)
        assertEquals(1, review.readCount)
        assertEquals(1, review.readPrevious)
        assertFalse(review.isQuiet)
    }

    @Test
    fun 期間の境目は_7日ちょうど前を含み_いまは含まない() {
        val review = WeeklyReviewCalculator.compute(
            listOf(bookmark(1, 7.0), bookmark(2, 0.0), bookmark(3, 14.0)), now
        )
        assertEquals(1, review.savedCount)
        // 14 日前ちょうどは前の期間に入る
        assertEquals(1, review.savedPrevious)
    }

    @Test
    fun 何も無ければ静か() {
        val review = WeeklyReviewCalculator.compute(emptyList(), now)
        assertTrue(review.isQuiet)
        assertEquals(0, review.unreadBacklog)
        assertTrue(review.topTags.isEmpty())
    }

    @Test
    fun 未読の積み残しはアーカイブを除いて数え_読む予定の数も出す() {
        val review = WeeklyReviewCalculator.compute(
            listOf(
                bookmark(1, 30.0), bookmark(2, 30.0, readDaysAgo = 20.0), bookmark(3, 30.0, archived = true),
                bookmark(4, 3.0, readingListAt = now - day)
            ),
            now
        )
        assertEquals(2, review.unreadBacklog)
        assertEquals(1, review.readingListCount)
    }

    @Test
    fun よく付いたタグは保存したものから多い順_同数なら名前順() {
        val review = WeeklyReviewCalculator.compute(
            listOf(
                bookmark(1, 1.0, listOf("Kotlin", "Android")), bookmark(2, 2.0, listOf("Kotlin")),
                bookmark(3, 3.0, listOf("料理")), bookmark(4, 3.0, listOf("Android")),
                // 前の期間のタグは数えない
                bookmark(5, 10.0, listOf("旅行", "旅行"))
            ),
            now
        )
        assertEquals(listOf("Android" to 2, "Kotlin" to 2, "料理" to 1), review.topTags.map { it.label to it.count })
    }

    @Test
    fun 初めて付いたタグは_それ以前のどのブックマークにも付いていないもの() {
        val review = WeeklyReviewCalculator.compute(
            listOf(
                bookmark(1, 1.0, listOf("Rust", "Kotlin")), bookmark(2, 2.0, listOf("Zig")),
                bookmark(3, 20.0, listOf("Kotlin"))
            ),
            now
        )
        assertEquals(listOf("Rust", "Zig"), review.newTags)
    }

    @Test
    fun 読んだ時間の目安は読んだものの本文から足し_本文が無いものは入れない() {
        val long = "あ".repeat(1500) // 日本語 1500 文字 = 3 分
        val review = WeeklyReviewCalculator.compute(
            listOf(
                bookmark(1, 30.0, readDaysAgo = 1.0, content = long), bookmark(2, 30.0, readDaysAgo = 2.0, content = long),
                bookmark(3, 30.0, readDaysAgo = 2.0), bookmark(4, 30.0, content = long)
            ),
            now
        )
        assertEquals(6, review.readingMinutes)
    }

    @Test
    fun 保存したものは新しい順に上限まで_読まずに放ってあるものは古い順() {
        val saved = (1..8).map { bookmark(it.toLong(), it * 0.5) }
        val stale = listOf(bookmark(20, 40.0), bookmark(21, 30.0), bookmark(22, 8.0), bookmark(23, 6.9), bookmark(24, 50.0, readDaysAgo = 45.0))
        val review = WeeklyReviewCalculator.compute(saved + stale, now)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), review.recentSaved.map { it.id })
        // 7 日以上たっていて、まだ読んでいない。古い順
        assertEquals(listOf(20L, 21L, 22L), review.oldestUnread.map { it.id })
    }

    @Test
    fun 復習の数を持ち_要約の一行に入る() {
        val review = WeeklyReviewCalculator.compute(
            listOf(bookmark(1, 1.0), bookmark(2, 30.0)), now, dueReviews = 3
        )
        assertEquals("この7日間: 保存1件・読了0件・未読2件・復習3件", review.summaryLine())
        assertEquals("この7日間: 保存0件・読了0件", WeeklyReviewCalculator.compute(emptyList(), now).summaryLine())
    }

    // ---- 通知の中身 ----

    private val title = "こんなブックマーク、忘れていませんか?"

    @Test
    fun ふり返ることも再発見するものも無ければ通知しない() {
        assertNull(WeeklyDigest.build(WeeklyReviewCalculator.compute(emptyList(), now), emptyList(), title))
    }

    @Test
    fun 再発見だけなら従来どおり_先頭のブックマークを開く通知() {
        val content = WeeklyDigest.build(
            WeeklyReviewCalculator.compute(emptyList(), now), listOf(bookmark(1, 50.0), bookmark(2, 60.0)), title
        )!!
        assertEquals(title, content.title)
        assertEquals("題1", content.text)
        assertEquals("・題1\n・題2", content.bigText)
        assertFalse(content.opensReview)
    }

    @Test
    fun ふり返りがあれば_ふり返りの画面を開く通知にし_再発見も並べる() {
        val review = WeeklyReviewCalculator.compute(listOf(bookmark(1, 1.0)), now)
        val content = WeeklyDigest.build(review, listOf(bookmark(2, 50.0)), title)!!
        assertEquals("今週のふり返り", content.title)
        assertEquals(review.summaryLine(), content.text)
        assertEquals(review.summaryLine() + "\n\n" + title + "\n・題2", content.bigText)
        assertTrue(content.opensReview)
    }

    @Test
    fun 復習の日時が来たものだけでも通知する() {
        val review = WeeklyReviewCalculator.compute(emptyList(), now, dueReviews = 2)
        val content = WeeklyDigest.build(review, emptyList(), title)!!
        assertEquals("今週のふり返り", content.title)
        assertEquals(review.summaryLine(), content.bigText)
        assertTrue(content.opensReview)
    }

    @Test
    fun 通知に並べる再発見は3件まで() {
        val content = WeeklyDigest.build(
            WeeklyReviewCalculator.compute(emptyList(), now), (1L..5L).map { bookmark(it, 50.0) }, title
        )!!
        assertEquals(WeeklyDigest.MAX_LISTED_TITLES, content.bigText.lines().size)
    }
}
