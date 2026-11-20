package com.unchunks.echomark.domain.bookmark.duplicates

import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateDetectorTest {

    private fun neighbor(a: Long, b: Long, distance: Double) = VectorNeighbor(a, b, distance)

    private fun bookmark(
        id: Long,
        tags: List<String> = emptyList(),
        category: String? = null,
        summary: String? = null,
        aiStatus: AiStatus = AiStatus.PENDING,
        createdAt: Long = id,
        title: String = "題$id"
    ) = Bookmark(
        id = id, type = BookmarkType.URL, title = title, tags = tags, category = category, summary = summary,
        aiStatus = aiStatus, createdAt = createdAt, lastAccessedAt = createdAt
    )

    @Test
    fun URLの違いは末尾の斜線とwwwと追跡用のクエリとフラグメントを同じとみなす() {
        val base = DuplicateDetector.urlKey("https://example.com/a")
        assertEquals(base, DuplicateDetector.urlKey("https://www.example.com/a/"))
        assertEquals(base, DuplicateDetector.urlKey("http://example.com/a"))
        assertEquals(base, DuplicateDetector.urlKey("https://example.com/a?utm_source=x&fbclid=abc#top"))
        assertEquals(base, DuplicateDetector.urlKey(" HTTPS://EXAMPLE.com/a "))
    }

    @Test
    fun 意味のあるクエリは同じとみなさない() {
        assertNotEquals(
            DuplicateDetector.urlKey("https://example.com/watch?id=1"),
            DuplicateDetector.urlKey("https://example.com/watch?id=2")
        )
        assertNotEquals(DuplicateDetector.urlKey("https://example.com/a"), DuplicateDetector.urlKey("https://example.com/b"))
    }

    @Test
    fun httpでないものとnullとからはキーを作らない() {
        assertNull(DuplicateDetector.urlKey(null))
        assertNull(DuplicateDetector.urlKey(""))
        assertNull(DuplicateDetector.urlKey("content://media/1"))
        assertNull(DuplicateDetector.urlKey("メモの文章"))
    }

    @Test
    fun YouTubeは動画が同じなら同じとみなす() {
        assertEquals(
            DuplicateDetector.urlKey("https://www.youtube.com/watch?v=abc"),
            DuplicateDetector.urlKey("https://youtu.be/abc?si=zzz")
        )
    }

    @Test
    fun URLが同じ3件は一番古いものとの組にする() {
        val pairs = DuplicateDetector.sameUrlCandidates(
            listOf(
                UrlEntry(7, "https://example.com/a"),
                UrlEntry(3, "https://example.com/a/"),
                UrlEntry(5, "https://www.example.com/a?utm_medium=x"),
                UrlEntry(9, "https://example.com/other")
            )
        )
        assertEquals(setOf(DuplicatePair.of(3, 5), DuplicatePair.of(3, 7)), pairs.map { it.pair }.toSet())
        assertTrue(pairs.all { it.reason == DuplicateReason.SAME_URL && it.distance == null })
    }

    @Test
    fun 組の順番が逆でも同じ組() {
        assertEquals(DuplicatePair.of(2, 9), DuplicatePair.of(9, 2))
        assertEquals(DuplicatePair.of(2, 9), DuplicatePair.parse("2:9"))
        assertNull(DuplicatePair.parse("9:9"))
        assertNull(DuplicatePair.parse("abc"))
        assertNull(DuplicatePair.parse("1:2:3"))
    }

    @Test
    fun 近い組だけが重複の候補になり_URLの一致が先で_近い順に並ぶ() {
        val sensitivity = DuplicateSensitivity.STANDARD
        val near = sensitivity.nearDuplicateDistance
        val result = DuplicateDetector.candidates(
            urls = listOf(UrlEntry(10, "https://a.example/x"), UrlEntry(11, "https://a.example/x/")),
            neighbors = listOf(
                neighbor(1, 2, near - 0.01),
                neighbor(3, 4, near - 0.05),
                neighbor(5, 6, near + 0.01)
            ),
            sensitivity = sensitivity
        )
        assertEquals(
            listOf(DuplicatePair.of(10, 11), DuplicatePair.of(3, 4), DuplicatePair.of(1, 2)),
            result.map { it.pair }
        )
        assertEquals(DuplicateReason.SAME_URL, result.first().reason)
    }

    @Test
    fun 両方向で見つかった組は1つにまとめ_URLの一致を優先する() {
        val result = DuplicateDetector.candidates(
            urls = listOf(UrlEntry(1, "https://a.example/x"), UrlEntry(2, "https://a.example/x/")),
            neighbors = listOf(neighbor(1, 2, 0.01), neighbor(2, 1, 0.02), neighbor(3, 4, 0.05), neighbor(4, 3, 0.03)),
            sensitivity = DuplicateSensitivity.STANDARD
        )
        assertEquals(2, result.size)
        assertEquals(DuplicateReason.SAME_URL, result[0].reason)
        // 同じ組が両方向で見つかったときは近い方の距離
        assertEquals(0.03, result[1].distance!!, 1e-9)
    }

    @Test
    fun 両方残すと答えた組は出さない() {
        val result = DuplicateDetector.candidates(
            urls = emptyList(),
            neighbors = listOf(neighbor(1, 2, 0.01), neighbor(3, 4, 0.01)),
            sensitivity = DuplicateSensitivity.STANDARD,
            dismissed = setOf(DuplicatePair.of(2, 1))
        )
        assertEquals(listOf(DuplicatePair.of(3, 4)), result.map { it.pair })
    }

    @Test
    fun 厳しさで候補の数が変わる() {
        val neighbors = listOf(neighbor(1, 2, 0.05), neighbor(3, 4, 0.09), neighbor(5, 6, 0.15))
        fun count(s: DuplicateSensitivity) = DuplicateDetector.candidates(emptyList(), neighbors, s).size
        assertEquals(1, count(DuplicateSensitivity.STRICT))
        assertEquals(2, count(DuplicateSensitivity.STANDARD))
        assertEquals(3, count(DuplicateSensitivity.LOOSE))
    }

    @Test
    fun しきい値は重複より関連の方が大きく_検索の採用の上限より小さい() {
        DuplicateSensitivity.entries.forEach {
            assertTrue(it.nearDuplicateDistance < it.relatedDistance)
            assertTrue(it.relatedDistance < 0.4)
        }
    }

    @Test
    fun 関連のまとまりは近い順につなぎ_3件に満たないものは出さない() {
        val d = DuplicateSensitivity.STANDARD.relatedDistance - 0.01
        val clusters = DuplicateDetector.relatedClusters(
            neighbors = listOf(
                neighbor(1, 2, d), neighbor(2, 3, d), // 1-2-3
                neighbor(10, 11, d), // 2件だけ
                neighbor(20, 21, d + 0.5) // しきい値を超える
            ),
            sensitivity = DuplicateSensitivity.STANDARD
        )
        assertEquals(1, clusters.size)
        assertEquals(listOf(1L, 2L, 3L), clusters.single().ids)
    }

    @Test
    fun 関連のまとまりは上限の人数を超えてつなげない() {
        // 1-2-3-...-20 と数珠つなぎ。近いものから順につなぐので、上限の人数で区切れる
        val neighbors = (1L..19L).map { neighbor(it, it + 1, 0.10 + it * 0.001) }
        val clusters = DuplicateDetector.relatedClusters(neighbors, DuplicateSensitivity.STANDARD)
        assertTrue(clusters.all { it.ids.size <= DuplicateDetector.MAX_GROUP_SIZE })
        assertTrue(clusters.any { it.ids.size == DuplicateDetector.MAX_GROUP_SIZE })
        // どのブックマークも2つのまとまりには入らない
        assertEquals(clusters.sumOf { it.ids.size }, clusters.flatMap { it.ids }.toSet().size)
    }

    @Test
    fun 関連のまとまりは人数の多い順_同じなら近い順() {
        val clusters = DuplicateDetector.relatedClusters(
            listOf(
                neighbor(1, 2, 0.20), neighbor(2, 3, 0.20),
                neighbor(10, 11, 0.10), neighbor(11, 12, 0.10), neighbor(12, 13, 0.10)
            ),
            DuplicateSensitivity.STANDARD
        )
        assertEquals(listOf(listOf(10L, 11L, 12L, 13L), listOf(1L, 2L, 3L)), clusters.map { it.ids })
        assertEquals(0.10, clusters.first().closest, 1e-9)
    }

    @Test
    fun まとまりの名前は2件以上に付くタグ_なければカテゴリ_なければ先頭のタイトル() {
        assertEquals(
            "Kotlin",
            DuplicateDetector.groupLabel(
                listOf(bookmark(1, tags = listOf("Kotlin", "a")), bookmark(2, tags = listOf("Kotlin")), bookmark(3, tags = listOf("b")))
            )
        )
        assertEquals(
            "旅行",
            DuplicateDetector.groupLabel(
                listOf(bookmark(1, tags = listOf("a"), category = "旅行"), bookmark(2, category = "旅行"), bookmark(3))
            )
        )
        assertEquals("題1", DuplicateDetector.groupLabel(listOf(bookmark(1), bookmark(2), bookmark(3))))
    }

    @Test
    fun おすすめは情報の多い方_同点なら先に保存した方() {
        val rich = bookmark(2, tags = listOf("a", "b"), summary = "要約", aiStatus = AiStatus.DONE)
        val poor = bookmark(1)
        assertEquals(2L, DuplicateDetector.suggestKeeper(poor, rich).id)
        assertEquals(2L, DuplicateDetector.suggestKeeper(rich, poor).id)

        assertEquals(1L, DuplicateDetector.suggestKeeper(bookmark(2, createdAt = 50), bookmark(1, createdAt = 50)).id)
        assertEquals(5L, DuplicateDetector.suggestKeeper(bookmark(5, createdAt = 10), bookmark(1, createdAt = 20)).id)
    }
}
