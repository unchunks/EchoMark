package com.unchunks.echomark.data.extract.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * PdfBox-Android で、テスト用の PDF(test/resources/pdf。英語の Helvetica と、
 * 日本語の定義済み CMap UniJIS-UCS2-H のページ。タイトルは日本語)からテキストとタイトルを取り出す。
 * 同梱の CMap(assets)を読むため Robolectric で動かす。
 */
@RunWith(AndroidJUnit4::class)
class PdfTextReaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PdfTextReader.ensureInitialized(context)
    }

    private fun resource(name: String): File {
        val file = temp.newFile(name)
        javaClass.getResourceAsStream("/pdf/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    @Test
    fun 英語と日本語のテキストとタイトルを取り出す() {
        val pdf = PdfTextReader.read(resource("sample_text.pdf"), temp.root)

        assertEquals(2, pdf.pageCount)
        assertEquals("テスト文書", pdf.title)
        assertTrue(pdf.text, pdf.text.contains("Hello EchoMark"))
        assertTrue(pdf.text, pdf.text.contains("日本語のテキスト抽出"))
        assertFalse(pdf.truncated)
        assertFalse(PdfTextQuality.looksScanned(pdf.text, pdf.pageCount))
    }

    @Test
    fun 文字数の上限を超えたらそこでやめる() {
        val pdf = PdfTextReader.read(resource("sample_text.pdf"), temp.root, maxChars = 5)

        assertTrue(pdf.truncated)
        assertFalse(pdf.text.contains("日本語"))
    }

    @Test
    fun テキストの無いPDFはスキャンとみなす() {
        val pdf = PdfTextReader.read(resource("sample_no_text.pdf"), temp.root)

        assertEquals(1, pdf.pageCount)
        assertEquals(null, pdf.title)
        assertTrue(PdfTextQuality.looksScanned(pdf.text, pdf.pageCount))
    }

    @Test
    fun 壊れたPDFは例外() {
        val broken = temp.newFile("broken.pdf").apply { writeText("not a pdf") }

        val result = runCatching { PdfTextReader.read(broken, temp.root) }

        assertTrue(result.isFailure)
    }

    @Test
    fun 意味のある文字の数え方() {
        assertEquals(5, PdfTextQuality.meaningfulCharCount("ab 1-2\nあ"))
        // 1 ページあたり 20 字未満ならスキャン扱い
        assertTrue(PdfTextQuality.looksScanned("a".repeat(39), pageCount = 2))
        assertFalse(PdfTextQuality.looksScanned("a".repeat(40), pageCount = 2))
        assertTrue(PdfTextQuality.looksScanned("", pageCount = 0))
    }
}
