package com.unchunks.echomark.screenshot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.ui.share.ShareSaveStatus
import com.unchunks.echomark.ui.share.ShareSheetContent
import com.unchunks.echomark.ui.share.SharedContent
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
}
