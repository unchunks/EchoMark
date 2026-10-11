package com.unchunks.echomark.data.extract.transcribe

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.unchunks.echomark.data.extract.media.PcmChunk
import com.unchunks.echomark.data.extract.media.PcmFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/**
 * 端末内の音声認識(SpeechRecognizer の on-device 認識)で文字起こしする。音声は端末の外に出ない。
 *
 * - Android 13(API 33)以降で、音声をマイクではなくファイル(パイプ)から渡せる([RecognizerIntent.EXTRA_AUDIO_SOURCE])。
 *   形式は 16kHz モノラル 16bit PCM(既定値)。区切り(セグメント)ごとに結果を受け取り、パイプを閉じると終わる
 * - 端末に on-device 認識が無い・Android 12 以前・言語のモデルが無いときは null(呼び出し側は文字起こしを諦める)。
 *   言語のモデルが未導入なら、ダウンロードを依頼しておく(次回以降に使える)
 * - 音声をファイルから渡すときはマイクの権限は要らない(RecognitionService は EXTRA_AUDIO_SOURCE があれば
 *   事前の権限確認を省く)。ただし認識サービスの実装によっては RECORD_AUDIO を求めることがあり、その場合は null になる
 */
@Singleton
class OnDeviceSpeechTranscriber @Inject constructor(
    @param:ApplicationContext private val context: Context
) : TranscriptionProvider {

    override val name: String = "on-device"

    /** この端末で使えるか(Android 13 以降で、on-device の認識サービスがある)。 */
    fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override suspend fun transcribe(chunk: PcmChunk): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isSupported()) return null
        return transcribeFromPipe(chunk)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun transcribeFromPipe(chunk: PcmChunk): String? {
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        // 認識サービスが読み取るより速く書くとパイプが詰まって書き込みが止まるため、別スレッドで流す。
        // 認識が途中で終わったら読み取り側を閉じ、書き込みを失敗させて抜けさせる
        val writer = thread(name = "on-device-transcriber-writer", isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                    out.write(chunk.readBytes())
                }
            } catch (e: IOException) {
                // 認識が先に終わった(読み取り側が閉じられた)
            }
        }
        return try {
            withTimeoutOrNull(timeoutMillis(chunk)) {
                withContext(Dispatchers.Main) { recognize(readSide, languageTag()) }
            }.also { if (it == null) Timber.w("OnDeviceSpeechTranscriber: 時間内に終わりませんでした") }
        } finally {
            runCatching { readSide.close() }
            if (writer.isAlive) writer.interrupt()
        }
    }

    /** メインスレッドで呼ぶ(SpeechRecognizer の決まり)。 */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun recognize(audio: ParcelFileDescriptor, language: String): String? =
        suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val destroyed = AtomicBoolean(false)
            val mainHandler = Handler(Looper.getMainLooper())
            fun destroy() {
                if (destroyed.compareAndSet(false, true)) {
                    mainHandler.post {
                        runCatching { recognizer.cancel() }
                        recognizer.destroy()
                    }
                }
            }
            val segments = mutableListOf<String>()
            fun finish(result: String?) {
                if (cont.isActive) cont.resume(result)
                destroy()
            }
            val intent = recognizerIntent(audio, language)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onSegmentResults(segmentResults: Bundle) {
                    bestResult(segmentResults)?.let { segments += it }
                }

                override fun onEndOfSegmentedSession() = finish(segments.joinToString("\n"))

                override fun onResults(results: Bundle) {
                    // セグメント単位に対応していない認識サービスは、最後にまとめて返す
                    bestResult(results)?.let { segments += it }
                    finish(segments.joinToString("\n"))
                }

                override fun onError(error: Int) {
                    Timber.w("OnDeviceSpeechTranscriber: 認識エラー %d", error)
                    when (error) {
                        // 話し声が無かった
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            finish(segments.joinToString("\n"))
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                            // 言語のモデルが未導入。ダウンロードを依頼して、今回は諦める
                            runCatching { recognizer.triggerModelDownload(intent) }
                            finish(null)
                        }
                        else -> finish(segments.takeIf { it.isNotEmpty() }?.joinToString("\n"))
                    }
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            cont.invokeOnCancellation { destroy() }
            try {
                recognizer.startListening(intent)
            } catch (e: Exception) {
                Timber.w(e, "OnDeviceSpeechTranscriber: 認識を始められません")
                finish(null)
            }
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun recognizerIntent(audio: ParcelFileDescriptor, language: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, PcmFormat.CHANNELS)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, PcmFormat.SAMPLE_RATE)
            // 無音で止めず、パイプが閉じられるまで区切りごとに結果を返させる
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }

    private fun bestResult(bundle: Bundle): String? =
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** 端末の言語(例: "ja-JP")。保存する音声は端末の言語で話されていることが多いため。 */
    private fun languageTag(): String = Locale.getDefault().toLanguageTag()

    /** 実時間で処理されても終わる長さ + 余裕。 */
    private fun timeoutMillis(chunk: PcmChunk): Long = chunk.durationMillis * 2 + 60_000L
}
