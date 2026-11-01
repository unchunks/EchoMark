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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * OCR・ラベルのライブラリ(ML Kit)のクラスの初期化に失敗したとき(ExceptionInInitializerError / NoClassDefFoundError)の画像の取り出し。
 * Error のまま投げると取り出しのワーカーが失敗し、後続の AI 処理まで実行されなくなる。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageContentExtractorLinkageTest {

    private lateinit var context: Context
    private lateinit var image: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        image = File(context.cacheDir, "photo.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @After
    fun tearDown() {
        image.delete()
    }

    private class Recognizer(private val failure: Throwable? = null) : TextRecognizerEngine {
        var calls = 0
        override suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int): String {
            calls++
            failure?.let { throw it }
            return "看板の文字"
        }
    }

    private class Labeler(private val failure: Throwable? = null) : ImageLabelerEngine {
        var calls = 0
        override suspend fun label(bitmap: Bitmap, rotationDegrees: Int): List<String> {
            calls++
            failure?.let { throw it }
            return listOf("Food")
        }
    }

    private fun extractor(recognizer: Recognizer, labeler: Labeler) =
        ImageContentExtractor(recognizer, labeler, TestDispatcherProvider(Dispatchers.Unconfined))

    @Test
    fun OCRのライブラリを初期化できなくてもラベルは使う() = runBlocking {
        val recognizer = Recognizer(ExceptionInInitializerError(IllegalStateException("init")))
        val labeler = Labeler()

        val content = extractor(recognizer, labeler).extract(image)

        assertEquals(1, recognizer.calls)
        assertEquals(1, labeler.calls)
        assertNotNull(content)
        assertTrue(content!!.text, content.text.contains("写っているもの"))
    }

    @Test
    fun ラベルのライブラリを初期化できなくてもOCRの結果は使う() = runBlocking {
        val recognizer = Recognizer()
        val labeler = Labeler(NoClassDefFoundError("com.google.mlkit.vision.label.ImageLabeling"))

        val content = extractor(recognizer, labeler).extract(image)

        assertEquals(1, labeler.calls)
        assertNotNull(content)
        assertTrue(content!!.text, content.text.contains("看板の文字"))
    }
}
