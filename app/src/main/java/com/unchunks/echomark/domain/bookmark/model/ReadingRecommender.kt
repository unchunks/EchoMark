package com.unchunks.echomark.domain.bookmark.model

import java.util.concurrent.TimeUnit

/** 「次に読む」におすすめする理由(#61)。画面ではこの中のいちばん強いもの1つだけを見せる。 */
sealed interface RecommendationReason {
    /** 最近読んだものに多いタグ */
    data class SharedTag(val tag: String) : RecommendationReason

    /** 最近読んだものに多いカテゴリ */
    data class SharedCategory(val category: String) : RecommendationReason

    /** お気に入りにしたまま、まだ読んでいない */
    data object Favorite : RecommendationReason

    /** すぐ読める */
    data class Short(val minutes: Int) : RecommendationReason

    /** 最近保存した */
    data object Recent : RecommendationReason
}

data class Recommendation(val bookmark: Bookmark, val reason: RecommendationReason)

/**
 * まだ読んでいないブックマークから、次に読むとよさそうなものを選ぶ(#61)。純粋関数(JVM 単体テスト可能)。
 *
 * 点数は、最近読んだもの(新しい順に重みを下げる)に付いているタグ・カテゴリとの重なり、お気に入りにしたまま読んでいないこと、
 * すぐ読めること、最近保存したことの合計。機械学習は使わず、なぜ選ばれたかを1つの理由で言えるようにしている。
 * 点数が同じなら、新しく保存したものを先にする。
 */
object ReadingRecommender {
    const val DEFAULT_LIMIT = 5

    /** 「すぐ読める」とする分数の上限 */
    const val SHORT_MINUTES = 5

    private const val TAG_WEIGHT = 1.0
    private const val CATEGORY_WEIGHT = 0.6
    private const val FAVORITE_WEIGHT = 0.8
    private const val SHORT_WEIGHT = 0.4
    private const val RECENT_WEIGHT = 0.5

    /** 新しさの点数が半分になる日数 */
    private const val RECENT_HALF_LIFE_DAYS = 14.0

    /** 読む対象にする種類(画像・音声・動画は「読む」ものではない) */
    private val READABLE_TYPES = setOf(BookmarkType.URL, BookmarkType.TEXT, BookmarkType.PDF)

    /**
     * @param candidates 未読のブックマーク。アーカイブ済み・読めない種類・読み終えたものは、ここで除く
     * @param recentlyRead 最近読んだブックマーク(新しい順)。何に興味があるかを知る手がかり
     */
    fun pick(
        candidates: List<Bookmark>,
        recentlyRead: List<Bookmark>,
        now: Long,
        limit: Int = DEFAULT_LIMIT
    ): List<Recommendation> {
        val tagInterest = HashMap<String, Double>()
        val categoryInterest = HashMap<String, Double>()
        recentlyRead.forEachIndexed { index, read ->
            // 新しく読んだものほど重く見る(10 件目で半分)
            val weight = 1.0 / (1.0 + index / 10.0)
            read.tags.forEach { tagInterest.merge(it.lowercase(), weight, Double::plus) }
            read.category?.takeIf { it.isNotBlank() }?.let { categoryInterest.merge(it.lowercase(), weight, Double::plus) }
        }
        val maxTag = tagInterest.values.maxOrNull() ?: 1.0
        val maxCategory = categoryInterest.values.maxOrNull() ?: 1.0

        return candidates
            .filter { !it.isRead && !it.isArchived && it.type in READABLE_TYPES }
            .map { bookmark ->
                // 理由の候補を (点数, 理由) で並べ、点数の合計と、いちばん強い理由を取る
                val parts = ArrayList<Pair<Double, RecommendationReason>>()
                bookmark.tags.maxByOrNull { tagInterest[it.lowercase()] ?: 0.0 }
                    ?.takeIf { tagInterest.containsKey(it.lowercase()) }
                    ?.let { tag -> parts += TAG_WEIGHT * (tagInterest.getValue(tag.lowercase()) / maxTag) to RecommendationReason.SharedTag(tag) }
                bookmark.category?.takeIf { categoryInterest.containsKey(it.lowercase()) }
                    ?.let { category ->
                        parts += CATEGORY_WEIGHT * (categoryInterest.getValue(category.lowercase()) / maxCategory) to
                            RecommendationReason.SharedCategory(category)
                    }
                if (bookmark.isFavorite) parts += FAVORITE_WEIGHT to RecommendationReason.Favorite
                bookmark.readingMinutes()?.takeIf { it <= SHORT_MINUTES }
                    ?.let { parts += SHORT_WEIGHT to RecommendationReason.Short(it) }
                val ageDays = (now - bookmark.createdAt).coerceAtLeast(0).toDouble() / TimeUnit.DAYS.toMillis(1)
                parts += RECENT_WEIGHT * Math.pow(0.5, ageDays / RECENT_HALF_LIFE_DAYS) to RecommendationReason.Recent
                Triple(bookmark, parts.sumOf { it.first }, parts.maxBy { it.first }.second)
            }
            .sortedWith(compareByDescending<Triple<Bookmark, Double, RecommendationReason>> { it.second }.thenByDescending { it.first.createdAt }.thenByDescending { it.first.id })
            .take(limit)
            .map { (bookmark, _, reason) -> Recommendation(bookmark, reason) }
    }
}
