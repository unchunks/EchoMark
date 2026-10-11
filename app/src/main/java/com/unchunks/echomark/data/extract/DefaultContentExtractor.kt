package com.unchunks.echomark.data.extract

import com.unchunks.echomark.data.extract.image.ImageContentExtractor
import com.unchunks.echomark.data.extract.media.MediaContentExtractor
import com.unchunks.echomark.data.extract.pdf.PdfContentExtractor
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.extract.ContentExtractor
import com.unchunks.echomark.domain.extract.ExtractedContent
import com.unchunks.echomark.domain.extract.ExtractionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/** 種類ごとの取り出し方を束ねる [ContentExtractor]。 */
class DefaultContentExtractor @Inject constructor(
    private val imageExtractor: ImageContentExtractor,
    private val pdfExtractor: PdfContentExtractor,
    private val mediaExtractor: MediaContentExtractor,
    private val dispatcherProvider: DispatcherProvider
) : ContentExtractor {

    override suspend fun extract(file: File, mimeType: String?, kind: ContentKind): ExtractedContent? {
        if (!file.isFile) return null
        val started = System.currentTimeMillis()
        return try {
            when (kind) {
                ContentKind.IMAGE -> imageExtractor.extract(file)
                ContentKind.DOCUMENT -> if (isPdf(file, mimeType)) pdfExtractor.extract(file) else null
                ContentKind.AUDIO -> mediaExtractor.extract(file, isVideo = false)
                ContentKind.VIDEO -> mediaExtractor.extract(file, isVideo = true)
                ContentKind.MEMO -> if (isText(file, mimeType)) readTextFile(file) else null
                ContentKind.WEB_PAGE -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "DefaultContentExtractor: 取り出しに失敗 kind=%s", kind)
            null
        } catch (e: OutOfMemoryError) {
            Timber.w(e, "DefaultContentExtractor: メモリ不足 kind=%s", kind)
            null
        }.also { result ->
            Timber.d(
                "DefaultContentExtractor: kind=%s engine=%s chars=%d %dms",
                kind, result?.engine, result?.text?.length ?: 0, System.currentTimeMillis() - started
            )
        }
    }

    override suspend fun isLongRunning(file: File, mimeType: String?, kind: ContentKind): Boolean = when (kind) {
        ContentKind.AUDIO, ContentKind.VIDEO -> {
            val duration = mediaExtractor.durationMs(file)
            // 長さが分からなければ、念のため長いものとして扱う
            duration == null || duration > LONG_MEDIA_MILLIS
        }
        else -> false
    }

    private suspend fun readTextFile(file: File): ExtractedContent? = withContext(dispatcherProvider.io) {
        val (text, truncated) = TextFileReader.read(file)
        ExtractedContent.of(text, ExtractionSource.TEXT_FILE, truncated = truncated, engine = "text")
    }

    private fun isPdf(file: File, mimeType: String?): Boolean =
        mimeType?.substringBefore(';')?.trim()?.lowercase() == "application/pdf" ||
            file.extension.lowercase() == "pdf" ||
            mimeType == null

    private fun isText(file: File, mimeType: String?): Boolean =
        mimeType?.substringBefore(';')?.trim()?.lowercase()?.startsWith("text/") == true ||
            file.extension.lowercase() in TEXT_EXTENSIONS

    private companion object {
        /** これより長い音声・動画は、フォアグラウンドで処理する */
        const val LONG_MEDIA_MILLIS = 60_000L

        val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "csv", "tsv", "log", "json")
    }
}
