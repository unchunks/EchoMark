package com.unchunks.echomark.domain.bookmark.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 何段目か・残り時間の目安・処理時間の見込み方。 */
class ProcessingProgressTest {

    private fun bookmark(
        type: BookmarkType,
        status: AiStatus = AiStatus.PROCESSING,
        stage: ProcessingStage? = null,
        filePath: String? = null,
        content: String? = null,
        contentFetchedAt: Long? = null,
        summary: String? = null,
        startedAt: Long? = null,
        stageStartedAt: Long? = null,
        finishedAt: Long? = null
    ) = Bookmark(
        type = type, title = "t", createdAt = 0L, lastAccessedAt = 0L, aiStatus = status,
        contentUri = if (type == BookmarkType.URL) "https://example.com/a" else null,
        filePath = filePath, content = content, contentFetchedAt = contentFetchedAt, summary = summary,
        processing = ProcessingInfo(
            stage = stage, startedAt = startedAt, stageStartedAt = stageStartedAt, finishedAt = finishedAt
        )
    )

    private val web = ContentKind.WEB_PAGE

    @Test
    fun 種類ごとの段() {
        val all = ProcessingStage.entries
        assertEquals(listOf(ProcessingStage.ANALYZE, ProcessingStage.EMBED), bookmark(BookmarkType.TEXT).processingStages())
        assertEquals(all.drop(1), bookmark(BookmarkType.AUDIO, filePath = "attachments/a.m4a").processingStages())
        assertEquals(all - ProcessingStage.EXTRACT_CONTENT, bookmark(BookmarkType.URL).processingStages())
        // リンク先がファイルだった URL は取り出しの段も通る
        assertEquals(all, bookmark(BookmarkType.URL, filePath = "attachments/a.pdf").processingStages())
    }

    @Test
    fun 処理中は今の段が何番目かと_今の段とこの後の段の平均から残り時間を見込む() {
        val averages = mapOf(
            StageDurationKey(ProcessingStage.FETCH_CONTENT, web) to 10_000L,
            StageDurationKey(ProcessingStage.ANALYZE, web) to 60_000L,
            StageDurationKey(ProcessingStage.EMBED, web) to 5_000L
        )
        val progress = bookmark(
            BookmarkType.URL, stage = ProcessingStage.FETCH_CONTENT, startedAt = 1_000L, stageStartedAt = 1_000L
        ).processingProgress(now = 5_000L, stageFraction = null, averages = averages)!!

        assertEquals(1, progress.stageNumber)
        assertEquals(3, progress.stageCount)
        assertEquals(4_000L, progress.elapsedMillis)
        // 取得の残り 6 秒 + 要約 60 秒 + 埋め込み 5 秒
        assertEquals(71_000L, progress.remainingMillis)
    }

    @Test
    fun 段が進み具合を報告していれば_今の段の残りはその割合から見込む() {
        val averages = mapOf(
            StageDurationKey(ProcessingStage.EXTRACT_CONTENT, ContentKind.AUDIO) to 1_000L,
            StageDurationKey(ProcessingStage.ANALYZE, ContentKind.AUDIO) to 20_000L,
            StageDurationKey(ProcessingStage.EMBED, ContentKind.AUDIO) to 2_000L
        )
        val progress = bookmark(
            BookmarkType.AUDIO, stage = ProcessingStage.EXTRACT_CONTENT, filePath = "attachments/a.m4a",
            startedAt = 0L, stageStartedAt = 0L
        ).processingProgress(now = 60_000L, stageFraction = 0.25f, averages = averages)!!

        assertEquals(0.25f, progress.stageFraction)
        assertEquals(1, progress.stageNumber)
        // 1/4 に 60 秒かかったので、残り 3/4 に 180 秒 + 要約 20 秒 + 埋め込み 2 秒
        assertEquals(202_000L, progress.remainingMillis)
    }

    @Test
    fun 記録の無い段があれば残り時間は出さない() {
        val progress = bookmark(BookmarkType.TEXT, stage = ProcessingStage.ANALYZE, stageStartedAt = 0L)
            .processingProgress(now = 0L, stageFraction = null, averages = emptyMap())!!

        assertEquals(1, progress.stageNumber)
        assertEquals(2, progress.stageCount)
        assertNull(progress.remainingMillis)
        assertNull(progress.elapsedMillis)
    }

    @Test
    fun 処理待ちは次に通る段と_通らない段を除いた残り時間を出す() {
        // 本文を取得済みの URL を再処理: 取得の段は通らず、要約から
        val averages = mapOf(
            StageDurationKey(ProcessingStage.ANALYZE, web) to 30_000L,
            StageDurationKey(ProcessingStage.EMBED, web) to 3_000L
        )
        val progress = bookmark(BookmarkType.URL, status = AiStatus.PENDING, content = "本文", contentFetchedAt = 1L)
            .processingProgress(now = 0L, stageFraction = 0.5f, averages = averages)!!

        assertEquals(2, progress.stageNumber)
        assertEquals(3, progress.stageCount)
        // 処理待ちのときは段の進み具合を使わない
        assertNull(progress.stageFraction)
        assertEquals(33_000L, progress.remainingMillis)
    }

    @Test
    fun 処理を終えたものは進み具合を出さず_処理時間を出す() {
        val done = bookmark(BookmarkType.TEXT, status = AiStatus.DONE, startedAt = 1_000L, finishedAt = 61_000L)

        assertNull(done.processingProgress(now = 0L, stageFraction = null, averages = emptyMap()))
        assertEquals(60_000L, done.processingDurationMillis())
        assertNull(bookmark(BookmarkType.TEXT, status = AiStatus.DONE, finishedAt = 1L).processingDurationMillis())
    }
}
