package com.unchunks.echomark.data.extract.image

import androidx.exifinterface.media.ExifInterface
import com.unchunks.echomark.data.extract.vision.ImageLabelerEngine
import com.unchunks.echomark.data.extract.vision.TextRecognizerEngine
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.extract.ExtractedContent
import com.unchunks.echomark.domain.extract.ExtractionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * 画像から、写っている文字(OCR)・写っているもの(ラベル)・撮影日時(EXIF)を取り出す。すべて端末内で処理する。
 * 大きな画像は長辺 [ImageSupport.MAX_SIDE] px までに縮めてから処理する(メモリ対策)。
 */
class ImageContentExtractor @Inject constructor(
    private val textRecognizer: TextRecognizerEngine,
    private val imageLabeler: ImageLabelerEngine,
    private val dispatcherProvider: DispatcherProvider
) {

    suspend fun extract(file: File): ExtractedContent? {
        val exif = withContext(dispatcherProvider.io) { readExif(file) }
        val bitmap = withContext(dispatcherProvider.io) { ImageSupport.decodeSampled(file) } ?: run {
            Timber.w("ImageContentExtractor: 画像を読み込めません")
            return null
        }
        try {
            val rotation = exif?.rotationDegrees ?: 0
            val ocrText = runOrDefault("OCR", "") { textRecognizer.recognize(bitmap, rotation) }
            val labels = runOrDefault("ラベル", emptyList()) { imageLabeler.label(bitmap, rotation) }
            val capturedAt = ImageSupport.formatExifDateTime(
                exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif?.getAttribute(ExifInterface.TAG_DATETIME)
            )
            val text = ImageSupport.composeImageText(ocrText, ImageLabelsJa.translate(labels), capturedAt)
            return ExtractedContent.of(text, ExtractionSource.IMAGE, engine = "mlkit")
        } finally {
            bitmap.recycle()
        }
    }

    private fun readExif(file: File): ExifInterface? = try {
        ExifInterface(file)
    } catch (e: Exception) {
        // EXIF を持たない形式(GIF など)・壊れたファイル
        null
    }

    /** 片方(OCR・ラベル)が失敗しても、もう片方の結果は使う。 */
    private suspend fun <T> runOrDefault(name: String, default: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "ImageContentExtractor: %s に失敗", name)
        default
    }
}
