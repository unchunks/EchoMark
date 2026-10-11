package com.unchunks.echomark.data.extract.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.unchunks.echomark.data.extract.image.ImageSupport
import com.unchunks.echomark.data.extract.vision.TextRecognizerEngine
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.extract.ExtractedContent
import com.unchunks.echomark.domain.extract.ExtractionSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PDF のテキストを取り出す。
 * 1. PdfBox-Android で埋め込まれたテキストとタイトルを取り出す
 * 2. テキストがほとんど無い(スキャンした)PDF は、端末の PdfRenderer で先頭 [MAX_OCR_PAGES] ページを画像にして OCR する
 */
class PdfContentExtractor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val textRecognizer: TextRecognizerEngine,
    private val dispatcherProvider: DispatcherProvider
) {

    suspend fun extract(file: File): ExtractedContent? {
        val pdfText = withContext(dispatcherProvider.io) { readText(file) }
        if (pdfText != null && !PdfTextQuality.looksScanned(pdfText.text, pdfText.pageCount)) {
            return pdfText.toContent()
        }
        // テキストが無い・少ない: ページを画像にして OCR する
        val ocr = ocrPages(file)
        val ocrChars = ocr?.let { PdfTextQuality.meaningfulCharCount(it.text) } ?: 0
        val textChars = pdfText?.let { PdfTextQuality.meaningfulCharCount(it.text) } ?: 0
        return if (ocr != null && ocrChars > textChars) {
            ExtractedContent.of(
                text = ocr.text,
                source = ExtractionSource.PDF_OCR,
                title = pdfText?.title,
                pageCount = pdfText?.pageCount ?: ocr.pageCount,
                truncated = ocr.truncated,
                engine = "pdfrenderer+mlkit"
            )
        } else {
            pdfText?.toContent()
        }
    }

    private fun PdfText.toContent(): ExtractedContent? = ExtractedContent.of(
        text = text,
        source = ExtractionSource.PDF_TEXT,
        title = title,
        pageCount = pageCount,
        truncated = truncated,
        engine = "pdfbox"
    )

    private fun readText(file: File): PdfText? = try {
        PdfTextReader.ensureInitialized(context)
        PdfTextReader.read(file, tempDir = context.cacheDir)
    } catch (e: Exception) {
        // パスワード付き(InvalidPasswordException)・壊れた PDF・メモリ不足以外の想定外の例外
        Timber.w(e, "PdfContentExtractor: テキストを取り出せません")
        null
    } catch (e: OutOfMemoryError) {
        Timber.w(e, "PdfContentExtractor: メモリ不足でテキストを取り出せません")
        null
    }

    private data class OcrResult(val text: String, val pageCount: Int, val truncated: Boolean)

    /** 先頭のページを画像にして OCR する。開けない PDF は null。 */
    private suspend fun ocrPages(file: File): OcrResult? {
        val renderer = withContext(dispatcherProvider.io) {
            try {
                PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            } catch (e: Exception) {
                // パスワード付き(SecurityException)・壊れた PDF
                Timber.w(e, "PdfContentExtractor: PDF を開けません")
                null
            }
        } ?: return null
        return try {
            val pages = min(renderer.pageCount, MAX_OCR_PAGES)
            val text = StringBuilder()
            for (index in 0 until pages) {
                currentCoroutineContext().ensureActive()
                val bitmap = withContext(dispatcherProvider.io) { renderPage(renderer, index) } ?: continue
                val pageText = try {
                    textRecognizer.recognize(bitmap)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "PdfContentExtractor: %d ページの OCR に失敗", index + 1)
                    ""
                } finally {
                    bitmap.recycle()
                }
                if (pageText.isNotBlank()) {
                    if (text.isNotEmpty()) text.append("\n\n")
                    text.append(pageHeader(index + 1)).append('\n').append(pageText.trim())
                }
            }
            OcrResult(text.toString(), renderer.pageCount, truncated = renderer.pageCount > pages)
        } finally {
            renderer.close()
        }
    }

    /** 1ページを白背景の画像にする(長辺 [ImageSupport.MAX_SIDE] px まで)。 */
    private fun renderPage(renderer: PdfRenderer, index: Int): Bitmap? = try {
        renderer.openPage(index).use { page ->
            val (width, height) = renderSize(page.width, page.height)
            val bitmap = createBitmap(width, height)
            // PdfRenderer は背景を描かない(透明のままだと OCR で文字が見えない)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    } catch (e: Exception) {
        Timber.w(e, "PdfContentExtractor: %d ページを画像にできません", index + 1)
        null
    } catch (e: OutOfMemoryError) {
        Timber.w(e, "PdfContentExtractor: メモリ不足で %d ページを画像にできません", index + 1)
        null
    }

    companion object {
        /** OCR するページ数の上限(先頭から) */
        const val MAX_OCR_PAGES = 10

        /** OCR 用に描く解像度(dpi)。PDF の大きさはポイント(1/72 インチ)単位 */
        private const val RENDER_DPI = 200

        /** ページの大きさ(ポイント)から、OCR 用に描く画像の大きさ(px)を決める。 */
        internal fun renderSize(widthPt: Int, heightPt: Int): Pair<Int, Int> {
            val scale = RENDER_DPI / 72.0
            val width = (widthPt * scale).roundToInt().coerceAtLeast(1)
            val height = (heightPt * scale).roundToInt().coerceAtLeast(1)
            return ImageSupport.fitWithin(width, height)
        }

        /** OCR したページの見出し */
        internal fun pageHeader(page: Int): String = "[$page ページ]"
    }
}
