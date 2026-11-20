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
import com.unchunks.echomark.data.linkcheck.LinkChecker
import com.unchunks.echomark.data.network.NetworkGate
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
 * 保存したリンクが今も開けるかを確認する(#94。[LinkChecker])。
 * [LinkCheckNightlyTask] が毎晩積み、設定の「充電中だけ」「Wi-Fi など従量課金でない接続のときだけ」を条件にして実行を待つ。
 */
@HiltWorker
class LinkCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val checker: LinkChecker,
    private val gate: NetworkGate
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // オフラインモードの間は確かめない(今夜の分は次の夜に回る)。途中でオンにされたら、LinkChecker が記録せずにやめる
        if (gate.isOffline()) return Result.success()
        val summary = checker.checkDue()
        Timber.i(
            "リンクを確認: %d 件を確認、%d 件が開けず、%d 件が新しく切れた%s",
            summary.checked, summary.failed, summary.newlyDead, if (summary.offline) "(通信が切れて途中でやめた)" else ""
        )
        // 通信が切れて途中でやめたときは、数回までやり直す(記録済みの分は次の確認の対象から外れている)
        return if (summary.offline && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    companion object {
        const val WORK_NAME = "link_check"
        private const val MAX_RETRIES = 2
        private const val BACKOFF_MINUTES = 30L

        /** 設定に合わせた実行の条件。充電中だけ(chargingOnly)、Wi-Fi など従量課金でない接続のときだけ(unmeteredOnly) */
        internal fun constraintsFor(settings: ProcessingSettings): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(if (settings.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(settings.chargingOnly)
                .build()

        internal fun requestFor(settings: ProcessingSettings): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<LinkCheckWorker>()
                .setConstraints(constraintsFor(settings))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
    }
}

/**
 * 夜間の仕事([NightlyTask])として、リンク切れの確認を積む(#94)。
 * 通信する仕事なので、ここでは行わず、処理の設定(充電中だけ・Wi-Fi など従量課金でない接続のときだけ)を条件にしたワークとして積む。
 * 設定でオフなら、また今夜確認するリンクが無ければ何もしない。前の夜のワークが条件を待っている間は、重ねて積まない。
 */
class LinkCheckNightlyTask @Inject constructor(
    private val workManager: WorkManager,
    private val checker: Lazy<LinkChecker>,
    private val appSettings: AppSettingsRepository
) : NightlyTask {
    override val name: String = "link_check"

    override suspend fun run() {
        if (!appSettings.linkCheckEnabled.first()) return
        // オフラインモードの間は積まない(通信の条件を満たしたときに確かめに行ってしまう)
        if (appSettings.offlineMode.first()) return
        if (checker.get().countDue() == 0) return
        val settings = appSettings.processingSettings.first()
        workManager.enqueueUniqueWork(LinkCheckWorker.WORK_NAME, ExistingWorkPolicy.KEEP, LinkCheckWorker.requestFor(settings))
    }
}
