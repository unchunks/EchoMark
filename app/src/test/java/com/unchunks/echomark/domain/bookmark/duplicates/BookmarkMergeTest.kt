package com.unchunks.echomark.domain.bookmark.duplicates

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkMergeTest {

    private fun bookmark(
        id: Long,
        createdAt: Long = 100,
        lastAccessedAt: Long = 100,
        isFavorite: Boolean = false,
        isArchived: Boolean = false,
        readAt: Long? = null,
        content: String? = null,
        snapshotPath: String? = null,
        tags: List<String> = emptyList(),
        aiTags: Set<String> = emptySet()
    ) = Bookmark(
        id = id, type = BookmarkType.URL, title = "t", createdAt = createdAt, lastAccessedAt = lastAccessedAt,
        isFavorite = isFavorite, isArchived = isArchived, readAt = readAt, content = content,
        snapshotPath = snapshotPath, snapshotPdfPath = snapshotPath?.let { "$it.pdf" }, snapshotAt = snapshotPath?.let { 5L },
        tags = tags, aiTags = aiTags
    )

    @Test
    fun 保存日時は古い方_最後に開いた日時は新しい方() {
        val fields = BookmarkMerge.merge(bookmark(1, createdAt = 200, lastAccessedAt = 300), bookmark(2, createdAt = 50, lastAccessedAt = 90))
        assertEquals(50L, fields.createdAt)
        assertEquals(300L, fields.lastAccessedAt)
    }

    @Test
    fun お気に入りはどちらかがそうなら残る() {
        assertTrue(BookmarkMerge.merge(bookmark(1), bookmark(2, isFavorite = true)).isFavorite)
        assertTrue(BookmarkMerge.merge(bookmark(1, isFavorite = true), bookmark(2)).isFavorite)
        assertFalse(BookmarkMerge.merge(bookmark(1), bookmark(2)).isFavorite)
    }

    @Test
    fun アーカイブは両方がアーカイブのときだけ() {
        assertTrue(BookmarkMerge.merge(bookmark(1, isArchived = true), bookmark(2, isArchived = true)).isArchived)
        assertFalse(BookmarkMerge.merge(bookmark(1, isArchived = true), bookmark(2)).isArchived)
        assertFalse(BookmarkMerge.merge(bookmark(1), bookmark(2, isArchived = true)).isArchived)
    }

    @Test
    fun 読んだ日時はどちらかが読んでいれば古い方() {
        assertEquals(40L, BookmarkMerge.merge(bookmark(1, readAt = 70), bookmark(2, readAt = 40)).readAt)
        assertEquals(70L, BookmarkMerge.merge(bookmark(1, readAt = 70), bookmark(2)).readAt)
        assertEquals(40L, BookmarkMerge.merge(bookmark(1), bookmark(2, readAt = 40)).readAt)
        assertNull(BookmarkMerge.merge(bookmark(1), bookmark(2)).readAt)
    }

    @Test
    fun 本文が空のときだけ消す方の本文を引き継ぐ() {
        assertEquals("残す方", BookmarkMerge.merge(bookmark(1, content = "残す方"), bookmark(2, content = "消す方")).content)
        assertEquals("消す方", BookmarkMerge.merge(bookmark(1, content = "  "), bookmark(2, content = "消す方")).content)
        assertEquals("消す方", BookmarkMerge.merge(bookmark(1), bookmark(2, content = "消す方")).content)
        assertNull(BookmarkMerge.merge(bookmark(1), bookmark(2)).content)
    }

    @Test
    fun オフラインコピーは残す方に無いときだけ引き継ぐ() {
        val moved = BookmarkMerge.merge(bookmark(1), bookmark(2, snapshotPath = "snapshots/b.mhtml"))
        assertEquals("snapshots/b.mhtml", moved.snapshotPath)
        assertEquals("snapshots/b.mhtml.pdf", moved.snapshotPdfPath)
        assertEquals(5L, moved.snapshotAt)

        val kept = BookmarkMerge.merge(bookmark(1, snapshotPath = "snapshots/a.mhtml"), bookmark(2, snapshotPath = "snapshots/b.mhtml"))
        assertEquals("snapshots/a.mhtml", kept.snapshotPath)
    }

    @Test
    fun 足すタグは残す方に無いものと_AIのタグをユーザーのタグにする分() {
        val keep = bookmark(1, tags = listOf("a", "b"), aiTags = setOf("b"))
        val remove = bookmark(2, tags = listOf("a", "b", "c", "d"), aiTags = setOf("d"))
        // b は残す方では AI のタグ・消す方ではユーザーのタグ。c, d は残す方に無い
        assertEquals(listOf("b", "c", "d"), BookmarkMerge.tagsToAdd(keep, remove))
    }

    @Test
    fun 戻す値は元のブックマークから作れる() {
        val b = bookmark(1, createdAt = 7, isFavorite = true, readAt = 3, content = "c", snapshotPath = "s")
        val fields = BookmarkMerge.Fields.of(b)
        assertEquals(7L, fields.createdAt)
        assertEquals("s", fields.snapshotPath)
        assertEquals(3L, fields.readAt)
        assertTrue(fields.isFavorite)
    }

    // ---- 場所(#85) ----

    private fun located(id: Long, lat: Double?, lng: Double?, place: String? = null) =
        bookmark(id).copy(latitude = lat, longitude = lng, placeName = place)

    @Test
    fun 場所は残す方に座標が無いときだけ消す方のものを引き継ぐ() {
        val kept = BookmarkMerge.merge(located(1, 35.0, 139.0), located(2, 10.0, 20.0))
        assertEquals(35.0, kept.latitude!!, 0.0)
        assertEquals(139.0, kept.longitude!!, 0.0)

        val moved = BookmarkMerge.merge(located(1, null, null), located(2, 10.0, 20.0, "カフェ"))
        assertEquals(10.0, moved.latitude!!, 0.0)
        assertEquals(20.0, moved.longitude!!, 0.0)
        assertEquals("カフェ", moved.placeName)
    }

    @Test
    fun 場所の名前は残す方を主にし_無ければ消す方のものを使う() {
        assertEquals("残す", BookmarkMerge.merge(located(1, null, null, "残す"), located(2, null, null, "消す")).placeName)
        assertEquals("消す", BookmarkMerge.merge(located(1, 1.0, 2.0, "  "), located(2, null, null, "消す")).placeName)
        assertNull(BookmarkMerge.merge(located(1, null, null), located(2, null, null)).placeName)
    }

    @Test
    fun 座標が片方だけの値は引き継がない() {
        // 片方だけの座標は座標として扱わない(geoPoint が null)ので、消す方の完全な座標があればそちらを使う
        val fields = BookmarkMerge.merge(located(1, 35.0, null), located(2, 10.0, 20.0))
        assertEquals(10.0, fields.latitude!!, 0.0)
        assertEquals(20.0, fields.longitude!!, 0.0)
    }

    @Test
    fun Fieldsのofは場所もそのまま写す() {
        val fields = BookmarkMerge.Fields.of(located(1, 35.0, 139.0, "名前"))
        assertEquals(35.0, fields.latitude!!, 0.0)
        assertEquals("名前", fields.placeName)
    }
}
