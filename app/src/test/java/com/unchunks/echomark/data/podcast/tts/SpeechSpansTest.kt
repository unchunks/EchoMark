package com.unchunks.echomark.data.podcast.tts

import android.text.Spanned
import android.text.style.TtsSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.speechtext.SpeechNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** 読み上げに渡す文の TtsSpan と、端末内の声の選び方。 */
@RunWith(AndroidJUnit4::class)
class SpeechSpansTest {

    @Test
    fun 電話番号と番号にTtsSpanを付け_文はそのまま() {
        val text = SpeechNormalizer.normalize("電話は03-1234-5678、番号は0042です")
        val spanned = SpeechSpans.build(text) as Spanned
        assertEquals(text.plain, spanned.toString())
        val spans = spanned.getSpans(0, spanned.length, TtsSpan::class.java).sortedBy { spanned.getSpanStart(it) }
        assertEquals(listOf(TtsSpan.TYPE_TELEPHONE, TtsSpan.TYPE_DIGITS), spans.map { it.type })
        assertEquals("03-1234-5678", spanned.subSequence(spanned.getSpanStart(spans[0]), spanned.getSpanEnd(spans[0])).toString())
        assertEquals("0042", spans[1].args.getString(TtsSpan.ARG_DIGITS))
    }

    @Test
    fun ふつうの文には付けない() {
        val spanned = SpeechSpans.build(SpeechNormalizer.normalize("今日は晴れ")) as Spanned
        assertEquals(0, spanned.getSpans(0, spanned.length, TtsSpan::class.java).size)
    }

    @Test
    fun 声は日本語の端末内のものだけを_品質の高い順に選ぶ() {
        fun voice(name: String, language: String = "ja", network: Boolean = false, notInstalled: Boolean = false, quality: Int = 300, latency: Int = 300) =
            VoiceChoice.Candidate(name, language, "JP", network, notInstalled, quality, latency)
        val voices = listOf(
            voice("ja-network", network = true, quality = 500),
            voice("ja-b", quality = 400),
            voice("en-a", language = "en", quality = 500),
            voice("ja-missing", notInstalled = true, quality = 500),
            voice("ja-a", quality = 400),
            voice("ja-low", quality = 100)
        )
        assertEquals(listOf("ja-a", "ja-b", "ja-low"), VoiceChoice.offlineJapanese(voices).map { it.name })
    }
}
