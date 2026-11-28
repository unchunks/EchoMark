package com.unchunks.echomark.ui.widget

import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.bookmark.model.ProcessingInfo
import com.unchunks.echomark.domain.bookmark.model.ProcessingQueue
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage
import com.unchunks.echomark.domain.bookmark.model.StageDurationKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetProcessingTest {


    /** メモ(TEXT)は「要約・タグ → 埋め込み」の 2 段 */
    private fun running(id: Long, stage: ProcessingStage, stageStartedAt: Long = 0L) = Bookmark(
        id = id, type = BookmarkType.TEXT, title = "t$id", createdAt = 0, lastAccessedAt = 0, aiStatus = AiStatus.PROCESSING,
        processing = ProcessingInfo(stage = stage, startedAt = 0L, stageStartedAt = stageStartedAt)
    )

    private fun waiting(id: Long) = Bookmark(
        id = id, type = BookmarkType.TEXT, title = "w$id", createdAt = 0, lastAccessedAt = 0, aiStatus = AiStatus.PENDING,
        processing = ProcessingInfo(queuedAt = 0L)
    )

    private val textKind = ContentKind.MEMO

    private val averages = mapOf(
        StageDurationKey(ProcessingStage.ANALYZE, textKind) to 60_000L,
        StageDurationKey(ProcessingStage.EMBED, textKind) to 10_000L
    )

    @Test
    fun 処理中も処理待ちも無ければ_nullで何も出さない() {
        assertNull(WidgetProcessingSummary.summarize(ProcessingQueue(), 0L, emptyMap(), averages))
        // 失敗しただけのものは、処理中ではないので出さない
        val failed = ProcessingQueue(recentFailures = listOf(waiting(1).copy(aiStatus = AiStatus.FAILED)))
        assertNull(WidgetProcessingSummary.summarize(failed, 0L, emptyMap(), averages))
    }

    @Test
    fun 処理中は件数と進み具合の平均と残り時間を出す() {
        // 1 は要約の段(1/2 段目)で 30 秒経過 → 残り 30 秒 + 埋め込み 10 秒。2 は埋め込みの段(2/2 段目)で始めたばかり
        val queue = ProcessingQueue(
            running = listOf(running(1, ProcessingStage.ANALYZE, stageStartedAt = 0L), running(2, ProcessingStage.EMBED, stageStartedAt = 30_000L))
        )
        val result = WidgetProcessingSummary.summarize(queue, now = 30_000L, stageFractions = emptyMap(), averages = averages)!!

        assertEquals(2, result.running)
        assertEquals(0, result.waiting)
        // (0/2 と 1/2 の平均 = 0.25)
        assertEquals(0.25f, result.fraction!!, 1e-6f)
        // 1: 要約の残り 30 秒 + 埋め込み 10 秒 = 40 秒。2: 埋め込みの残り 10 秒 → 計 50 秒 = 1 分に切り上げ
        assertEquals(1, result.remainingMinutes)
        assertEquals("AI 処理中 2件", result.title())
        assertEquals("残り約1分", result.detail())
    }

    @Test
    fun 段が報告した進み具合を平均に入れ_5パーセント刻みに丸める() {
        val queue = ProcessingQueue(running = listOf(running(1, ProcessingStage.ANALYZE)))
        // 1 段目の 40% → 全体の 0.2。0.37 なら (0.37 / 2) = 0.185 → 0.20
        val result = WidgetProcessingSummary.summarize(queue, now = 1_000L, stageFractions = mapOf(1L to 0.37f), averages = averages)!!
        assertEquals(0.20f, result.fraction!!, 1e-6f)
    }

    @Test
    fun 処理待ちを足した残り時間と_待ちだけのときの表示() {
        // 待ちは 2 件。要約 60 秒 + 埋め込み 10 秒 = 70 秒ずつ → 140 秒 = 3 分に切り上げ
        val queue = ProcessingQueue(waiting = listOf(waiting(1), waiting(2)))
        val result = WidgetProcessingSummary.summarize(queue, now = 0L, stageFractions = emptyMap(), averages = averages)!!

        assertEquals(0, result.running)
        assertEquals(2, result.waiting)
        // 処理中が無いので、棒の進み具合は出さない
        assertNull(result.fraction)
        assertEquals(3, result.remainingMinutes)
        assertEquals("AI 処理待ち 2件", result.title())
        assertEquals("残り約3分", result.detail())
    }

    @Test
    fun 処理中と待ちの両方があれば_両方の件数を出す() {
        val queue = ProcessingQueue(running = listOf(running(1, ProcessingStage.ANALYZE)), waiting = listOf(waiting(2), waiting(3)))
        val result = WidgetProcessingSummary.summarize(queue, now = 0L, stageFractions = emptyMap(), averages = averages)!!
        assertEquals("AI 処理中 1件・待ち 2件", result.title())
        assertEquals("AI 処理中 1件・待ち 2件、${result.detail()}", result.description())
    }

    @Test
    fun 過去の記録が無ければ残り時間は出さず_進み具合の割合を出す() {
        val queue = ProcessingQueue(running = listOf(running(1, ProcessingStage.EMBED)))
        val result = WidgetProcessingSummary.summarize(queue, now = 0L, stageFractions = emptyMap(), averages = emptyMap())!!
        assertNull(result.remainingMinutes)
        // 2 段目に入っているので 1/2 = 50%
        assertEquals(0.5f, result.fraction!!, 1e-6f)
        assertEquals("50% 完了", result.detail())
    }

    @Test
    fun 記録も進み具合も無ければ_詳細の行は出さない() {
        val queue = ProcessingQueue(waiting = listOf(waiting(1)))
        val result = WidgetProcessingSummary.summarize(queue, now = 0L, stageFractions = emptyMap(), averages = emptyMap())!!
        assertNull(result.remainingMinutes)
        assertNull(result.detail())
        assertEquals("AI 処理待ち 1件", result.description())
    }

    @Test
    fun 処理待ちがたまりすぎていれば_残り時間の見込みは出さない() {
        val many = (1L..(WidgetProcessingSummary.MAX_WAITING_FOR_ESTIMATE + 1L)).map { waiting(it) }
        val result = WidgetProcessingSummary.summarize(ProcessingQueue(waiting = many), 0L, emptyMap(), averages)!!
        assertNull(result.remainingMinutes)
        assertEquals(many.size, result.waiting)
    }

    @Test
    fun 画面に出す粒度が同じなら同じ値になる_何度も描き直さない() {
        val queue = ProcessingQueue(running = listOf(running(1, ProcessingStage.ANALYZE)))
        // 進み具合が 1% 動いても(丸めると同じ)、残り時間が秒だけ動いても(分に切り上げると同じ)、値は変わらない
        val a = WidgetProcessingSummary.summarize(queue, 1_000L, mapOf(1L to 0.10f), averages)
        val b = WidgetProcessingSummary.summarize(queue, 2_000L, mapOf(1L to 0.11f), averages)
        assertNotNull(a)
        assertEquals(a, b)
    }
}
