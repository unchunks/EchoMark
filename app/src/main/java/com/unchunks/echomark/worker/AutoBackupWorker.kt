package com.unchunks.echomark.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.unchunks.echomark.EchoMarkApplication
import com.unchunks.echomark.MainActivity
import com.unchunks.echomark.R
import com.unchunks.echomark.data.backup.AutoBackupNotifier
import com.unchunks.echomark.data.backup.AutoBackupRunner
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.ProcessingSettings
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

/** 自動バックアップを1回行う([AutoBackupRunner])。失敗は設定画面と通知で知らせるので、ワークは成功で終える */
@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: AutoBackupRunner
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = runner.run()
        Timber.i("自動バックアップ: %s", outcome)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "auto_backup"

        /** 処理の設定の「充電中だけ」に従う。書き出しは端末の中だけで済むので、ネットワークは求めない */
        internal fun constraintsFor(settings: ProcessingSettings): Constraints =
            Constraints.Builder()
                .setRequiresCharging(settings.chargingOnly)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()

        internal fun requestFor(settings: ProcessingSettings): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<AutoBackupWorker>().setConstraints(constraintsFor(settings)).build()
    }
}

/**
 * 夜間の仕事([NightlyTask])として、自動バックアップを積む。書き出しは重いことがある(ファイルも含めるとき)ので、
 * ここでは行わず、処理の設定(充電中だけ)を条件にしたワークとして積む。前の夜のワークが条件を待っている間は、重ねて積まない。
 */
class AutoBackupNightlyTask @Inject constructor(
    private val workManager: WorkManager,
    private val appSettings: AppSettingsRepository
) : NightlyTask {
    override val name: String = "auto_backup"

    override suspend fun run() {
        val backup = appSettings.autoBackup.first()
        if (!backup.enabled || backup.folderUri == null) return
        val settings = appSettings.processingSettings.first()
        workManager.enqueueUniqueWork(AutoBackupWorker.WORK_NAME, ExistingWorkPolicy.KEEP, AutoBackupWorker.requestFor(settings))
    }
}

/** 設定画面の「今すぐバックアップ」。ViewModel のテストでは Fake に差し替える */
interface AutoBackupController {
    /** 条件(充電中など)を待たずに、自動バックアップを1回行う */
    fun runNow()
}

class WorkManagerAutoBackupController @Inject constructor(
    private val workManager: WorkManager
) : AutoBackupController {
    override fun runNow() {
        workManager.enqueueUniqueWork(
            AutoBackupWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AutoBackupWorker>().build()
        )
    }
}

/** 保存先を使えなくなったことを通知で知らせる。押すとアプリを開く(設定の「自動バックアップ」で選び直す) */
class NotificationAutoBackupNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) : AutoBackupNotifier {

    override fun notifyFolderUnavailable() {
        val open = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, EchoMarkApplication.AUTO_BACKUP_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("自動バックアップを保存できませんでした")
            .setContentText(AutoBackupRunner.FOLDER_UNAVAILABLE_MESSAGE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(AutoBackupRunner.FOLDER_UNAVAILABLE_MESSAGE))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // 通知の許可が無い。設定画面には理由が出ている
            Timber.w(e, "自動バックアップの通知を出せない")
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 3001
        const val REQUEST_CODE = 3001
    }
}
