package com.unchunks.echomark.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.unchunks.components.web.NetworkGate
import com.unchunks.components.web.isOfflineModeBlock
import com.unchunks.echomark.R
import com.unchunks.echomark.data.video.VideoDownloadException
import com.unchunks.echomark.data.video.VideoFileDownloader
import com.unchunks.echomark.data.video.VideoFileStore
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.video.OfflineVideoRepository
import com.unchunks.echomark.domain.video.SavedVideoFile
import com.unchunks.echomark.domain.video.VideoDownloadFailure
import com.unchunks.echomark.domain.video.videoFileSourceKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

/**
 * ダウンロードが認められた動画のファイルを、オフライン用に端末へ保存する(`docs/video-offline.md`)。
 * - 対象: 動画のファイルへの直接のリンク(video/…)・投稿者がダウンロードを許した PeerTube の動画
 * - 前景のワーク(通知に進み具合を出す)。Wi-Fi のときだけの設定なら、従量課金でない接続を条件にして積む(スケジューラ)
 * - 合計の上限・端末の空き容量を確かめる。途中で止まったら(接続が切れた・Wi-Fi から外れた)、次は続きから落とす
 * - オフラインモードの間・途中でオンにされたら、何も書かずに止める
 */
@HiltWorker
class VideoFileDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: BookmarkRepository,
    private val offlineVideos: OfflineVideoRepository,
    private val appSettings: AppSettingsRepository,
    private val downloader: VideoFileDownloader,
    private val store: VideoFileStore,
    private val gate: NetworkGate
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookmarkId = inputData.getLong(KEY_BOOKMARK_ID, -1L)
        val bookmark = repository.getBookmarkById(bookmarkId)
        val url = bookmark?.contentUri
        val kind = bookmark?.videoFileSourceKind()
        if (url == null || kind == null) return failed(VideoDownloadFailure.NOT_TARGET)
        if (gate.isOffline()) return failed(VideoDownloadFailure.OFFLINE_MODE)

        val settings = appSettings.videoDownloadSettings.first()
        // 上限は、ほかの動画の分を除いた残り(同じブックマークの前のファイルは、落とし終えたら置き換えるので数えない)
        val limit = settings.maxTotalBytes - offlineVideos.totalVideoBytes(excludingBookmarkId = bookmarkId)
        if (limit <= 0) return failed(VideoDownloadFailure.OVER_LIMIT)

        setForeground(foregroundInfo(0, null))
        val part = store.partFile(bookmarkId)
        return try {
            val downloaded = withContext(Dispatchers.IO) {
                val source = downloader.resolve(url, kind)
                val result = downloader.download(
                    source = source,
                    part = part,
                    maxBytes = limit,
                    freeBytes = store::freeBytes,
                    isStopped = { isStopped || gate.isOffline() },
                    onProgress = { done, total -> runBlocking { report(done, total) } }
                )
                source to result
            }
            if (gate.isOffline()) return failed(VideoDownloadFailure.OFFLINE_MODE)
            val (source, result) = downloaded
            val path = store.adopt(part, bookmarkId, result.mimeType)
            val video = SavedVideoFile(
                path = path,
                mimeType = result.mimeType,
                size = result.size,
                sourceUrl = source.url,
                label = source.label,
                savedAt = System.currentTimeMillis()
            )
            // ブックマークが消えていたら、保存しない(落としたファイルは消える)
            offlineVideos.saveVideo(bookmarkId, video)
            Result.success()
        } catch (e: VideoDownloadException) {
            Timber.w(e, "VideoFileDownloadWorker: 保存できない id=%d", bookmarkId)
            // 上限・空き容量・認められていない・動画でないときは、途中のファイルを残しても続きは無いので消す
            if (e.reason != VideoDownloadFailure.NETWORK) part.delete()
            if (e.reason == VideoDownloadFailure.NETWORK) retryOrFail() else failed(e.reason)
        } catch (e: CancellationException) {
            // 止められた(利用者・WorkManager・オフラインモード)。途中のファイルは残し、次は続きから落とす
            if (gate.isOffline()) return failed(VideoDownloadFailure.OFFLINE_MODE)
            throw e
        } catch (e: IOException) {
            if (e.isOfflineModeBlock()) return failed(VideoDownloadFailure.OFFLINE_MODE)
            Timber.w(e, "VideoFileDownloadWorker: 通信に失敗 id=%d", bookmarkId)
            retryOrFail()
        }
    }

    /** 通信の失敗は、何度か(続きから)試し直す */
    private fun retryOrFail(): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else failed(VideoDownloadFailure.NETWORK)

    private fun failed(reason: VideoDownloadFailure): Result = Result.failure(workDataOf(KEY_FAILURE to reason.name))

    private var lastNotifiedPercent = -1

    private suspend fun report(done: Long, total: Long?) {
        setProgress(workDataOf(KEY_DOWNLOADED to done, KEY_TOTAL to (total ?: -1L)))
        // 通知は 1% ごとに書き換える(書き換えすぎると通知の更新が間引かれる)
        val percent = total?.takeIf { it > 0 }?.let { (done * 100 / it).toInt() } ?: -1
        if (percent != lastNotifiedPercent) {
            lastNotifiedPercent = percent
            setForeground(foregroundInfo(done, total))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, null)

    private fun foregroundInfo(done: Long, total: Long?): ForegroundInfo {
        val context = applicationContext
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.notification_channel_video_download))
                .setDescription(context.getString(R.string.notification_channel_video_download_desc))
                .build()
        )
        val percent = total?.takeIf { it > 0 }?.let { (done * 100 / it).toInt().coerceIn(0, 100) }
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.video_download_notification_title))
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_BOOKMARK_ID = "bookmark_id"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_FAILURE = "failure"
        const val CHANNEL_ID = "video_download"
        private const val NOTIFICATION_ID = 3004
        private const val MAX_ATTEMPTS = 5
    }
}
