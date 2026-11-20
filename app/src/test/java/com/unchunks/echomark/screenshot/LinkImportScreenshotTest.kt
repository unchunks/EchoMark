package com.unchunks.echomark.screenshot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.importer.ImportFormat
import com.unchunks.echomark.domain.importer.ImportParseResult
import com.unchunks.echomark.domain.importer.ImportResult
import com.unchunks.echomark.domain.importer.ImportedLink
import com.unchunks.echomark.ui.importer.LinkImportDialog
import com.unchunks.echomark.ui.importer.LinkImportState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** 他のサービスから取り込むダイアログの各状態(確認・保存中・結果・失敗)。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LinkImportScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val parsed = ImportParseResult(
        ImportFormat.HTML,
        links = listOf(
            ImportedLink("https://example.com/a", "A", tags = listOf("kotlin"), folder = "読み物"),
            ImportedLink("https://example.com/b", "B", folder = "仕事", archived = true),
            ImportedLink("https://example.com/c", "C")
        ),
        invalidCount = 2
    )

    @Test
    fun preview() = screenshot.captureDialogLightDark("import_preview") {
        Dialog(LinkImportState.Preview(parsed, folderAsTag = true))
    }

    @Test
    fun previewWithoutFolders() = screenshot.captureDialogLightDark("import_preview_no_folders") {
        Dialog(LinkImportState.Preview(ImportParseResult(ImportFormat.CSV, parsed.links.map { it.copy(folder = null) }, truncated = true), folderAsTag = false))
    }

    @Test
    fun importing() = screenshot.captureDialogLightDark("import_importing") {
        Dialog(LinkImportState.Importing(done = 1200, total = 3400))
    }

    @Test
    fun done() = screenshot.captureDialogLightDark("import_done") {
        Dialog(LinkImportState.Done(ImportResult(added = 3380, duplicates = 20, failed = 0, processingStarted = true)))
    }

    @Test
    fun doneDeferred() = screenshot.captureDialogLightDark("import_done_deferred") {
        Dialog(LinkImportState.Done(ImportResult(added = 12, duplicates = 0, failed = 1, processingStarted = false)))
    }

    @Test
    fun failed() = screenshot.captureDialogLightDark("import_failed") {
        Dialog(LinkImportState.Failed("このファイルの形式には対応していません。ブラウザ・Pocket・Raindrop.io・Instapaper・Pinboard などの書き出し(HTML・CSV・JSON)を選んでください"))
    }

    @Composable
    private fun Dialog(state: LinkImportState) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            LinkImportDialog(state = state, onFolderAsTagChange = {}, onImport = {}, onClose = {})
        }
    }
}
