package com.unchunks.echomark.ui.widget

import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.bookmark.model.ProcessingInfo
import com.unchunks.echomark.domain.bookmark.model.ProcessingQueue
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage
import com.unchunks.echomark.domain.bookmark.model.StageDurationKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetProcessingSourceTest {

    private fun running(id: Long, stageStartedAt: Long = 0L) = Bookmark(
        id = id, type = BookmarkType.TEXT, title = "t$id", createdAt = 0, lastAccessedAt = 0, aiStatus = AiStatus.PROCESSING,
        processing = ProcessingInfo(stage = ProcessingStage.ANALYZE, startedAt = 0L, stageStartedAt = stageStartedAt)
    )

    private val averages = mapOf(
        StageDurationKey(ProcessingStage.ANALYZE, ContentKind.MEMO) to 10 * 60_000L,
        StageDurationKey(ProcessingStage.EMBED, ContentKind.MEMO) to 60_000L
    )

    private fun source(
        queue: Flow<ProcessingQueue>,
        clock: () -> Long,
        progress: (Long) -> Flow<Float?> = { flowOf(null) }
    ) = WidgetProcessingSource.summaries(queue, progress, flowOf(averages), clock, tickMillis = 60_000L)

    @Test
    fun 処理中のものがあれば流し_無くなったら_nullを流す() = runTest {
        val queue = MutableStateFlow(ProcessingQueue())
        val results = mutableListOf<WidgetProcessing?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source(queue, { 0L }).toList(results) }
        runCurrent()
        assertEquals(listOf<WidgetProcessing?>(null), results)

        queue.value = ProcessingQueue(running = listOf(running(1)))
        runCurrent()
        assertEquals(1, results.last()!!.running)

        queue.value = ProcessingQueue()
        runCurrent()
        assertNull(results.last())
    }

    @Test
    fun 段が進まなくても_一定時間ごとに残り時間を見込み直す() = runTest {
        var now = 0L
        val queue = MutableStateFlow(ProcessingQueue(running = listOf(running(1, stageStartedAt = 0L))))
        val results = mutableListOf<WidgetProcessing?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source(queue, { now }).toList(results) }
        runCurrent()
        // 要約 10 分 + 埋め込み 1 分 = 11 分
        assertEquals(11, results.last()!!.remainingMinutes)

        now = 5 * 60_000L
        advanceTimeBy(60_001); runCurrent()
        // 5 分経った → 要約の残り 5 分 + 埋め込み 1 分 = 6 分
        assertEquals(6, results.last()!!.remainingMinutes)
    }

    @Test
    fun 画面に出す値が変わらなければ流さない() = runTest {
        val queue = MutableStateFlow(ProcessingQueue(running = listOf(running(1))))
        val results = mutableListOf<WidgetProcessing?>()
        // 時刻が動かないので、1 分ごとに見込み直しても同じ値
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source(queue, { 0L }).toList(results) }
        runCurrent()
        advanceTimeBy(5 * 60_000); runCurrent()
        assertEquals(1, results.size)
    }

    @Test
    fun 段の進み具合の報告が進み具合に反映される() = runTest {
        val progress = MutableStateFlow<Float?>(null)
        val queue = MutableStateFlow(ProcessingQueue(running = listOf(running(1))))
        val results = mutableListOf<WidgetProcessing?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source(queue, { 0L }, progress = { progress }).toList(results)
        }
        runCurrent()
        assertEquals(0f, results.last()!!.fraction!!, 1e-6f)

        progress.value = 0.5f
        runCurrent()
        // 2 段のうち 1 段目の 50% = 全体の 25%
        assertNotNull(results.last())
        assertEquals(0.25f, results.last()!!.fraction!!, 1e-6f)
    }
}
