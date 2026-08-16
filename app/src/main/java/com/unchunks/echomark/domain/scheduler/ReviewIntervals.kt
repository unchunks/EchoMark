package com.unchunks.echomark.domain.scheduler

object ReviewIntervals {
    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L

    val stagesInMillis: List<Long> = listOf(
        1, // TODO: 1 * ONE_DAY_MS, に戻す
        1, // TODO: 3 * ONE_DAY_MS, に戻す
        1, // TODO: 7 * ONE_DAY_MS, に戻す
        1  // TODO: 30 * ONE_DAY_MS に戻す
    )

    fun nextReviewAt(fromTime: Long, reviewStage: Int): Long? {
        val interval = stagesInMillis.getOrNull(reviewStage) ?: return null
        return fromTime + interval
    }
}
