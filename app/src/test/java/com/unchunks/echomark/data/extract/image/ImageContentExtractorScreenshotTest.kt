package com.unchunks.echomark.data.extract.image

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.documenttext.ImageLabelerEngine
import com.unchunks.components.documenttext.TextRecognizerEngine
import com.unchunks.components.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** スクリーンショットの OCR(長い画像を帯に分けて読む・種類を書き添える)。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageContentExtractorScreenshotTest {

    private lateinit var context: Context
    private val files = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        files.forEach { it.delete() }
    }

    private fun png(name: String, width: Int, height: Int): File {
        val file = File(context.cacheDir, name).also { files += it }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /** 呼ばれるたびに、受け取った画像の大きさを記録して決まった文字を返す */
    private class Recognizer(private val results: List<String>) : TextRecognizerEngine {
        val sizes = mutableListOf<Pair<Int, Int>>()
        override suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int): String {
            sizes += bitmap.width to bitmap.height
            return results.getOrElse(sizes.size - 1) { results.last() }
        }
    }

    private object NoLabels : ImageLabelerEngine {
        override suspend fun label(bitmap: Bitmap, rotationDegrees: Int): List<String> = emptyList()
    }

    private fun extractor(recognizer: Recognizer) =
        ImageContentExtractor(recognizer, NoLabels, TestDispatcherProvider(Dispatchers.Unconfined))

    @Test
    fun 長いスクリーンショットは縮めずに帯に分けて読みつなぐ() = runBlocking {
        val image = png("long.png", 600, 5000)
        val recognizer = Recognizer(listOf("見出し\n一行目", "一行目\n二行目", "二行目\n三行目", "三行目\n終わり"))

        val content = extractor(recognizer).extract(image)

        assertNotNull(content)
        // 帯は元の幅のまま(縮められていない)
        assertTrue(recognizer.sizes.size > 1)
        assertTrue(recognizer.sizes.all { it.first == 600 && it.second <= 2048 })
        assertTrue(content!!.text, content.text.contains("見出し\n一行目\n二行目\n三行目\n終わり"))
    }

    @Test
    fun スクリーンショットらしい画像は本文の先頭に種類を書き添える() = runBlocking {
        val image = png("shot.png", 540, 1200)

        val content = extractor(Recognizer(listOf("設定の画面"))).extract(image)

        assertTrue(content!!.text, content.text.startsWith(SCREENSHOT_LABEL))
        assertTrue(content.text.contains("設定の画面"))
    }

    @Test
    fun 文字が読めなかったスクリーンショットには種類だけを書き添えない() = runBlocking {
        val image = png("blank.png", 540, 1200)

        val content = extractor(Recognizer(listOf(""))).extract(image)

        assertTrue(content == null || content.text.isEmpty())
    }

    @Test
    fun 縦横比が普通の画像には種類を書き添えない() = runBlocking {
        val image = png("square.png", 800, 800)

        val content = extractor(Recognizer(listOf("看板"))).extract(image)

        assertFalse(content!!.text.contains(SCREENSHOT_LABEL))
    }
}
