package com.unchunks.echomark.domain.rediscover

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import java.util.concurrent.TimeUnit

/** 再発見ダイジェストで通知するブックマークを選ぶ純粋ロジック。 */
object RediscoverSelector {
    const val STALE_DAYS = 30L
    const val COOLDOWN_DAYS = 30L
    const val MAX_ITEMS = 3

    /**
     * [STALE_DAYS] 日以上開かれておらず、直近 [COOLDOWN_DAYS] 日以内に通知していないものを、
     * 最終アクセスが古い順に最大 [limit] 件返す。
     * @param notifiedAt bookmarkId -> 前回通知した時刻(epoch millis)
     */
    fun select(
        candidates: List<Bookmark>,
        notifiedAt: Map<Long, Long>,
        now: Long,
        staleDays: Long = STALE_DAYS,
        cooldownDays: Long = COOLDOWN_DAYS,
        limit: Int = MAX_ITEMS
    ): List<Bookmark> {
        val staleMs = TimeUnit.DAYS.toMillis(staleDays)
        val cooldownMs = TimeUnit.DAYS.toMillis(cooldownDays)
        return candidates
            .filter { now - it.lastAccessedAt >= staleMs }
            .filter { b -> notifiedAt[b.id]?.let { now - it >= cooldownMs } ?: true }
            .sortedBy { it.lastAccessedAt }
            .take(limit)
    }
}
