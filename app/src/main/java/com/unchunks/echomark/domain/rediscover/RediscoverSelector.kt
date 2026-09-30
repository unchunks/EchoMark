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

    /** 一覧上部の「今日の再発見」で、しばらく開いていないとみなす日数(通知より短め) */
    const val IN_APP_STALE_DAYS = 14L

    /**
     * 一覧上部の「今日の再発見」に出すブックマークを選ぶ。
     * [IN_APP_STALE_DAYS] 日以上開いていない、アーカイブしていないものから、日付ごとに決まった [limit] 件を返す
     * (同じ日は何度開いても同じ顔ぶれ、日が変わると入れ替わる)。
     * @param dayIndex 日付の通し番号(例: エポック日)。これで候補の並びを回す
     */
    fun pickDaily(
        candidates: List<Bookmark>,
        now: Long,
        dayIndex: Long,
        staleDays: Long = IN_APP_STALE_DAYS,
        limit: Int = MAX_ITEMS
    ): List<Bookmark> {
        val staleMs = TimeUnit.DAYS.toMillis(staleDays)
        val pool = candidates
            .filter { !it.isArchived && now - it.lastAccessedAt >= staleMs }
            .sortedWith(compareBy<Bookmark> { it.lastAccessedAt }.thenBy { it.id })
        if (pool.size <= limit) return pool
        val offset = Math.floorMod(dayIndex * limit, pool.size.toLong()).toInt()
        return (pool.drop(offset) + pool.take(offset)).take(limit)
    }
}
