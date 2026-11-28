package com.unchunks.echomark.worker

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.unchunks.echomark.domain.video.VideoOfflineScheduler
import com.unchunks.echomark.domain.video.VideoOfflineStage
import com.unchunks.echomark.domain.video.VideoOfflineWork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 動画のオフライン保存([VideoOfflineWorker])を積む。ブックマークごとの一意名で積み、同じブックマークの保存を
 * 並行させない(動いている間に頼まれても、そのまま続ける)。
 * 通信できるときだけ動かす(字幕とサムネイルは小さいので、従量課金の接続でも動かす)。
 * 優先実行(エクスペディット)にはしない(古い Android では通知を出す前景のワークが要るため。短い作業なので通常で足りる)。
 * すべてに [TAG] を付け、「すべてのデータを削除」で取り消す。
 */
class VideoOfflineWorkScheduler @Inject constructor(
    private val workManager: WorkManager
) : VideoOfflineScheduler {

    override fun enqueue(bookmarkId: Long) {
        val request = OneTimeWorkRequestBuilder<VideoOfflineWorker>()
            .setInputData(workDataOf(VideoOfflineWorker.KEY_BOOKMARK_ID to bookmarkId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(uniqueWorkName(bookmarkId), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(bookmarkId: Long) {
        workManager.cancelUniqueWork(uniqueWorkName(bookmarkId))
    }

    override fun observe(bookmarkId: Long): Flow<VideoOfflineWork?> =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName(bookmarkId))
            .map { infos -> infos.lastOrNull()?.let { workOf(it) } }
            .distinctUntilChanged()

    companion object {
        /** すべての動画のオフライン保存のワークに付けるタグ */
        const val TAG = "video_offline"

        private const val BACKOFF_SECONDS = 30L

        fun uniqueWorkName(bookmarkId: Long): String = "video_offline_$bookmarkId"

        internal fun workOf(info: WorkInfo): VideoOfflineWork = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> VideoOfflineWork.Running(VideoOfflineStage.WAITING)
            WorkInfo.State.RUNNING -> VideoOfflineWork.Running(
                info.progress.getString(VideoOfflineWorker.KEY_STAGE)
                    ?.let { name -> VideoOfflineStage.entries.firstOrNull { it.name == name } }
                    ?: VideoOfflineStage.TRANSCRIPT
            )
            WorkInfo.State.SUCCEEDED -> VideoOfflineWork.Finished(
                hasTranscript = info.outputData.getBoolean(VideoOfflineWorker.KEY_HAS_TRANSCRIPT, false),
                hasThumbnail = info.outputData.getBoolean(VideoOfflineWorker.KEY_HAS_THUMBNAIL, false)
            )
            WorkInfo.State.FAILED -> VideoOfflineWork.Failed(
                offlineMode = info.outputData.getString(VideoOfflineWorker.KEY_FAILURE) == VideoOfflineWorker.FAILURE_OFFLINE_MODE
            )
            WorkInfo.State.CANCELLED -> VideoOfflineWork.Cancelled
        }
    }
}
