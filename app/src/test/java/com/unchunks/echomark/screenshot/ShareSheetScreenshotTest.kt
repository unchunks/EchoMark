package com.unchunks.echomark.screenshot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.ui.share.ShareSaveStatus
import com.unchunks.echomark.ui.share.ShareSheetContent
import com.unchunks.echomark.ui.share.SharedContent
import com.unchunks.echomark.ui.common.SelectedFile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** 共有シート(入力中・保存後)の見た目。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareSheetScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val shared = SharedContent(
        url = "https://www.example.com/articles/compose-performance?utm_source=share",
        text = "https://www.example.com/articles/compose-performance?utm_source=share",
        tentativeTitle = "https://www.example.com/articles/compose-performance?utm_source=share"
    )

    @Test
    fun form() = screenshot.captureLightDark("library_share_sheet") {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            ShareSheetContent(
                shared = shared,
                status = ShareSaveStatus.Editing,
                title = "",
                memo = "",
                tags = "",
                onTitleChange = {},
                onMemoChange = {},
                onTagsChange = {},
                onSave = {},
                onCancel = {}
            )
        }
    }

    @Test
    fun saved() = screenshot.captureLightDark("library_share_sheet_saved") {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            ShareSheetContent(
                shared = shared,
                status = ShareSaveStatus.Saved(isDuplicate = true),
                title = "",
                memo = "",
                tags = "",
                onTitleChange = {},
                onMemoChange = {},
                onTagsChange = {},
                onSave = {},
                onCancel = {}
            )
        }
    }

    private fun fileShare(vararg files: SelectedFile, text: String = "") =
        SharedContent(url = null, text = text, tentativeTitle = "", files = files.toList())

    private fun capture(name: String, shared: SharedContent, status: ShareSaveStatus = ShareSaveStatus.Editing, memo: String = "") =
        screenshot.captureLightDark(name) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                ShareSheetContent(
                    shared = shared,
                    status = status,
                    title = "",
                    memo = memo,
                    tags = "",
                    onTitleChange = {},
                    onMemoChange = {},
                    onTagsChange = {},
                    onSave = {},
                    onCancel = {}
                )
            }
        }

    @Test
    fun image() = capture(
        "library_share_sheet_image",
        fileShare(SelectedFile("content://media/1", "image/jpeg", "PXL_20261004_183012345.jpg", 3_400_000L)),
        memo = "鎌倉の夕焼け"
    )

    @Test
    fun pdf() = capture(
        "library_share_sheet_pdf",
        fileShare(SelectedFile("content://docs/2", "application/pdf", "2026年度 事業計画書(最終版).pdf", 1_250_000L))
    )

    @Test
    fun audio() = capture(
        "library_share_sheet_audio",
        fileShare(SelectedFile("content://rec/3", "audio/mp4", "定例ミーティング 10月6日.m4a", 18_000_000L)),
        status = ShareSaveStatus.Saving(0, 1)
    )

    @Test
    fun multiple() = capture(
        "library_share_sheet_multiple",
        fileShare(
            SelectedFile("content://media/1", "image/jpeg", "IMG_0001.jpg", 2_100_000L),
            SelectedFile("content://media/2", "image/png", "スクリーンショット 2026-10-05.png", 820_000L),
            SelectedFile("content://docs/3", "application/pdf", "invoice.pdf", 90_000L),
            SelectedFile("content://media/4", "video/mp4", "旅行の動画.mp4", 350_000_000L),
            SelectedFile("content://p/5", "application/zip", "archive.zip", 5_000L)
        )
    )

    @Test
    fun filesSavedPartially() = capture(
        "library_share_sheet_files_saved",
        fileShare(SelectedFile("content://media/1", "image/jpeg", "a.jpg", 1L)),
        status = ShareSaveStatus.Saved(isDuplicate = false, savedCount = 3, failedCount = 1)
    )
}
