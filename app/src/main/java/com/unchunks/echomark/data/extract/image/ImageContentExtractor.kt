package com.unchunks.echomark.data.extract.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.documenttext.ImageLabelerEngine
import com.unchunks.components.documenttext.ImageLabelsJa
import com.unchunks.components.documenttext.ImageSupport
import com.unchunks.components.documenttext.TextRecognizerEngine
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
 * ただし長いスクリーンショットは、縮めると文字が読めなくなるので、元の大きさのまま帯に分けて OCR する([planBands])。
 * スクリーンショットらしい画像には、本文の先頭に種類([SCREENSHOT_LABEL])を書き添える(検索や AI の分類の手がかり)。
 */
class ImageContentExtractor @Inject constructor(
    private val textRecognizer: TextRecognizerEngine,
    private val imageLabeler: ImageLabelerEngine,
    private val dispatcherProvider: DispatcherProvider
) {

    suspend fun extract(file: File): ExtractedContent? {
        val exif = withContext(dispatcherProvider.io) { readExif(file) }
        val size = withContext(dispatcherProvider.io) { readSize(file) }
        val bitmap = withContext(dispatcherProvider.io) { ImageSupport.decodeSampled(file) } ?: run {
            Timber.w("ImageContentExtractor: 画像を読み込めません")
            return null
        }
        try {
            val rotation = exif?.rotationDegrees ?: 0
            val ocrText = runOrDefault("OCR", "") { recognize(file, bitmap, rotation, size) }
            val labels = runOrDefault("ラベル", emptyList()) { imageLabeler.label(bitmap, rotation) }
            val capturedAt = ImageSupport.formatExifDateTime(
                exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif?.getAttribute(ExifInterface.TAG_DATETIME)
            )
            val body = ImageSupport.composeImageText(ocrText, ImageLabelsJa.translate(labels), capturedAt)
            val (width, height) = size ?: (bitmap.width to bitmap.height)
            val screenshot = body.isNotEmpty() && looksLikeScreenshot(file.extension, width, height, hasCameraInfo(exif))
            val text = if (screenshot) "$SCREENSHOT_LABEL\n\n$body" else body
            return ExtractedContent.of(text, ExtractionSource.IMAGE, engine = "mlkit")
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 文字を読み取る。細長い画像は帯に分けて元の大きさで読み、読めなかった(帯を切り出せない)ときは縮めた画像全体を読む。
     * 向きを直す必要がある画像(EXIF の回転あり)は、帯に分けずに全体を読む。
     */
    private suspend fun recognize(file: File, bitmap: Bitmap, rotation: Int, size: Pair<Int, Int>?): String {
        val bands = if (rotation == 0 && size != null) planBands(size.first, size.second) else emptyList()
        if (bands.isNotEmpty()) {
            recognizeBands(file, bands, size!!.first, size.second)?.let { return it }
        }
        return textRecognizer.recognize(bitmap, rotation)
    }

    /** [bands] を元の大きさで切り出して OCR し、つなげる。切り出せなければ null */
    private suspend fun recognizeBands(file: File, bands: List<Band>, width: Int, height: Int): String? {
        val decoder = withContext(dispatcherProvider.io) {
            try {
                BitmapRegionDecoder.newInstance(file.path)
            } catch (e: Exception) {
                // 領域の読み込みに対応しない形式・壊れたファイル
                Timber.w(e, "ImageContentExtractor: 帯に分けて読めません")
                null
            }
        } ?: return null
        try {
            val horizontal = width > height
            val texts = mutableListOf<String>()
            for (band in bands) {
                val region = if (horizontal) Rect(band.start, 0, band.end, height) else Rect(0, band.start, width, band.end)
                val piece = withContext(dispatcherProvider.io) {
                    decoder.decodeRegion(region, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                } ?: return null
                try {
                    texts += textRecognizer.recognize(piece, 0)
                } finally {
                    piece.recycle()
                }
            }
            return mergeBandTexts(texts)
        } finally {
            decoder.recycle()
        }
    }

    private fun readSize(file: File): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
    }

    private fun readExif(file: File): ExifInterface? = try {
        ExifInterface(file)
    } catch (e: Exception) {
        // EXIF を持たない形式(GIF など)・壊れたファイル
        null
    }

    /** カメラで撮った写真か(機種・撮影日時の EXIF がある)。スクリーンショットにはこれらが無い */
    private fun hasCameraInfo(exif: ExifInterface?): Boolean =
        exif != null && listOf(ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME_ORIGINAL)
            .any { !exif.getAttribute(it).isNullOrBlank() }

    /** 片方(OCR・ラベル)が失敗しても、もう片方の結果は使う。 */
    private suspend fun <T> runOrDefault(name: String, default: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "ImageContentExtractor: %s に失敗", name)
        default
    } catch (e: LinkageError) {
        // ML Kit のクラスの初期化・ネイティブライブラリの読み込みに失敗した。その機能は使えないものとして扱う
        Timber.e(e, "ImageContentExtractor: %s のライブラリを初期化できません", name)
        default
    }
}
