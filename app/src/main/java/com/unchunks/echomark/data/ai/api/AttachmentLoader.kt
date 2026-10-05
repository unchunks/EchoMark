package com.unchunks.echomark.data.ai.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.model.AnalysisAttachment
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** 保存したファイルを、クラウド API に渡せる形([ApiAttachment])に読み込む。 */
interface AttachmentLoader {
    /**
     * [kind]([AttachmentPolicy.plan] で決めたもの)として読み込む。
     * 読めない・画像を復元できない・PDF のページ数が多すぎる(暗号化されている)ときは null(テキストだけで要約する)。
     */
    suspend fun load(attachment: AnalysisAttachment, kind: AttachmentKind): ApiAttachment?
}

/**
 * 端末のファイルを読み込む [AttachmentLoader]。
 * 画像は長辺 [AttachmentPolicy.MAX_IMAGE_LONG_EDGE] px 以下に縮小し、向き(EXIF)を直して JPEG にする
 * (透過部分は白にする)。PDF・音声・動画はそのまま base64 にする。
 */
@Singleton
class AndroidAttachmentLoader @Inject constructor(
    private val dispatcherProvider: DispatcherProvider
) : AttachmentLoader {

    override suspend fun load(attachment: AnalysisAttachment, kind: AttachmentKind): ApiAttachment? =
        withContext(dispatcherProvider.io) {
            val file = File(attachment.path)
            if (!file.isFile) return@withContext null
            try {
                when (kind) {
                    AttachmentKind.IMAGE -> encodeImage(file)?.let { encode(kind, "image/jpeg", it, file) }
                    AttachmentKind.PDF -> {
                        val pages = pdfPageCount(file)
                        if (pages == null || pages > AttachmentPolicy.MAX_PDF_PAGES) {
                            Timber.i("PDF を添付しない(ページ数=%s)", pages)
                            null
                        } else {
                            encode(kind, "application/pdf", file.readBytes(), file)
                        }
                    }
                    AttachmentKind.AUDIO, AttachmentKind.VIDEO -> {
                        val mimeType = AttachmentPolicy.apiMimeType(kind, attachment.mimeType)
                        if (mimeType == null || file.length() > AttachmentPolicy.MAX_FILE_BYTES) {
                            null
                        } else {
                            encode(kind, mimeType, file.readBytes(), file)
                        }
                    }
                }
            } catch (e: IOException) {
                Timber.w(e, "添付のファイルを読めない")
                null
            } catch (e: SecurityException) {
                // パスワード付きの PDF など
                Timber.w(e, "添付のファイルを開けない")
                null
            }
        }

    private fun encode(kind: AttachmentKind, mimeType: String, bytes: ByteArray, file: File) =
        ApiAttachment(kind, mimeType, Base64.getEncoder().encodeToString(bytes), file.name)

    /** 縮小・向きの補正をした JPEG。画像として読めなければ null。 */
    private fun encodeImage(file: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = AttachmentPolicy.inSampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
        val oriented = rotateByExif(decoded, file)
        val (width, height) = AttachmentPolicy.scaledSize(oriented.width, oriented.height)
        // 縮小と白い下地への描画を一度に行う(JPEG は透過を持てず、そのままだと透過部分が黒になる)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(Color.WHITE)
            val matrix = Matrix().apply { setScale(width / oriented.width.toFloat(), height / oriented.height.toFloat()) }
            drawBitmap(oriented, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()
        return ByteArrayOutputStream().use { stream ->
            output.compress(Bitmap.CompressFormat.JPEG, AttachmentPolicy.JPEG_QUALITY, stream)
            output.recycle()
            stream.toByteArray()
        }
    }

    /** 写真の向き(EXIF の回転・反転)を画素に反映する。情報が無ければそのまま返す。 */
    private fun rotateByExif(bitmap: Bitmap, file: File): Bitmap {
        val orientation = try {
            ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> matrix.apply { postRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> matrix.apply { postRotate(270f); postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** PDF のページ数。開けない(壊れている・パスワード付き)なら null。 */
    private fun pdfPageCount(file: File): Int? = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { it.pageCount }
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }
}
