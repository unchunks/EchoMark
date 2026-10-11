package com.unchunks.echomark.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.ui.attachment.AttachmentPreviewer
import com.unchunks.echomark.ui.attachment.LocalAttachmentPreviewer
import com.unchunks.echomark.ui.components.BookmarkCard
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.detail.BookmarkDetailCallbacks
import com.unchunks.echomark.ui.detail.BookmarkDetailContent
import com.unchunks.echomark.ui.detail.BookmarkDetailUiState
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 保存したファイル(画像・PDF・音声・動画)の一覧カードと詳細画面の見た目。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    @Before
    fun setUp() {
        // 画面は端末にファイルがあるときだけプレビューを出すため、仮のファイルを置く(中身は仮の previewer が描く)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        listOf(IMAGE_PATH, PDF_PATH, AUDIO_PATH, VIDEO_PATH, URL_PDF_PATH).forEach {
            File(dir, it.removePrefix("attachments/")).writeBytes(ByteArray(16))
        }
    }

    private val base = Bookmark(
        type = BookmarkType.IMAGE,
        title = "",
        createdAt = PreviewSamples.NOW - 2 * 60 * 60 * 1000L,
        lastAccessedAt = PreviewSamples.NOW,
        aiStatus = AiStatus.DONE
    )

    private val image = base.copy(
        id = 101,
        type = BookmarkType.IMAGE,
        title = "ホワイトボード(設計レビュー)",
        summary = "画面遷移とデータの流れを描いた図。共有シートから保存したファイルを抽出 → AI 処理へつなぐ順序が示されている。",
        category = "仕事",
        tags = listOf("設計", "メモ"),
        aiTags = setOf("設計"),
        content = "撮影: 会議室B\n\n共有シート → 保存 → 抽出 → AI 処理\nファイルは filesDir/attachments に置く",
        filePath = IMAGE_PATH, mimeType = "image/jpeg", fileName = "PXL_20261005_101530.jpg", fileSize = 3_400_000L
    )
    private val pdf = base.copy(
        id = 102,
        type = BookmarkType.PDF,
        title = "2026年度 事業計画書",
        summary = "来期の重点施策は3つ。既存顧客の継続率改善、法人向けプランの開始、サポートの自動化。",
        category = "資料",
        content = "第1章 はじめに\n本書は2026年度の事業計画をまとめたものである。",
        filePath = PDF_PATH, mimeType = "application/pdf", fileName = "2026年度 事業計画書(最終版).pdf", fileSize = 1_250_000L
    )
    private val audio = base.copy(
        id = 103,
        type = BookmarkType.AUDIO,
        title = "定例ミーティング 10月6日",
        aiStatus = AiStatus.PROCESSING,
        filePath = AUDIO_PATH, mimeType = "audio/mp4", fileName = "定例ミーティング 10月6日.m4a", fileSize = 18_000_000L
    )
    private val video = base.copy(
        id = 104,
        type = BookmarkType.VIDEO,
        title = "子どもの運動会",
        summary = "リレーの最終走者がゴールする場面。",
        filePath = VIDEO_PATH, mimeType = "video/mp4", fileName = "VID_20261004.mp4", fileSize = 84_000_000L
    )
    private val urlPdf = base.copy(
        id = 105,
        type = BookmarkType.URL,
        title = "Attention Is All You Need",
        contentUri = "https://arxiv.org/pdf/1706.03762",
        summary = "自己注意だけで系列変換を行う Transformer を提案した論文。",
        filePath = URL_PDF_PATH, mimeType = "application/pdf", fileName = "1706.03762.pdf", fileSize = 2_215_000L
    )
    private val missingFile = image.copy(id = 106, filePath = "attachments/missing.jpg", title = "バックアップから読み込んだ写真")

    private fun withPreviewer(content: @Composable () -> Unit): @Composable () -> Unit = {
        CompositionLocalProvider(LocalAttachmentPreviewer provides FakePreviewer) { content() }
    }

    private fun detail(bookmark: Bookmark): @Composable () -> Unit = withPreviewer {
        BookmarkDetailContent(
            uiState = BookmarkDetailUiState(isLoading = false, bookmark = bookmark),
            snackbarHostState = SnackbarHostState(),
            callbacks = BookmarkDetailCallbacks(),
            nowMillis = PreviewSamples.NOW
        )
    }

    @Test
    fun cards() = screenshot.captureLightDark("library_cards_files", content = withPreviewer {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(image, pdf, audio, video, urlPdf).forEach {
                BookmarkCard(bookmark = it, onClick = {}, nowMillis = PreviewSamples.NOW)
            }
        }
    })

    @Test
    fun detailImage() = screenshot.captureScreenLightDark("library_detail_image", heightDp = 1200, content = detail(image))

    @Test
    fun detailPdf() = screenshot.captureScreenLightDark("library_detail_pdf", heightDp = 1000, content = detail(pdf))

    @Test
    fun detailAudio() = screenshot.captureScreenLightDark("library_detail_audio", content = detail(audio))

    @Test
    fun detailVideo() = screenshot.captureScreenLightDark("library_detail_video", content = detail(video))

    @Test
    fun detailUrlPdf() = screenshot.captureScreenLightDark("library_detail_url_pdf", content = detail(urlPdf))

    @Test
    fun detailMissingFile() = screenshot.captureScreenLightDark("library_detail_file_missing", content = detail(missingFile))

    /** 端末の PdfRenderer などの代わりに、それらしい絵を描く */
    private object FakePreviewer : AttachmentPreviewer {
        override suspend fun pdfPageCount(file: File): Int = 12

        override suspend fun pdfPage(file: File, pageIndex: Int, widthPx: Int): ImageBitmap {
            val height = (widthPx * 1.414f).toInt()
            val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            val paint = Paint().apply { color = 0xFF9AA4B2.toInt() }
            val margin = widthPx * 0.1f
            val line = height / 30f
            canvas.drawRect(margin, margin, widthPx * 0.7f, margin + line * 1.5f, paint.apply { color = 0xFF3F4A5A.toInt() })
            paint.color = 0xFFB8C0CC.toInt()
            var y = margin + line * 3
            while (y < height - margin) {
                canvas.drawRect(margin, y, widthPx - margin - (y.toInt() % 3) * margin, y + line * 0.5f, paint)
                y += line
            }
            return bitmap.asImageBitmap()
        }

        override suspend fun mediaDurationMillis(file: File): Long = if (file.name.contains("video")) 95_000L else 1_845_000L

        override suspend fun videoFrame(file: File, widthPx: Int): ImageBitmap {
            val height = widthPx * 9 / 16
            val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xFF5B8C5A.toInt())
            canvas.drawRect(0f, 0f, widthPx.toFloat(), height * 0.45f, Paint().apply { color = 0xFF8DB8E0.toInt() })
            return bitmap.asImageBitmap()
        }
    }

    private companion object {
        const val IMAGE_PATH = "attachments/sample-image.jpg"
        const val PDF_PATH = "attachments/sample-doc.pdf"
        const val AUDIO_PATH = "attachments/sample-audio.m4a"
        const val VIDEO_PATH = "attachments/sample-video.mp4"
        const val URL_PDF_PATH = "attachments/sample-url.pdf"
    }
}
