package com.unchunks.echomark.ui.components

import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.bookmark.model.ProcessingInfo
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 一覧・詳細のバッジに出す、処理待ちと処理中(段の名前付き)の言い方。 */
class ProcessingBadgeTest {

    private fun bookmark(type: BookmarkType, status: AiStatus, stage: ProcessingStage?) = Bookmark(
        type = type, title = "t", createdAt = 0L, lastAccessedAt = 0L, aiStatus = status,
        processing = ProcessingInfo(stage = stage)
    )

    @Test
    fun 処理待ちと処理中を分けて出す() {
        assertEquals("処理待ち", ProcessingBadge(AiStatus.PENDING).label())
        assertEquals("処理中…", ProcessingBadge(AiStatus.PROCESSING).label())
        assertEquals("要約・タグ付け中…", ProcessingBadge(AiStatus.PROCESSING, ProcessingStage.ANALYZE).label())
    }

    @Test
    fun 段の名前は中身の種類に合わせる() {
        fun label(type: BookmarkType, stage: ProcessingStage) =
            bookmark(type, AiStatus.PROCESSING, stage).processingBadge().label()

        assertEquals("本文の取得中…", label(BookmarkType.URL, ProcessingStage.FETCH_CONTENT))
        assertEquals("文字起こし中…", label(BookmarkType.AUDIO, ProcessingStage.EXTRACT_CONTENT))
        assertEquals("文字起こし中…", label(BookmarkType.VIDEO, ProcessingStage.EXTRACT_CONTENT))
        assertEquals("画像の読み取り中…", label(BookmarkType.IMAGE, ProcessingStage.EXTRACT_CONTENT))
        assertEquals("PDFの読み取り中…", label(BookmarkType.PDF, ProcessingStage.EXTRACT_CONTENT))
        assertEquals("検索の準備中…", label(BookmarkType.TEXT, ProcessingStage.EMBED))
    }

    @Test
    fun 処理中でなければ残っている段は出さない() {
        val badge = bookmark(BookmarkType.URL, AiStatus.PENDING, ProcessingStage.FETCH_CONTENT).processingBadge()

        assertNull(badge.stage)
        assertEquals("処理待ち", badge.label())
    }

    @Test
    fun 説明は完了以外の全部の状態と段にある() {
        AiStatus.entries.filter { it != AiStatus.DONE }.forEach { status ->
            assertTrue("$status の説明が無い", ProcessingBadge(status).description() != null)
        }
        ProcessingStage.entries.forEach { stage ->
            ContentKind.entries.forEach { kind ->
                assertTrue(ProcessingBadge(AiStatus.PROCESSING, stage, kind).description() != null)
            }
        }
        assertNull(ProcessingBadge(AiStatus.DONE).description())
    }
}
