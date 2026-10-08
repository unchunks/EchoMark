package com.unchunks.echomark.worker

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/** 再発見ダイジェストを週1回、指定の曜日・時刻に実行するスケジューラ。 */
object RediscoverDigestScheduler {
    const val WORK_NAME = "rediscover_digest_weekly"

    /** 曜日・時刻の変更を反映するため UPDATE で登録し直す(次回実行は新しい初期遅延から)。 */
    fun schedule(
        workManager: WorkManager,
        dayOfWeek: DayOfWeek,
        hour: Int,
        minute: Int,
        policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE
    ) {
        val delay = calculateInitialDelayMillis(ZonedDateTime.now(), dayOfWeek, hour, minute)
        val request = PeriodicWorkRequestBuilder<RediscoverDigestWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, policy, request)
    }

    fun cancel(workManager: WorkManager) {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    /** [now] から見て次に訪れる「[dayOfWeek] の [hour]:[minute]」までのミリ秒。ちょうど今なら1週間後。 */
    internal fun calculateInitialDelayMillis(
        now: ZonedDateTime,
        dayOfWeek: DayOfWeek,
        hour: Int,
        minute: Int
    ): Long {
        var target = now
            .with(TemporalAdjusters.nextOrSame(dayOfWeek))
            .withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusWeeks(1)
        return Duration.between(now, target).toMillis()
    }
}
