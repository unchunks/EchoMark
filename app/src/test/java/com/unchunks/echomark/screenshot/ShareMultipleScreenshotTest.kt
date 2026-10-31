package com.unchunks.echomark.screenshot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.ui.share.ShareSaveStatus
import com.unchunks.echomark.ui.share.ShareSheetContent
import com.unchunks.echomark.ui.share.SharedContent
import com.unchunks.echomark.ui.share.SharedEntry
import com.unchunks.echomark.ui.share.quoteContentOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** 複数のテキスト・URL をまとめて共有したときの共有シート。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareMultipleScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val entries = listOf(
        SharedEntry("https://www.example.com/articles/compose-performance", "https://www.example.com/articles/compose-performance", "Compose"),
        SharedEntry(null, "買うもの\n牛乳と卵", "買うもの"),
        SharedEntry("https://kotlinlang.org/docs/home.html", "Kotlin 公式 https://kotlinlang.org/docs/home.html", "Kotlin 公式"),
        SharedEntry(null, "会議のメモ", "会議のメモ"),
        SharedEntry("https://developer.android.com/", "https://developer.android.com/", "https://developer.android.com/"),
        SharedEntry(null, "最後のメモ", "最後のメモ")
    )

    private val shared = SharedContent(url = null, text = entries.joinToString("\n") { it.text }, tentativeTitle = "", entries = entries)

    @Test
    fun form() = screenshot.captureLightDark("share_multiple_entries") {
        Sheet(ShareSaveStatus.Editing)
    }

    @Test
    fun saving() = screenshot.captureLightDark("share_multiple_entries_saving") {
        Sheet(ShareSaveStatus.Saving(done = 2, total = 6))
    }

    @Test
    fun savedPartially() = screenshot.captureLightDark("share_multiple_entries_saved") {
        Sheet(ShareSaveStatus.Saved(isDuplicate = false, savedCount = 5, failedCount = 1))
    }

    @Test
    fun quote() = screenshot.captureLightDark("share_quote") {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            ShareSheetContent(
                shared = quoteContentOf("プログラムは、人間が読むために書かれるべきであり、機械が実行するのは、ついでに過ぎない。\n二行目の文章")!!,
                status = ShareSaveStatus.Editing,
                title = "プログラムは、人間が読むために書かれるべきであり、機械が実行するのは、ついでに過ぎない。",
                memo = "",
                tags = "引用",
                onTitleChange = {},
                onMemoChange = {},
                onTagsChange = {},
                onSave = {},
                onCancel = {}
            )
        }
    }

    @Composable
    private fun Sheet(status: ShareSaveStatus) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            ShareSheetContent(
                shared = shared,
                status = status,
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
