package com.unchunks.echomark.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.unchunks.echomark.data.feed.FeedChecker
import com.unchunks.echomark.data.local.dao.SubscriptionDao
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.ProcessingSettings
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 購読しているフィードを確認して、新しい記事・動画を保存する([FeedChecker])。
 * [FeedCheckNightlyTask] が毎晩積み、設定の「充電中だけ」「Wi-Fi など従量課金でない接続のときだけ」を条件にして実行を待つ。
 */
@HiltWorker
class FeedCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val checker: FeedChecker
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val summary = checker.checkAll()
        Timber.i("フィードを確認: %d 件を確認、%d 件が失敗、%d 件を保存", summary.checked, summary.failed, summary.added)
        // すべて失敗したのは通信の問題かもしれないので、数回までやり直す(失敗は購読ごとに記録済みで、続けて失敗すると間引く)
        return if (summary.checked > 0 && summary.failed == summary.checked && runAttemptCount < MAX_RETRIES) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "feed_check"
        private const val MAX_RETRIES = 2
        private const val BACKOFF_MINUTES = 30L

        /** 設定に合わせた実行の条件。充電中だけ(chargingOnly)、Wi-Fi など従量課金でない接続のときだけ(unmeteredOnly) */
        internal fun constraintsFor(settings: ProcessingSettings): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(if (settings.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(settings.chargingOnly)
                .build()

        internal fun requestFor(settings: ProcessingSettings): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<FeedCheckWorker>()
                .setConstraints(constraintsFor(settings))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
    }
}

/**
 * 夜間の仕事([NightlyTask])として、購読しているフィードの確認を積む(#78)。
 * 通信する仕事なので、ここでは行わず、処理の設定(充電中だけ・Wi-Fi など従量課金でない接続のときだけ)を条件にしたワークとして積む。
 * 前の夜のワークが条件を待っている間は、重ねて積まない。購読が無い(または全部オフ)なら何もしない。
 */
class FeedCheckNightlyTask @Inject constructor(
    private val workManager: WorkManager,
    private val subscriptionDao: Lazy<SubscriptionDao>,
    private val appSettings: AppSettingsRepository
) : NightlyTask {
    override val name: String = "feed_subscriptions"

    override suspend fun run() {
        if (subscriptionDao.get().getEnabled().isEmpty()) return
        val settings = appSettings.processingSettings.first()
        workManager.enqueueUniqueWork(FeedCheckWorker.WORK_NAME, ExistingWorkPolicy.KEEP, FeedCheckWorker.requestFor(settings))
    }
}
