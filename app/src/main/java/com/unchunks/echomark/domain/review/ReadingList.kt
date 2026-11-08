package com.unchunks.echomark.domain.review

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.readingMinutes

/**
 * 「あとで読む」(#92)の中身。入れた順(古いものが先)に並べ、読み終えるまでの時間の目安を出す。
 * 読む(詳細画面を開く・読んだことにする)と自動で外れるので、ここに残っているのは「まだ読んでいない」ものと、
 * 読んだあとにもう一度読むつもりで入れたもの。
 *
 * @param totalMinutes 本文から時間の目安が出せたものの合計(分)
 * @param unknownCount 時間の目安が出せなかった(本文が無い・短い・ファイルなど)件数
 */
data class ReadingList(val items: List<Bookmark>, val totalMinutes: Int, val unknownCount: Int) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** 次に読むもの(先頭) */
    val next: Bookmark? get() = items.firstOrNull()

    companion object {
        val EMPTY = ReadingList(emptyList(), 0, 0)

        /** [bookmarks] から「あとで読む」に入れたものを集める。 */
        fun of(bookmarks: List<Bookmark>): ReadingList {
            val items = bookmarks
                .filter { it.readingListAt != null }
                .sortedWith(compareBy<Bookmark>({ it.readingListAt }, { it.id }))
            val minutes = items.map { it.readingMinutes() }
            return ReadingList(items, minutes.filterNotNull().sum(), minutes.count { it == null })
        }
    }

    /** 「全部で約25分(ほか 2 件は目安なし)」の言い方。目安が 1 件も出せなければ null。 */
    fun totalLabel(): String? {
        if (items.isEmpty() || totalMinutes == 0) return null
        val hours = totalMinutes / 60
        val rest = totalMinutes % 60
        val time = when {
            hours == 0 -> "約${rest}分"
            rest == 0 -> "約${hours}時間"
            else -> "約${hours}時間${rest}分"
        }
        return if (unknownCount > 0) "全部で$time(ほか${unknownCount}件は目安なし)" else "全部で$time"
    }
}
