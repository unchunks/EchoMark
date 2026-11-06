package com.unchunks.echomark.domain.insights

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** 表やグラフの1項目(名前と件数)。 */
data class CountEntry(val label: String, val count: Int)

/** 月ごとの件数。[added] はその月に保存した数、[read] はその月に読んだ数。 */
data class MonthlyActivity(val month: YearMonth, val added: Int, val read: Int)

/** ライブラリ全体の集計。 */
data class LibraryStats(
    val total: Int,
    val unread: Int,
    val read: Int,
    val favorites: Int,
    val archived: Int,
    val withSummary: Int,
    /** 種類ごとの件数(多い順。0 件の種類は含めない) */
    val byType: List<Pair<BookmarkType, Int>>,
    /** カテゴリごとの件数(多い順。多すぎるときは [LibraryStatsCalculator.MAX_CATEGORIES] 個までにして、残りは「その他」にまとめる) */
    val byCategory: List<CountEntry>,
    /** 直近の月ごとの保存数・読了数(古い月から。件数 0 の月も含めて連続させる) */
    val monthly: List<MonthlyActivity>
) {
    companion object {
        val EMPTY = LibraryStats(0, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList())
    }
}

/** ブックマークの一覧から [LibraryStats] を数える純粋な処理。 */
object LibraryStatsCalculator {
    const val MAX_CATEGORIES = 8
    const val DEFAULT_MONTHS = 12

    /** カテゴリが付いていないブックマークの表示名 */
    const val UNCATEGORIZED = "未分類"

    /** 上位に入りきらなかったカテゴリをまとめた表示名 */
    const val OTHER = "その他"

    /**
     * @param months 月ごとの集計に出す月数([now] の月を含む直近)
     */
    fun compute(bookmarks: List<Bookmark>, now: Long, zone: ZoneId, months: Int = DEFAULT_MONTHS): LibraryStats {
        if (bookmarks.isEmpty()) return LibraryStats.EMPTY
        val byType = bookmarks.groupingBy { it.type }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key to it.value }
        val categoryCounts = bookmarks
            .groupingBy { it.category?.takeIf { name -> name.isNotBlank() } ?: UNCATEGORIZED }.eachCount()
            // 未分類は件数が多くても最後に回す(分類できているものの内訳を見たいため)
            .entries.sortedWith(
                compareBy<Map.Entry<String, Int>> { it.key == UNCATEGORIZED }
                    .thenByDescending { it.value }
                    .thenBy { it.key }
            )
            .map { CountEntry(it.key, it.value) }
        return LibraryStats(
            total = bookmarks.size,
            unread = bookmarks.count { !it.isRead },
            read = bookmarks.count { it.isRead },
            favorites = bookmarks.count { it.isFavorite },
            archived = bookmarks.count { it.isArchived },
            withSummary = bookmarks.count { !it.summary.isNullOrBlank() },
            byType = byType,
            byCategory = foldCategories(categoryCounts),
            monthly = monthly(bookmarks, now, zone, months)
        )
    }

    /** 上位 (MAX-1) 個と、残りを足した「その他」にする。 */
    private fun foldCategories(sorted: List<CountEntry>): List<CountEntry> {
        if (sorted.size <= MAX_CATEGORIES) return sorted
        val head = sorted.take(MAX_CATEGORIES - 1)
        val rest = sorted.drop(MAX_CATEGORIES - 1)
        return head + CountEntry(OTHER, rest.sumOf { it.count })
    }

    private fun monthly(bookmarks: List<Bookmark>, now: Long, zone: ZoneId, months: Int): List<MonthlyActivity> {
        val count = months.coerceAtLeast(1)
        val last = monthOf(now, zone)
        val first = last.minusMonths((count - 1).toLong())
        val added = HashMap<YearMonth, Int>()
        val read = HashMap<YearMonth, Int>()
        for (bookmark in bookmarks) {
            added.merge(monthOf(bookmark.createdAt, zone), 1, Int::plus)
            bookmark.readAt?.let { read.merge(monthOf(it, zone), 1, Int::plus) }
        }
        return (0 until count).map { i ->
            val month = first.plusMonths(i.toLong())
            MonthlyActivity(month, added[month] ?: 0, read[month] ?: 0)
        }
    }

    internal fun monthOf(millis: Long, zone: ZoneId): YearMonth = YearMonth.from(Instant.ofEpochMilli(millis).atZone(zone))
}
