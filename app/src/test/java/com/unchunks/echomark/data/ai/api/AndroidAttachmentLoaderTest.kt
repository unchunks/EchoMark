package com.unchunks.echomark.data.ai.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.model.AnalysisAttachment
import com.unchunks.echomark.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Base64

/** 画像の縮小・JPEG 化と、読めないファイルの扱い(Robolectric のネイティブ描画で実際に画像を作って確かめる)。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidAttachmentLoaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val loader = AndroidAttachmentLoader(TestDispatcherProvider(Dispatchers.Unconfined))

    private fun pngFile(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
        return folder.newFile("image.png").apply {
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun 大きな画像は長辺1568pxに縮小してJPEGにする() = runBlocking {
        val file = pngFile(4000, 3000)

        val result = loader.load(
            AnalysisAttachment(file.path, "image/png", file.length()),
            AttachmentKind.IMAGE
        )

        assertNotNull(result)
        assertEquals("image/jpeg", result!!.mimeType)
        assertEquals("image.png", result.fileName)
        val bytes = Base64.getDecoder().decode(result.base64Data)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(1568, decoded.width)
        assertEquals(1176, decoded.height)
        // 透過部分は黒ではなく白にする
        val pixel = decoded.getPixel(10, 10)
        assertTrue("pixel=${Integer.toHexString(pixel)}", listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)).all { it > 240 })
    }

    @Test
    fun 小さな画像は拡大しない() = runBlocking {
        val file = pngFile(300, 200)

        val result = loader.load(AnalysisAttachment(file.path, "image/png", file.length()), AttachmentKind.IMAGE)!!

        val bytes = Base64.getDecoder().decode(result.base64Data)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(300, decoded.width)
        assertEquals(200, decoded.height)
    }

    @Test
    fun 画像として読めないファイルや存在しないファイルはnull() = runBlocking {
        val broken = folder.newFile("broken.jpg").apply { writeText("not an image") }

        assertNull(loader.load(AnalysisAttachment(broken.path, "image/jpeg", broken.length()), AttachmentKind.IMAGE))
        assertNull(loader.load(AnalysisAttachment("/no/such/file.jpg", "image/jpeg", 10), AttachmentKind.IMAGE))
    }

    @Test
    fun 音声はそのままbase64にし_MIMEタイプを各社の表記にそろえる() = runBlocking {
        val file = folder.newFile("voice.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val result = loader.load(AnalysisAttachment(file.path, "audio/mp4", file.length()), AttachmentKind.AUDIO)!!

        assertEquals("audio/m4a", result.mimeType)
        assertEquals("AQID", result.base64Data)
    }
}
