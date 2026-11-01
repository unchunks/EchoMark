package com.unchunks.echomark.data.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.unchunks.echomark.domain.repository.ChatSpeechOutput
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Android の [TextToSpeech] で回答を読み上げる。読み上げの仕組みはプロセスを起動するため、最初に読み上げるときに作り、使い回す。
 * 作れなかったときは次の読み上げで作り直す(読み上げのアプリを入れた後に使えるように)。
 */
@Singleton
class AndroidChatSpeechOutput @Inject constructor(
    @ApplicationContext private val context: Context
) : ChatSpeechOutput {

    private val speaking = MutableStateFlow<Long?>(null)
    override val speakingMessageId: StateFlow<Long?> = speaking.asStateFlow()

    private var engine: TextToSpeech? = null

    override suspend fun speak(messageId: Long, parts: List<String>): Boolean {
        if (parts.isEmpty()) return false
        val tts = engine ?: createEngine() ?: return false
        if (!tts.supportsJapanese()) return false
        speaking.value = messageId
        tts.setOnUtteranceProgressListener(CompletionListener(utteranceId(messageId, parts.lastIndex)))
        parts.forEachIndexed { index, part ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (tts.speak(part, mode, null, utteranceId(messageId, index)) != TextToSpeech.SUCCESS) {
                Timber.w("読み上げを始められなかった")
                stop()
                return false
            }
        }
        return true
    }

    override fun stop() {
        engine?.stop()
        speaking.value = null
    }

    private fun TextToSpeech.supportsJapanese(): Boolean =
        setLanguage(Locale.JAPANESE).let { it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED }

    private fun utteranceId(messageId: Long, index: Int) = "$messageId-$index"

    private suspend fun createEngine(): TextToSpeech? = suspendCancellableCoroutine { continuation ->
        var created: TextToSpeech? = null
        created = TextToSpeech(context) { status ->
            val tts = created
            if (status == TextToSpeech.SUCCESS && tts != null) {
                engine = tts
                if (continuation.isActive) continuation.resume(tts)
            } else {
                Timber.w("読み上げの仕組みを使えない: %d", status)
                tts?.shutdown()
                if (continuation.isActive) continuation.resume(null)
            }
        }
        continuation.invokeOnCancellation { if (engine == null) created?.shutdown() }
    }

    /** 最後の部分の読み上げが終わったら(失敗・停止でも)、読み上げ中の表示を外す。 */
    private inner class CompletionListener(private val lastUtteranceId: String) : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            if (utteranceId == lastUtteranceId) speaking.value = null
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            speaking.value = null
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            speaking.value = null
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            if (utteranceId == lastUtteranceId) speaking.value = null
        }
    }
}
