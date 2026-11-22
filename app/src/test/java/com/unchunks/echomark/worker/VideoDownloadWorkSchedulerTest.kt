package com.unchunks.echomark.worker

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.unchunks.echomark.domain.video.VideoDownloadFailure
import com.unchunks.echomark.domain.video.VideoDownloadWork
import com.unchunks.echomark.ui.video.VideoOfflineViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** 動画のファイルのダウンロードのワークの状態と、結果の知らせ */
class VideoDownloadWorkSchedulerTest {

    private fun info(state: WorkInfo.State, output: Data = Data.EMPTY, progress: Data = Data.EMPTY) =
        WorkInfo(UUID.randomUUID(), state, setOf(VideoDownloadWorkScheduler.TAG), output, progress)

    @Test
    fun 待っている間と落としている間() {
        assertEquals(VideoDownloadWork.Waiting, VideoDownloadWorkScheduler.workOf(info(WorkInfo.State.ENQUEUED)))
        assertEquals(
            VideoDownloadWork.Downloading(500L, 1500L),
            VideoDownloadWorkScheduler.workOf(
                info(
                    WorkInfo.State.RUNNING,
                    progress = workDataOf(VideoFileDownloadWorker.KEY_DOWNLOADED to 500L, VideoFileDownloadWorker.KEY_TOTAL to 1500L)
                )
            )
        )
        // 大きさが分からない
        assertEquals(
            VideoDownloadWork.Downloading(0L, null),
            VideoDownloadWorkScheduler.workOf(info(WorkInfo.State.RUNNING, progress = workDataOf(VideoFileDownloadWorker.KEY_TOTAL to -1L)))
        )
    }

    @Test
    fun 終わったら結果と理由() {
        assertEquals(VideoDownloadWork.Finished, VideoDownloadWorkScheduler.workOf(info(WorkInfo.State.SUCCEEDED)))
        assertEquals(
            VideoDownloadWork.Failed(VideoDownloadFailure.NOT_ALLOWED),
            VideoDownloadWorkScheduler.workOf(
                info(WorkInfo.State.FAILED, output = workDataOf(VideoFileDownloadWorker.KEY_FAILURE to VideoDownloadFailure.NOT_ALLOWED.name))
            )
        )
        assertEquals(VideoDownloadWork.Failed(VideoDownloadFailure.NETWORK), VideoDownloadWorkScheduler.workOf(info(WorkInfo.State.FAILED)))
        assertEquals(VideoDownloadWork.Cancelled, VideoDownloadWorkScheduler.workOf(info(WorkInfo.State.CANCELLED)))
    }

    @Test
    fun 結果の知らせはすべての理由に文言がある() {
        VideoDownloadFailure.entries.forEach { reason ->
            assertTrue(reason.name, VideoOfflineViewModel.failureMessage(reason).isNotBlank())
        }
        assertEquals(VideoOfflineViewModel.OFFLINE_MESSAGE, VideoOfflineViewModel.failureMessage(VideoDownloadFailure.OFFLINE_MODE))
        assertTrue(VideoOfflineViewModel.downloadResultMessage(VideoDownloadWork.Finished)!!.contains("再生"))
        assertNull(VideoOfflineViewModel.downloadResultMessage(VideoDownloadWork.Waiting))
        assertNull(VideoOfflineViewModel.downloadResultMessage(VideoDownloadWork.Downloading(1, 2)))
    }
}
