package com.unchunks.echomark.worker

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.AnalysisAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** AI 処理ワーカーが組み立てる入力(AI に渡すファイル・埋め込みのテキスト)。 */
class AiProcessingInputTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun bookmark(filePath: String?, mimeType: String? = "image/png") = Bookmark(
        type = BookmarkType.IMAGE, title = "t", createdAt = 0, lastAccessedAt = 0,
        filePath = filePath, mimeType = mimeType
    )

    @Test
    fun アプリの領域のファイルを絶対パスとサイズで渡す() {
        val filesDir = folder.newFolder("files")
        val file = filesDir.resolve("images/a.png").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)) }

        assertEquals(
            AnalysisAttachment(file.canonicalPath, "image/png", 10),
            analysisAttachmentOf(bookmark("images/a.png"), filesDir)
        )
    }

    @Test
    fun ファイルが無い_MIMEタイプが無い_領域の外を指すときは渡さない() {
        val filesDir = folder.newFolder("files")
        filesDir.resolve("a.png").writeBytes(ByteArray(1))
        folder.newFile("secret.txt")

        assertNull(analysisAttachmentOf(bookmark(null), filesDir))
        assertNull(analysisAttachmentOf(bookmark("missing.png"), filesDir))
        assertNull(analysisAttachmentOf(bookmark("a.png", mimeType = null), filesDir))
        assertNull(analysisAttachmentOf(bookmark("../secret.txt"), filesDir))
    }
}
