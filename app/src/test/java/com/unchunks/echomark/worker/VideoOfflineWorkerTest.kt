package com.unchunks.echomark.worker

import com.unchunks.echomark.data.remote.FetchedContent
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.worker.VideoOfflineWorker.Companion.contentWithTranscript
import com.unchunks.echomark.worker.VideoOfflineWorker.Companion.needsSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 動画のオフライン保存で、後から字幕を入れた本文と、要約を作り直すかの決め方 */
class VideoOfflineWorkerTest {

    private val url = "https://www.youtube.com/watch?v=abcdefghijk"

    private fun bookmark(
        content: String? = null,
        summary: String? = "要約",
        summaryEdited: Boolean = false,
        aiStatus: AiStatus = AiStatus.DONE
    ) = Bookmark(
        type = BookmarkType.URL, contentUri = url, title = "動画", content = content, summary = summary,
        summaryEdited = summaryEdited, aiStatus = aiStatus, createdAt = 0, lastAccessedAt = 0
    )

    private val withoutTranscript = "チャンネル: A\n\n説明"
    private val fetched = FetchedContent(
        title = "動画",
        description = null,
        text = "チャンネル: A\n\n説明\n\n字幕:\nこんにちは",
        textWithoutTranscript = withoutTranscript
    )

    @Test
    fun 前に字幕なしで保存した説明文を字幕の入った本文に置き換えてメモは残す() {
        val content = contentWithTranscript(bookmark(content = "自分のメモ\n\n$withoutTranscript"), fetched)

        assertEquals("自分のメモ\n\nチャンネル: A\n\n説明\n\n字幕:\nこんにちは", content)
    }

    @Test
    fun 形の違う本文には字幕の入った本文を後ろに足す() {
        val content = contentWithTranscript(bookmark(content = "自分のメモ"), fetched)

        assertEquals("自分のメモ\n\nチャンネル: A\n\n説明\n\n字幕:\nこんにちは", content)
    }

    @Test
    fun 本文が無ければ字幕の入った本文にする() {
        assertEquals(fetched.text, contentWithTranscript(bookmark(content = null), fetched))
    }

    @Test
    fun 字幕を新しく入れたら要約を作り直す() {
        assertTrue(needsSummary(bookmark(), transcriptAdded = true))
        assertFalse(needsSummary(bookmark(), transcriptAdded = false))
    }

    @Test
    fun 要約が無ければ作る() {
        assertTrue(needsSummary(bookmark(summary = null), transcriptAdded = false))
        assertTrue(needsSummary(bookmark(summary = null, aiStatus = AiStatus.FAILED), transcriptAdded = false))
    }

    @Test
    fun 手で直した要約と処理中のものは作り直さない() {
        assertFalse(needsSummary(bookmark(summaryEdited = true), transcriptAdded = true))
        assertFalse(needsSummary(bookmark(summary = null, aiStatus = AiStatus.PROCESSING), transcriptAdded = true))
        assertFalse(needsSummary(bookmark(summary = null, aiStatus = AiStatus.PENDING), transcriptAdded = true))
        assertFalse(needsSummary(bookmark(summary = null, aiStatus = AiStatus.WAITING_MODEL), transcriptAdded = true))
    }
}
