package com.unchunks.echomark.domain.review

import com.unchunks.echomark.domain.bookmark.model.Bookmark

/**
 * 週1回の通知の中身。通知のタップで何を開くかは [opensReview](true ならふり返りの画面、false なら先頭の再発見のブックマーク)。
 */
data class DigestContent(val title: String, val text: String, val bigText: String, val opensReview: Boolean)

/**
 * 週1回の通知(再発見ダイジェスト)の中身を決める純粋な処理。
 * 今週のふり返りと、しばらく開いていないブックマーク(再発見)を 1 つの通知にまとめる。
 * ふり返ることが無く(保存も読みも無く、復習の日時が来たものも無い)、再発見するものも無いときは、通知しない(null)。
 */
object WeeklyDigest {
    /** 通知に並べる再発見のブックマークの題の数 */
    const val MAX_LISTED_TITLES = 3

    fun build(review: WeeklyReview, rediscovered: List<Bookmark>, rediscoverTitle: String): DigestContent? {
        val hasReview = !review.isQuiet || review.dueReviews > 0
        if (!hasReview && rediscovered.isEmpty()) return null

        val titles = rediscovered.take(MAX_LISTED_TITLES).joinToString("\n") { "・${it.title}" }
        if (!hasReview) {
            return DigestContent(
                title = rediscoverTitle,
                text = rediscovered.first().title,
                bigText = titles,
                opensReview = false
            )
        }
        val summary = review.summaryLine()
        val big = if (rediscovered.isEmpty()) summary else "$summary\n\n$rediscoverTitle\n$titles"
        return DigestContent(title = "今週のふり返り", text = summary, bigText = big, opensReview = true)
    }
}
