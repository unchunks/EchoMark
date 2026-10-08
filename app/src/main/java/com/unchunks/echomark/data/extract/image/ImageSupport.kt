package com.unchunks.echomark.data.extract.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** 画像の読み込み(メモリ対策の縮小)と EXIF の値の整形。 */
object ImageSupport {

    /**
     * OCR に使う画像の長辺の上限(px)。ML Kit は文字1つが 16px 以上あれば読めるため、
     * スクリーンショットや書類の写真でもこの大きさで足りる。これより大きい画像は縮小してメモリを抑える。
     */
    const val MAX_SIDE = 2048

    /**
     * 長辺が [maxSide] の2倍未満になるまで 1/2 ずつ縮める倍率(BitmapFactory の inSampleSize。2 のべき乗)。
     */
    fun inSampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        val longSide = max(width, height)
        while (longSide / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    /** 縮小後の大きさ(長辺が [maxSide] 以下。縦横比を保つ)。収まっていればそのまま。 */
    fun fitWithin(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val longSide = max(width, height)
        if (longSide <= maxSide) return width to height
        val ratio = maxSide.toDouble() / longSide
        return max(1, (width * ratio).roundToInt()) to max(1, (height * ratio).roundToInt())
    }

    /** 画像ファイルを長辺 [maxSide] 以下に縮めて読み込む。読めなければ null。 */
    fun decodeSampled(file: File, maxSide: Int = MAX_SIDE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = inSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
        return scaleWithin(decoded, maxSide)
    }

    /** [bitmap] を長辺 [maxSide] 以下に縮める(縮めたら元の Bitmap は解放する)。 */
    fun scaleWithin(bitmap: Bitmap, maxSide: Int = MAX_SIDE): Bitmap {
        val (w, h) = fitWithin(bitmap.width, bitmap.height, maxSide)
        if (w == bitmap.width && h == bitmap.height) return bitmap
        val scaled = bitmap.scale(w, h)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /**
     * EXIF の日時("2024:05:03 14:20:11")を本文用に整える("2024年5月3日 14:20")。
     * 空・未設定("0000:00:00 00:00:00")・形式違いは null。
     */
    fun formatExifDateTime(value: String?): String? {
        val text = value?.trim()?.takeIf { it.length >= 19 }?.substring(0, 19) ?: return null
        return try {
            val dateTime = LocalDateTime.parse(text, EXIF_DATE)
            "${dateTime.year}年${dateTime.monthValue}月${dateTime.dayOfMonth}日 " +
                "%d:%02d".format(Locale.ROOT, dateTime.hour, dateTime.minute)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * 画像の本文を組み立てる。読み取った文字・写っているもの・撮影日時のうち、あるものだけを入れる。
     * どれも無ければ空文字。
     */
    fun composeImageText(ocrText: String, labels: List<String>, capturedAt: String?): String =
        buildList {
            if (ocrText.isNotBlank()) add("画像内の文字:\n${ocrText.trim()}")
            if (labels.isNotEmpty()) add("写っているもの: ${labels.joinToString(", ")}")
            if (capturedAt != null && (ocrText.isNotBlank() || labels.isNotEmpty())) add("撮影日時: $capturedAt")
        }.joinToString("\n\n")
}
