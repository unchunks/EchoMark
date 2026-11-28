package com.unchunks.echomark.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.unchunks.echomark.MainActivity
import com.unchunks.echomark.R
import com.unchunks.echomark.data.podcast.PodcastEpisodeRunner
import com.unchunks.echomark.data.podcast.PodcastWorkScheduler
import com.unchunks.echomark.domain.podcast.EpisodeStatus
import com.unchunks.echomark.ui.navigation.Routes
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject

/**
 * ポッドキャストのエピソードを作る([PodcastEpisodeRunner])。端末内 AI の台本と読み上げで数分かかるため、通知つきの
 * フォアグラウンドで動かす。通信は要らない(端末内 AI・端末の中の声。クラウド AI を選んでいれば、その呼び出しだけ
 * オフラインモードの守りを通る)ので、通信の条件は付けない。
 * できたら(作れなかったら)通知で知らせ、タップでエピソードの一覧を開く。
 */
@HiltWorker
class PodcastGenerationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: PodcastEpisodeRunner
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_EPISODE_ID) ?: return Result.failure()
        startForeground(null)
        // 消されていた・もう作り終えていたら、知らせずに終える
        val episode = runner.run(id) { label -> notifyProgress(label) } ?: return Result.success()
        val ready = episode.status == EpisodeStatus.READY
        notifyFinished(ready, episode.title)
        // 作れなかったものは、理由を付けて一覧に残してある。WorkManager にはやり直させない
        return if (ready) Result.success() else Result.failure()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private suspend fun startForeground(text: String?) {
        try {
            setForeground(foregroundInfo(text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ポッドキャストを作るのをフォアグラウンドにできません")
        }
    }

    /** 進み具合が変わったら、フォアグラウンドの通知の文を変える(失敗しても作るのは続ける) */
    private fun notifyProgress(label: String) {
        try {
            NotificationManagerCompat.from(applicationContext).notify(PROGRESS_NOTIFICATION_ID, progressNotification(label))
        } catch (e: SecurityException) {
            // 通知の権限が無い。作るのは続ける
        }
    }

    private fun foregroundInfo(text: String?): ForegroundInfo {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return ForegroundInfo(PROGRESS_NOTIFICATION_ID, progressNotification(text), type)
    }

    private fun progressNotification(text: String?) = run {
        ensureChannel(applicationContext)
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.podcast_notification_generating))
            .setContentText(text)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun notifyFinished(ready: Boolean, title: String?) {
        val context = applicationContext
        val intent = Intent(Intent.ACTION_VIEW, Routes.PODCASTS_DEEP_LINK.toUri(), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, FINISHED_NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(if (ready) R.string.podcast_notification_ready else R.string.podcast_notification_failed))
            .setContentText(title)
            .setContentIntent(pending)
            .setAutoCancel(true)
            // ブックマークのタイトルを、ロック画面に出さない
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(FINISHED_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Timber.i("通知の権限が無いため、ポッドキャストができた知らせを出さない")
        }
    }

    companion object {
        const val KEY_EPISODE_ID = "episode_id"
        const val CHANNEL_ID = "podcast"
        private const val PROGRESS_NOTIFICATION_ID = 3101
        private const val FINISHED_NOTIFICATION_ID = 3102

        fun uniqueName(episodeId: String) = "podcast-$episodeId"

        fun ensureChannel(context: Context) {
            NotificationManagerCompat.from(context).createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.notification_channel_podcast))
                    .setDescription(context.getString(R.string.notification_channel_podcast_desc))
                    .build()
            )
        }
    }
}

/** [PodcastGenerationWorker] を WorkManager に積む。エピソードごとに1つ */
class WorkManagerPodcastScheduler @Inject constructor(private val workManager: WorkManager) : PodcastWorkScheduler {
    override fun enqueue(episodeId: String) {
        val request = OneTimeWorkRequestBuilder<PodcastGenerationWorker>()
            .setInputData(workDataOf(PodcastGenerationWorker.KEY_EPISODE_ID to episodeId))
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(PodcastGenerationWorker.uniqueName(episodeId), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(episodeId: String) {
        workManager.cancelUniqueWork(PodcastGenerationWorker.uniqueName(episodeId))
    }

    private companion object {
        const val TAG = "podcast"
    }
}
