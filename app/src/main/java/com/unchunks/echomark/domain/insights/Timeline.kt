package com.unchunks.echomark.domain.insights

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** タイムラインを並べる基準の日時。 */
enum class TimelineBasis {
    /** 保存した日 */
    SAVED,

    /** 最後に開いた日 */
    LAST_OPENED
}

/** タイムラインの1行(月の見出し・日の見出し・ブックマーク)。LazyColumn にそのまま並べる。 */
sealed interface TimelineRow {
    /** 一覧の中で一意なキー */
    val key: String

    data class MonthHeader(val month: YearMonth, val count: Int) : TimelineRow {
        override val key: String get() = "m$month"
    }

    data class DayHeader(val date: LocalDate) : TimelineRow {
        override val key: String get() = "d$date"
    }

    data class Item(val bookmark: Bookmark, val date: LocalDate) : TimelineRow {
        override val key: String get() = "b${bookmark.id}"
    }
}

/** ブックマークを日付順に並べ、月・日の見出しをはさんだ行にする純粋な処理(新しい順)。 */
object Timeline {

    fun rows(bookmarks: List<Bookmark>, basis: TimelineBasis, zone: ZoneId): List<TimelineRow> {
        if (bookmarks.isEmpty()) return emptyList()
        fun millis(b: Bookmark) = if (basis == TimelineBasis.SAVED) b.createdAt else b.lastAccessedAt
        val sorted = bookmarks.sortedWith(compareByDescending<Bookmark> { millis(it) }.thenByDescending { it.id })
        val dated = sorted.map { it to Instant.ofEpochMilli(millis(it)).atZone(zone).toLocalDate() }
        val result = ArrayList<TimelineRow>(dated.size + dated.size / 2)
        var index = 0
        while (index < dated.size) {
            val month = YearMonth.from(dated[index].second)
            val monthEnd = firstIndexFrom(dated, index, dated.size) { YearMonth.from(it.second) != month }
            result += TimelineRow.MonthHeader(month, monthEnd - index)
            var dayStart = index
            while (dayStart < monthEnd) {
                val date = dated[dayStart].second
                val dayEnd = firstIndexFrom(dated, dayStart, monthEnd) { it.second != date }
                result += TimelineRow.DayHeader(date)
                for (i in dayStart until dayEnd) result += TimelineRow.Item(dated[i].first, date)
                dayStart = dayEnd
            }
            index = monthEnd
        }
        return result
    }

    /** [from] 以降で最初に条件に合う位置(無ければ [limit])。 */
    private inline fun <T> firstIndexFrom(list: List<T>, from: Int, limit: Int, predicate: (T) -> Boolean): Int {
        for (i in from until limit) if (predicate(list[i])) return i
        return limit
    }
}
