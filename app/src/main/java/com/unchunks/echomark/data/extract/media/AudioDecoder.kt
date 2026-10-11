package com.unchunks.echomark.data.extract.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.unchunks.echomark.di.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import javax.inject.Inject

/**
 * 音声・動画ファイルの音声トラックをデコードして、文字起こし用の PCM([PcmFormat]: 16kHz モノラル 16bit)をファイルに書き出す。
 * 端末の MediaExtractor / MediaCodec を使う(端末が再生できる形式なら読める。MP3・AAC・Opus・FLAC・AMR など)。
 */
class AudioDecoder @Inject constructor(
    private val dispatcherProvider: DispatcherProvider
) {

    /**
     * @property pcmBytes 書き出した PCM のバイト数
     * @property sourceDurationMs 元のファイルの長さ(分からなければ null)
     * @property truncated [decode] の上限の長さで打ち切った
     */
    data class DecodedAudio(val pcmBytes: Long, val sourceDurationMs: Long?, val truncated: Boolean)

    /** ファイルの長さ(ミリ秒)。分からなければ null。 */
    suspend fun durationMs(file: File): Long? = withContext(dispatcherProvider.io) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * [file] の音声トラックを先頭から [maxDurationMs] まで [output] に書き出す。
     * 音声トラックが無い・デコードできない形式なら null。
     */
    suspend fun decode(file: File, output: File, maxDurationMs: Long): DecodedAudio? =
        withContext(dispatcherProvider.io) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(file.path)
                val track = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return@withContext null
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null
                val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION) / 1000
                } else {
                    null
                }
                val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
                decoder.configure(format, null, null, 0)
                decoder.start()
                val (bytes, truncated) = BufferedOutputStream(FileOutputStream(output)).use { out ->
                    pump(extractor, decoder, format, out, maxDurationMs)
                }
                DecodedAudio(bytes, durationMs, truncated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "AudioDecoder: デコードに失敗")
                null
            } finally {
                codec?.let {
                    runCatching { it.stop() }
                    it.release()
                }
                extractor.release()
            }
        }

    /** デコーダーに読み込んだデータを渡し、出力を 16kHz モノラルにして書き出す。(書いたバイト数, 打ち切ったか) を返す。 */
    private suspend fun pump(
        extractor: MediaExtractor,
        codec: MediaCodec,
        inputFormat: MediaFormat,
        out: BufferedOutputStream,
        maxDurationMs: Long
    ): Pair<Long, Boolean> {
        val maxBytes = PcmFormat.millisToBytes(maxDurationMs)
        val maxInputUs = maxDurationMs * 1000
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var resampler = LinearResampler(inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), PcmFormat.SAMPLE_RATE)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var written = 0L
        var truncated = false
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex) ?: error("input buffer")
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0 || extractor.sampleTime > maxInputUs) {
                        if (size >= 0) truncated = true
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val outputFormat = codec.outputFormat
                channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
                resampler = LinearResampler(outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), PcmFormat.SAMPLE_RATE)
            } else if (outIndex >= 0) {
                val buffer = codec.getOutputBuffer(outIndex)
                if (buffer != null && info.size > 0 && written < maxBytes) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val slice = buffer.slice().order(ByteOrder.nativeOrder())
                    val interleaved = when (encoding) {
                        AudioFormat.ENCODING_PCM_FLOAT -> {
                            val floats = FloatArray(slice.remaining() / 4)
                            slice.asFloatBuffer().get(floats)
                            PcmMath.floatToPcm16(floats)
                        }
                        else -> ShortArray(slice.remaining() / 2).also { slice.asShortBuffer().get(it) }
                    }
                    val mono = PcmMath.downmixToMono(interleaved, channels = channels.coerceAtLeast(1))
                    val resampled = resampler.process(mono)
                    val bytes = PcmMath.toLittleEndianBytes(resampled)
                    val room = (maxBytes - written).coerceAtMost(bytes.size.toLong()).toInt()
                    out.write(bytes, 0, room)
                    written += room
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                if (written >= maxBytes) {
                    truncated = true
                    break
                }
            }
        }
        return written to truncated
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
