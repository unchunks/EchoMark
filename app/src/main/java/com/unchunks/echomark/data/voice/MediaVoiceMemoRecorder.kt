package com.unchunks.echomark.data.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.unchunks.echomark.domain.capture.CaptureTarget
import com.unchunks.echomark.domain.capture.VoiceMemoRecorder
import com.unchunks.echomark.domain.capture.VoiceMemoStop
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject

/**
 * 音声メモを AAC(.m4a)で録音する。同じ長さの PCM(WAV)の約 1/4 の大きさで残せ、保存した後の文字起こし
 * ([com.unchunks.echomark.data.extract.media.MediaContentExtractor])がそのまま読める形式にする。
 * 文字にするのは保存後の取り出しの段で、チャットの音声入力([MicrophoneChatVoiceInput])と同じ
 * [com.unchunks.components.speech.Transcriber] が行う。録音と書き出しは端末の中だけで、通信しない。
 */
class MediaVoiceMemoRecorder @Inject constructor(
    @param:ApplicationContext private val context: Context
) : VoiceMemoRecorder {

    private var recorder: MediaRecorder? = null
    private var startedAtMillis = 0L

    @Synchronized
    override fun start(target: CaptureTarget, onError: () -> Unit): Boolean {
        cancel()
        val created = newRecorder()
        return try {
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioChannels(1)
            created.setAudioSamplingRate(SAMPLE_RATE)
            created.setAudioEncodingBitRate(BIT_RATE)
            created.setOutputFile(target.path)
            created.setOnErrorListener { _, what, extra ->
                Timber.w("音声メモの録音が止まった: what=%d extra=%d", what, extra)
                onError()
            }
            created.prepare()
            created.start()
            recorder = created
            startedAtMillis = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            // マイクをほかのアプリが使っている・権限が無い・出力先に書けない
            Timber.w(e, "音声メモの録音を始められない")
            runCatching { created.release() }
            false
        }
    }

    @Synchronized
    override fun elapsedMillis(): Long = if (recorder == null) 0L else SystemClock.elapsedRealtime() - startedAtMillis

    @Synchronized
    override fun level(): Float {
        val current = recorder ?: return 0f
        val amplitude = runCatching { current.maxAmplitude }.getOrDefault(0)
        return (amplitude / MAX_AMPLITUDE).coerceIn(0f, 1f)
    }

    @Synchronized
    override fun stop(): VoiceMemoStop {
        val current = recorder ?: return VoiceMemoStop.Failed
        recorder = null
        return try {
            current.stop()
            VoiceMemoStop.Saved
        } catch (e: RuntimeException) {
            // 録音が短すぎる・始めていないときに stop() は例外を投げる
            Timber.w(e, "音声メモを仕上げられない")
            VoiceMemoStop.Failed
        } finally {
            runCatching { current.release() }
        }
    }

    @Synchronized
    override fun cancel() {
        val current = recorder ?: return
        recorder = null
        runCatching { current.stop() }
        runCatching { current.release() }
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private companion object {
        /** 声の録音には十分で、どの端末の AAC エンコーダーも受け付ける */
        const val SAMPLE_RATE = 44_100

        /** 約 29MB/時間。長い会議の録音でも添付ファイルの上限(200MB)に収まる */
        const val BIT_RATE = 64_000

        /** [MediaRecorder.getMaxAmplitude] の最大値 */
        const val MAX_AMPLITUDE = 32_767f
    }
}
