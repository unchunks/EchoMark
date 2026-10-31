package com.unchunks.echomark.domain.bookmark.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ReadingRecommenderTest {

    private val now = 100L * 24 * 60 * 60 * 1000
    private val day = TimeUnit.DAYS.toMillis(1)

    private fun bookmark(
        id: Long,
        ageDays: Int = 30,
        tags: List<String> = emptyList(),
        category: String? = null,
        type: BookmarkType = BookmarkType.URL,
        favorite: Boolean = false,
        archived: Boolean = false,
        readAt: Long? = null,
        content: String? = null
    ) = Bookmark(
        id = id, type = type, title = "b$id", tags = tags, category = category, isFavorite = favorite, isArchived = archived,
        readAt = readAt, content = content, createdAt = now - ageDays * day, lastAccessedAt = now - ageDays * day
    )

    @Test
    fun 最近読んだものに多いタグを持つ未読を先に出し_理由はそのタグ() {
        val read = listOf(bookmark(1, tags = listOf("kotlin"), readAt = 1), bookmark(2, tags = listOf("kotlin", "android"), readAt = 1))
        val candidates = listOf(
            bookmark(10, tags = listOf("cooking")),
            bookmark(11, tags = listOf("Kotlin"))
        )

        val result = ReadingRecommender.pick(candidates, read, now)

        assertEquals(listOf(11L, 10L), result.map { it.bookmark.id })
        assertEquals(RecommendationReason.SharedTag("Kotlin"), result.first().reason)
    }

    @Test
    fun 読み終えたもの_アーカイブ_読めない種類は出さない() {
        val candidates = listOf(
            bookmark(1, readAt = 5),
            bookmark(2, archived = true),
            bookmark(3, type = BookmarkType.IMAGE),
            bookmark(4, type = BookmarkType.AUDIO),
            bookmark(5, type = BookmarkType.VIDEO),
            bookmark(6, type = BookmarkType.PDF),
            bookmark(7, type = BookmarkType.TEXT)
        )

        assertEquals(setOf(6L, 7L), ReadingRecommender.pick(candidates, emptyList(), now).map { it.bookmark.id }.toSet())
    }

    @Test
    fun お気に入りのまま読んでいないものを上げる() {
        val candidates = listOf(bookmark(1, ageDays = 10), bookmark(2, ageDays = 40, favorite = true))

        val result = ReadingRecommender.pick(candidates, emptyList(), now)

        assertEquals(listOf(2L, 1L), result.map { it.bookmark.id })
        assertEquals(RecommendationReason.Favorite, result.first().reason)
    }

    @Test
    fun すぐ読めるものを上げる() {
        val short = bookmark(1, ageDays = 60, content = "あ".repeat(1_000))
        val long = bookmark(2, ageDays = 60, content = "あ".repeat(10_000))

        val result = ReadingRecommender.pick(listOf(long, short), emptyList(), now)

        assertEquals(listOf(1L, 2L), result.map { it.bookmark.id })
        assertEquals(RecommendationReason.Short(2), result.first().reason)
    }

    @Test
    fun 手がかりが無ければ新しい順で理由は最近保存() {
        val candidates = listOf(bookmark(1, ageDays = 50), bookmark(2, ageDays = 1), bookmark(3, ageDays = 20))

        val result = ReadingRecommender.pick(candidates, emptyList(), now)

        assertEquals(listOf(2L, 3L, 1L), result.map { it.bookmark.id })
        assertTrue(result.all { it.reason == RecommendationReason.Recent })
    }

    @Test
    fun 件数の上限があり_同点なら新しい方を先にする() {
        val candidates = (1L..10L).map { bookmark(it, ageDays = 30) }

        val result = ReadingRecommender.pick(candidates, emptyList(), now, limit = 3)

        assertEquals(3, result.size)
        // 保存日時が同じなら、ID の大きい(新しい)方が先
        assertEquals(listOf(10L, 9L, 8L), result.map { it.bookmark.id })
    }

    @Test
    fun 最近読んだカテゴリも手がかりになる() {
        val read = listOf(bookmark(1, category = "技術", readAt = 1))
        val candidates = listOf(bookmark(10, category = "旅行"), bookmark(11, category = "技術"))

        val result = ReadingRecommender.pick(candidates, read, now)

        assertEquals(11L, result.first().bookmark.id)
        assertEquals(RecommendationReason.SharedCategory("技術"), result.first().reason)
    }
}
