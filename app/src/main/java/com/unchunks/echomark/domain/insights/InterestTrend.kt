package com.unchunks.echomark.domain.insights

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import java.time.YearMonth
import java.time.ZoneId

/** 関心の変わり方の種類。 */
enum class InterestChangeKind {
    /** 前半より後半に増えた */
    RISING,

    /** 前半より後半に減った */
    FALLING,

    /** 期間の前には無く、後半に初めて現れた */
    NEW
}

/**
 * タグごとの変化。[earlier]・[recent] は期間の前半・後半に保存したブックマークのうち、そのタグが付いた数。
 */
data class InterestChange(val tag: String, val earlier: Int, val recent: Int, val kind: InterestChangeKind) {
    val delta: Int get() = recent - earlier
}

/** よく付くタグ1つの月ごとの件数(グラフの線 1 本)。[counts] は [InterestTrend.months] と同じ並び。 */
data class InterestSeries(val tag: String, val counts: List<Int>) {
    val total: Int get() = counts.sum()
}

/** 期間内の関心の移り変わり。 */
data class InterestTrend(
    val months: List<YearMonth>,
    /** 期間内に多く付いたタグの月ごとの件数(多い順) */
    val series: List<InterestSeries>,
    val rising: List<InterestChange>,
    val falling: List<InterestChange>,
    val newTopics: List<InterestChange>,
    /** 期間内に保存したブックマークの数 */
    val bookmarkCount: Int
) {
    val isEmpty: Boolean get() = series.isEmpty()
}

/** 保存したブックマークのタグの付き方から、関心の移り変わりを調べる純粋な処理。 */
object InterestTrends {
    const val DEFAULT_MONTHS = 6
    const val MAX_SERIES = 5
    const val MAX_CHANGES = 5

    /** 増減として扱う最小の差(1 件の違いは偶然とみなす) */
    const val MIN_DELTA = 2

    /**
     * 直近 [months] か月([now] の月を含む)を前半と後半に分け、タグごとの付き方を比べる。
     * 時期はブックマークを保存した日([Bookmark.createdAt])で見る(タグを付けた日は記録していないため)。
     * 「新しい話題」は、期間より前のどのブックマークにも付いておらず、後半に [MIN_DELTA] 件以上付いたタグ。
     */
    fun compute(bookmarks: List<Bookmark>, now: Long, zone: ZoneId, months: Int = DEFAULT_MONTHS): InterestTrend {
        val count = months.coerceAtLeast(2)
        val last = LibraryStatsCalculator.monthOf(now, zone)
        val first = last.minusMonths((count - 1).toLong())
        val monthList = (0 until count).map { first.plusMonths(it.toLong()) }
        // 後半 = 新しい側の count / 2 か月
        val recentSlotStart = count - count / 2

        val perTag = HashMap<String, IntArray>()
        val seenBefore = HashSet<String>()
        var inPeriod = 0
        for (bookmark in bookmarks) {
            val month = LibraryStatsCalculator.monthOf(bookmark.createdAt, zone)
            if (month < first) {
                seenBefore += bookmark.tags
                continue
            }
            if (month > last) continue
            inPeriod++
            val slot = monthList.indexOf(month)
            for (tag in bookmark.tags.distinct()) perTag.getOrPut(tag) { IntArray(count) }[slot]++
        }

        val changes = perTag.map { (tag, counts) ->
            val earlier = (0 until recentSlotStart).sumOf { counts[it] }
            val recent = (recentSlotStart until count).sumOf { counts[it] }
            Triple(tag, earlier, recent)
        }
        val newTopics = changes
            .filter { (tag, earlier, recent) -> tag !in seenBefore && earlier == 0 && recent >= MIN_DELTA }
            .map { (tag, earlier, recent) -> InterestChange(tag, earlier, recent, InterestChangeKind.NEW) }
            .sortedWith(compareByDescending<InterestChange> { it.recent }.thenBy { it.tag })
            .take(MAX_CHANGES)
        val newNames = newTopics.mapTo(HashSet()) { it.tag }
        val rising = changes
            .filter { (tag, earlier, recent) -> tag !in newNames && recent - earlier >= MIN_DELTA }
            .map { (tag, earlier, recent) -> InterestChange(tag, earlier, recent, InterestChangeKind.RISING) }
            .sortedWith(compareByDescending<InterestChange> { it.delta }.thenBy { it.tag })
            .take(MAX_CHANGES)
        val falling = changes
            .filter { (_, earlier, recent) -> earlier - recent >= MIN_DELTA }
            .map { (tag, earlier, recent) -> InterestChange(tag, earlier, recent, InterestChangeKind.FALLING) }
            .sortedWith(compareBy<InterestChange> { it.delta }.thenBy { it.tag })
            .take(MAX_CHANGES)
        val series = perTag
            .map { (tag, counts) -> InterestSeries(tag, counts.toList()) }
            .sortedWith(compareByDescending<InterestSeries> { it.total }.thenBy { it.tag })
            .take(MAX_SERIES)
        return InterestTrend(monthList, series, rising, falling, newTopics, inPeriod)
    }
}
