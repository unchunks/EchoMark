package com.unchunks.echomark.data.podcast.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.unchunks.echomark.domain.podcast.PodcastGenerationException
import com.unchunks.echomark.domain.podcast.PodcastSpeaker
import com.unchunks.echomark.domain.podcast.SpeechText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * 端末の読み上げ([TextToSpeech])で、台本の文を WAV のファイルにする。
 *
 * - **端末の中だけで読む**: 日本語の声のうち、通信の要らない(端末に入っている)ものだけを使い、読み上げのたびに
 *   通信による合成を断る([TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS] を false)。台本(ブックマークの中身)を
 *   読み上げのアプリが端末の外に送らないように。端末の中で終わるので、オフラインモードでも使える
 * - **読みの手がかり**: 電話番号・番号には `TtsSpan` を付ける([SpeechSpans])。付けた部分を、エンジンが1けたずつ読む
 * - **2人の声**: 日本語の端末内の声が2つ以上あれば、聞き役に別の声を使う。1つしか無ければ、同じ声の高さを変える
 */
class AndroidPodcastSpeechEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : PodcastSpeechEngine {

    override suspend fun open(): PodcastSpeechSession {
        val tts = createEngine() ?: throw PodcastGenerationException("読み上げのアプリを使えません。端末の設定で読み上げのアプリを確かめてください。")
        try {
            val language = tts.setLanguage(Locale.JAPAN)
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                throw PodcastGenerationException("日本語の読み上げの声がありません。端末の設定で日本語の音声データを入れてください。")
            }
            val voices = VoiceChoice.offlineJapanese(tts.voices.orEmpty().map { it.toCandidate() })
            val defaultVoice = tts.voice
            if (voices.isEmpty() && (defaultVoice == null || defaultVoice.isNetworkConnectionRequired)) {
                throw PodcastGenerationException("端末に入っている日本語の声がありません。端末の設定で日本語の音声データをダウンロードしてください。")
            }
            val byName = tts.voices.orEmpty().associateBy { it.name }
            return Session(tts, host = voices.getOrNull(0)?.let { byName[it.name] } ?: defaultVoice, guest = voices.getOrNull(1)?.let { byName[it.name] })
        } catch (e: Exception) {
            tts.shutdown()
            throw e
        }
    }

    private fun Voice.toCandidate() = VoiceChoice.Candidate(
        name = name,
        language = locale?.language.orEmpty(),
        country = locale?.country.orEmpty(),
        needsNetwork = isNetworkConnectionRequired,
        notInstalled = features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true,
        quality = quality,
        latency = latency
    )

    private suspend fun createEngine(): TextToSpeech? = suspendCancellableCoroutine { continuation ->
        var created: TextToSpeech? = null
        created = TextToSpeech(context) { status ->
            val tts = created
            if (status == TextToSpeech.SUCCESS && tts != null) {
                if (continuation.isActive) continuation.resume(tts) else tts.shutdown()
            } else {
                Timber.w("読み上げの仕組みを使えない: %d", status)
                tts?.shutdown()
                if (continuation.isActive) continuation.resume(null)
            }
        }
        continuation.invokeOnCancellation { created?.shutdown() }
    }

    private class Session(private val tts: TextToSpeech, private val host: Voice?, private val guest: Voice?) : PodcastSpeechSession {
        private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
        private val counter = AtomicLong()

        override val maxInputChars: Int = TextToSpeech.getMaxSpeechInputLength().coerceAtMost(MAX_CHUNK_CHARS)
        override val hasDistinctVoices: Boolean = guest != null && guest.name != host?.name

        init {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { pending.remove(it)?.complete(true) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { pending.remove(it)?.complete(false) }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Timber.w("読み上げの合成に失敗: %d", errorCode)
                    utteranceId?.let { pending.remove(it)?.complete(false) }
                }
            })
        }

        override suspend fun synthesize(text: SpeechText, speaker: PodcastSpeaker, output: File) {
            val voice = if (speaker == PodcastSpeaker.GUEST && hasDistinctVoices) guest else host
            voice?.let { tts.voice = it }
            // 同じ声で2人を話し分けるときは、高さを変える
            tts.setPitch(if (speaker == PodcastSpeaker.GUEST && !hasDistinctVoices) GUEST_PITCH else 1.0f)
            tts.setSpeechRate(1.0f)
            val id = "podcast-${counter.incrementAndGet()}"
            val done = CompletableDeferred<Boolean>()
            pending[id] = done
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "false")
                putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, "true")
            }
            if (tts.synthesizeToFile(SpeechSpans.build(text), params, output, id) != TextToSpeech.SUCCESS) {
                pending.remove(id)
                throw PodcastGenerationException("読み上げを始められませんでした。")
            }
            val ok = withTimeoutOrNull(UTTERANCE_TIMEOUT_MS) { done.await() }
            pending.remove(id)
            if (ok != true) throw PodcastGenerationException(if (ok == null) "読み上げが時間内に終わりませんでした。" else "読み上げで音声を作れませんでした。")
        }

        override fun close() {
            tts.stop()
            tts.shutdown()
        }
    }

    private companion object {
        /** 1回に渡す文字数の上限(エンジンの上限より小さくし、失敗したときにやり直す量を小さくする) */
        const val MAX_CHUNK_CHARS = 1_000
        const val GUEST_PITCH = 1.25f
        const val UTTERANCE_TIMEOUT_MS = 120_000L
    }
}

/** 声の選び方(純粋関数。単体テスト可能)。 */
object VoiceChoice {
    data class Candidate(
        val name: String,
        val language: String,
        val country: String,
        val needsNetwork: Boolean,
        val notInstalled: Boolean,
        val quality: Int,
        val latency: Int
    )

    /**
     * 使える日本語の声を、よい順に並べる。通信の要るもの・まだ入っていないものは使わない。
     * 品質の高いもの → 速いもの → 名前の順(同じ端末なら毎回同じ声になるように)。
     */
    fun offlineJapanese(voices: List<Candidate>): List<Candidate> =
        voices.filter { it.language == "ja" && !it.needsNetwork && !it.notInstalled }
            .sortedWith(compareByDescending<Candidate> { it.quality }.thenBy { it.latency }.thenBy { it.name })
}
