package com.unchunks.echomark.data.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.speech.PcmChunk
import com.unchunks.components.speech.PcmFormat
import com.unchunks.components.speech.PcmMath
import com.unchunks.components.speech.Transcriber
import com.unchunks.echomark.data.extract.transcribe.TranscriptionProviderSelector
import com.unchunks.echomark.domain.chat.SpeechEndDetector
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.ChatVoiceInput
import com.unchunks.echomark.domain.repository.VoiceInputResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * マイクの音声を 16kHz モノラルの PCM で録音し、ファイルの文字起こしと同じ仕組み([Transcriber])で文字にする。
 * 音声は一時ファイルに書き、文字起こしが終わったら消す。
 * クラウドへ送るのは、チャットにクラウド API を選んでいて、ファイルの送信もオンのときだけ([TranscriptionProviderSelector])。
 */
class MicrophoneChatVoiceInput @Inject constructor(
    @ApplicationContext private val context: Context,
    private val providerSelector: TranscriptionProviderSelector,
    private val dispatcherProvider: DispatcherProvider
) : ChatVoiceInput {

    override suspend fun listen(finish: Deferred<Unit>, onTranscribing: () -> Unit): VoiceInputResult =
        withContext(dispatcherProvider.io) {
            val pcm = File.createTempFile("voice", ".pcm", context.cacheDir)
            try {
                val recording = record(pcm, finish) ?: return@withContext VoiceInputResult.MicrophoneUnavailable
                if (!recording.heardSpeech || pcm.length() == 0L) return@withContext VoiceInputResult.NoSpeech
                onTranscribing()
                transcribe(pcm)
            } catch (e: IOException) {
                Timber.w(e, "音声を一時ファイルに書けなかった")
                VoiceInputResult.MicrophoneUnavailable
            } finally {
                pcm.delete()
            }
        }

    private suspend fun transcribe(pcm: File): VoiceInputResult {
        val chunk = PcmChunk(pcm, 0, pcm.length())
        val providers = providerSelector.providers(AiTask.CHAT)
        val transcript = Transcriber.transcribe(listOf(chunk), providers)
            ?: return VoiceInputResult.TranscriptionUnavailable
        return transcript.text.trim().takeIf { it.isNotEmpty() }?.let(VoiceInputResult::Text) ?: VoiceInputResult.NoSpeech
    }

    /** [file] に録音する。マイクを使えなければ null。 */
    // 権限は画面で確かめてから呼ぶ。取り消されていたら SecurityException になるので、ここで受ける
    @SuppressLint("MissingPermission")
    private suspend fun record(file: File, finish: Deferred<Unit>): SpeechEndDetector? {
        val minBufferBytes = AudioRecord.getMinBufferSize(PcmFormat.SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
        if (minBufferBytes <= 0) return null
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                PcmFormat.SAMPLE_RATE,
                CHANNEL_CONFIG,
                ENCODING,
                maxOf(minBufferBytes, PcmFormat.millisToBytes(BUFFER_MILLIS).toInt())
            )
        } catch (e: SecurityException) {
            Timber.w(e, "マイクの権限が無い")
            return null
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "マイクの設定が受け付けられなかった")
            return null
        }
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return null
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) return null
            val detector = SpeechEndDetector()
            val samples = ShortArray((PcmFormat.BYTES_PER_SECOND * FRAME_MILLIS / 1000 / PcmFormat.BYTES_PER_SAMPLE).toInt())
            file.outputStream().buffered().use { out ->
                while (!finish.isCompleted) {
                    coroutineContext.ensureActive()
                    val read = recorder.read(samples, 0, samples.size)
                    if (read < 0) {
                        Timber.w("マイクから読めなかった: %d", read)
                        return null
                    }
                    if (read == 0) continue
                    out.write(PcmMath.toLittleEndianBytes(samples, read))
                    val millis = PcmFormat.bytesToMillis(read.toLong() * PcmFormat.BYTES_PER_SAMPLE)
                    if (detector.accept(PcmMath.rms(samples, 0, read), millis)) break
                }
            }
            return detector
        } finally {
            if (recorder.state == AudioRecord.STATE_INITIALIZED) runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private companion object {
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 録音の内部のバッファの長さ */
        const val BUFFER_MILLIS = 1_000L

        /** 音の大きさを測る単位 */
        const val FRAME_MILLIS = 100
    }
}
