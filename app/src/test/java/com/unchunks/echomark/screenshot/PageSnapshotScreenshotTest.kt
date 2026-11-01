package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.ui.snapshot.PageSnapshotCallbacks
import com.unchunks.echomark.ui.snapshot.PageSnapshotProgressContent
import com.unchunks.echomark.ui.snapshot.PageSnapshotSection
import com.unchunks.echomark.ui.snapshot.SnapshotStep
import com.unchunks.echomark.ui.snapshot.SnapshotViewerContent
import com.unchunks.echomark.ui.snapshot.SnapshotViewerState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** ページのオフラインコピーの見た目(詳細画面のカード・作っている間の画面・コピーを開く画面)。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageSnapshotScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val link = Bookmark(
        id = 1, type = BookmarkType.URL, contentUri = "https://www.example.com/articles/compose-performance",
        title = "Jetpack Compose のパフォーマンス入門", createdAt = 1, lastAccessedAt = 1
    )

    // 2026-10-31 12:04 JST
    private val savedAt = 1_793_415_845_000L

    @Test
    fun sectionNotSaved() = screenshot.captureLightDark("snapshot_section_none") {
        Section(link)
    }

    @Test
    fun sectionBusy() = screenshot.captureLightDark("snapshot_section_busy") {
        Section(link, busy = true)
    }

    @Test
    fun sectionSaved() = screenshot.captureLightDark("snapshot_section_saved") {
        Section(link.copy(snapshotPath = "attachments/a.mhtml", snapshotPdfPath = "attachments/a.pdf", snapshotAt = savedAt))
    }

    @Test
    fun sectionSavedWithoutPdf() = screenshot.captureLightDark("snapshot_section_saved_no_pdf") {
        Section(link.copy(snapshotPath = "attachments/a.mhtml", snapshotAt = savedAt))
    }

    @Test
    fun progressLoading() = screenshot.captureScreenLightDark("snapshot_progress_loading", 400, 800) {
        PageSnapshotProgressContent(url = link.contentUri!!, step = SnapshotStep.LOADING, onCancel = {}) { PagePlaceholder() }
    }

    @Test
    fun progressPdf() = screenshot.captureScreenLightDark("snapshot_progress_pdf", 400, 800) {
        PageSnapshotProgressContent(url = link.contentUri!!, step = SnapshotStep.MAKING_PDF, onCancel = {}) { PagePlaceholder() }
    }

    @Test
    fun viewerReady() = screenshot.captureScreenLightDark("snapshot_viewer", 400, 800) {
        SnapshotViewerContent(
            state = ready(pdfPath = "attachments/a.pdf"),
            onBack = {},
            onOpenPdf = {},
            onOpenOriginal = {},
            page = { PagePlaceholder() }
        )
    }

    @Test
    fun viewerMissing() = screenshot.captureScreenLightDark("snapshot_viewer_missing", 400, 800) {
        SnapshotViewerContent(
            state = SnapshotViewerState.Missing,
            onBack = {},
            onOpenPdf = {},
            onOpenOriginal = {},
            page = {}
        )
    }

    private fun ready(pdfPath: String?) = SnapshotViewerState.Ready(
        bookmarkId = 1, title = link.title, originalUrl = link.contentUri, fileUrl = "file:///x.mhtml", pdfPath = pdfPath, savedAt = savedAt
    )

    @Composable
    private fun Section(bookmark: Bookmark, busy: Boolean = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.padding(20.dp)) {
                PageSnapshotSection(bookmark = bookmark, callbacks = PageSnapshotCallbacks(), busy = busy)
            }
        }
    }

    /** 実際には WebView が入る場所(単体描画では中身の代わりに文字を置く) */
    @Composable
    private fun PagePlaceholder() {
        Box(Modifier.fillMaxSize().padding(24.dp)) { Text("ここにページが表示されます") }
    }
}
