package com.unchunks.echomark.worker

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.unchunks.echomark.domain.video.VideoDownloadFailure
import com.unchunks.echomark.domain.video.VideoDownloadScheduler
import com.unchunks.echomark.domain.video.VideoDownloadWork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 動画のファイルのダウンロード([VideoFileDownloadWorker])を積む。ブックマークごとの一意名で、同じブックマークの
 * ダウンロードを並行させない(動いている間に頼まれても、そのまま続ける)。
 * - Wi-Fi のときだけ(既定)なら、従量課金でない接続(NetworkType.UNMETERED)を条件にする。外れたら WorkManager が止め、
 *   つながったら続きから落とす
 * - 端末の空き容量が少ないときは動かさない(setRequiresStorageNotLow)
 * - すべてに [TAG] を付け、「すべてのデータを削除」・「すべて削除」で取り消す
 */
class VideoDownloadWorkScheduler @Inject constructor(
    private val workManager: WorkManager
) : VideoDownloadScheduler {

    override fun enqueue(bookmarkId: Long, wifiOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<VideoFileDownloadWorker>()
            .setInputData(workDataOf(VideoFileDownloadWorker.KEY_BOOKMARK_ID to bookmarkId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(uniqueWorkName(bookmarkId), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(bookmarkId: Long) {
        workManager.cancelUniqueWork(uniqueWorkName(bookmarkId))
    }

    override fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
    }

    override fun observe(bookmarkId: Long): Flow<VideoDownloadWork?> =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName(bookmarkId))
            .map { infos -> infos.lastOrNull()?.let { workOf(it) } }
            .distinctUntilChanged()

    companion object {
        /** すべての動画のファイルのダウンロードに付けるタグ */
        const val TAG = "video_download"

        private const val BACKOFF_SECONDS = 60L

        fun uniqueWorkName(bookmarkId: Long): String = "video_download_$bookmarkId"

        internal fun workOf(info: WorkInfo): VideoDownloadWork = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> VideoDownloadWork.Waiting
            WorkInfo.State.RUNNING -> VideoDownloadWork.Downloading(
                downloadedBytes = info.progress.getLong(VideoFileDownloadWorker.KEY_DOWNLOADED, 0L),
                totalBytes = info.progress.getLong(VideoFileDownloadWorker.KEY_TOTAL, -1L).takeIf { it > 0 }
            )
            WorkInfo.State.SUCCEEDED -> VideoDownloadWork.Finished
            WorkInfo.State.FAILED -> VideoDownloadWork.Failed(
                info.outputData.getString(VideoFileDownloadWorker.KEY_FAILURE)
                    ?.let { name -> VideoDownloadFailure.entries.firstOrNull { it.name == name } }
                    ?: VideoDownloadFailure.NETWORK
            )
            WorkInfo.State.CANCELLED -> VideoDownloadWork.Cancelled
        }
    }
}
