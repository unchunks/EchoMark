package com.unchunks.echomark.ui.settings.queue

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.ProcessingFailureReason
import com.unchunks.echomark.domain.bookmark.model.ProcessingInfo
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakeProcessingStatsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessingQueueViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakeBookmarkRepository()
    private val settings = FakeAppSettingsRepository()
    private val stats = FakeProcessingStatsRepository()

    private fun createViewModel() = ProcessingQueueViewModel(repository, settings, stats)

    private fun bookmark(id: Long, status: AiStatus, processing: ProcessingInfo = ProcessingInfo()) = Bookmark(
        id = id,
        type = BookmarkType.TEXT,
        title = "メモ$id",
        content = "本文",
        createdAt = 1L,
        lastAccessedAt = 1L,
        aiStatus = status,
        processing = processing
    )

    @Test
    fun 処理中_処理待ち_失敗を分けて出す() = runTest(mainRule.dispatcher) {
        repository.bookmarks.value = listOf(
            bookmark(1, AiStatus.PROCESSING, ProcessingInfo(stage = ProcessingStage.ANALYZE)),
            bookmark(2, AiStatus.PENDING),
            bookmark(3, AiStatus.FAILED, ProcessingInfo(failureReason = ProcessingFailureReason.TIMEOUT)),
            bookmark(4, AiStatus.DONE)
        )
        val viewModel = createViewModel()
        val job = viewModel.uiState.launchIn(backgroundScope)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf(1L), state.queue.running.map { it.id })
        assertEquals(listOf(2L), state.queue.waiting.map { it.id })
        assertEquals(listOf(3L), state.queue.recentFailures.map { it.id })
        job.cancel()
    }

    @Test
    fun 処理中のものの段の進み具合を出す() = runTest(mainRule.dispatcher) {
        repository.bookmarks.value = listOf(
            bookmark(1, AiStatus.PROCESSING, ProcessingInfo(stage = ProcessingStage.ANALYZE)),
            bookmark(2, AiStatus.PENDING)
        )
        repository.stageProgress.value = 0.4f
        val viewModel = createViewModel()
        viewModel.uiState.launchIn(backgroundScope)
        advanceUntilIdle()

        // 処理待ちのものは進み具合を持たない
        assertEquals(mapOf(1L to 0.4f), viewModel.uiState.value.stageProgress)
    }

    @Test
    fun 操作はリポジトリに渡す() = runTest(mainRule.dispatcher) {
        val viewModel = createViewModel()

        viewModel.cancel(1)
        viewModel.processNow(2)
        viewModel.retry(3)
        advanceUntilIdle()

        assertEquals(listOf(1L), repository.cancelledIds)
        assertEquals(listOf(2L), repository.processNowIds)
        assertEquals(listOf(3L), repository.reprocessedIds)
    }

    @Test
    fun やめると失敗の一覧に移る() = runTest(mainRule.dispatcher) {
        repository.bookmarks.value = listOf(bookmark(1, AiStatus.PENDING))
        val viewModel = createViewModel()
        viewModel.uiState.launchIn(backgroundScope)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.queue.waiting.isNotEmpty())

        viewModel.cancel(1)
        advanceUntilIdle()

        val queue = viewModel.uiState.value.queue
        assertTrue(queue.waiting.isEmpty())
        assertEquals(
            ProcessingFailureReason.CANCELLED,
            queue.recentFailures.single().processing.failureReason
        )
        // 設定の読み込みが済んだ後の状態を返す
        assertEquals(settings.processingFlow.value, viewModel.uiState.first().settings)
    }
}
