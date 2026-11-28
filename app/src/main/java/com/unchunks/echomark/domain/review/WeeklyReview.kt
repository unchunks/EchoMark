package com.unchunks.echomark.domain.review

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.readingMinutes
import com.unchunks.echomark.domain.insights.CountEntry
import java.util.concurrent.TimeUnit

/**
 * この 7 日間のふり返り(#89)。保存したもの・読んだもの・積んでいる未読・よく付いたタグなど。
 * 「今週」は暦の週ではなく、いまから 7 日さかのぼった期間(週のどの日に見ても同じ長さで比べられるように)。
 *
 * @param savedCount この期間に保存した数。[savedPrevious] はその前の 7 日間
 * @param readCount この期間に読んだ数(読んだ日時が期間内)。[readPrevious] はその前の 7 日間
 * @param readingMinutes この期間に読んだものの、読む時間の目安の合計(目安が出せないものは入れない)
 * @param unreadBacklog 読んでいない(アーカイブ以外の)ブックマークの数
 * @param topTags この期間に保存したものによく付いたタグ(多い順)
 * @param newTags この期間に初めて付いたタグ(それ以前のどのブックマークにも付いていないもの)
 * @param recentSaved この期間に保存したもの(新しい順)
 * @param oldestUnread 読まずに [WeeklyReviewCalculator.STALE_DAYS] 日以上たっているもの(古い順)
 * @param dueReviews 復習の日時が来ているカードの数
 */
data class WeeklyReview(
    val from: Long,
    val to: Long,
    val savedCount: Int,
    val savedPrevious: Int,
    val readCount: Int,
    val readPrevious: Int,
    val readingMinutes: Int,
    val unreadBacklog: Int,
    val readingListCount: Int,
    val topTags: List<CountEntry>,
    val newTags: List<String>,
    val recentSaved: List<Bookmark>,
    val oldestUnread: List<Bookmark>,
    val dueReviews: Int
) {
    /** この期間に何も保存も読みもしていない */
    val isQuiet: Boolean get() = savedCount == 0 && readCount == 0

    /** 通知などで使う 1 行(例: 「この7日間: 保存12件・読了5件・積み残し34件」)。 */
    fun summaryLine(): String = buildString {
        append("この7日間: 保存${savedCount}件・読了${readCount}件")
        if (unreadBacklog > 0) append("・未読${unreadBacklog}件")
        if (dueReviews > 0) append("・復習${dueReviews}件")
    }
}

/** ブックマークの一覧から [WeeklyReview] を作る純粋な処理。 */
object WeeklyReviewCalculator {
    const val PERIOD_DAYS = 7L
    const val MAX_TOP_TAGS = 5
    const val MAX_LISTED = 5

    /** 「読まずに放ってある」とみなす日数 */
    const val STALE_DAYS = 7L

    fun compute(bookmarks: List<Bookmark>, now: Long, dueReviews: Int = 0): WeeklyReview {
        val period = TimeUnit.DAYS.toMillis(PERIOD_DAYS)
        val from = now - period
        val previousFrom = from - period
        fun inPeriod(time: Long) = time in from until now
        fun inPrevious(time: Long) = time in previousFrom until from

        val saved = bookmarks.filter { inPeriod(it.createdAt) }
        val read = bookmarks.filter { b -> b.readAt?.let(::inPeriod) == true }
        val unread = bookmarks.filter { !it.isRead && !it.isArchived }

        val tagsBefore = bookmarks.filter { it.createdAt < from }.flatMapTo(HashSet()) { it.tags }
        val savedTagCounts = saved.flatMap { it.tags.distinct() }.groupingBy { it }.eachCount()
        val topTags = savedTagCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key })
            .take(MAX_TOP_TAGS)
            .map { CountEntry(it.key, it.value) }
        val newTags = savedTagCounts.keys.filter { it !in tagsBefore }.sortedWith(String.CASE_INSENSITIVE_ORDER)

        val staleBefore = now - TimeUnit.DAYS.toMillis(STALE_DAYS)
        return WeeklyReview(
            from = from,
            to = now,
            savedCount = saved.size,
            savedPrevious = bookmarks.count { inPrevious(it.createdAt) },
            readCount = read.size,
            readPrevious = bookmarks.count { b -> b.readAt?.let(::inPrevious) == true },
            readingMinutes = read.sumOf { it.readingMinutes() ?: 0 },
            unreadBacklog = unread.size,
            readingListCount = bookmarks.count { it.readingListAt != null },
            topTags = topTags,
            newTags = newTags,
            recentSaved = saved.sortedWith(compareByDescending<Bookmark> { it.createdAt }.thenByDescending { it.id }).take(MAX_LISTED),
            oldestUnread = unread.filter { it.createdAt < staleBefore }
                .sortedWith(compareBy<Bookmark>({ it.createdAt }, { it.id })).take(MAX_LISTED),
            dueReviews = dueReviews
        )
    }
}
