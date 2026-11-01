package com.unchunks.echomark.screenshot

import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.ProcessingFailureReason
import com.unchunks.echomark.domain.bookmark.model.ProcessingInfo
import com.unchunks.echomark.domain.bookmark.model.ProcessingQueue
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage
import com.unchunks.echomark.domain.repository.ProcessingSettings
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.settings.queue.ProcessingQueueActions
import com.unchunks.echomark.ui.settings.queue.ProcessingQueueContent
import com.unchunks.echomark.ui.settings.queue.ProcessingQueueUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** 処理の状況の画面(処理中・処理待ち・最近の失敗)。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProcessingQueueScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val now = PreviewSamples.NOW
    private val minute = 60 * 1000L

    private fun bookmark(
        id: Long,
        title: String,
        status: AiStatus,
        type: BookmarkType = BookmarkType.URL,
        processing: ProcessingInfo = ProcessingInfo()
    ) = Bookmark(
        id = id,
        type = type,
        contentUri = if (type == BookmarkType.URL) "https://www.example.com/$id" else null,
        title = title,
        createdAt = now - 30 * minute,
        lastAccessedAt = now - 30 * minute,
        aiStatus = status,
        processing = processing
    )

    private val running = listOf(
        bookmark(
            1, "Jetpack Compose のパフォーマンス改善", AiStatus.PROCESSING,
            processing = ProcessingInfo(
                stage = ProcessingStage.ANALYZE,
                queuedAt = now - 10 * minute,
                startedAt = now - 2 * minute,
                stageStartedAt = now - minute
            )
        ),
        bookmark(
            2, "会議の録音 2026-10-20", AiStatus.PROCESSING, type = BookmarkType.AUDIO,
            processing = ProcessingInfo(
                stage = ProcessingStage.EXTRACT_CONTENT,
                queuedAt = now - 10 * minute,
                startedAt = now - 3 * minute,
                stageStartedAt = now - 2 * minute
            )
        )
    )

    private val waiting = listOf(
        bookmark(3, "Android アーキテクチャガイド", AiStatus.PENDING, processing = ProcessingInfo(queuedAt = now - 9 * minute)),
        bookmark(4, "読書メモ: 『知の編集術』", AiStatus.PENDING, type = BookmarkType.TEXT, processing = ProcessingInfo(queuedAt = now - 8 * minute)),
        bookmark(5, "2026年度 研究計画書.pdf", AiStatus.PENDING, type = BookmarkType.PDF, processing = ProcessingInfo(queuedAt = now - 7 * minute))
    )

    private val failures = listOf(
        bookmark(
            6, "https://example.com/very/long/path", AiStatus.FAILED,
            processing = ProcessingInfo(failureReason = ProcessingFailureReason.TIMEOUT, finishedAt = now - 5 * minute)
        ),
        bookmark(
            7, "古い記事のメモ", AiStatus.FAILED, type = BookmarkType.TEXT,
            processing = ProcessingInfo(failureReason = ProcessingFailureReason.CANCELLED, finishedAt = now - 20 * minute)
        )
    )

    @Composable
    private fun Screen(state: ProcessingQueueUiState) {
        ProcessingQueueContent(uiState = state, actions = ProcessingQueueActions(), nowMillis = now)
    }

    @Test
    fun all() = screenshot.captureScreenLightDark("settings_queue_all") {
        Screen(
            ProcessingQueueUiState(
                isLoading = false,
                queue = ProcessingQueue(running, waiting, failures),
                stageProgress = mapOf(2L to 0.45f)
            )
        )
    }

    /** 夜間にまとめて処理する設定のときは、処理待ちの説明を添える */
    @Test
    fun waitingForNight() = screenshot.captureScreenLightDark("settings_queue_nightly") {
        Screen(
            ProcessingQueueUiState(
                isLoading = false,
                queue = ProcessingQueue(waiting = waiting),
                settings = ProcessingSettings(nightlyBatch = true, nightlyHour = 2)
            )
        )
    }

    @Test
    fun empty() = screenshot.captureScreenLightDark("settings_queue_empty") {
        Screen(ProcessingQueueUiState(isLoading = false))
    }

    @Test
    fun loading() = screenshot.captureScreenLightDark("settings_queue_loading") {
        Screen(ProcessingQueueUiState())
    }
}
