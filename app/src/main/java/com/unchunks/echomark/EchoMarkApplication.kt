package com.unchunks.echomark

import android.app.Application
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.unchunks.echomark.worker.ReembedAllWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class EchoMarkApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var workManager: WorkManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        createNotificationChannels()

        workManager.enqueueUniqueWork(
            ReembedAllWorker.WORK_NAME,
            // 起動のたびに再実行・中断しないよう、実行中/待機中のものがあればそれを維持する
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ReembedAllWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
        )
    }

    /** 通知チャンネルを作成する(既存なら何もしない)。 */
    private fun createNotificationChannels() {
        val channel = NotificationChannelCompat.Builder(
            REDISCOVER_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(getString(R.string.notification_channel_rediscover))
            .setDescription(getString(R.string.notification_channel_rediscover_desc))
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    companion object {
        const val REDISCOVER_CHANNEL_ID = "rediscover_digest"
    }
}
