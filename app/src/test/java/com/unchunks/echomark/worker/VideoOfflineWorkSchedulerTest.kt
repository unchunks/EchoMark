package com.unchunks.echomark.worker

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.unchunks.echomark.domain.video.VideoOfflineStage
import com.unchunks.echomark.domain.video.VideoOfflineWork
import com.unchunks.echomark.ui.video.VideoOfflineViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** WorkManager のワークの状態から、画面に出す保存の状態と結果の知らせを決める */
class VideoOfflineWorkSchedulerTest {

    private fun info(state: WorkInfo.State, output: Data = Data.EMPTY, progress: Data = Data.EMPTY) =
        WorkInfo(UUID.randomUUID(), state, setOf(VideoOfflineWorkScheduler.TAG), output, progress)

    @Test
    fun 順番待ちと実行中は段を出す() {
        assertEquals(VideoOfflineWork.Running(VideoOfflineStage.WAITING), VideoOfflineWorkScheduler.workOf(info(WorkInfo.State.ENQUEUED)))
        assertEquals(
            VideoOfflineWork.Running(VideoOfflineStage.THUMBNAIL),
            VideoOfflineWorkScheduler.workOf(
                info(WorkInfo.State.RUNNING, progress = workDataOf(VideoOfflineWorker.KEY_STAGE to VideoOfflineStage.THUMBNAIL.name))
            )
        )
        // 進み具合をまだ報告していない
        assertEquals(VideoOfflineWork.Running(VideoOfflineStage.TRANSCRIPT), VideoOfflineWorkScheduler.workOf(info(WorkInfo.State.RUNNING)))
    }

    @Test
    fun 終わったら保存できたものと失敗の理由を出す() {
        assertEquals(
            VideoOfflineWork.Finished(hasTranscript = true, hasThumbnail = false),
            VideoOfflineWorkScheduler.workOf(
                info(WorkInfo.State.SUCCEEDED, output = workDataOf(VideoOfflineWorker.KEY_HAS_TRANSCRIPT to true))
            )
        )
        assertEquals(
            VideoOfflineWork.Failed(offlineMode = true),
            VideoOfflineWorkScheduler.workOf(
                info(WorkInfo.State.FAILED, output = workDataOf(VideoOfflineWorker.KEY_FAILURE to VideoOfflineWorker.FAILURE_OFFLINE_MODE))
            )
        )
        assertEquals(VideoOfflineWork.Failed(offlineMode = false), VideoOfflineWorkScheduler.workOf(info(WorkInfo.State.FAILED)))
        assertEquals(VideoOfflineWork.Cancelled, VideoOfflineWorkScheduler.workOf(info(WorkInfo.State.CANCELLED)))
    }

    @Test
    fun 結果の知らせ() {
        assertEquals("字幕とサムネイルを保存しました", VideoOfflineViewModel.resultMessage(VideoOfflineWork.Finished(true, true)))
        assertTrue(VideoOfflineViewModel.resultMessage(VideoOfflineWork.Finished(false, true))!!.contains("字幕は取れませんでした"))
        assertEquals(VideoOfflineViewModel.OFFLINE_MESSAGE, VideoOfflineViewModel.resultMessage(VideoOfflineWork.Failed(offlineMode = true)))
        assertNull(VideoOfflineViewModel.resultMessage(VideoOfflineWork.Running(VideoOfflineStage.WAITING)))
        assertNull(VideoOfflineViewModel.resultMessage(null))
    }
}
