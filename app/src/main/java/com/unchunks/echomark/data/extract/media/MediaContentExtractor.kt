package com.unchunks.echomark.data.extract.media

import android.content.Context
import android.media.MediaMetadataRetriever
import com.unchunks.echomark.data.extract.image.ImageSupport
import com.unchunks.echomark.data.extract.transcribe.Transcriber
import com.unchunks.echomark.data.extract.transcribe.TranscriptionProviderSelector
import com.unchunks.echomark.data.extract.vision.TextRecognizerEngine
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.extract.ExtractedContent
import com.unchunks.echomark.domain.extract.ExtractionSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * 音声・動画ファイルの文字起こし。
 * 1. 音声トラックを 16kHz モノラル PCM にデコードして一時ファイルに書く(先頭 [MAX_DURATION_MS] まで)
 * 2. [CHUNK_MILLIS] ごとに(話の途中で切らないよう静かなところで)区切り、クラウド → 端末内の順に文字起こしする
 * 3. 動画で音声が無い・話していないときは、代表フレーム(中ほど)の文字を OCR する
 */
class MediaContentExtractor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val decoder: AudioDecoder,
    private val providerSelector: TranscriptionProviderSelector,
    private val textRecognizer: TextRecognizerEngine,
    private val dispatcherProvider: DispatcherProvider
) {

    suspend fun extract(file: File, isVideo: Boolean): ExtractedContent? {
        transcribe(file)?.let { return it }
        return if (isVideo) ocrFrame(file) else null
    }

    suspend fun durationMs(file: File): Long? = decoder.durationMs(file)

    private suspend fun transcribe(file: File): ExtractedContent? {
        val pcm = withContext(dispatcherProvider.io) { File.createTempFile("transcribe", ".pcm", context.cacheDir) }
        try {
            val decoded = decoder.decode(file, pcm, MAX_DURATION_MS) ?: return null
            if (decoded.pcmBytes <= 0) return null
            val chunks = withContext(dispatcherProvider.io) { PcmChunker.split(pcm, decoded.pcmBytes, CHUNK_MILLIS) }
            val transcript = Transcriber.transcribe(chunks, providerSelector.providers()) ?: return null
            return ExtractedContent.of(
                text = transcript.text,
                source = ExtractionSource.TRANSCRIPT,
                durationMs = decoded.sourceDurationMs ?: PcmFormat.bytesToMillis(decoded.pcmBytes),
                processedDurationMs = transcript.transcribedMillis,
                truncated = decoded.truncated || transcript.incomplete,
                engine = transcript.engines.joinToString("+")
            )
        } finally {
            withContext(dispatcherProvider.io) { pcm.delete() }
        }
    }

    /** 動画の中ほどのフレームを OCR する。 */
    private suspend fun ocrFrame(file: File): ExtractedContent? {
        val frame = withContext(dispatcherProvider.io) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val timeUs = (durationMs ?: 0L) * 1000 / 2
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    ImageSupport.MAX_SIDE,
                    ImageSupport.MAX_SIDE
                )
            } catch (e: Exception) {
                Timber.w(e, "MediaContentExtractor: フレームを取り出せません")
                null
            } finally {
                retriever.release()
            }
        } ?: return null
        return try {
            val text = textRecognizer.recognize(frame)
            ExtractedContent.of(text, ExtractionSource.VIDEO_FRAME, engine = "mlkit")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "MediaContentExtractor: フレームの OCR に失敗")
            null
        } finally {
            frame.recycle()
        }
    }

    companion object {
        /** 文字起こしする長さの上限(先頭から) */
        const val MAX_DURATION_MS = 60L * 60 * 1000

        /**
         * 1回の文字起こしに渡す長さ。クラウドの1リクエストの上限(Gemini は Base64 込みで 20MB = 約 7 分、
         * OpenAI は 25MB = 約 13 分)に収まり、端末内の認識が途中で止まっても失う量が少ない長さにする。
         */
        const val CHUNK_MILLIS = 5L * 60 * 1000
    }
}
