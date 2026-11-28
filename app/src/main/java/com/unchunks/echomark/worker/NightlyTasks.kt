package com.unchunks.echomark.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * 毎晩、決まった時刻に 1 回まとめて行う仕事。[NightlyTaskWorker] が登録されたものを順に実行する。
 * 新しい仕事(購読しているフィードの取得など)は、実装を Hilt の `@IntoSet` で [NightlyTask] の集合に足すだけで加わる。
 *
 * 時間のかかる処理は [run] の中で行わず、条件(充電中など)を付けたワークとして積む([NightlyProcessingTask] など)。
 */
interface NightlyTask {
    /** ログに出す名前 */
    val name: String

    /** 今夜の仕事を行う(行うことが無ければ何もしない)。失敗しても他の仕事は続ける */
    suspend fun run()
}

/** 登録された [NightlyTask] を順に実行する。1 日 1 回、[NightlyTaskScheduler] の時刻に動く。 */
@HiltWorker
class NightlyTaskWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val tasks: Set<@JvmSuppressWildcards NightlyTask>
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        for (task in tasks.sortedBy { it.name }) {
            try {
                task.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "夜間の仕事に失敗: %s", task.name)
            }
        }
        return Result.success()
    }
}

/** [NightlyTaskWorker] を毎日 [hour] 時に動かす。 */
object NightlyTaskScheduler {
    const val WORK_NAME = "nightly_tasks"

    /**
     * 毎日 [hour]:00 に動くよう登録する。アプリの起動時は KEEP(登録済みならそのまま)、時刻を変えたときは UPDATE。
     * 夜間の仕事が無い日も動くが、各仕事は行うことが無ければ何もしない。
     */
    fun schedule(workManager: WorkManager, hour: Int, policy: ExistingPeriodicWorkPolicy) {
        val request = PeriodicWorkRequestBuilder<NightlyTaskWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayMillis(ZonedDateTime.now(), hour), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, policy, request)
    }

    /** [now] から見て次に訪れる [hour]:00 までのミリ秒。ちょうど今なら 1 日後。 */
    internal fun initialDelayMillis(now: ZonedDateTime, hour: Int): Long {
        var target = now.withHour(hour.coerceIn(0, 23)).withMinute(0).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target).toMillis()
    }
}
