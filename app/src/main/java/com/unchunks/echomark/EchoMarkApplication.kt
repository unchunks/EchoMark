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
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.unchunks.echomark.data.attachment.UnusedAttachmentCleaner
import com.unchunks.echomark.ui.widget.RediscoverWidgetUpdater
import com.unchunks.echomark.worker.ReembedAllWorker
import com.unchunks.echomark.worker.StalledAiProcessingRecovery
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class EchoMarkApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var workManager: WorkManager
    // 画像を初めて読み込むまで生成しないよう Lazy で受け取る
    @Inject lateinit var okHttpClient: Lazy<OkHttpClient>
    @Inject lateinit var rediscoverWidgetUpdater: RediscoverWidgetUpdater
    @Inject lateinit var stalledAiProcessingRecovery: StalledAiProcessingRecovery
    @Inject lateinit var unusedAttachmentCleaner: UnusedAttachmentCleaner

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // ログは debug ビルドだけ Logcat に出す(release では本文や URL を残さない)
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())

        createNotificationChannels()
        // ブックマークの保存・削除・閲覧に合わせて、ホーム画面ウィジェットを描き直す
        rediscoverWidgetUpdater.start()
        // 処理待ち・処理中のままワークが失われたブックマークの AI 処理を積み直す
        stalledAiProcessingRecovery.start()
        // 削除したブックマークの添付ファイルなど、使われなくなってしばらく経ったファイルを消す
        unusedAttachmentCleaner.start()

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

    /** Coil の画像読み込み。通信は NetworkModule の共通 OkHttpClient を使う。 */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient.get() })) }
            .crossfade(true)
            .build()

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
