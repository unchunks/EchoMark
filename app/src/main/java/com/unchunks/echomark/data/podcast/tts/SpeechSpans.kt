package com.unchunks.echomark.data.podcast.tts

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TtsSpan
import com.unchunks.components.speechtext.SpeechPiece
import com.unchunks.components.speechtext.SpeechText

/**
 * 読み上げ用に整えた文([SpeechText])を、[TtsSpan] を付けた文にする。
 * 電話番号には TELEPHONE、番号には DIGITS を付け、エンジンに1けたずつ読ませる。付けた部分の文字はそのままなので、
 * TtsSpan を読まないエンジンでも、同じ文として読める。
 */
object SpeechSpans {
    fun build(text: SpeechText): CharSequence {
        val builder = SpannableStringBuilder()
        for (piece in text.pieces) {
            val start = builder.length
            builder.append(piece.spoken)
            val span = when (piece) {
                is SpeechPiece.Telephone -> TtsSpan.TelephoneBuilder(piece.spoken).build()
                is SpeechPiece.Digits -> TtsSpan.DigitsBuilder(piece.spoken).build()
                else -> null
            }
            if (span != null && builder.length > start) builder.setSpan(span, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return builder
    }
}
