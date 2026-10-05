package com.unchunks.echomark.ui.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.unchunks.echomark.data.attachment.attachmentFileName
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * 添付ファイルのプレビュー(PDF のページ・動画のコマ・再生時間)を作る。
 * 画面からは [LocalAttachmentPreviewer] 経由で使い、スクリーンショットテストでは仮の実装に差し替える。
 * 作れなかったとき(壊れたファイル・未対応の形式)は null を返し、画面は種類のアイコンを見せる。
 */
interface AttachmentPreviewer {
    /** PDF のページ数 */
    suspend fun pdfPageCount(file: File): Int?

    /** PDF の [pageIndex] ページ目(0 始まり)を幅 [widthPx] で描いた画像 */
    suspend fun pdfPage(file: File, pageIndex: Int, widthPx: Int): ImageBitmap?

    /** 音声・動画の長さ(ミリ秒) */
    suspend fun mediaDurationMillis(file: File): Long?

    /** 動画の1秒目あたりのコマ(幅 [widthPx] 程度) */
    suspend fun videoFrame(file: File, widthPx: Int): ImageBitmap?
}

/** プレビューを作る実装。null なら端末の機能を使う([AndroidAttachmentPreviewer]) */
val LocalAttachmentPreviewer = staticCompositionLocalOf<AttachmentPreviewer?> { null }

@Composable
fun rememberAttachmentPreviewer(): AttachmentPreviewer {
    val provided = LocalAttachmentPreviewer.current
    val context = LocalContext.current
    return provided ?: remember { AndroidAttachmentPreviewer.get(context) }
}

/** ブックマークの添付ファイル。無い(ファイルでない・端末に無い)なら null */
@Composable
fun rememberAttachmentFile(bookmark: Bookmark): File? {
    val context = LocalContext.current
    val path = bookmark.filePath
    return remember(path) {
        attachmentFileName(path)?.let { File(File(context.filesDir, "attachments"), it) }?.takeIf { it.isFile }
    }
}

@Composable
fun rememberPdfPage(file: File, pageIndex: Int, widthPx: Int): State<ImageBitmap?> {
    val previewer = rememberAttachmentPreviewer()
    return produceState<ImageBitmap?>(null, file, pageIndex, widthPx) { value = previewer.pdfPage(file, pageIndex, widthPx) }
}

@Composable
fun rememberPdfPageCount(file: File): State<Int?> {
    val previewer = rememberAttachmentPreviewer()
    return produceState<Int?>(null, file) { value = previewer.pdfPageCount(file) }
}

@Composable
fun rememberMediaDuration(file: File): State<Long?> {
    val previewer = rememberAttachmentPreviewer()
    return produceState<Long?>(null, file) { value = previewer.mediaDurationMillis(file) }
}

@Composable
fun rememberVideoFrame(file: File, widthPx: Int): State<ImageBitmap?> {
    val previewer = rememberAttachmentPreviewer()
    return produceState<ImageBitmap?>(null, file, widthPx) { value = previewer.videoFrame(file, widthPx) }
}

/**
 * 端末の機能(PdfRenderer・MediaMetadataRetriever)でプレビューを作る。
 * 作った画像はメモリ(LRU)と cacheDir に置き、一覧のスクロールで何度も作らない。
 */
class AndroidAttachmentPreviewer private constructor(context: Context) : AttachmentPreviewer {

    private val cacheDir = File(context.cacheDir, "attachment_previews")

    /** PdfRenderer は同時に1ページしか開けないため、描画を1つずつにする */
    private val pdfMutex = Mutex()

    private val bitmaps = object : LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val durations = LruCache<String, Long>(DURATION_CACHE_ENTRIES)
    private val pageCounts = LruCache<String, Int>(DURATION_CACHE_ENTRIES)

    override suspend fun pdfPageCount(file: File): Int? {
        pageCounts.get(key(file))?.let { return it }
        return guarded("PDF のページ数") {
            pdfMutex.withLock {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { it.pageCount }
                }
            }
        }?.also { pageCounts.put(key(file), it) }
    }

    override suspend fun pdfPage(file: File, pageIndex: Int, widthPx: Int): ImageBitmap? =
        cachedBitmap("${key(file)}_p${pageIndex}_w$widthPx") {
            pdfMutex.withLock {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        if (pageIndex !in 0 until renderer.pageCount) return@withLock null
                        renderer.openPage(pageIndex).use { page ->
                            val width = widthPx.coerceIn(1, MAX_RENDER_WIDTH)
                            val height = (width.toFloat() * page.height / page.width.coerceAtLeast(1)).toInt()
                                .coerceIn(1, MAX_RENDER_WIDTH * 2)
                            // PDF は背景が透明なことがあるため、紙の白で塗ってから描く
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                                eraseColor(Color.WHITE)
                                page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }
        }

    override suspend fun mediaDurationMillis(file: File): Long? {
        durations.get(key(file))?.let { return it }
        return guarded("再生時間") {
            withRetriever(file) { it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }
        }?.also { durations.put(key(file), it) }
    }

    override suspend fun videoFrame(file: File, widthPx: Int): ImageBitmap? =
        cachedBitmap("${key(file)}_frame_w$widthPx") {
            withRetriever(file) { retriever ->
                val width = widthPx.coerceIn(1, MAX_RENDER_WIDTH)
                retriever.getScaledFrameAtTime(
                    FRAME_TIME_US,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    width,
                    width
                )
            }
        }

    /** メモリ → ディスクの順に探し、無ければ [create] で作って両方に置く */
    private suspend fun cachedBitmap(cacheKey: String, create: suspend () -> Bitmap?): ImageBitmap? {
        bitmaps.get(cacheKey)?.let { return it.asImageBitmap() }
        val bitmap = guarded("プレビュー") {
            val cached = File(cacheDir, "$cacheKey.jpg")
            if (cached.isFile) {
                BitmapFactory.decodeFile(cached.path)
            } else {
                create()?.also { created ->
                    cacheDir.mkdirs()
                    FileOutputStream(cached).use { created.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                }
            }
        } ?: return null
        bitmaps.put(cacheKey, bitmap)
        return bitmap.asImageBitmap()
    }

    private inline fun <T> withRetriever(file: File, block: (MediaMetadataRetriever) -> T): T {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            return block(retriever)
        } finally {
            retriever.release()
        }
    }

    /** I/O スレッドで実行し、失敗したら null(壊れたファイルなどで画面を落とさない) */
    private suspend fun <T> guarded(what: String, block: suspend () -> T?): T? =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "%s を作れない", what)
                null
            }
        }

    /** ファイルが変わったら別のものとして扱う(同じ名前で中身が違うことは無いが念のため) */
    private fun key(file: File): String = "${file.nameWithoutExtension}_${file.length()}"

    companion object {
        private const val MEMORY_CACHE_BYTES = 16 * 1024 * 1024
        private const val DURATION_CACHE_ENTRIES = 200
        private const val MAX_RENDER_WIDTH = 2048
        private const val FRAME_TIME_US = 1_000_000L
        private const val JPEG_QUALITY = 85

        @Volatile
        private var instance: AndroidAttachmentPreviewer? = null

        fun get(context: Context): AndroidAttachmentPreviewer =
            instance ?: synchronized(this) {
                instance ?: AndroidAttachmentPreviewer(context.applicationContext).also { instance = it }
            }
    }
}
